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

import com.microsoftopentechnologies.azure.util.Constants;

import hudson.model.Descriptor;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;

public class AzureCloudRetensionStrategy extends RetentionStrategy<AzureComputer>  {
	public final int idleTerminationMinutes;
	private static final Logger LOGGER = Logger.getLogger(AzureManagementServiceDelegate.class.getName());

	@DataBoundConstructor
	public AzureCloudRetensionStrategy(int idleTerminationMinutes) {
		this.idleTerminationMinutes = idleTerminationMinutes;
	}

	public long check(AzureComputer slaveNode) {
        // if idleTerminationMinutes is zero then it means that never terminate the slave instance 
		if  (idleTerminationMinutes == 0) {
			LOGGER.info("AzureCloudRetensionStrategy: check: Idle termination time for node is zero , "
					+ "no need to terminate the slave "+slaveNode.getDisplayName());
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
                		if (slaveNode.getChannel() != null ) {
    						slaveNode.getChannel().close();
                		}
                	} catch (Exception e) {
    					e.printStackTrace();
    					LOGGER.info("AzureCloudRetensionStrategy: check: exception occured while closing channel for: "+slaveNode.getName());
    				}
                    LOGGER.info("AzureCloudRetensionStrategy: check: Idle timeout reached for slave: "+slaveNode.getName());
                    
                    int retryCount = 0;
                    boolean successfull = false;
                    // Retrying for 30 times with 30 seconds wait time between each retry
                    while (retryCount < 30 && !successfull) {
	                    try {
							slaveNode.getNode().idleTimeout();
							successfull = true;
						} catch (Exception e) {
							retryCount++;
							LOGGER.info("AzureCloudRetensionStrategy: check: Exception occured while calling timeout on node , \n"
									+ "Will retry again after 30 seconds. Current retry count "+retryCount + "\n"
									+ "Error code "+e.getMessage());
							// We won't get exception for RNF , so for other exception types we can retry
							try {
								Thread.sleep(30 * 1000);
							} catch (InterruptedException e1) {
								e1.printStackTrace();
							}
						}
                    }
                }
            } 
        }
        return 1;
	}

	public void start(AzureComputer azureComputer) {
		//TODO: check when this method is getting called and add code accordingly
		LOGGER.info("AzureCloudRetensionStrategy: start: azureComputer name "+azureComputer.getDisplayName());
		azureComputer.connect(false);
	}

	public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
		public String getDisplayName() {
			return Constants.AZURE_CLOUD_DISPLAY_NAME;
		}
	}

}
