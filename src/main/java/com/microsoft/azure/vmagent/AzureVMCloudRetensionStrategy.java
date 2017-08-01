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

import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.retry.LinearRetryForAllExceptions;
import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.ExecutionEngine;
import hudson.model.Descriptor;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.logging.Level;
import java.util.logging.Logger;

public class AzureVMCloudRetensionStrategy extends RetentionStrategy<AzureVMComputer> {

    // Configured idle termination
    private final long idleTerminationMillis;

    private static final Logger LOGGER = Logger.getLogger(AzureVMManagementServiceDelegate.class.getName());

    @DataBoundConstructor
    public AzureVMCloudRetensionStrategy(int idleTerminationMinutes) {
        this.idleTerminationMillis = TimeUnit2.MINUTES.toMillis(idleTerminationMinutes);
    }

    /**
     * Called by Jenkins to determine what to do with a particular node.
     * Node could be shut down, deleted, etc.
     *
     * @param agentNode Node to check
     * @return Number of minutes before node will be checked again.
     */
    @Override
    public long check(AzureVMComputer agentNode) {
        return check(agentNode, new ExecutionEngine());
    }

    protected long check(final AzureVMComputer agentNode, ExecutionEngine executionEngine) {
        // Determine whether we can recycle this machine.
        // The CRS is the way that nodes that are currently operating "correctly"
        // can be retained/reclaimed.  Any failure modes need to be dealt with through
        // the clean up task.

        boolean canRecycle = true;
        // Node must be idle
        canRecycle &= agentNode.isIdle();
        // The node must also be online.  This also implies not temporarily disconnected
        // (like by a user).
        canRecycle &= agentNode.isOnline();
        // The configured idle time must be > 0 (which means leave forever)
        canRecycle &= idleTerminationMillis > 0;
        // The number of ms it's been idle must be greater than the current idle time.
        canRecycle &= idleTerminationMillis < (System.currentTimeMillis() - agentNode.getIdleStartMilliseconds());

        if (agentNode.getNode() == null) {
            return 1;
        }

        final AzureVMAgent agent = agentNode.getNode();

        if (canRecycle) {
            LOGGER.log(Level.INFO,
                    "AzureVMCloudRetensionStrategy: check: Idle timeout reached for agent: {0}, action: {1}",
                    new Object[]{agentNode.getName(), agent.isShutdownOnIdle() ? "shutdown" : "delete"});

            java.util.concurrent.Callable<Void> task = new java.util.concurrent.Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    // Block cleanup while we execute so the cleanup task doesn't try to take it
                    // away (node will go offline).  Also blocks cleanup in case of shutdown.
                    agent.blockCleanUpAction();
                    if (agent.isShutdownOnIdle()) {
                        LOGGER.log(Level.INFO, "AzureVMCloudRetensionStrategy: going to idleTimeout agent: {0}",
                                agentNode.getName());
                        agent.shutdown(Messages._Idle_Timeout_Shutdown());
                    } else {
                        agent.deprovision(Messages._Idle_Timeout_Delete());
                    }
                    return null;
                }
            };

            try {
                final int maxRetries = 30;
                final int waitInterval = 30;
                final int defaultTimeoutInSeconds = 30 * 60;
                executionEngine.executeAsync(task,
                        new LinearRetryForAllExceptions(
                                maxRetries,
                                waitInterval,
                                defaultTimeoutInSeconds
                        ));
            } catch (AzureCloudException ae) {
                LOGGER.log(Level.INFO,
                        "AzureVMCloudRetensionStrategy: check: could not terminate or shutdown {0}: {1}",
                        new Object[]{agentNode.getName(), ae});
                // If we have an exception, set the agent for deletion.
                // It's unlikely we'll be able to shut it down properly ever.
                AzureVMAgent node = agentNode.getNode();
                if (node != null) {
                    node.setCleanUpAction(CleanUpAction.DELETE, Messages._Failed_Initial_Shutdown_Or_Delete());
                }
            } catch (Exception e) {
                LOGGER.log(Level.INFO,
                        "AzureVMCloudRetensionStrategy: check: "
                                + "Exception occured while calling timeout on node {0}: {1}",
                        new Object[]{agentNode.getName(), e});
                // If we have an exception, set the agent for deletion.
                // It's unlikely we'll be able to shut it down properly ever.
                AzureVMAgent node = agentNode.getNode();
                if (node != null) {
                    node.setCleanUpAction(CleanUpAction.DELETE, Messages._Failed_Initial_Shutdown_Or_Delete());
                }
            }
        }
        return 1;
    }

    @Override
    public void start(AzureVMComputer azureComputer) {
        //TODO: check when this method is getting called and add code accordingly
        LOGGER.log(Level.INFO, "AzureVMCloudRetensionStrategy: start: azureComputer name {0}",
                azureComputer.getDisplayName());
        azureComputer.connect(false);
    }

    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {

        @Override
        public String getDisplayName() {
            return Constants.AZURE_CLOUD_DISPLAY_NAME;
        }
    }
}
