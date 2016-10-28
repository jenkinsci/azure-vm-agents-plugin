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
package com.microsoft.azure;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import com.microsoft.azure.exceptions.AzureCloudException;
import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.ResourceManagementService;
import com.microsoft.azure.management.resources.models.DeploymentGetResult;
import com.microsoft.azure.management.resources.models.ProvisioningState;
import com.microsoft.azure.retry.DefaultRetryStrategy;
import com.microsoft.azure.util.AzureUserAgentFilter;
import com.microsoft.azure.util.ExecutionEngine;
import com.microsoft.azure.util.CleanUpAction;
import com.microsoft.windowsazure.Configuration;

import jenkins.model.Jenkins;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.Computer;
import java.util.Calendar;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

@Extension
public final class AzureVMAgentCleanUpTask extends AsyncPeriodicWork {

    private static class DeploymentInfo {
        public DeploymentInfo(String cloudName, String resourceGroupName, String deploymentName) {
            this.cloudName = cloudName;
            this.deploymentName = deploymentName;
            this.resourceGroupName = resourceGroupName;
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
        
        private String cloudName;
        private String deploymentName;
        private String resourceGroupName;
    }
    
    private static final long succesfullDeploymentTimeoutInMinutes = 60 * 1;
    private static final long failingDeploymentTimeoutInMinutes = 60 * 8;
    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentCleanUpTask.class.getName());
    private static final ConcurrentLinkedQueue<DeploymentInfo> deploymentsToClean = new ConcurrentLinkedQueue<DeploymentInfo>();
            
    public AzureVMAgentCleanUpTask() {
        super("Azure VM Agents Clean Task");
    }
    
    public static void registerDeployment(String cloudName, String resourceGroupName, String deploymentName) {
        LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: registerDeployment: Registering deployment {0} in {1}",
            new Object [] { deploymentName, resourceGroupName } );
        DeploymentInfo newDeploymentToClean = new DeploymentInfo(cloudName, resourceGroupName, deploymentName);
        deploymentsToClean.add(newDeploymentToClean);
    }
    
    public void cleanDeployments() {
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
                final Configuration config = ServiceDelegateHelper.getConfiguration(cloud);
                final ResourceManagementClient rmc = ResourceManagementService.create(config)
                    .withRequestFilterFirst(new AzureUserAgentFilter());

                DeploymentGetResult deployment = 
                    rmc.getDeploymentsOperations().get(info.getResourceGroupName(), info.getDeploymentName());
                if (deployment.getDeployment() == null) {
                    LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Deployment not found, skipping");
                    continue;
                }
                
                Calendar deploymentTime = deployment.getDeployment().getProperties().getTimestamp();
                
                LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Deployment created on {0}", deploymentTime.getTime());
                long deploymentTimeInMillis = deploymentTime.getTimeInMillis();
                
                // Compare to now
                Calendar nowTime = Calendar.getInstance(deploymentTime.getTimeZone());
                long nowTimeInMillis = nowTime.getTimeInMillis();
                
                long diffTime = nowTimeInMillis - deploymentTimeInMillis;
                long diffTimeInMinutes = (diffTime / 1000) / 60;
                
                String state = deployment.getDeployment().getProperties().getProvisioningState();
                
                if (!state.equals(ProvisioningState.SUCCEEDED) && diffTimeInMinutes > failingDeploymentTimeoutInMinutes) {
                    LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Failed deployment older than {0} minutes, deleting",
                       failingDeploymentTimeoutInMinutes);
                    // Delete the deployment
                    rmc.getDeploymentsOperations().delete(info.getResourceGroupName(), info.getDeploymentName());
                }
                else if(state.equals(ProvisioningState.SUCCEEDED) && diffTimeInMinutes > succesfullDeploymentTimeoutInMinutes) {
                    LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Succesfull deployment older than {0} minutes, deleting",
                       succesfullDeploymentTimeoutInMinutes);
                    // Delete the deployment
                    rmc.getDeploymentsOperations().delete(info.getResourceGroupName(), info.getDeploymentName());
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
                LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Failed to delete deployment: {0}", e.toString());
            }
        }
        LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Done cleaning deployments");
    }
    
    private void cleanVMs() {
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
                    ExecutionEngine.executeAsync(task, new DefaultRetryStrategy(
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
