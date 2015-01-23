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

import java.io.IOException;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.Computer;

@Extension
public final class AzureSlaveCleanUpTask extends AsyncPeriodicWork {
	private static final Logger LOGGER = Logger.getLogger(AzureSlaveCleanUpTask.class.getName());

	public AzureSlaveCleanUpTask() {
		super("Azure slave clean task");
	}

	public void execute(TaskListener arg0) throws IOException, InterruptedException {
		for (Computer computer : Jenkins.getInstance().getComputers()) {
			if (computer instanceof AzureComputer) {
				AzureComputer azureComputer = (AzureComputer)computer;
				AzureSlave slaveNode = azureComputer.getNode();
				
				try {
					if (azureComputer.isOffline()) {
						if (!slaveNode.isDeleteSlave()) {
							// Find out if node exists in azure, if not continue with delete else do not delete node
							// although it is offline. May be JNLP or SSH launch is in progress
							if(AzureManagementServiceDelegate.isVirtualMachineExists(slaveNode)) {
								LOGGER.info("AzureSlaveCleanUpTask: execute: VM "+slaveNode.getDisplayName()+" exists in cloud");
								continue;
							}
						}
						
						int retryCount = 0;
						boolean successful = false;
						
						// Retrying for 30 times with 30 seconds wait time between each retry
						while (retryCount < 30 && !successful) {
							try {
								slaveNode.idleTimeout();
								successful = true;
							} catch (Exception e) {
								retryCount++;
								LOGGER.info("AzureSlaveCleanUpTask: execute: Exception occured while calling timeout on node, \n"
											+ "Will retry again after 30 seconds. Current retry count "+retryCount + " / 30\n"
											+ "Error code "+e.getMessage());
								// We won't get exception for RNF , so for other exception types we can retry
								if (e.getMessage().contains("not found in the currently deployed service")) {
									LOGGER.info("AzureSlaveCleanUpTask: execute: Slave does not exist in the subscription anymore, setting shutdownOnIdle to True");
									slaveNode.setShutdownOnIdle(true);
									break;
								}
								try {
									Thread.sleep(30 * 1000);
								} catch (InterruptedException e1) {
									e1.printStackTrace();
								}
							}
						}
						
						Jenkins.getInstance().removeNode(slaveNode);
					}
				} catch (Exception e) {
					LOGGER.severe("AzureSlaveCleanUpTask: execute: failed to remove node " + e);
					e.printStackTrace();
				}
			}
		}
	}

	public long getRecurrencePeriod() {
		// Every 5 minutes
		return 5 * 60 * 1000;
	}

}
