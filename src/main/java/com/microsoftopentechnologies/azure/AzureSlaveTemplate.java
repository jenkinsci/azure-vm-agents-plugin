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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.retry.DefaultRetryStrategy;
import com.microsoftopentechnologies.azure.retry.LinearRetryForAllExceptions;
import com.microsoftopentechnologies.azure.util.ExecutionEngine;
import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.management.compute.ComputeManagementClient;
import com.microsoft.windowsazure.management.compute.models.DeploymentSlot;
import com.microsoftopentechnologies.azure.util.AzureUtil;
import com.microsoftopentechnologies.azure.util.Constants;
import com.microsoftopentechnologies.azure.util.FailureStage;

import hudson.Extension;
import hudson.RelativePath;
import hudson.model.Describable;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

/**
 * This class defines the configuration of Azure instance templates
 * @author Suresh Nallamilli 
 * 
 */
public class AzureSlaveTemplate implements Describable<AzureSlaveTemplate> {
	// General Configuration
	private String templateName;
	private String templateDesc;
	private String labels;
	private String location;
	private String virtualMachineSize;
	private String storageAccountName;
	private int noOfParallelJobs;
	private Node.Mode useSlaveAlwaysIfAvail;
	private boolean shutdownOnIdle;
	// Image Configuration
	private String imageIdOrFamily;
	private String slaveLaunchMethod;
	private String initScript;
	private String adminUserName;
	private String adminPassword;
	private String slaveWorkSpace;
	private int retentionTimeInMin;
	private String virtualNetworkName;
	private String subnetName;
	private String jvmOptions;
	private String cloudServiceName;
	private String templateStatus;
	private String templateStatusDetails;
	public transient AzureCloud azureCloud;
	private transient Set<LabelAtom> labelDataSet;
	private static final Logger LOGGER = Logger.getLogger(AzureSlaveTemplate.class.getName());
	
	@DataBoundConstructor
	public AzureSlaveTemplate(String templateName, String templateDesc, String labels, String location, String virtualMachineSize,
			String storageAccountName, String noOfParallelJobs, Node.Mode useSlaveAlwaysIfAvail, String imageIdOrFamily, String slaveLaunchMethod,
			String initScript, String adminUserName, String adminPassword, String virtualNetworkName, String subnetName,
			String slaveWorkSpace, String jvmOptions, String cloudServiceName, String retentionTimeInMin, boolean shutdownOnIdle, 
			String templateStatus, String templateStatusDetails) {
		this.templateName = templateName;
		this.templateDesc = templateDesc;
		this.labels = labels;
		this.location = location;
		this.virtualMachineSize = virtualMachineSize;
		this.storageAccountName = storageAccountName;
		if (AzureUtil.isNull(noOfParallelJobs) || !noOfParallelJobs.matches(Constants.REG_EX_DIGIT) || noOfParallelJobs.trim().equals("0")) {
			this.noOfParallelJobs = 1;
		} else {
			this.noOfParallelJobs = Integer.parseInt(noOfParallelJobs);
		}
		this.useSlaveAlwaysIfAvail = useSlaveAlwaysIfAvail;
		this.shutdownOnIdle = shutdownOnIdle;
		this.imageIdOrFamily = imageIdOrFamily;
		this.initScript = initScript;
		this.slaveLaunchMethod = slaveLaunchMethod;
		this.adminUserName = adminUserName;
		this.adminPassword = adminPassword;
		this.virtualNetworkName = virtualNetworkName;
		this.subnetName = subnetName;
		this.slaveWorkSpace = slaveWorkSpace;
		this.jvmOptions = jvmOptions;
		if (AzureUtil.isNull(retentionTimeInMin) || !retentionTimeInMin.matches(Constants.REG_EX_DIGIT)) {
			this.retentionTimeInMin = Constants.DEFAULT_IDLE_TIME;
		} else {
			this.retentionTimeInMin = Integer.parseInt(retentionTimeInMin);
		}
		this.cloudServiceName = cloudServiceName;
		this.templateStatus = templateStatus;
		
		if(templateStatus.equalsIgnoreCase(Constants.TEMPLATE_STATUS_ACTIVE)) {
			this.templateStatusDetails = "";
		} else {
			this.templateStatusDetails = templateStatusDetails;
		}
		
		// Forms data which is not persisted
		readResolve();
	}

	private Object readResolve() {
		labelDataSet = Label.parse(labels);
		return this;
	}

	public String getLabels() {
		return labels;
	}

	public String getLocation() {
		return location;
	}

	public String getVirtualMachineSize() {
		return virtualMachineSize;
	}

	public String getStorageAccountName() {
		return storageAccountName;
	}
	
	public void setStorageAccountName(String storageAccountName) {
		this.storageAccountName = storageAccountName;
	}

	public Node.Mode getUseSlaveAlwaysIfAvail() {
		return useSlaveAlwaysIfAvail;
	}

	public boolean isShutdownOnIdle() {
		return shutdownOnIdle;
	}

	public String getImageIdOrFamily() {
		return imageIdOrFamily;
	}

	public String getInitScript() {
		return initScript;
	}

	public String getAdminUserName() {
		return adminUserName;
	}

	public String getAdminPassword() {
		return adminPassword;
	}
	
	public String getVirtualNetworkName() {
		return virtualNetworkName;
	}
	
	public void setVirtualNetworkName(String virtualNetworkName) {
		this.virtualNetworkName = virtualNetworkName;
	}
	
	public String getSubnetName() {
		return subnetName;
	}

	public void setSubnetName(String subnetName) {
		this.subnetName = subnetName;
	}
	
	public String getSlaveWorkSpace() {
		return slaveWorkSpace;
	}

	public int getRetentionTimeInMin() {
		return retentionTimeInMin;
	}

	public String getJvmOptions() {
		return jvmOptions;
	}

	public String getCloudServiceName() {
		return cloudServiceName;
	}

	public AzureCloud getAzureCloud() {
		return azureCloud;
	}

	public String getTemplateName() {
		return templateName;
	}

	public String getTemplateDesc() {
		return templateDesc;
	}

	public int getNoOfParallelJobs() {
		return noOfParallelJobs;
	}

	public String getSlaveLaunchMethod() {
		return slaveLaunchMethod;
	}
	
	public void setTemplateStatus(String templateStatus) {
		this.templateStatus = templateStatus;
	}

	public String getTemplateStatus() {
		return templateStatus;
	}
	
	public String getTemplateStatusDetails() {
		return templateStatusDetails;
	}
	
	public void setTemplateStatusDetails(String templateStatusDetails) {
		this.templateStatusDetails = templateStatusDetails;
	}

	public Descriptor<AzureSlaveTemplate> getDescriptor() {
		return Jenkins.getInstance().getDescriptor(getClass());
	}

	public Set<LabelAtom> getLabelDataSet() {
		return labelDataSet;
	}

	public AzureSlave provisionSlave(TaskListener listener) throws Exception {
			// TODO: Get nodes with label and see if we can use existing slave 
			return AzureManagementServiceDelegate.createVirtualMachine(this);
	}
	
	public void waitForReadyRole(final AzureSlave slave) throws Exception {
		final Configuration config = ServiceDelegateHelper.loadConfiguration(azureCloud.getSubscriptionId(), 
				azureCloud.getServiceManagementCert(), azureCloud.getPassPhrase(), azureCloud.getServiceManagementURL());
		
		Callable<Void> task = new Callable<Void>() {
			public Void call() throws Exception {
				String status = "NA";
				while (!status.equalsIgnoreCase(Constants.READY_ROLE_STATUS)) {
					LOGGER.info("AzureSlaveTemplate: waitForReadyRole: Current status of virtual machine "+slave.getNodeName()+" is "+status);
					Thread.sleep(30 * 1000);
					status = AzureManagementServiceDelegate.getVirtualMachineStatus(config, slave.getCloudServiceName(), 
							DeploymentSlot.Production, slave.getNodeName());
					LOGGER.info("AzureSlaveTemplate: waitForReadyRole: Waiting for 30 more seconds for role to be provisioned");
				}
				return null;
			}
		};
		
		try {
			ExecutionEngine.executeWithRetry(task, new DefaultRetryStrategy(10 /*max retries*/, 10 /*Default backoff*/ , 45 * 60 /* Max. timeout in seconds */));
            LOGGER.info("AzureSlaveTemplate: waitForReadyRole: virtual machine "+slave.getNodeName()+" is in ready state");
         } catch (AzureCloudException exception) {
        	 handleTemplateStatus("Got exception while checking for role availability "+exception, FailureStage.PROVISIONING, slave);
        	 LOGGER.info("AzureSlaveTemplate: waitForReadyRole: Got exception while checking for role availability "+exception);
        	 throw exception;
         }
	}
	
	public void handleTemplateStatus(String message, FailureStage failureStep, final AzureSlave slave) {
		// Delete slave in azure
		if (slave != null) {
			Callable<Void> task = new Callable<Void>() {
				public Void call() throws Exception {
					AzureManagementServiceDelegate.terminateVirtualMachine(slave, false);
					return null;
				}
			};
			
			try {
    			ExecutionEngine.executeWithRetry(task,  new LinearRetryForAllExceptions(3 /*maxRetries*/, 30/*waitinterval*/, 2 * 60/*timeout*/));
    		} catch (AzureCloudException e) {
    			LOGGER.info("AzureSlaveTemplate: handleTemplateStatus: could not terminate or shutdown "+slave.getNodeName());
    		}
		}
		
		// Disable template if applicable
		if (!templateStatus.equals(Constants.TEMPLATE_STATUS_ACTIVE_ALWAYS)) {
			setTemplateStatus(Constants.TEMPLATE_STATUS_DISBALED);
			// Register template for periodic check so that jenkins can make template active if validation errors are corrected
			AzureTemplateMonitorTask.registerTemplate(this);
		} else {
			// Wait for a while before retry
			if (FailureStage.VALIDATION.equals(failureStep)) {
				// No point trying immediately - wait for 5 minutes.
				LOGGER.info("AzureSlaveTemplate: handleTemplateStatus: Got validation error while provisioning slave, waiting for 5 minutes before retry");
				try {
					Thread.sleep(5 * 60 * 1000);
				} catch (InterruptedException e) {}
			} else {
				// Failure might be during Provisioning or post provisioning. back off for 5 minutes before retry.
				LOGGER.info("AzureSlaveTemplate: handleTemplateStatus: Got "+failureStep+" error, waiting for 5 minutes before retry");
				try {
					Thread.sleep(5 * 60 * 1000);
				} catch (InterruptedException e) {}
			}
			
		}
		setTemplateStatusDetails(message);
	}
	
	public int getVirtualMachineCount() throws Exception {
		Configuration config = ServiceDelegateHelper.loadConfiguration(azureCloud.getSubscriptionId(), 
				azureCloud.getServiceManagementCert(), azureCloud.getPassPhrase(), azureCloud.getServiceManagementURL());
		ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
		return AzureManagementServiceDelegate.getVirtualMachineCount(client);
	}
	
	public List<String> verifyTemplate() throws Exception {
		return AzureManagementServiceDelegate.verifyTemplate
				(azureCloud.getSubscriptionId(), azureCloud.getServiceManagementCert(), azureCloud.getPassPhrase(), azureCloud.getServiceManagementURL(),
				 azureCloud.getMaxVirtualMachinesLimit()+"", templateName, labels, location, virtualMachineSize, storageAccountName, noOfParallelJobs+"", 
				 imageIdOrFamily,slaveLaunchMethod, initScript, adminUserName, adminPassword, virtualNetworkName, subnetName,
				 retentionTimeInMin+"", cloudServiceName, templateStatus, jvmOptions, true);
	}

    @Extension
	public static final class DescriptorImpl extends Descriptor<AzureSlaveTemplate> {

		public String getDisplayName() {
			return null;
		}
		
		private transient Map<String, List<String>> vmSizesMap = new HashMap<String, List<String>>();
		private transient Map<String, List<String>> locationsMap = new HashMap<String, List<String>>();
		private transient Map<String, Set<String>> imageFamilyListMap = new HashMap<String, Set<String>>();
		private transient Map<String, Configuration> configObjectsMap = new HashMap<String, Configuration>();
		
		private synchronized List<String> getVMSizes(String subscriptionId, String serviceManagementCert, String passPhrase, String serviceManagementURL) {
			// check if there is entry already in map
			List<String> vmSizes = vmSizesMap.get(subscriptionId+serviceManagementCert);
			
			try {
				if (vmSizes != null) {
					return vmSizes;
				} else {
					Configuration config = getConfiguration(subscriptionId, serviceManagementCert, passPhrase, serviceManagementURL);
					vmSizes =  AzureManagementServiceDelegate.getVMSizes(config);
					vmSizesMap.put(subscriptionId+serviceManagementCert, vmSizes);				
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			if (vmSizes == null) {
				vmSizes = getDefaultVMSizes(serviceManagementURL);
			}
			
			return vmSizes;
		 }
		
		private List<String> getDefaultVMSizes(String serviceManagementURL) {
			List<String> vmSizes = new ArrayList<String>();
			vmSizes.add("Basic_A1");
			vmSizes.add("Basic_A0");
			vmSizes.add("Basic_A2");
			vmSizes.add("Basic_A3");
			vmSizes.add("Basic_A4");
			vmSizes.add("A5");
			vmSizes.add("A6");
			vmSizes.add("A7");
			vmSizes.add("A8");
			vmSizes.add("A9");
			
			return vmSizes;
		}
		
		private synchronized List<String> getVMLocations(String subscriptionId, String serviceManagementCert, String passPhrase, String serviceManagementURL) {
			// check if there is entry already in map
			List<String> locations = locationsMap.get(subscriptionId+serviceManagementCert);
			
			try {
				if (locations != null) {
					return locations;
				} else {
					Configuration config = getConfiguration(subscriptionId, serviceManagementCert, passPhrase, serviceManagementURL);
					locations =  AzureManagementServiceDelegate.getVirtualMachineLocations(config);
					locationsMap.put(subscriptionId+serviceManagementCert, locations);				
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			if (locations == null) {
				locations = getDefaultLocations(serviceManagementURL);
			}
			
			return locations;
		 }
		
		private List<String> getDefaultLocations(String serviceManagementURL) {
			List<String> locations = new ArrayList<String>();
			
			if (serviceManagementURL != null && serviceManagementURL.toLowerCase().equalsIgnoreCase("china")) {
				locations.add("China North");
				locations.add("China East");
			} else {
				locations.add("East US");
				locations.add("West US");
				locations.add("North Central US");
				locations.add("South Central US");
				locations.add("North Europe");
				locations.add("West Europe");
				locations.add("East Asia");
				locations.add("Southeast Asia");
				locations.add("Japan East");
				locations.add("Japan West");
				locations.add("Brazil South");			
			}
						
			return locations;
		}
		
		private synchronized Set<String> getImageFamilyList(String subscriptionId, String serviceManagementCert, String passPhrase, String serviceManagementURL) {
			// check if there is entry already in map
			Set<String> imageFamilyList = imageFamilyListMap.get(subscriptionId+serviceManagementCert);
			
			try {
				if (imageFamilyList != null) {
					return imageFamilyList;
				} else {
					Configuration config = getConfiguration(subscriptionId, serviceManagementCert, passPhrase, serviceManagementURL);
					imageFamilyList =  AzureManagementServiceDelegate.getVirtualImageFamilyList(config);
					imageFamilyListMap.put(subscriptionId+serviceManagementCert, imageFamilyList);				
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			return imageFamilyList;
		 }
		
		
		private Configuration getConfiguration(String subscriptionId, String serviceManagementCert, String passPhrase, String serviceManagementURL) throws IOException {
			// check if there is an entry already in a map
			Configuration config = configObjectsMap.get(subscriptionId+serviceManagementCert);
			if (config != null ) {
				return config;
			} else {
				config = ServiceDelegateHelper.loadConfiguration(subscriptionId, serviceManagementCert, passPhrase, serviceManagementURL);
				configObjectsMap.put(subscriptionId+serviceManagementCert, config);
				return config;
			}
		}

		public ListBoxModel doFillVirtualMachineSizeItems(
				@RelativePath("..") @QueryParameter String subscriptionId,
				@RelativePath("..") @QueryParameter String serviceManagementCert,
				@RelativePath("..") @QueryParameter String passPhrase,
				@RelativePath("..") @QueryParameter String serviceManagementURL)
				throws IOException, ServletException {
			
			ListBoxModel model = new ListBoxModel();
			// Validate data
			if (AzureUtil.isNull(subscriptionId) || AzureUtil.isNull(serviceManagementCert)) {
				return model;
			}
			
			List<String> vmSizes = vmSizesMap.get(subscriptionId+serviceManagementCert);
			
			if (vmSizes == null) {
				vmSizes = getVMSizes(subscriptionId, serviceManagementCert, passPhrase, serviceManagementURL);
			}
			
			for (String vmSize : vmSizes) {
				model.add(vmSize);
			}
			
			return model;
		}

		public ListBoxModel doFillLocationItems(
				@RelativePath("..") @QueryParameter String subscriptionId,
				@RelativePath("..") @QueryParameter String serviceManagementCert,
				@RelativePath("..") @QueryParameter String passPhrase,
				@RelativePath("..") @QueryParameter String serviceManagementURL)
				throws IOException, ServletException {
			ListBoxModel model = new ListBoxModel();
			// validate
			if (AzureUtil.isNull(subscriptionId) || AzureUtil.isNull(serviceManagementCert)) {
				return model;
			}

			List<String> locations = locationsMap.get(subscriptionId+serviceManagementCert);
			
			if (locations == null ){
				locations = getVMLocations(subscriptionId, serviceManagementCert, passPhrase, serviceManagementURL);
			}
			
			for (String location : locations) {
				model.add(location);
			}
			
			return model;
		}
		
		public ListBoxModel doFillSlaveLaunchMethodItems() {
			ListBoxModel model = new ListBoxModel();
			model.add(Constants.LAUNCH_METHOD_SSH);
			model.add(Constants.LAUNCH_METHOD_JNLP);
			
			return model;
		}
		
		public ListBoxModel doFillTemplateStatusItems() {
			ListBoxModel model = new ListBoxModel();
			model.add(Constants.TEMPLATE_STATUS_ACTIVE);
			model.add(Constants.TEMPLATE_STATUS_ACTIVE_ALWAYS);
			model.add(Constants.TEMPLATE_STATUS_DISBALED);
			return model;
		}
		
		public ComboBoxModel doFillImageIdOrFamilyItems(
				@RelativePath("..") @QueryParameter String subscriptionId,
				@RelativePath("..") @QueryParameter String serviceManagementCert,
				@RelativePath("..") @QueryParameter String passPhrase,
				@RelativePath("..") @QueryParameter String serviceManagementURL) {
			ComboBoxModel model = new ComboBoxModel();
			
			if (AzureUtil.isNull(subscriptionId) || AzureUtil.isNull(serviceManagementCert)) {
				return model;
			}
			
			Set<String> imageFamilyList = imageFamilyListMap.get(subscriptionId+serviceManagementCert);
			
			if (imageFamilyList == null) {
				imageFamilyList = getImageFamilyList(subscriptionId, serviceManagementCert, passPhrase, serviceManagementURL);
			}
			
			if (imageFamilyList != null) {
				for (String imageFamily : imageFamilyList) {
					model.add(imageFamily);
				}
			}

			return model;
        }
		
		public FormValidation doCheckInitScript(@QueryParameter String value, @QueryParameter String slaveLaunchMethod) {
			if (AzureUtil.isNull(value)) {
				return FormValidation.warningWithMarkup(Messages.Azure_GC_InitScript_Warn_Msg());
			}
			return FormValidation.ok();
		}
		
		public FormValidation doCheckStorageAccountName(@QueryParameter String value) {
			if (AzureUtil.isNull(value)) {
				return FormValidation.ok(Messages.SA_Blank_Create_New());
			}
			return FormValidation.ok();
		}
		
		public FormValidation doCheckSlaveLaunchMethod(@QueryParameter String value) {
			if (Constants.LAUNCH_METHOD_JNLP.equals(value)) {
				return FormValidation.warning(Messages.Azure_GC_LaunchMethod_Warn_Msg());
			}
			return FormValidation.ok();
		}
		
		public FormValidation doCheckTemplateName(@QueryParameter String value, @QueryParameter String templateStatus) {
			if (templateStatus.equals(Constants.TEMPLATE_STATUS_DISBALED)) {
				return FormValidation.error(Messages.Azure_GC_TemplateStatus_Warn_Msg());
			}
			return FormValidation.ok();
		}
		
//		public FormValidation doCheckTemplateStatus(@QueryParameter String value, @QueryParameter String templateStatusDetails) {
//			if (value != null && value.trim().length() > 0 && value.equalsIgnoreCase(Constants.TEMPLATE_STATUS_DISBALED)) {
//				return FormValidation.error(templateStatusDetails);
//			}
//			return FormValidation.ok();
//		}
//		
		public FormValidation doCheckAdminUserName(@QueryParameter String value) {
			if (AzureUtil.isNotNull(value)) {
				if (AzureUtil.isValidUserName(value)) {
					return FormValidation.ok();
				} else {
					return FormValidation.error(Messages.Azure_GC_UserName_Err());
				}
			}
			return FormValidation.ok();
		}
		
		public FormValidation doCheckNoOfParallelJobs(@QueryParameter String value) {
			if (AzureUtil.isNotNull(value)) {
				String result = AzureManagementServiceDelegate.verifyNoOfExecutors(value);
				
				if (result.equalsIgnoreCase(Constants.OP_SUCCESS)) {
					return FormValidation.ok();
				} else {
					return FormValidation.error(result);
				}
			}
			return FormValidation.ok();
		}
		
		public FormValidation doCheckRetentionTimeInMin(@QueryParameter String value) {
			if (AzureUtil.isNotNull(value)) {
				String result = AzureManagementServiceDelegate.verifyRetentionTime(value);
				
				if (result.equalsIgnoreCase(Constants.OP_SUCCESS)) {
					return FormValidation.ok();
				} else {
					return FormValidation.error(result);
				}
			}
			return FormValidation.ok();
		}
		
		public FormValidation doCheckAdminPassword(@QueryParameter String value) {
			if (AzureUtil.isNotNull(value)) {
				if (AzureUtil.isValidPassword(value)) {
					return FormValidation.ok();
				} else {
					return FormValidation.error(Messages.Azure_GC_Password_Err());
				}
			}
			return FormValidation.ok();
		}
		
		public FormValidation doCheckJvmOptions(@QueryParameter String value) {
			if (AzureUtil.isNotNull(value)) {
				if (AzureUtil.isValidJvmOption(value)) {
					return FormValidation.ok();
				} else {
					return FormValidation.error(Messages.Azure_GC_JVM_Option_Err());
				}
			}
			return FormValidation.ok();
		}
		
		public FormValidation doVerifyConfiguration(
				@RelativePath("..") @QueryParameter String subscriptionId,
				@RelativePath("..") @QueryParameter String serviceManagementCert,
				@RelativePath("..") @QueryParameter String passPhrase,
				@RelativePath("..") @QueryParameter String serviceManagementURL,
				@RelativePath("..") @QueryParameter String maxVirtualMachinesLimit,
				@QueryParameter String templateName, @QueryParameter String labels, @QueryParameter String location,
				@QueryParameter String virtualMachineSize, @QueryParameter String storageAccountName,
				@QueryParameter String noOfParallelJobs, @QueryParameter String imageIdOrFamily, @QueryParameter String slaveLaunchMethod,
				@QueryParameter String initScript, @QueryParameter String adminUserName,
				@QueryParameter String adminPassword, @QueryParameter String virtualNetworkName, @QueryParameter String subnetName,
				@QueryParameter String retentionTimeInMin, @QueryParameter String cloudServiceName,
				@QueryParameter String templateStatus, @QueryParameter String jvmOptions) {
			
			List<String> errors = AzureManagementServiceDelegate.verifyTemplate(
					subscriptionId, serviceManagementCert, passPhrase, serviceManagementURL, maxVirtualMachinesLimit,
					templateName, labels, location, virtualMachineSize, storageAccountName, noOfParallelJobs, imageIdOrFamily,
					slaveLaunchMethod, initScript, adminUserName, adminPassword, virtualNetworkName, subnetName,
					retentionTimeInMin, cloudServiceName,templateStatus,jvmOptions, false);
			
			
			if (errors.size() > 0) {
				StringBuilder errorString = new StringBuilder(Messages.Azure_GC_Template_Error_List()).append("\n");
				
				for (int i = 0 ; i < errors.size(); i++) {
					errorString.append(i+1).append(": ").append(errors.get(i)).append("\n");
				}

				return FormValidation.error(errorString.toString()) ;
				
			} else {
				return FormValidation.ok(Messages.Azure_Template_Config_Success());
			}
		}
		
		public String getDefaultNoOfExecutors() {
			return 1+"";
		}
	}

	public void setVirtualMachineDetails(AzureSlave slave) throws Exception {
		AzureManagementServiceDelegate.setVirtualMachineDetails(slave,this);		
	}
}
