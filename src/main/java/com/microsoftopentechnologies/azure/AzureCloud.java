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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.OfflineCause;
import hudson.util.FormValidation;
import hudson.util.StreamTaskListener;

import com.microsoftopentechnologies.azure.AzureSlaveTemplate;
import com.microsoftopentechnologies.azure.util.AzureUtil;
import com.microsoftopentechnologies.azure.util.Constants;
import com.microsoftopentechnologies.azure.util.FailureStage;

public class AzureCloud extends Cloud {
	private final String subscriptionId;
	private final String serviceManagementCert;
	private final String passPhrase="";
	private final String serviceManagementURL;
	private final int maxVirtualMachinesLimit;
	private final List<AzureSlaveTemplate> instTemplates;

	public static final Logger LOGGER = Logger.getLogger(AzureCloud.class.getName());

	@DataBoundConstructor
	public AzureCloud(String id, String subscriptionId, String serviceManagementCert, String serviceManagementURL, 
			String maxVirtualMachinesLimit, List<AzureSlaveTemplate> instTemplates, String fileName, String fileData) {
		super(Constants.AZURE_CLOUD_PREFIX+subscriptionId);
		this.subscriptionId = subscriptionId;
		this.serviceManagementCert = serviceManagementCert;
		
		if (AzureUtil.isNull(serviceManagementURL)) {
			this.serviceManagementURL = Constants.DEFAULT_MANAGEMENT_URL;
		} else {
			this.serviceManagementURL = serviceManagementURL;
		}
		
		if (AzureUtil.isNull(maxVirtualMachinesLimit) || !maxVirtualMachinesLimit.matches(Constants.REG_EX_DIGIT)) {
			this.maxVirtualMachinesLimit = Constants.DEFAULT_MAX_VM_LIMIT;
		} else {
			this.maxVirtualMachinesLimit = Integer.parseInt(maxVirtualMachinesLimit);
		}

		if (instTemplates == null) {
			this.instTemplates = Collections.emptyList();
		} else {
			this.instTemplates = instTemplates;
		}
		readResolve();
	}

	protected Object readResolve() {
		for (AzureSlaveTemplate template : instTemplates) {
			template.azureCloud = this;
		}
		return this;
	}

	public boolean canProvision(Label label) {
		AzureSlaveTemplate template =  getAzureSlaveTemplate(label);
		
		// return false if there is no template
		if (template == null) {
			LOGGER.info("Azurecloud: canProvision: template not found for label "+label.getDisplayName());
			return false;
		} else if (template.getTemplateStatus().equalsIgnoreCase(Constants.TEMPLATE_STATUS_DISBALED)) {
			LOGGER.info("Azurecloud: canProvision: template "+template.getTemplateName() + 
					" is marked has disabled, cannot provision slaves");
			return false;
		} else {
			return true;	
		}
	}

	public String getSubscriptionId() {
		return subscriptionId;
	}

	public String getServiceManagementCert() {
		return serviceManagementCert;
	}

	public String getServiceManagementURL() {
		return serviceManagementURL;
	}

	public int getMaxVirtualMachinesLimit() {
		return maxVirtualMachinesLimit;
	}
	
	public String getPassPhrase() {
		return passPhrase;
	}

	/** Returns slave template associated with the label */
	public AzureSlaveTemplate getAzureSlaveTemplate(Label label) {
		for (AzureSlaveTemplate slaveTemplate : instTemplates) {
			if (label.matches(slaveTemplate.getLabelDataSet())) {
				return slaveTemplate;
			}
		}
		return null;
	}
	
	/** Returns slave template associated with the name */
	public AzureSlaveTemplate getAzureSlaveTemplate(String name) {
		if (AzureUtil.isNull(name)) {
			return null;
		}
		
		for (AzureSlaveTemplate slaveTemplate : instTemplates) {
			if (name.equalsIgnoreCase(slaveTemplate.getTemplateName())) {
				return slaveTemplate;
			}
		}
		return null;
	}

	public Collection<PlannedNode> provision(Label label, int workLoad) {
		LOGGER.info("Azure Cloud: provision: start for label " + label+" workLoad "+workLoad);
		final AzureSlaveTemplate slaveTemplate = getAzureSlaveTemplate(label);
		List<PlannedNode> plannedNodes = new ArrayList<PlannedNode>();
		
		while (workLoad > 0) {
			// Verify template
			try {
				LOGGER.info("Azure Cloud: provision: Verifying template " + slaveTemplate.getTemplateName());
				List<String> errors = slaveTemplate.verifyTemplate();
				
				if (errors.size() > 0 ) {
					LOGGER.info("Azure Cloud: provision: template " + slaveTemplate.getTemplateName() + "has validation errors , cannot"
							+" provision slaves with this configuration "+errors);
					slaveTemplate.handleTemplateStatus("Validation Error: Validation errors in template \n" + " Root cause: "+errors, 
							FailureStage.VALIDATION, null);
					
					// Register template for periodic check so that jenkins can make template active if validation errors are corrected
					if (!Constants.TEMPLATE_STATUS_ACTIVE_ALWAYS.equals(slaveTemplate.getTemplateStatus())) {
						AzureTemplateMonitorTask.registerTemplate(slaveTemplate);
					}
					break;
				} else {
					LOGGER.info("Azure Cloud: provision: template " + slaveTemplate.getTemplateName() + " has no validation errors");
				}
			} catch (Exception e) {
				LOGGER.severe("Azure Cloud: provision: Exception occured while validating template"+e);
				slaveTemplate.handleTemplateStatus("Validation Error: Exception occured while validating template "+e.getMessage(), 
						FailureStage.VALIDATION, null);
				
				// Register template for periodic check so that jenkins can make template active if validation errors are corrected
				if (!Constants.TEMPLATE_STATUS_ACTIVE_ALWAYS.equals(slaveTemplate.getTemplateStatus())) {
					AzureTemplateMonitorTask.registerTemplate(slaveTemplate);
				}
				break;
			}

			plannedNodes.add(new PlannedNode(slaveTemplate.getTemplateName(),
					Computer.threadPoolForRemoting.submit(new Callable<Node>() {
						
						public Node call() throws Exception {
							@SuppressWarnings("deprecation")
							AzureSlave slave = slaveTemplate.provisionSlave(new StreamTaskListener(System.out));
							// Get virtual machine properties
							LOGGER.info("Azure Cloud: provision: Getting virtual machine properties for slave "+slave.getNodeName() 
									+ " with OS "+slave.getOsType());
							slaveTemplate.setVirtualMachineDetails(slave);
							try {
								if (slave.getSlaveLaunchMethod().equalsIgnoreCase("SSH")) {
									 slaveTemplate.waitForReadyRole(slave);
									 LOGGER.info("Azure Cloud: provision: Waiting for ssh server to comeup");
									 Thread.sleep(2 * 60 * 1000);
									 LOGGER.info("Azure Cloud: provision: ssh server may be up by this time");
									 LOGGER.info("Azure Cloud: provision: Adding slave to azure nodes ");
									 Hudson.getInstance().addNode(slave);
									 slave.toComputer().connect(false).get();
								 } else if (slave.getSlaveLaunchMethod().equalsIgnoreCase("JNLP")) {
									 LOGGER.info("Azure Cloud: provision: Checking for slave status");
									 slaveTemplate.waitForReadyRole(slave);
									 Hudson.getInstance().addNode(slave);
									 
									 // Wait until node is online
									 waitUntilOnline(slave);
								 }
							} catch (Exception e) {
								slaveTemplate.handleTemplateStatus(e.getMessage(), FailureStage.POSTPROVISIONING, slave);
								throw e;
							}
							return slave;
						}
					}), slaveTemplate.getNoOfParallelJobs()));

			// Decrement workload
			workLoad -= slaveTemplate.getNoOfParallelJobs();
		}
		return plannedNodes;
	}
	
	/** this methods wait for node to be available */
	private void waitUntilOnline(final AzureSlave slave) {
		LOGGER.info("Azure Cloud: waitUntilOnline: for slave "+slave.getDisplayName());
		ExecutorService executorService = Executors.newCachedThreadPool();
		Callable<String> callableTask = new Callable<String>() {
			public String call() {
				try {
					slave.toComputer().waitUntilOnline();
				} catch (InterruptedException e) {
                	 // just ignore
                }
				return "success";
			}
		};
	    Future<String> future = executorService.submit(callableTask);
	    
	    try {
	    	// 30 minutes is decent time for the node to be alive
	    	String result = future.get(30, TimeUnit.MINUTES); 
	    	LOGGER.info("Azure Cloud: waitUntilOnline: node is alive , result "+result);
		} catch (TimeoutException ex) {
			LOGGER.info("Azure Cloud: waitUntilOnline: Got TimeoutException "+ex);
			markSlaveForDeletion(slave, Constants.JNLP_POST_PROV_LAUNCH_FAIL);
		} catch (InterruptedException ex) {
			LOGGER.info("Azure Cloud: InterruptedException: Got TimeoutException "+ex);
			markSlaveForDeletion(slave, Constants.JNLP_POST_PROV_LAUNCH_FAIL);
		} catch (ExecutionException ex) {
			LOGGER.info("Azure Cloud: ExecutionException: Got TimeoutException "+ex);
			markSlaveForDeletion(slave, Constants.JNLP_POST_PROV_LAUNCH_FAIL);
	    } finally {
	       future.cancel(true);
	       executorService.shutdown();
	    }
	}
	
	private static void markSlaveForDeletion(AzureSlave slave, String message) {
		slave.setTemplateStatus(Constants.TEMPLATE_STATUS_DISBALED, message);
		slave.toComputer().setTemporarilyOffline(true, OfflineCause.create(Messages._Slave_Failed_To_Connect()));
		slave.setDeleteSlave(true);
	}
	
	public void doProvision(StaplerRequest req, StaplerResponse rsp, @QueryParameter String templateName) throws Exception {
		LOGGER.info("Azure Cloud: doProvision: start name = "+templateName);
		checkPermission(PROVISION);
		
		if (AzureUtil.isNull(templateName)) {
			sendError("Azure Cloud: doProvision: Azure Slave template name is missing", req, rsp);
			return;
		}
		
		final AzureSlaveTemplate slaveTemplate = getAzureSlaveTemplate(templateName);
		
		if (slaveTemplate == null) {
			sendError("Azure Cloud: doProvision: Azure Slave template configuration is not there for  : " + templateName, req, rsp);
			return;
		}
		
		// 1. Verify template
		try {
			LOGGER.info("Azure Cloud: doProvision: Verifying template " + slaveTemplate.getTemplateName());
			List<String> errors = slaveTemplate.verifyTemplate();
			
			if (errors.size() > 0 ) {
				LOGGER.info("Azure Cloud: doProvision: template " + slaveTemplate.getTemplateName() + " has validation errors , cannot"
						+" provision slaves with this configuration "+errors);
				sendError("template " + slaveTemplate.getTemplateName() + "has validation errors "+errors, req, rsp);
				return;
			} else {
				LOGGER.info("Azure Cloud: provision: template " + slaveTemplate.getTemplateName() + "has no validation errors");
			}
		} catch (Exception e) {
			LOGGER.severe("Azure Cloud: provision: Exception occurred while validating template "+e);
			sendError("Exception occurred while validating template "+e);
			return;
		}
		
		LOGGER.severe("Azure Cloud: doProvision: creating slave ");
		Computer.threadPoolForRemoting.submit(new Callable<Node>() {
			
			public Node call() throws Exception {
				@SuppressWarnings("deprecation")
				AzureSlave slave = slaveTemplate.provisionSlave(new StreamTaskListener(System.out));
				// Get virtual machine properties
				LOGGER.info("Azure Cloud: provision: Getting virtual machine properties for slave "+slave.getNodeName() 
						+ " with OS "+slave.getOsType());
				slaveTemplate.setVirtualMachineDetails(slave);
				
				if (slave.getSlaveLaunchMethod().equalsIgnoreCase("SSH")) {
					 slaveTemplate.waitForReadyRole(slave);
					 LOGGER.info("Azure Cloud: provision: Waiting for ssh server to come up");
					 Thread.sleep(2 * 60 * 1000);
					 LOGGER.info("Azure Cloud: provision: ssh server may be up by this time");
					 LOGGER.info("Azure Cloud:  provision: Adding slave to azure nodes ");
					 Hudson.getInstance().addNode(slave);
					 slave.toComputer().connect(false).get();
				 } else if (slave.getSlaveLaunchMethod().equalsIgnoreCase("JNLP")) {
					 LOGGER.info("Azure Cloud:  provision: Checking for slave status");
					 slaveTemplate.waitForReadyRole(slave);
					 Hudson.getInstance().addNode(slave);
					 
					 // Wait until node is online
					 waitUntilOnline(slave);
				 }
				return slave;
			}
		});
		rsp.sendRedirect2(req.getContextPath() + "/computer/");
		return;
	}

	public List<AzureSlaveTemplate> getInstTemplates() {
		return Collections.unmodifiableList(instTemplates);
	}
	
	@Extension
	public static class DescriptorImpl extends Descriptor<Cloud> {

		public String getDisplayName() {
			return Constants.AZURE_CLOUD_DISPLAY_NAME;
		}

		public String getDefaultserviceManagementURL() {
			return Constants.DEFAULT_MANAGEMENT_URL;
		}
		
		public int getDefaultMaxVMLimit() {
			return Constants.DEFAULT_MAX_VM_LIMIT;
		}

		public FormValidation doVerifyConfiguration(@QueryParameter String subscriptionId, @QueryParameter String serviceManagementCert, 
				@QueryParameter String passPhrase, @QueryParameter String serviceManagementURL) {
			
			if (AzureUtil.isNull(subscriptionId)) {
				return FormValidation.error("Error: Subscription ID is missing");
			}
			
			if (AzureUtil.isNull(serviceManagementCert)) {
				return FormValidation.error("Error: Management service certificate is missing");
			}
			
			if (AzureUtil.isNull(serviceManagementURL)) {
				serviceManagementURL = Constants.DEFAULT_MANAGEMENT_URL;
			}
			
			String response = AzureManagementServiceDelegate.verifyConfiguration(subscriptionId, serviceManagementCert, passPhrase, serviceManagementURL);
			
			if (response.equalsIgnoreCase("Success")) {
				return FormValidation.ok(Messages.Azure_Config_Success());
			} else {
				return FormValidation.error(response);
			}
		}
	}
}
