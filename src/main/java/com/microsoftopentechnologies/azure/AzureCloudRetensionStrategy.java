/*
 Copyright 2014 Microsoft Open Technologies, Inc.
 
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
package com.microsoftopentechnologies.azure;

import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.retry.LinearRetryForAllExceptions;
import com.microsoftopentechnologies.azure.util.CleanUpAction;
import com.microsoftopentechnologies.azure.util.Constants;
import com.microsoftopentechnologies.azure.util.ExecutionEngine;

import hudson.model.Descriptor;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;
import java.util.logging.Level;

public class AzureCloudRetensionStrategy extends RetentionStrategy<AzureComputer> {

    // Configured idle termination
    private final long idleTerminationMillis;

    private static final Logger LOGGER = Logger.getLogger(AzureManagementServiceDelegate.class.getName());
    
    @DataBoundConstructor
    public AzureCloudRetensionStrategy(int idleTerminationMinutes) {
        this.idleTerminationMillis = TimeUnit2.MINUTES.toMillis(idleTerminationMinutes);
    }

    /**
     * Called by Jenkins to determine what to do with a particular node.
     * Node could be shut down, deleted, etc.
     * @param slaveNode Node to check
     * @return Number of minutes before node will be checked again.
     */
    @Override
    public long check(final AzureComputer slaveNode) {
        // Determine whether we can recycle this machine.
        // The CRS is the way that nodes that are currently operating "correctly"
        // can be retained/reclaimed.  Any failure modes need to be dealt with through
        // the clean up task.
        
        boolean canRecycle = true;
        // Node must be idle
        canRecycle &= slaveNode.isIdle();
        // The node must also be online.  This also implies not temporarily disconnected
        // (like by a user).
        canRecycle &= slaveNode.isOnline();
        // The configured idle time must be > 0 (which means leave forever)
        canRecycle &= idleTerminationMillis > 0;
        // The number of ms it's been idle must be greater than the current idle time.
        canRecycle &= idleTerminationMillis < (System.currentTimeMillis() - slaveNode.getIdleStartMilliseconds());
        
        if (slaveNode.getNode() == null) {
            return 1;
        }
        
        final AzureSlave slave = slaveNode.getNode();
        
        if (canRecycle) {
            LOGGER.log(Level.INFO, "AzureCloudRetensionStrategy: check: Idle timeout reached for slave: {0}, action: {1}",
                    new Object [] {slaveNode.getName(), slave.isShutdownOnIdle() ? "shutdown" : "delete"} );

            java.util.concurrent.Callable<Void> task = new java.util.concurrent.Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    // Block cleanup while we execute so the cleanup task doesn't try to take it
                    // away (node will go offline).  Also blocks cleanup in case of shutdown.
                    slave.blockCleanUpAction();
                    if (slave.isShutdownOnIdle()) {
                        LOGGER.log(Level.INFO, "AzureCloudRetensionStrategy: going to idleTimeout slave: {0}",
                            slaveNode.getName());
                        slave.shutdown(Messages._Idle_Timeout_Shutdown());
                    } else {
                        slave.deprovision(Messages._Idle_Timeout_Delete());
                    }
                    return null;
                }
            };

            try {
                ExecutionEngine.executeAsync(task,
                        new LinearRetryForAllExceptions(
                                30, // maxRetries
                                30, // waitinterval
                                30 * 60 // timeout
                        ));
            } catch (AzureCloudException ae) {
                LOGGER.log(Level.INFO, "AzureCloudRetensionStrategy: check: could not terminate or shutdown {0}: {1}",
                        new Object [] { slaveNode.getName(), ae });
                // If we have an exception, set the slave for deletion.  It's unlikely we'll be able to shut it down properly ever.
                slaveNode.getNode().setCleanUpAction(CleanUpAction.DELETE, Messages._Failed_Initial_Shutdown_Or_Delete());
            } catch (Exception e) {
                LOGGER.log(Level.INFO,
                    "AzureCloudRetensionStrategy: check: Exception occured while calling timeout on node {0}: {1}",
                        new Object [] { slaveNode.getName(), e });
                // If we have an exception, set the slave for deletion.  It's unlikely we'll be able to shut it down properly ever.
                slaveNode.getNode().setCleanUpAction(CleanUpAction.DELETE, Messages._Failed_Initial_Shutdown_Or_Delete());
            }
        }
        return 1;
    }

    @Override
    public void start(AzureComputer azureComputer) {
        //TODO: check when this method is getting called and add code accordingly
        LOGGER.log(Level.INFO, "AzureCloudRetensionStrategy: start: azureComputer name {0}",
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
