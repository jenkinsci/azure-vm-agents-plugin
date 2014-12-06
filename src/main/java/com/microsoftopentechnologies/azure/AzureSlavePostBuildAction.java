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

import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ListBoxModel;

import com.microsoftopentechnologies.azure.Messages;

public class AzureSlavePostBuildAction extends Recorder {
	
	/** Windows Azure Storage Account Name. */
	private String slavePostBuildAction;
	public static final Logger LOGGER = Logger.getLogger(AzureSlavePostBuildAction.class.getName());
	
	@DataBoundConstructor
	public AzureSlavePostBuildAction(final String slavePostBuildAction) {
		super();
		this.slavePostBuildAction = slavePostBuildAction;
	}
	
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		
		LOGGER.info("AzureSlavePostBuildAction: perform: build is not successful , taking post build action "+slavePostBuildAction+"  for slave ");
		Node node = Computer.currentComputer().getNode();
	
		int retryCount = 0;
        boolean successfull = false;
        // Retrying for 30 times with 30 seconds wait time between each retry
        while (retryCount < 30 && !successfull) {
			try {
				//check if node is instance of azure slave
				if (node instanceof AzureSlave) {
					AzureSlave slave = (AzureSlave)node;
					if (slave.getChannel() != null) {
						slave.getChannel().close();
					}
					
					if (Messages.Build_Action_Shutdown_Slave().equalsIgnoreCase(slavePostBuildAction)) {
						slave.setShutdownOnIdle(true);
						slave.idleTimeout();
					} else if (Messages.Build_Action_Delete_Slave().equalsIgnoreCase(slavePostBuildAction)
							|| (Messages.Build_Action_Delete_Slave_If_Not_Success().equalsIgnoreCase(slavePostBuildAction) && build.getResult() != Result.SUCCESS))	{
						slave.setShutdownOnIdle(false);
						slave.idleTimeout();
					} 
				}
				successfull = true;
			} catch (Exception e) {
				retryCount++;
				LOGGER.info("AzureSlavePostBuildAction: perform: Exception occured while " + slavePostBuildAction + "\n"
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
		
		return true;
	}
	
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.STEP;
	}

	@Extension
	public static final class AzureSlavePostBuildDescriptor extends
			BuildStepDescriptor<Publisher> {

		public boolean isApplicable(Class<? extends AbstractProject> arg0) {
			return true;
		}
		
		public ListBoxModel doFillSlavePostBuildActionItems() {
			ListBoxModel model = new ListBoxModel();
			model.add(Messages.Build_Action_Shutdown_Slave());
			model.add(Messages.Build_Action_Delete_Slave());
			model.add(Messages.Build_Action_Delete_Slave_If_Not_Success());			
			return model;
		}

		@Override
		public String getDisplayName() {
			// TODO Auto-generated method stub
			return Messages.Azure_Slave_Post_Build_Action();
		}
		
	}
	
}
