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
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.retry.DefaultRetryStrategy;
import com.microsoftopentechnologies.azure.util.ExecutionEngine;

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
				final AzureSlave slaveNode = azureComputer.getNode();
				
				if (azureComputer.isOffline()) {
					if(AzureManagementServiceDelegate.isVirtualMachineExists(slaveNode)) {
						if (!slaveNode.isDeleteSlave()) { 
							continue; //If agent is not marked for deletion, it means it is active.
						}
						
						Callable<Void> task = new Callable<Void>() {
							public Void call() throws Exception {
								slaveNode.idleTimeout();
								return null;
							}
						};
						
						try {
							ExecutionEngine.executeWithRetry(task, new DefaultRetryStrategy(3 /*max retries*/, 
									10 /*Default backoff in seconds*/ , 1 * 60 /* Max. timeout in seconds */));
				         } catch (AzureCloudException exception) {
				        	// No need to throw exception back, just log and move on. 
				        	 LOGGER.info("AzureSlaveCleanUpTask: execute: failed to remove node "+exception);
				         }
					} else {
						Jenkins.getInstance().removeNode(slaveNode);
					}
				}
			}
		}
	}

	public long getRecurrencePeriod() {
		// Every 5 minutes
		return 15 * 60 * 1000;
	}
}
