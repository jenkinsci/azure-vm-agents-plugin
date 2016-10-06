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

import com.microsoft.azure.Messages;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import com.microsoft.azure.exceptions.AzureCloudException;
import com.microsoft.azure.retry.DefaultRetryStrategy;
import com.microsoft.azure.util.ExecutionEngine;
import com.microsoft.azure.util.CleanUpAction;

import jenkins.model.Jenkins;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.Computer;
import java.util.logging.Level;

@Extension
public final class AzureVMAgentCleanUpTask extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentCleanUpTask.class.getName());

    public AzureVMAgentCleanUpTask() {
        super("Azure VM Agents Clean Task");
    }

    @Override
    public void execute(TaskListener arg0) throws IOException, InterruptedException {
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
                        "AzureVMAgentCleanUpTask: execute: node {0} was set offline by user, skipping", agentNode.getDisplayName());
                    continue;
                }
                
                // If the machine is in "keep" state, skip
                if (agentNode.isCleanUpBlocked()) {
                    LOGGER.log(Level.INFO,
                        "AzureVMAgentCleanUpTask: execute: node {0} blocked to cleanup", agentNode.getDisplayName());
                    continue;
                }
                    
                // Check if the virtual machine exists.  If not, it could have been
                // deleted in the background.  Remove from Jenkins if that is the case.
                if (!AzureVMManagementServiceDelegate.virtualMachineExists(agentNode)) {
                    LOGGER.log(Level.INFO,
                        "AzureVMAgentCleanUpTask: execute: node {0} doesn't exist, removing", agentNode.getDisplayName());
                    Jenkins.getInstance().removeNode(agentNode);
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
                                "AzureVMAgentCleanUpTask: execute: deleting {0}", agentNode.getDisplayName());
                            agentNode.deprovision(agentNode.getCleanUpReason());
                        }
                        else if(agentNode.getCleanUpAction() == CleanUpAction.SHUTDOWN) {
                            LOGGER.log(Level.INFO,
                                "AzureVMAgentCleanUpTask: execute: shutting down {0}", agentNode.getDisplayName());
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
                            "AzureVMAgentCleanUpTask: execute: failed to shutdown/delete " + agentNode.getDisplayName(),
                            exception);
                    // In case the node had a non-delete cleanup action before,
                    // set the cleanup action to delete
                    agentNode.setCleanUpAction(CleanUpAction.DELETE, Messages._Failed_Initial_Shutdown_Or_Delete());
                }
            }
        }
    }

    @Override
    public long getRecurrencePeriod() {
        // Every 15 minutes
        return 15 * 60 * 1000;
    }
}
