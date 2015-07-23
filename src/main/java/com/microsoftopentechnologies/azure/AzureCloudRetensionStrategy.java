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
import com.microsoftopentechnologies.azure.util.Constants;
import com.microsoftopentechnologies.azure.util.ExecutionEngine;

import hudson.model.Descriptor;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;

public class AzureCloudRetensionStrategy extends RetentionStrategy<AzureComputer> {

    public final int idleTerminationMinutes;

    private static final Logger LOGGER = Logger.getLogger(AzureManagementServiceDelegate.class.getName());

    @DataBoundConstructor
    public AzureCloudRetensionStrategy(int idleTerminationMinutes) {
        this.idleTerminationMinutes = idleTerminationMinutes;
    }

    @Override
    public long check(final AzureComputer slaveNode) {
        // if idleTerminationMinutes is zero then it means that never terminate the slave instance 
        if (idleTerminationMinutes == 0) {
            return 1;
        }

        // Do we need to check about slave status?
        if (slaveNode.isIdle()) {
            if (idleTerminationMinutes > 0) {
                final long idleMilliseconds = System.currentTimeMillis() - slaveNode.getIdleStartMilliseconds();
                if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(idleTerminationMinutes)) {
                    // close channel
                    try {
                        slaveNode.setAcceptingTasks(false);
                        slaveNode.disconnect(OfflineCause.create(Messages._IDLE_TIMEOUT_SHUTDOWN()));
                        if (slaveNode.getChannel() != null) {
                            slaveNode.getChannel().close();
                        }
                    } catch (Exception e) {
                        LOGGER.info("AzureCloudRetensionStrategy: check: exception occured while closing channel for: "
                                + slaveNode.getName());
                    }
                    LOGGER.info("AzureCloudRetensionStrategy: check: Idle timeout reached for slave: " + slaveNode.
                            getName());

                    java.util.concurrent.Callable<Void> task = new java.util.concurrent.Callable<Void>() {

                        public Void call() throws Exception {
                            LOGGER.info("AzureCloudRetensionStrategy: going to idleTimeout slave: " + slaveNode.
                                    getName());
                            slaveNode.getNode().idleTimeout();
                            return null;
                        }
                    };

                    try {
                        ExecutionEngine.executeWithRetry(task,
                                new LinearRetryForAllExceptions(
                                        30, // maxRetries
                                        30, // waitinterval
                                        30 * 60 // timeout
                                ));
                    } catch (AzureCloudException ae) {
                        LOGGER.info("AzureCloudRetensionStrategy: check: could not terminate or shutdown " + slaveNode.
                                getName());
                    } catch (Exception e) {
                        LOGGER.info(
                                "AzureCloudRetensionStrategy: execute: Exception occured while calling timeout on node, \n"
                                + "Error code " + e.getMessage());
                        // We won't get exception for RNF , so for other exception types we can retry
                        if (e.getMessage().contains("not found in the currently deployed service")) {
                            LOGGER.info(
                                    "AzureCloudRetensionStrategy: execute: Slave does not exist in the subscription anymore, setting shutdownOnIdle to True");
                            slaveNode.getNode().setShutdownOnIdle(true);
                        }
                    }
                }
            }
        }
        return 1;
    }

    @Override
    public void start(AzureComputer azureComputer) {
        //TODO: check when this method is getting called and add code accordingly
        LOGGER.info("AzureCloudRetensionStrategy: start: azureComputer name " + azureComputer.getDisplayName());
        azureComputer.connect(false);
    }

    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {

        @Override
        public String getDisplayName() {
            return Constants.AZURE_CLOUD_DISPLAY_NAME;
        }
    }

}
