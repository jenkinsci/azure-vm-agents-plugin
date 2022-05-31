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
import com.microsoft.azure.vmagent.util.ExecutionEngine;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.slaves.RetentionStrategy;
import java.util.concurrent.Callable;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AzureVMCloudRetensionStrategy extends AzureVMCloudBaseRetentionStrategy {
    private static final long serialVersionUID = 15743279621L;

    // Configured idle termination
    private final long idleTerminationMillis;

    private final long idleTerminationMinutes;

    private static final Logger LOGGER = Logger.getLogger(AzureVMManagementServiceDelegate.class.getName());

    @DataBoundConstructor
    public AzureVMCloudRetensionStrategy(int idleTerminationMinutes) {
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.idleTerminationMillis = TimeUnit.MINUTES.toMillis(idleTerminationMinutes);
    }

    /**
     * Called by Jenkins to determine what to do with a particular node.
     * Node could be shut down, deleted, etc.
     *
     * @param agentNode Node to check
     * @return Number of minutes before node will be checked again.
     */
    @Override
    public long check(@NonNull AzureVMComputer agentNode) {
        return check(agentNode, new ExecutionEngine());
    }

    protected long check(final AzureVMComputer agentComputer, ExecutionEngine executionEngine) {
        // Determine whether we can recycle this machine.
        // The CRS is the way that nodes that are currently operating "correctly"
        // can be retained/reclaimed.  Any failure modes need to be dealt with through
        // the cleanup task.

        // Node must be idle
        boolean canRecycle = agentComputer.isIdle();
        // The node must also be online.  This also implies not temporarily disconnected
        // (like by a user).
        canRecycle &= agentComputer.isOnline();
        // The configured idle time must be > 0 (which means leave forever)
        canRecycle &= idleTerminationMillis > 0;
        // The number of ms it's been idle must be greater than the current idle time.
        canRecycle &= idleTerminationMillis < (System.currentTimeMillis() - agentComputer.getIdleStartMilliseconds());

        if (agentComputer.getNode() == null) {
            return 1;
        }

        final AzureVMAgent agent = agentComputer.getNode();

        if (canRecycle) {
            LOGGER.log(Level.INFO, "Idle timeout reached for agent: {0}, action: {1}",
                    new Object[]{agentComputer.getName(), agent.isShutdownOnIdle() ? "shutdown" : "delete"});

            Callable<Void> task = () -> {
                // Block cleanup while we execute so the cleanup task doesn't try to take it
                // away (node will go offline).  Also blocks cleanup in case of shutdown.
                agent.blockCleanUpAction();
                if (agent.isShutdownOnIdle()) {
                    LOGGER.log(Level.INFO, "Going to idleTimeout agent: {0}", agentComputer.getName());
                    agent.shutdown(Messages._Idle_Timeout_Shutdown());
                } else {
                    agent.deprovision(Messages._Idle_Timeout_Delete());
                }
                return null;
            };

            try {
                final int maxRetries = 3;
                final int waitInterval = 30;
                final int defaultTimeoutInSeconds = 30 * 60;
                executionEngine.executeAsync(task,
                        new LinearRetryForAllExceptions(
                                maxRetries,
                                waitInterval,
                                defaultTimeoutInSeconds
                        ));
            } catch (AzureCloudException ae) {
                LOGGER.log(Level.WARNING, String.format("Could not terminate or shutdown %s",
                        agentComputer.getName()), ae);
                // If we have an exception, set the agent for deletion.
                // It's unlikely we'll be able to shut it down properly ever.
                AzureVMAgent node = agentComputer.getNode();
                if (node != null) {
                    node.setCleanUpAction(CleanUpAction.DELETE, Messages._Failed_Initial_Shutdown_Or_Delete());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        String.format("Exception occurred while calling timeout on node %s",
                                agentComputer.getName()), e);
                // If we have an exception, set the agent for deletion.
                // It's unlikely we'll be able to shut it down properly ever.
                AzureVMAgent node = agentComputer.getNode();
                if (node != null) {
                    node.setCleanUpAction(CleanUpAction.DELETE, Messages._Failed_Initial_Shutdown_Or_Delete());
                }
            }
        }

        if (agentComputer.isOffline() && !agentComputer.isConnecting() && agentComputer.isLaunchSupported()) {
            agentComputer.tryReconnect();
        }

        return 1;
    }

    public long getIdleTerminationMinutes() {
        return idleTerminationMinutes;
    }

    @Override
    public void start(AzureVMComputer azureComputer) {
        //TODO: check when this method is getting called and add code accordingly
        LOGGER.log(Level.INFO, "Starting azureComputer {0}",
                azureComputer.getDisplayName());
        azureComputer.connect(false);
        resetShutdownVMStatus(azureComputer.getNode());
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    @Symbol("azureVMCloudRetentionStrategy") //  TODO evaluate impact of renaming class to fix the default symbol name
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override @NonNull
        public String getDisplayName() {
            return "Azure VM Idle Retention Strategy";
        }
    }

    @Extension
    public static class DescriptorVisibilityFilterImpl extends DescriptorVisibilityFilter {
        @Override
        public boolean filter(@CheckForNull Object context, @NonNull Descriptor descriptor) {
            return !(descriptor instanceof DescriptorImpl);
        }
    }
}
