/*
 Copyright 2016 Microsoft, Inc.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 
 http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.Messages;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.resources.Deployment;
import com.microsoft.azure.vmagent.retry.DefaultRetryStrategy;
import com.microsoft.azure.vmagent.util.ExecutionEngine;
import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.TokenCache;

import jenkins.model.Jenkins;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.Computer;
import java.util.Calendar;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import org.joda.time.DateTime;

@Extension
public class AzureVMAgentCleanUpTask extends AsyncPeriodicWork {

    private static class DeploymentInfo {
        public DeploymentInfo(String cloudName, String resourceGroupName, String deploymentName, int deleteAttempts) {
            this.cloudName = cloudName;
            this.deploymentName = deploymentName;
            this.resourceGroupName = resourceGroupName;
            this.attemptsRemaining = deleteAttempts;
        }

        public String getCloudName() {
            return cloudName;
        }

        public String getDeploymentName() {
            return deploymentName;
        }

        public String getResourceGroupName() {
            return resourceGroupName;
        }
        
        public boolean hasAttemptsRemaining() {
            return attemptsRemaining > 0;
        }

        public void decrementAttemptsRemaining() {
            attemptsRemaining--;
        }

        private String cloudName;
        private String deploymentName;
        private String resourceGroupName;
        private int attemptsRemaining;
    }
    
    private static final long succesfullDeploymentTimeoutInMinutes = 60 * 1;
    private static final long failingDeploymentTimeoutInMinutes = 60 * 8;
    private static final int maxDeleteAttempts = 3;
    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentCleanUpTask.class.getName());
    private static final ConcurrentLinkedQueue<DeploymentInfo> deploymentsToClean = new ConcurrentLinkedQueue<DeploymentInfo>();
    
    public AzureVMAgentCleanUpTask() {
        super("Azure VM Agents Clean Task");
    }

    public static class DeploymentRegistrar {

        public void registerDeployment(String cloudName, String resourceGroupName, String deploymentName) {
            LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: registerDeployment: Registering deployment {0} in {1}",
                new Object [] { deploymentName, resourceGroupName } );
            DeploymentInfo newDeploymentToClean = new DeploymentInfo(cloudName, resourceGroupName, deploymentName, maxDeleteAttempts);
            deploymentsToClean.add(newDeploymentToClean);
        }
    }

    public void cleanDeployments() {
        cleanDeployments(succesfullDeploymentTimeoutInMinutes, failingDeploymentTimeoutInMinutes);
    }

    public void cleanDeployments(final long successTimeoutInMinutes, final long failTimeoutInMinutes) {
        LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Cleaning deployments");
        // Walk the queue, popping and pushing until we reach an item that we've already
        // dealt with or the queue is empty.
        DeploymentInfo firstBackInQueue = null;
        while(!deploymentsToClean.isEmpty() && firstBackInQueue != deploymentsToClean.peek()) {
            DeploymentInfo info = deploymentsToClean.remove();
            
            LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Checking deployment {0}", info.getDeploymentName());
            
            AzureVMCloud cloud = getCloud(info.getCloudName());
            
            if (cloud == null) {
                // Cloud could have been deleted, skip
                continue;
            }
            
            try {
                final Azure azureClient  = TokenCache.getInstance(cloud.getServicePrincipal()).getAzureClient();

                // This will throw if the deployment can't be found.  This could happen in a couple instances
                // 1) The deployment has already been deleted
                // 2) The deployment doesn't exist yet (race between creating the deployment and it
                //    being accepted by Azure.
                // To avoid this, we implement a retry.  If we hit an exception, we will decrement the number
                // of retries.  If we hit 0, we remove the deployment from our list.
                Deployment deployment = azureClient.deployments().getByGroup(info.getResourceGroupName(), info.getDeploymentName());
                if (deployment == null) {
                    LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Deployment not found, skipping");
                    continue;
                }

                DateTime deploymentTime = deployment.timestamp();

                LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Deployment created on {0}", deploymentTime.toDate());
                long deploymentTimeInMillis = deploymentTime.getMillis();
                
                // Compare to now
                Calendar nowTime = Calendar.getInstance (deploymentTime.getZone().toTimeZone());
                long nowTimeInMillis = nowTime.getTimeInMillis();
                
                long diffTime = nowTimeInMillis - deploymentTimeInMillis;
                long diffTimeInMinutes = (diffTime / 1000) / 60;
                
                String state = deployment.provisioningState();

                if (!state.equalsIgnoreCase("succeeded") && diffTimeInMinutes > failTimeoutInMinutes) {
                    LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Failed deployment older than {0} minutes, deleting",
                       failTimeoutInMinutes);
                    // Delete the deployment
                    azureClient.deployments().deleteByGroup(info.getResourceGroupName(), info.getDeploymentName());
                }
                else if(state.equalsIgnoreCase("succeeded") && diffTimeInMinutes > successTimeoutInMinutes) {
                    LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Succesfull deployment older than {0} minutes, deleting",
                       successTimeoutInMinutes);
                    // Delete the deployment
                    azureClient.deployments().deleteByGroup(info.getResourceGroupName(), info.getDeploymentName());
                }
                else {
                    LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Deployment newer than timeout, keeping");
                    
                    if (firstBackInQueue == null) {
                        firstBackInQueue = info;
                    }
                    // Put it back
                    deploymentsToClean.add(info);
                }
            }
            catch (Exception e) {
                LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Failed to get/delete deployment: {0}", e.toString());
                // Check the number of attempts remaining. If greater than 0, decrement
                // and add back into the queue.
                if (info.hasAttemptsRemaining()) {
                    info.decrementAttemptsRemaining();

                    if (firstBackInQueue == null) {
                        firstBackInQueue = info;
                    }

                    // Put it back in the queue for another attempt
                    deploymentsToClean.add(info);
                }
            }
        }
        LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Done cleaning deployments");
    }
    
    private void cleanVMs() {
        cleanVMs(new ExecutionEngine());
    }

    private void cleanVMs(ExecutionEngine executionEngine) {
        for (Computer computer : Jenkins.getInstance().getComputers()) {
            if (computer instanceof AzureVMComputer) {
                AzureVMComputer azureComputer = (AzureVMComputer) computer;
                final AzureVMAgent agentNode = azureComputer.getNode();

                // If the machine is not offline, then don't do anything.
                if (!azureComputer.isOffline()) {
                    continue;
                }
                
                // If the machine is not idle, don't do anything.
                // Could have been taken offline by the plugin while still running
                // builds.
                if (!azureComputer.isIdle()) {
                    continue;
                }
                
                // Even if offline, a machine that has been temporarily marked offline
                // should stay (this could be for investigation).
                if (azureComputer.isSetOfflineByUser()) {
                    LOGGER.log(Level.INFO,
                        "AzureVMAgentCleanUpTask: cleanVMs: node {0} was set offline by user, skipping", agentNode.getDisplayName());
                    continue;
                }
                
                // If the machine is in "keep" state, skip
                if (agentNode.isCleanUpBlocked()) {
                    LOGGER.log(Level.INFO,
                        "AzureVMAgentCleanUpTask: cleanVMs: node {0} blocked to cleanup", agentNode.getDisplayName());
                    continue;
                }
                    
                // Check if the virtual machine exists.  If not, it could have been
                // deleted in the background.  Remove from Jenkins if that is the case.
                if (!AzureVMManagementServiceDelegate.virtualMachineExists(agentNode)) {
                    LOGGER.log(Level.INFO,
                        "AzureVMAgentCleanUpTask: cleanVMs: node {0} doesn't exist, removing", agentNode.getDisplayName());
                    try {
                        Jenkins.getInstance().removeNode(agentNode);
                    }
                    catch (IOException e) {
                        LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanVMs: node {0} could not be removed: {1}", 
                            new Object [] { agentNode.getDisplayName(), e.getMessage() } );
                    }
                    continue;
                }
                
                // Machine exists but is in either DELETE or SHUTDOWN state.
                // Execute that action.
                Callable<Void> task = new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        // Depending on the cleanup action, run the appropriate
                        if (agentNode.getCleanUpAction() == CleanUpAction.DELETE) {
                            LOGGER.log(Level.INFO,
                                "AzureVMAgentCleanUpTask: cleanVMs: deleting {0}", agentNode.getDisplayName());
                            agentNode.deprovision(agentNode.getCleanUpReason());
                        }
                        else if(agentNode.getCleanUpAction() == CleanUpAction.SHUTDOWN) {
                            LOGGER.log(Level.INFO,
                                "AzureVMAgentCleanUpTask: cleanVMs: shutting down {0}", agentNode.getDisplayName());
                            agentNode.shutdown(agentNode.getCleanUpReason());
                            // We shut down the agent properly.  Mark the agent
                            // as "KEEP" so that it doesn't get deleted.
                            agentNode.blockCleanUpAction();
                        }
                        else {
                            throw new IllegalStateException("Unknown cleanup action");
                        }
                        return null;
                    }
                };

                try {
                    executionEngine.executeAsync(task, new DefaultRetryStrategy(
                            3, // max retries
                            10, // Default backoff in seconds 
                            30 * 60 // Max timeout in seconds
                    ));
                } catch (AzureCloudException exception) {
                    // No need to throw exception back, just log and move on. 
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanVMs: failed to shutdown/delete " + agentNode.getDisplayName(),
                            exception);
                    // In case the node had a non-delete cleanup action before,
                    // set the cleanup action to delete
                    agentNode.setCleanUpAction(CleanUpAction.DELETE, Messages._Failed_Initial_Shutdown_Or_Delete());
                }
            }
        }
    }
    
    public AzureVMCloud getCloud(final String cloudName) {
        return Jenkins.getInstance() == null ? null : (AzureVMCloud) Jenkins.getInstance().getCloud(cloudName);
    }

    @Override
    public void execute(TaskListener arg0) throws InterruptedException {
        cleanVMs();
        // Clean up the deployments
        cleanDeployments();
    }

    @Override
    public long getRecurrencePeriod() {
        // Every 15 minutes
        return 15 * 60 * 1000;
    }
}
