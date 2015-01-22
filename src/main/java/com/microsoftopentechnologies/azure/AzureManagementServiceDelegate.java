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

import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import jenkins.slaves.JnlpSlaveAgentProtocol;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.xml.sax.SAXException;

import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.core.OperationStatusResponse;
import com.microsoft.windowsazure.exception.ServiceException;
import com.microsoft.windowsazure.management.ManagementClient;
import com.microsoft.windowsazure.management.compute.ComputeManagementClient;
import com.microsoft.windowsazure.management.compute.ComputeManagementService;
import com.microsoft.windowsazure.management.compute.models.ConfigurationSet;
import com.microsoft.windowsazure.management.compute.models.ConfigurationSetTypes;
import com.microsoft.windowsazure.management.compute.models.DeploymentGetResponse;
import com.microsoft.windowsazure.management.compute.models.DeploymentSlot;
import com.microsoft.windowsazure.management.compute.models.HostedServiceCreateParameters;
import com.microsoft.windowsazure.management.compute.models.HostedServiceGetResponse;
import com.microsoft.windowsazure.management.compute.models.HostedServiceListResponse;
import com.microsoft.windowsazure.management.compute.models.HostedServiceListResponse.HostedService;
import com.microsoft.windowsazure.management.compute.models.HostedServiceProperties;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineOSImageGetResponse;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineOSImageListResponse.VirtualMachineOSImage;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineVMImageListResponse.VirtualMachineVMImage;
import com.microsoft.windowsazure.management.compute.models.InputEndpoint;
import com.microsoft.windowsazure.management.compute.models.InstanceEndpoint;
import com.microsoft.windowsazure.management.compute.models.OSVirtualHardDisk;
import com.microsoft.windowsazure.management.compute.models.PostShutdownAction;
import com.microsoft.windowsazure.management.compute.models.ResourceExtensionParameterValue;
import com.microsoft.windowsazure.management.compute.models.ResourceExtensionReference;
import com.microsoft.windowsazure.management.compute.models.Role;
import com.microsoft.windowsazure.management.compute.models.RoleInstance;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineCreateDeploymentParameters;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineCreateParameters;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineOSImageListResponse;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineRoleType;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineShutdownParameters;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineVMImageListResponse;
import com.microsoft.windowsazure.management.models.AffinityGroupGetResponse;
import com.microsoft.windowsazure.management.models.LocationsListResponse;
import com.microsoft.windowsazure.management.models.LocationsListResponse.Location;
import com.microsoft.windowsazure.management.models.RoleSizeListResponse;
import com.microsoft.windowsazure.management.models.RoleSizeListResponse.RoleSize;
import com.microsoft.windowsazure.management.network.NetworkManagementClient;
import com.microsoft.windowsazure.management.network.models.NetworkListResponse;
import com.microsoft.windowsazure.management.network.models.NetworkListResponse.Subnet;
import com.microsoft.windowsazure.management.network.models.NetworkListResponse.VirtualNetworkSite;
import com.microsoft.windowsazure.management.storage.StorageManagementClient;
import com.microsoft.windowsazure.management.storage.StorageManagementService;
import com.microsoft.windowsazure.management.storage.models.CheckNameAvailabilityResponse;
import com.microsoft.windowsazure.management.storage.models.StorageAccount;
import com.microsoft.windowsazure.management.storage.models.StorageAccountCreateParameters;
import com.microsoft.windowsazure.management.storage.models.StorageAccountListResponse;
import com.microsoft.windowsazure.management.storage.models.StorageAccountProperties;
import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.retry.ExponentialRetryStrategy;
import com.microsoftopentechnologies.azure.util.AzureUtil;
import com.microsoftopentechnologies.azure.util.Constants;
import com.microsoftopentechnologies.azure.util.ExecutionEngine;
import com.microsoftopentechnologies.azure.util.FailureStage;
/**
 * Business delegate class which handles calls to Azure management service SDK.
 * @author Suresh Nallamilli (snallami@gmail.com)
 *
 */
public class AzureManagementServiceDelegate {
	private static final Logger LOGGER = Logger.getLogger(AzureManagementServiceDelegate.class.getName());

    /**
     * Handles virtual machine creation in azure as per the configuration in slave template.
     * @param  template Slave template configuration as defined in global configuration.
     * @return instance of Azure slave which holds necessary details of virtual machine and cloud details.
     * @throws AzureCloudException throws exception in case of any error during virtual machine creation.
     */
	public static AzureSlave createVirtualMachine(AzureSlaveTemplate template) throws AzureCloudException {
		AzureSlave slave = null;

		try {
			LOGGER.info("AzureManagementServiceDelegate: createVirtualMachine: Initializing create virtual "
					  + "machine request for slaveTemaple " + template.getTemplateName());
			AzureCloud azureCloud = template.getAzureCloud();
			String subscriptionID = azureCloud.getSubscriptionId();

			// Load configuration
			Configuration config = ServiceDelegateHelper.loadConfiguration(subscriptionID, azureCloud.getServiceManagementCert(), 
					azureCloud.getPassPhrase(), azureCloud.getServiceManagementURL());
			ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
			
			// Get image properties
			Map<ImageProperties, String> imageProperties = getImageProperties(config, template.getImageIdOrFamily().trim());
			LOGGER.info("AzureManagementServiceDelegate: createVirtualMachine: imageProperties "+imageProperties);
			
			// Get virtual network location
			VirtualNetworkSite virtualNetworkSite  = getVirtualNetworkSite(config, template.getVirtualNetworkName());
			String virtualNetworkLocation = null;
			String affinityGroupName = null;
			if (virtualNetworkSite != null) {
				Subnet subnet = getSubnet(virtualNetworkSite, template.getSubnetName());
				// set derived values back in template so that there won't be case sensitive issues.
				template.setVirtualNetworkName(virtualNetworkSite.getName());
				template.setSubnetName(subnet != null ? subnet.getName() : null);
				virtualNetworkLocation = getVirtualNetworkLocation(config, virtualNetworkSite);
				affinityGroupName = virtualNetworkSite.getAffinityGroup();
			}
			String resourceLocation = virtualNetworkLocation == null ? template.getLocation() : virtualNetworkLocation;
			
			String mediaURI = imageProperties.get(ImageProperties.MEDIAURI);
			String customImageStorageAccountName = null ;
			if (mediaURI != null) {
				customImageStorageAccountName = getCustomImageStorageAccountName(new URI(imageProperties.get(ImageProperties.MEDIAURI)));
			}
			
			if (customImageStorageAccountName != null ) {
				template.setStorageAccountName(customImageStorageAccountName);
			}
						
			// create storage account if required
			if (customImageStorageAccountName == null && AzureUtil.isNull(template.getStorageAccountName()))  {
				try {
					String storageAccountName = createStorageAccount(config, resourceLocation, affinityGroupName);
					template.setStorageAccountName(storageAccountName);
				} catch(Exception e) {
					template.handleTemplateStatus("Pre-Provisioning Failure: Exception occured while creating storage account. Root cause: "+e.getMessage(), FailureStage.PREPROVISIONING, null);
					throw new AzureCloudException("Pre-Provisioning Failure: Exception occured while creating storage account. Root cause: "+e.getMessage());
				}
			}

			// Get cloud service name
			String cloudServiceName = AzureUtil.isNotNull(template.getCloudServiceName()) ? template.getCloudServiceName() : template.getTemplateName();
			String deploymentName = getExistingDeploymentName(config, cloudServiceName);
			OperationStatusResponse response = null;
			boolean successful = false;
			int retryCount = 0;
			
			// Check if cloud service exists or not , if not create new one
			if ((createCloudServiceIfNotExists(config, cloudServiceName, resourceLocation, affinityGroupName)) || (deploymentName == null) ) {
				deploymentName = cloudServiceName;
				LOGGER.info("AzureManagementServiceDelegate: createVirtualMachine: Creating deployment " + deploymentName +
						    " for cloud service " + cloudServiceName);

				VirtualMachineCreateDeploymentParameters params = createVirtualMachineDeploymentParams(config, 
																  cloudServiceName, deploymentName, imageProperties, template);
				while (retryCount < Constants.MAX_PROV_RETRIES && !successful) {
					retryCount++;	
					try {
						response = client.getVirtualMachinesOperations().createDeployment(cloudServiceName, params);
						successful = true;
					} catch (Exception ex) {
						handleProvisioningException(ex, template, deploymentName);
						
						if (retryCount >= Constants.MAX_PROV_RETRIES) {
							template.handleTemplateStatus("Provisioning Failure: Not able to create virtual machine even after 20 retries "+ex, FailureStage.PROVISIONING, null);
							throw new AzureCloudException("AzureManagementServiceDelegate: createVirtualMachine: Unable to create virtual machine "+ex.getMessage());
						}
					}
				}
				
				// Parse deployment response and create node/slave oject
				slave = parseDeploymentResponse(response, cloudServiceName, template, params);
			} else {
				LOGGER.info("AzureManagementServiceDelegate: createVirtualMachine: Creating VM for cloud service " + cloudServiceName);
				VirtualMachineCreateParameters params = createVirtualMachineParams(config, cloudServiceName, 
						deploymentName, imageProperties, template);

				while (retryCount < Constants.MAX_PROV_RETRIES && !successful) {
					retryCount++;
					
					try {
						response = client.getVirtualMachinesOperations().create(cloudServiceName, deploymentName, params);
						successful = true;
					} catch (Exception ex) {
						handleProvisioningException(ex, template, deploymentName);
						
						if (retryCount == Constants.MAX_PROV_RETRIES) {
							template.handleTemplateStatus("Provisioning Failure: Not able to create virtual machine even after 20 retries "+ex, FailureStage.PROVISIONING, null);
							throw new AzureCloudException("AzureManagementServiceDelegate: createVirtualMachine: Unable to create virtual machine "+ex.getMessage());
						}
												
					}
				}
				
				slave = parseResponse(response, cloudServiceName, deploymentName, template, params);
			}
			
			// Reset template status if call is successful after retries
			if (successful && Constants.TEMPLATE_STATUS_DISBALED.equals(template.getTemplateStatus())) {
				template.setTemplateStatus(Constants.TEMPLATE_STATUS_ACTIVE);
				template.setTemplateStatusDetails("");
			}
			LOGGER.info("Successfully created virtual machine in cloud service "	+ slave);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.severe("AzureManagementServiceDelegate: createVirtualMachine:: Unable to create virtual machine due to " + e.getMessage());
			throw new AzureCloudException("AzureManagementServiceDelegate: createVirtualMachine: Unable to create virtual machine due to "
							+ e.getMessage());
		}
		return slave;
	}
	
	/** Handle provisioning errors 	*/
	public static void handleProvisioningException(Exception ex, AzureSlaveTemplate template, String deploymentName) throws AzureCloudException {
		// conflict error - wait for 1 minute and try again
		if (AzureUtil.isConflictError(ex.getMessage())) {
			if (AzureUtil.isDeploymentAlreadyOccupied(ex.getMessage())) {
				LOGGER.info("AzureManagementServiceDelegate: handleProvisioningServiceException: Deployment already occupied");
				
				// Throw exception so that in retry this will go through
				throw new AzureCloudException("Provisioning Failure: Exception occured while creating virtual machine. Root cause: "+ex.getMessage());
			} else {
				LOGGER.info("AzureManagementServiceDelegate: handleProvisioningServiceException: conflict error: waiting for a minute and will try again");
				try {
					Thread.sleep(60 * 1000);
				} catch (InterruptedException e) {
					//ignore
				}
			}
		} else if (AzureUtil.isBadRequestOrForbidden(ex.getMessage())) {
			LOGGER.info("AzureManagementServiceDelegate: handleProvisioningServiceException: Got bad request or forbidden");
			// no point in retrying
			template.handleTemplateStatus("Provisioning Failure: Exception occured while creating virtual machine. Root cause: "+ex.getMessage(), FailureStage.PROVISIONING, null);
			throw new AzureCloudException("Provisioning Failure: Exception occured while creating virtual machine. Root cause: "+ex.getMessage());
		} else if (AzureUtil.isDeploymentNotFound(ex.getMessage(), deploymentName)) {
			LOGGER.info("AzureManagementServiceDelegate: handleProvisioningServiceException: Deployment not found");
			
			// Throw exception so that in retry this will go through
			throw new AzureCloudException("Provisioning Failure: Exception occured while creating virtual machine. Root cause: "+ex.getMessage());
		} else {
			LOGGER.info("AzureManagementServiceDelegate: handleProvisioningException: "+ex);
			// set template status to disabled so that jenkins won't provision more slaves
			template.handleTemplateStatus("Provisioning Failure: Exception occured while creating virtual machine. Root cause: "+ex.getMessage(), FailureStage.PROVISIONING, null);
			// wait for 10 seconds and then retry
			try {
				Thread.sleep(10 * 1000);
			} catch (InterruptedException e) {
				//ignore
			}
		}
	}
	
	
	/** Retrieves production slot deployment name for cloud service*/
	private static String getExistingDeploymentName(Configuration config, String cloudServiceName) {
		ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
		
		try {
			DeploymentGetResponse response = client.getDeploymentsOperations().getBySlot(cloudServiceName, DeploymentSlot.Production);
			
			if (response.getRoleInstances().size() > 0) {
				return response.getName();
			} else {
				return null;
			}
		
		} catch (Exception e) {
			// No need to check for specific exceptions since anyway final provisioning will fail
			LOGGER.info("AzureManagementServiceDelegate: Got exception while getting deployment name which may indicates there are no deployments");
			return null;
		}
	}
	
	/**
	 * Adds custom script extension
	 * @param config Azure cloud config object
	 * @param roleName rolename 
	 * @param cloudServiceName cloud service name
	 * @param template slave template configuration
	 * @return List of ResourceExtensionReference
	 * @throws AzureCloudException
	 */
	public static ArrayList<ResourceExtensionReference> handleCustomScriptExtension(Configuration config, String roleName,
			String cloudServiceName, AzureSlaveTemplate template) throws AzureCloudException {
		
		try {
			StorageManagementClient client = ServiceDelegateHelper.getStorageManagementClient(config);		
			String storageAccountKey = client.getStorageAccountsOperations().getKeys(template.getStorageAccountName()).getPrimaryKey();
			// upload init script.
			String fileName = cloudServiceName+roleName+"initscript.ps1";
			String initScript = null;
			
			String jnlpSecret = JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(roleName);
			
			if (AzureUtil.isNull(template.getInitScript())) {
				// Move this to a file
				initScript = AzureUtil.DEFAULT_INIT_SCRIPT;
			} else {
				initScript = template.getInitScript();
			}
			
			// upload file to storage.
			String blobURL = StorageServiceDelegate.uploadConfigFileToStorage(config, template.getStorageAccountName(), storageAccountKey, 
					         												  client.getBaseUri().toString(), fileName, initScript);
			
			// Get jenkins server url as configured in global configuration.
			String jenkinsServerURL = Hudson.getInstance().getRootUrl();
			if (jenkinsServerURL != null && !jenkinsServerURL.endsWith(Constants.FWD_SLASH)) {
				jenkinsServerURL = jenkinsServerURL + Constants.FWD_SLASH;
			}
			LOGGER.info("AzureManagementServiceDelegate: handleCustomScriptExtension: Jenkins server url "+jenkinsServerURL);
			// set custom script extension in role
			return addResourceExtenions(roleName, template.getStorageAccountName(), storageAccountKey, Constants.CONFIG_CONTAINER_NAME, blobURL, fileName, jenkinsServerURL, jnlpSecret);
		} catch (Exception e) {
			e.printStackTrace();
			throw new AzureCloudException("AzureManagementServiceDelegate: handleCustomScriptExtension: Exception occured while adding custom extension "+e);
		}
		
	} 
	
    /** Adds BGInfo and CustomScript extension */
	public static ArrayList<ResourceExtensionReference> addResourceExtenions(String roleName, String storageAccountName, 
			String storageAccountKey, String containerName, String blobURL, String fileName, String jenkinsServerURL, String jnlpSecret) throws Exception {
		ArrayList<ResourceExtensionReference> resourceExtensions = new ArrayList<ResourceExtensionReference>();

		// Add custom script extension
		ResourceExtensionReference customExtension = new ResourceExtensionReference();
		resourceExtensions.add(customExtension);
		customExtension.setReferenceName("CustomScriptExtension");
		customExtension.setPublisher("Microsoft.Compute");
		customExtension.setName("CustomScriptExtension");
		customExtension.setVersion("1.*");
		customExtension.setState("Enable");

		ArrayList<ResourceExtensionParameterValue> resourceParams = new ArrayList<ResourceExtensionParameterValue>();
		customExtension.setResourceExtensionParameterValues(resourceParams);

		ResourceExtensionParameterValue pubicConfig = new ResourceExtensionParameterValue();
		resourceParams.add(pubicConfig);
		pubicConfig.setKey("CustomScriptExtensionPublicConfigParameter");

		// Generate SAS URL
		String sasURL = StorageServiceDelegate.generateSASURL(storageAccountName, storageAccountKey, containerName, blobURL);
		
		// Get user credentials
//		User user = User.current() != null ? User.current(): Jenkins.ANONYMOUS.getName();
//		String userID = null;
//		String token = null;
//		if (user != null ) {
//			userID = user.getId();
//			ApiTokenProperty apiToken = user.getProperty(jenkins.security.ApiTokenProperty.class);
//			if (apiToken != null) {
//				token = apiToken.getApiToken();
//			}
//		}
//		LOGGER.info("AzureManagementServiceDelegate: addResourceExtenions: user.getId() "+userID + " API token "+token);
		
		pubicConfig.setValue(getCustomScriptPublicConfigValue(sasURL, fileName, jenkinsServerURL, roleName, jnlpSecret));
		pubicConfig.setType("Public");

		ResourceExtensionParameterValue privateConfig = new ResourceExtensionParameterValue();
		resourceParams.add(privateConfig);
		privateConfig.setKey("CustomScriptExtensionPrivateConfigParameter");
		privateConfig.setValue(getCustomScriptPrivateConfigValue(storageAccountName, storageAccountKey));
		privateConfig.setType("Private");
		
		return resourceExtensions;
	}
	
	/** JSON string custom script public config value */
	public static String getCustomScriptPublicConfigValue(String sasURL, String fileName, String jenkinsServerURL, String vmName, 
			String jnlpSecret) throws Exception {
		JsonFactory factory = new JsonFactory();
		StringWriter stringWriter = new StringWriter();
		JsonGenerator json = factory.createJsonGenerator(stringWriter);
		
		json.writeStartObject();
		json.writeArrayFieldStart("fileUris");
		json.writeString(sasURL);
		json.writeEndArray();
		json.writeStringField("commandToExecute","powershell -ExecutionPolicy Unrestricted -file " + fileName
			    + " " + jenkinsServerURL + " " + vmName + " " + jnlpSecret+ "  "+ " 2>>c:\\error.log");
		json.writeEndObject();
		json.close();
		return stringWriter.toString();
	}

	/** JSON string for custom script private config value */
	public static String getCustomScriptPrivateConfigValue(String storageAccountName, String storageAccountKey)
	throws Exception {
		JsonFactory factory = new JsonFactory();
		StringWriter stringWriter = new StringWriter();
		JsonGenerator json = factory.createJsonGenerator(stringWriter);

		json.writeStartObject();
		json.writeStringField("storageAccountName", storageAccountName);
		json.writeStringField("storageAccountKey", storageAccountKey);
		json.writeEndObject();
		json.close();
		return stringWriter.toString();
	}

	/**
	 * Sets properties of virtual machine like IP and ssh port
	 * @param azureSlave
	 * @param template
	 * @throws Exception
	 */
	public static void setVirtualMachineDetails(AzureSlave azureSlave, AzureSlaveTemplate template) throws Exception {
		AzureCloud azureCloud = template.getAzureCloud();
		Configuration config = ServiceDelegateHelper.loadConfiguration(azureCloud.getSubscriptionId(), 
				azureCloud.getServiceManagementCert(), azureCloud.getPassPhrase(), azureCloud.getServiceManagementURL());
		ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
		DeploymentGetResponse response = client.getDeploymentsOperations().getByName(azureSlave.getCloudServiceName(), 
				azureSlave.getDeploymentName());
		
		// Getting the first virtual IP
		azureSlave.setPublicDNSName(response.getVirtualIPAddresses().get(0).getAddress().getHostAddress());

		ArrayList<RoleInstance> instances = response.getRoleInstances();
		for (RoleInstance roleInstance : instances) {
			if (roleInstance.getRoleName().equals(azureSlave.getNodeName())) {
				ArrayList<InstanceEndpoint> endPoints = roleInstance.getInstanceEndpoints();

				for (InstanceEndpoint endPoint : endPoints) {
					if (endPoint.getLocalPort() == Constants.DEFAULT_SSH_PORT) {
						azureSlave.setSshPort(endPoint.getPort());
						break;
					}
				}
				break;
			}
		}
	}
	
	public static boolean isVirtualMachineExists(AzureSlave slave) {
		LOGGER.info("AzureManagementServiceDelegate: isVirtualMachineExists: VM name "+slave.getDisplayName());
		try {
			ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);
			client.getVirtualMachinesOperations().get(slave.getCloudServiceName(), slave.getDeploymentName(), slave.getNodeName());
		} catch (ServiceException se) {
			if (se.getErrorCode().equals(Constants.ERROR_CODE_RESOURCE_NF)) {
				LOGGER.info("AzureManagementServiceDelegate: isVirtualMachineExists: VM name "+slave.getDisplayName()+ " ");
				return false;
			}
			//For rest of the errors just assume vm exists
		} catch (Exception e) {
			//For rest of the errors just assume vm exists
		}
		return true;
	}
	
	public static boolean confirmVMExists(ComputeManagementClient client, String cloudServiceName, String deploymentName, String VMName)
	throws ServiceException, Exception{
		LOGGER.info("AzureManagementServiceDelegate: confirmVirtualMachineExists: VM name "+VMName);
		try {
			client.getVirtualMachinesOperations().get(cloudServiceName, deploymentName, VMName);
			return true;
		} catch (ServiceException se) {
			if (Constants.ERROR_CODE_RESOURCE_NF.equalsIgnoreCase(se.getErrorCode())) { 
				return false;
			}
		} catch (Exception e) {
			throw e;
		}
		return false;
	}

	/** Creates Azure slave object with necessary info */
	private static AzureSlave parseDeploymentResponse(OperationStatusResponse response, String cloudServiceName,
			AzureSlaveTemplate template, VirtualMachineCreateDeploymentParameters params) throws AzureCloudException {
		String osType = "Windows";
			
		for (ConfigurationSet configSet : params.getRoles().get(0).getConfigurationSets()) {
			if (configSet.getConfigurationSetType().equals(ConfigurationSetTypes.LINUXPROVISIONINGCONFIGURATION)) {
				osType = "Linux";
				break;
			}
		}
			
		LOGGER.info("AzureManagementServiceDelegate: parseDeploymentResponse: found slave OS type as "+osType);
		AzureCloud azureCloud = template.getAzureCloud();
			
		try {
			LOGGER.info("AzureManagementServiceDelegate: parseDeploymentResponse: no of executors "+template.getNoOfParallelJobs());
			
			return new AzureSlave(params.getRoles().get(0).getRoleName(), template.getTemplateName(),
					template.getTemplateDesc(),osType, template.getSlaveWorkSpace(),
					template.getNoOfParallelJobs(),
					template.getUseSlaveAlwaysIfAvail(), template.getLabels(),
					template.getAzureCloud().getDisplayName(),
					template.getAdminUserName(), null,
					null, template.getAdminPassword(),
					template.getJvmOptions(), template.isShutdownOnIdle(),
					cloudServiceName, params.getName(),
					template.getRetentionTimeInMin(), template.getInitScript(), azureCloud.getSubscriptionId(),
					azureCloud.getServiceManagementCert(), azureCloud.getPassPhrase(), azureCloud.getServiceManagementURL(), template.getSlaveLaunchMethod(), false);
		} catch (FormException e) {
			e.printStackTrace();
			throw new AzureCloudException("AzureManagementServiceDelegate: parseDeploymentResponse: Exception occured while creating slave object"+e);
		} catch (IOException e) {
			throw new AzureCloudException("AzureManagementServiceDelegate: parseDeploymentResponse: Exception occured while creating slave object"+e);
		}
	}

	/** Creates Azure slave object with necessary info  
	 * @throws AzureCloudException */
	private static AzureSlave parseResponse(OperationStatusResponse response, String cloudServiceName, String deploymentName,
			AzureSlaveTemplate template, VirtualMachineCreateParameters params) throws AzureCloudException {
		try {
			String osType = "Windows";
			for (ConfigurationSet configSet : params.getConfigurationSets()) {
				if (configSet.getConfigurationSetType().equals(ConfigurationSetTypes.LINUXPROVISIONINGCONFIGURATION)) {
					osType = "Linux";
					break;
				}
			}
			
			LOGGER.info("AzureManagementServiceDelegate: parseResponse: found slave OS type as "+osType);
			LOGGER.info("AzureManagementServiceDelegate: parseDeploymentResponse: no of executors "+template.getNoOfParallelJobs());
			AzureCloud azureCloud = template.getAzureCloud();
			return new AzureSlave(params.getRoleName(), template.getTemplateName(),
					template.getTemplateDesc(), osType, template.getSlaveWorkSpace(),
					template.getNoOfParallelJobs(),
					template.getUseSlaveAlwaysIfAvail(), template.getLabels(),
					template.getAzureCloud().getDisplayName(),
					template.getAdminUserName(), null,
					null, template.getAdminPassword(),
					template.getJvmOptions(), template.isShutdownOnIdle(),
					cloudServiceName, deploymentName,
					template.getRetentionTimeInMin(), template.getInitScript(), azureCloud.getSubscriptionId(), azureCloud.getServiceManagementCert(),
					azureCloud.getPassPhrase(), azureCloud.getServiceManagementURL(), template.getSlaveLaunchMethod(), false);
		} catch (FormException e) {
			e.printStackTrace();
			throw new AzureCloudException("AzureManagementServiceDelegate: parseResponse: Exception occured while creating slave object"+e);
		} catch (IOException e) {
			e.printStackTrace();
			throw new AzureCloudException("AzureManagementServiceDelegate: parseResponse: Exception occured while creating slave object"+e);
		}
	}
	
	/**
	 * Creates new cloud service if cloud service is does not exists.
	 * @param config Azure cloud configuration object
	 * @param cloudServiceName cloud service name
	 * @param location location of Azure datacenter
	 * @return true if cloud service is created newly else false
	 * @throws IOException
	 * @throws ServiceException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 * @throws AzureCloudException
	 * @throws URISyntaxException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws TransformerException
	 */
	private static boolean createCloudServiceIfNotExists(Configuration config, String cloudServiceName, String location, String affinityGroupName) throws IOException,
			ServiceException, ParserConfigurationException, SAXException, AzureCloudException, URISyntaxException, InterruptedException,
			ExecutionException, TransformerException {
		ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
	
		// check if cloud service already exists in subscription
		if (checkIfCloudServiceExists(client, cloudServiceName)) {
			LOGGER.info("AzureManagementServiceDelegate: createCloudServiceIfNotExists: Cloud service already exists , no need to create one");
			return false;
		}
		
		// Check if cloud service name is available
		if (!checkIfCloudServiceNameAvailable(client, cloudServiceName)) {
			LOGGER.severe("AzureManagementServiceDelegate: createCloudServiceIfNotExists: Cloud service Name "+cloudServiceName+" is not available");
			LOGGER.severe("AzureManagementServiceDelegate: createCloudServiceIfNotExists: Please retry by configuring a different cloudservice name");
			throw new AzureCloudException(
					"AzureManagementServiceDelegate: createCloudServiceIfNotExists: Cloud Service Name is not available, try configuring a different name in global configuration");
		}

		// Create new cloud service
		LOGGER.info("AzureManagementServiceDelegate: createCloudServiceIfNotExists: creating new cloud service" + cloudServiceName);
		HostedServiceCreateParameters params = new HostedServiceCreateParameters();
		params.setServiceName(cloudServiceName);
		params.setLabel(cloudServiceName);
		if (affinityGroupName == null) 
			params.setLocation(location);
		else
			params.setAffinityGroup(affinityGroupName);
		client.getHostedServicesOperations().create(params);

		LOGGER.info("AzureManagementServiceDelegate: createCloudServiceIfNotExists: Created new cloud service with name " + cloudServiceName);
		return true;
	}

	/**
	 * Checks if cloud service already exists in the subscription.
	 * @param client ComputeManagementClient
	 * @param cloudServiceName cloud service name
	 * @return true if cloud service exists else returns false
	 */
	private static boolean checkIfCloudServiceExists(ComputeManagementClient client, String cloudServiceName) {
		boolean exists = true;

		try {
			// Ideally need to list hosted services , iterate and compare but i find below 
			// approach is faster.
			client.getHostedServicesOperations().get(cloudServiceName);
		} catch (Exception e) {
			LOGGER.info("AzureManagementServiceDelegate: checkIfCloudServiceExists: Cloud service doesnot exists in subscription, need to create one");
			exists = false;
		}
		
		return exists;
	}
	
	/**
	 * Checks if cloud service already exists in the subscription.
	 * @param config ComputeManagementClient
	 * @param cloudServiceName cloud service name
	 * @return true if cloud service exists else returns false
	 */
	private static boolean doesCloudServiceLocationMatch(Configuration config, String cloudServiceName, String location) throws Exception {
		boolean locationMatches = false;

		try {
			ComputeManagementClient client = ComputeManagementService.create(config);
			HostedServiceGetResponse resp = client.getHostedServicesOperations().get(cloudServiceName);
			HostedServiceProperties props = resp.getProperties();
			if (location != null && props.getLocation().equalsIgnoreCase(location)) {
				return true;
			}
		} catch (Exception e) {
			LOGGER.info("AzureManagementServiceDelegate: doesCloudServiceLocationMatch: Got exception while checking for cloud service location");
			throw e;
		}
		
		return locationMatches;
	}

	
	/**
	 * Checks if cloud service name is available.
	 * @param client
	 * @param cloudServiceName
	 * @return
	 * @throws Exception
	 */
	private static boolean isCloudServiceNameAvailable(ComputeManagementClient client, String cloudServiceName) throws Exception {
		boolean exists = true;

		try {
			exists = client.getHostedServicesOperations().checkNameAvailability(cloudServiceName).isAvailable();
		} catch (Exception e) {
			LOGGER.info("AzureManagementServiceDelegate: isCloudServiceNameAvailable: Cloud service name is not valid or available "+e.getMessage());
			exists = false;
		}
		
		return exists;
	}
	
	public static boolean validateCloudServiceName(Configuration config, String cloudServiceName) throws Exception {
		ComputeManagementClient client = ComputeManagementService.create(config);
		
		// Check if cloud service name already exists in subscription or if name is available
        return checkIfCloudServiceExists(client, cloudServiceName) ||
                isCloudServiceNameAvailable(client, cloudServiceName);
	}

	/**
	 * Checks if cloud service name is available.
	 * @param client ComputeManagementClient
	 * @param cloudServiceName cloudServiceName
	 * @return
	 * @throws IOException
	 * @throws ServiceException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public static boolean checkIfCloudServiceNameAvailable(ComputeManagementClient client, String cloudServiceName) {
		boolean exists = true;
		
		try {
			exists = client.getHostedServicesOperations().checkNameAvailability(cloudServiceName).isAvailable();
		} catch (Exception e) {
			LOGGER.severe("AzureManagementServiceDelegate: checkIfCloudServiceNameAvailable: Cloud service doesnot exists in subscription, need to create one");
			// Incase of exception, ignoring silently and returning false.
			exists = false;
		}
		
		return exists;
	}
	
	/**
	 * Forms virtual machine deployment parameters 
	 * @param config Azure cloud configuration object
	 * @param cloudServiceName cloud service name
	 * @param deploymentName deployment name
	 * @param template slave template definition
	 * @return VirtualMachineCreateDeploymentParameters
	 * @throws Exception
	 */
	private static VirtualMachineCreateDeploymentParameters createVirtualMachineDeploymentParams(Configuration config, 
			String cloudServiceName, String deploymentName, Map<ImageProperties, String> imageProperties, AzureSlaveTemplate template) throws AzureCloudException {
		VirtualMachineCreateDeploymentParameters parameters = new VirtualMachineCreateDeploymentParameters();
		parameters.setLabel(deploymentName);
		parameters.setName(deploymentName);
		// Persistent VM Role always needs to be created in production slot.
		parameters.setDeploymentSlot(DeploymentSlot.Production);

		ArrayList<Role> roles = new ArrayList<Role>();
		parameters.setRoles(roles);
		
		Role role = new Role();
		roles.add(role);

		String virtualMachineName = Constants.VM_NAME_PREFIX + getCurrentDate();

		ArrayList<ConfigurationSet> configurationSets = new ArrayList<ConfigurationSet>();
		role.setConfigurationSets(configurationSets);
		role.setRoleName(virtualMachineName);
		role.setRoleType(VirtualMachineRoleType.PersistentVMRole.toString());
		role.setRoleSize(template.getVirtualMachineSize());
		role.setProvisionGuestAgent(true);
		// set OS disk configuration param
		ImageType imageType = ImageType.valueOf(imageProperties.get(ImageProperties.IMAGETYPE));
		if (ImageType.VMIMAGE_GENERALIZED.equals(imageType) || ImageType.VMIMAGE_SPECIALIZED.equals(imageType)) {
			role.setVMImageName(imageProperties.get(ImageProperties.NAME));
		} else {
			role.setOSVirtualHardDisk(getOSVirtualHardDisk(config, template, cloudServiceName, virtualMachineName, imageProperties));
		}
		
		String osType = imageProperties.get(ImageProperties.OSTYPE);
		// If image OS type is windows and launch method is JNLP then custom script extension needs to be enabled , 
		// so that init script can run after provisioning
		if (Constants.OS_TYPE_WINDOWS.equalsIgnoreCase(osType) && 
				Constants.LAUNCH_METHOD_JNLP.equalsIgnoreCase(template.getSlaveLaunchMethod())) {
			role.setResourceExtensionReferences(handleCustomScriptExtension(config, virtualMachineName, cloudServiceName, template));
		}

		if (!ImageType.VMIMAGE_SPECIALIZED.equals(imageType)) {
			// set OS configuration params
			configurationSets.add(getOSConfigurationSet(template, cloudServiceName, virtualMachineName, osType));
		}
		// set Network configuration set
		configurationSets.add(getNetworkConfigurationSet(osType, template));
		
		// set virtual network name
		if (AzureUtil.isNotNull(template.getVirtualNetworkName())) {
			parameters.setVirtualNetworkName(template.getVirtualNetworkName().trim());
		}
		return parameters;
	}
	
	/**
	 * Forms virtual machine deployment parameters
	 * @param config Azure cloud configuration object
	 * @param cloudServiceName cloud service name
	 * @param deploymentName deployment name
	 * @param template  slave template definition
	 * @return VirtualMachineCreateParameters
	 * @throws Exception
	 */
	private static VirtualMachineCreateParameters createVirtualMachineParams(Configuration config, String cloudServiceName, 
			String deploymentName, Map<ImageProperties, String> imageProperties, AzureSlaveTemplate template)	throws AzureCloudException {
		VirtualMachineCreateParameters params = new VirtualMachineCreateParameters();
		String virtualMachineName =  Constants.VM_NAME_PREFIX + getCurrentDate();
	
		ArrayList<ConfigurationSet> configurationSets = new ArrayList<ConfigurationSet>();
		params.setConfigurationSets(configurationSets);
		params.setRoleName(virtualMachineName);
		params.setRoleSize(template.getVirtualMachineSize());
		params.setProvisionGuestAgent(true);
		// set OS disk configuration param
		ImageType imageType = ImageType.valueOf(imageProperties.get(ImageProperties.IMAGETYPE));
		if (ImageType.VMIMAGE_GENERALIZED.equals(imageType) || ImageType.VMIMAGE_SPECIALIZED.equals(imageType)) {
			params.setVMImageName(imageProperties.get(ImageProperties.NAME));
		} else {
			params.setOSVirtualHardDisk(getOSVirtualHardDisk(config, template, cloudServiceName, virtualMachineName, imageProperties));
		}
	
		String osType = imageProperties.get(ImageProperties.OSTYPE);
		// If image OS type is windows and launch method is JNLP then custom script extension needs 
		// to be enabled , so that init script can run after provisioning
		if (Constants.OS_TYPE_WINDOWS.equalsIgnoreCase(osType) && 
				Constants.LAUNCH_METHOD_JNLP.equalsIgnoreCase(template.getSlaveLaunchMethod())) {
			params.setResourceExtensionReferences(handleCustomScriptExtension(config, virtualMachineName, cloudServiceName, template));
		}
		
		if (!ImageType.VMIMAGE_SPECIALIZED.equals(imageType)) {
			// set OS configuration params
			configurationSets.add(getOSConfigurationSet(template, cloudServiceName, virtualMachineName, osType));
		}
		// set Network configuration set
		configurationSets.add(getNetworkConfigurationSet(osType, template));
		
		return params;
	}
	
	/** Prepares OSVirtualHardDisk object */
	private static OSVirtualHardDisk getOSVirtualHardDisk(Configuration config, AzureSlaveTemplate template, String cloudServiceName, 
			String virtualMachineName, Map<ImageProperties, String> imageProperties) throws AzureCloudException {
		OSVirtualHardDisk osDisk = new OSVirtualHardDisk();
		ImageType imageType = ImageType.valueOf(imageProperties.get(ImageProperties.IMAGETYPE));
	
		if (ImageType.OSIMAGE.equals(imageType)) {
			String blobURI = StorageServiceDelegate.getStorageAccountURI(config, template.getStorageAccountName(), Constants.BLOB);
			URI mediaLinkUriValue = null;

			try {
				mediaLinkUriValue = new URI(blobURI + Constants.CI_SYSTEM + Constants.FWD_SLASH + 
	                        cloudServiceName + "-" + virtualMachineName + getCurrentDate() +".vhd");
			} catch (URISyntaxException e) {
				throw new AzureCloudException("AzureManagementServiceDelegate: createVirtualMachineDeploymentParams: Exception occured while "
				+ "forming media link URI "+e);
			}
	
			osDisk.setMediaLink(mediaLinkUriValue);
		}
		osDisk.setSourceImageName(imageProperties.get(ImageProperties.NAME));		
		return osDisk;
	}
	
	/** Prepares OS specific configuration */
	private static ConfigurationSet getOSConfigurationSet(AzureSlaveTemplate template, String cloudServiceName, String virtualMachineName, String osType)
	throws AzureCloudException {
		ConfigurationSet osSpecificConf = new ConfigurationSet();

		if (Constants.OS_TYPE_WINDOWS.equalsIgnoreCase(osType)) {
			osSpecificConf.setConfigurationSetType(ConfigurationSetTypes.WINDOWSPROVISIONINGCONFIGURATION);
			osSpecificConf.setComputerName(virtualMachineName);
			osSpecificConf.setAdminUserName(template.getAdminUserName());
			osSpecificConf.setAdminPassword(template.getAdminPassword());
			osSpecificConf.setEnableAutomaticUpdates(false);
		} else if (Constants.OS_TYPE_LINUX.equalsIgnoreCase(osType)) {
			//sshPublicKeyPath = "/home/user/.ssh/authorized_keys";

			osSpecificConf.setConfigurationSetType(ConfigurationSetTypes.LINUXPROVISIONINGCONFIGURATION);
			osSpecificConf.setHostName(virtualMachineName);
			osSpecificConf.setUserName(template.getAdminUserName());

			if (template.getAdminPassword() == null) {
				osSpecificConf.setDisableSshPasswordAuthentication(true);
			} else {
				osSpecificConf.setUserPassword(template.getAdminPassword());
				osSpecificConf.setAdminPassword(template.getAdminPassword());
			}
			osSpecificConf.setDisableSshPasswordAuthentication(false);

			// Configure SSH
			// SshSettings sshSettings = new SshSettings();
			// osSpecificConf.setSshSettings(sshSettings);
			//
			// //Get certificate thumprint
			// Map<String, String> certMap =
			// getCertInfo(template.getSshPublicKey().getBytes("UTF-8"));
			//
			// ArrayList<SshSettingPublicKey> publicKeys= new
			// ArrayList<SshSettingPublicKey>();
			// sshSettings.setPublicKeys(publicKeys);
			// // Add public key
			// SshSettingPublicKey publicKey = new SshSettingPublicKey();
			// publicKeys.add(publicKey);
			// publicKey.setFingerprint(certMap.get("thumbPrint"));
			// publicKey.setPath(sshPublicKeyPath);
			//
			// ArrayList<SshSettingKeyPair> keyPairs= new
			// ArrayList<SshSettingKeyPair>();
			// sshSettings.setKeyPairs(keyPairs);
			// // Add key pair
			// SshSettingKeyPair keyPair = new SshSettingKeyPair();
			// keyPairs.add(keyPair);
			// keyPair.setFingerprint(sshPKFingerPrint);
			// keyPair.setPath(sshKeyPairPath);

		} else {
			throw new AzureCloudException("Unsupported OSType " + osType);
		}
		
		return osSpecificConf;
	}
	
	/** Prepares OS specific configuration 
	 * @param template.getSlaveLaunchMethod() 
	 * @param osType */
	private static ConfigurationSet getNetworkConfigurationSet(String osType, AzureSlaveTemplate template) {
		ConfigurationSet networkConfigset = new ConfigurationSet();
		networkConfigset.setConfigurationSetType(ConfigurationSetTypes.NETWORKCONFIGURATION);
		// Define endpoints
		ArrayList<InputEndpoint> enpoints = new ArrayList<InputEndpoint>();
		networkConfigset.setInputEndpoints(enpoints);
		
		// Add SSH endpoint if launch method is SSH
		if (Constants.LAUNCH_METHOD_SSH.equalsIgnoreCase(template.getSlaveLaunchMethod())) {
			InputEndpoint sshPort = new InputEndpoint();
			enpoints.add(sshPort);
			sshPort.setName(Constants.EP_SSH_NAME);
			sshPort.setProtocol(Constants.PROTOCOL_TCP);
			sshPort.setLocalPort(Constants.DEFAULT_SSH_PORT);
		}
		
		// In case if OStype is windows add RDP endpoint as well
		if (Constants.OS_TYPE_WINDOWS.equalsIgnoreCase(osType)) {
			InputEndpoint rdpPort = new InputEndpoint();
			enpoints.add(rdpPort);
			rdpPort.setName(Constants.EP_RDP_NAME);
			rdpPort.setProtocol(Constants.PROTOCOL_TCP);
			rdpPort.setLocalPort(Constants.DEFAULT_RDP_PORT);
		}
		
		if (AzureUtil.isNotNull(template.getVirtualNetworkName()) && 
				AzureUtil.isNotNull(template.getSubnetName())) {
			ArrayList<String> subnetNames = new ArrayList<String>();
			subnetNames.add(template.getSubnetName().trim());
			
			networkConfigset.setSubnetNames(subnetNames);
		}
		
		return networkConfigset;
	}

	/** Returns current date in MMddhhmmss */
	private static String getCurrentDate() {
		Format formatter = new SimpleDateFormat("MMddhhmmss");
		return formatter.format(new Date(System.currentTimeMillis()));
	}

	/** Gets list of Azure datacenter locations which supports Persistent VM role */
	public static List<String> getVirtualMachineLocations(String subscriptionId, String serviceManagementCert, 
			String passPhrase, String serviceManagementURL) throws IOException, ServiceException, 
			ParserConfigurationException, SAXException {
		Configuration config = ServiceDelegateHelper.loadConfiguration(subscriptionId, serviceManagementCert, 
				passPhrase, serviceManagementURL);
		
		return getVirtualMachineLocations(config);
	}
	
	/** Gets list of Azure datacenter locations which supports Persistent VM role */
	public static List<String> getVirtualMachineLocations(Configuration config) throws IOException, ServiceException, 
			ParserConfigurationException, SAXException {
		List<String> locations = new ArrayList<String>();
		ManagementClient managementClient = ServiceDelegateHelper.getManagementClient(config);
		LocationsListResponse listResponse = managementClient.getLocationsOperations().list();

		for (Iterator<Location> iterator = listResponse.iterator(); iterator.hasNext();) {
			Location location = iterator.next();
			for (String availServices : location.getAvailableServices()) {
				// check for PersistentVMRole
				if ("PersistentVMRole".equalsIgnoreCase(availServices)) {
					locations.add(location.getName());
					// break inner for loop
					break;
				}
			}
		}
		return locations;
	}
	
	public static List<String> getStorageAccountsInfo(Configuration config) throws Exception {
		List<String> storageAccounts = new ArrayList<String>();
		StorageManagementClient client = StorageManagementService.create(config);
		
		StorageAccountListResponse response = client.getStorageAccountsOperations().list();
		for (StorageAccount sa : response.getStorageAccounts()) {
			storageAccounts.add(sa.getName() +" ("+ sa.getProperties().getLocation()+")");
		}
		return storageAccounts;
	}
	
	public static String createStorageAccount(Configuration config, String location, String affinityGroupName) throws Exception {
		LOGGER.info("AzureManagemenServiceDelegate: createStorageAccount: location "+location+ " affinityGroup "+affinityGroupName);
		StorageManagementClient client = StorageManagementService.create(config);
		
		// Prepare storage account create properties
		StorageAccountCreateParameters sacp = new StorageAccountCreateParameters();
		String name = null;
		do {
			name = Constants.STORAGE_ACCOUNT_PREFIX + randomString(4) + getCurrentDate();
			
		} while (!isStorageAccountNameAvailable(config, name));
		
		sacp.setName(name);
		if (affinityGroupName == null) {
			sacp.setLocation(location);
		} else {
			sacp.setAffinityGroup(affinityGroupName);
		}
		sacp.setLabel(name);
		
		OperationStatusResponse response = client.getStorageAccountsOperations().create(sacp);
		if (response.getHttpStatusCode() == 200 || response.getHttpStatusCode() == 201) {
			// 200 or 201 means that create operation is successful
			return name;
		} else {
			throw new Exception("AzureManagemenServiceDelegate: createStorageAccount: Not able to create storage account due to "+response.getStatus());
		}
	}
	
	private static boolean isStorageAccountNameAvailable(Configuration config, String name) 
			throws IOException, ServiceException, ParserConfigurationException, SAXException {
		LOGGER.info("AzureManagemenServiceDelegate: isStorageAccountNameAvailable: name "+name);
		boolean available = true;
		
		StorageManagementClient client = StorageManagementService.create(config);
		CheckNameAvailabilityResponse response = client.getStorageAccountsOperations().checkNameAvailability(name);
		available = response.isAvailable();
		
		LOGGER.info("AzureManagemenServiceDelegate: isStorageAccountNameAvailable: name "+name + " availability "+available);
		return available;
		
	}

	private static String randomString(int StringLength) {
		String allowedCharacters = "abcdefghijklmnopqrstuvwxyz";
		Random rand = new Random();
		StringBuilder buf = new StringBuilder();
		
		for (int i=0; i<StringLength; i++) {
			buf.append(allowedCharacters.charAt(rand.nextInt(allowedCharacters.length())));
		}
		return buf.toString();
	}


	/** Gets list of virtual machine sizes */
	public static List<String> getVMSizes(String subscriptionId, String serviceManagementCert, String passPhrase,
			String serviceManagementURL) throws IOException, ServiceException, ParserConfigurationException, SAXException {
		Configuration config = ServiceDelegateHelper.loadConfiguration(subscriptionId, serviceManagementCert, passPhrase, 
																		serviceManagementURL);
		
		return getVMSizes(config);
	}
	
	/** Gets list of virtual machine sizes */
	public static List<String> getVMSizes(Configuration config) throws IOException, ServiceException, 
	ParserConfigurationException, SAXException {
		List<String> vmSizes = new ArrayList<String>();
		ManagementClient managementClient = ServiceDelegateHelper.getManagementClient(config);
		RoleSizeListResponse roleSizeListResponse = managementClient.getRoleSizesOperations().list();

		for (RoleSize roleSize : roleSizeListResponse.getRoleSizes()) {
			if (roleSize.isSupportedByVirtualMachines()) {
				vmSizes.add(roleSize.getName());
			}
		}

		return vmSizes;
	}

	/**
	 * Validates certificate configuration
	 * @param subscriptionId
	 * @param serviceManagementCert
	 * @param passPhrase
	 * @param serviceManagementURL
	 * @return
	 */
	public static String verifyConfiguration(String subscriptionId, String serviceManagementCert, String passPhrase,
			String serviceManagementURL) {
		try {
			Configuration config = ServiceDelegateHelper.loadConfiguration(subscriptionId, serviceManagementCert, 
					passPhrase, serviceManagementURL);
			return verifyConfiguration(config);
		} catch (Exception e) {
			return "Failure: Exception occured while validating subscription configuration" + e;
		}
	}
	
	public static String verifyConfiguration(final Configuration config) {
		Callable<String> task = new Callable<String>() {
			public String call() throws Exception {
				ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
				client.getHostedServicesOperations().checkNameAvailability("CI_SYSTEM");
				return "Success";
			}
		};
		
		try {
			return ExecutionEngine.executeWithRetry(task, new ExponentialRetryStrategy(3 /*Max. retries*/, 2/*Max wait interval between retries*/));
		} catch (AzureCloudException e) {
			return "Failure: Exception occured while validating subscription configuration" + e;
		}
	}
	
	/**
	 * Gets current status of virtual machine
	 * @param config
	 * @param cloudServiceName
	 * @param slot
	 * @param VMName
	 * @return
	 * @throws Exception
	 */
	public static String getVirtualMachineStatus(Configuration config, String cloudServiceName, DeploymentSlot slot, String VMName) throws Exception {
		String status = "";
		ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
		ArrayList<RoleInstance> roleInstances = client.getDeploymentsOperations().getBySlot(cloudServiceName, DeploymentSlot.Production).getRoleInstances();
		for (RoleInstance instance : roleInstances) {
			if (instance.getRoleName().equals(VMName)) {
				status = instance.getInstanceStatus();
				break;
			}
		}
		return status;
	}
	
	/** Checks if VM is reachable and in a valid state to connect  */	
	public static boolean isVMAliveOrHealthy(AzureSlave slave) throws Exception {
		Configuration config = ServiceDelegateHelper.loadConfiguration(slave.getSubscriptionID(), slave.getManagementCert(),
				                                                         slave.getPassPhrase(), slave.getManagementURL());
		String status = getVirtualMachineStatus(config, slave.getCloudServiceName(), DeploymentSlot.Production, slave.getNodeName());
		LOGGER.info("AzureManagementServiceDelegate: isVMAliveOrHealthy: status " + status);
		// if VM status is DeletingVM/StoppedVM/StoppingRole/StoppingVM then consider VM to be not healthy
		if (status != null &&  
				(Constants.DELETING_VM_STATUS.equalsIgnoreCase(status) ||
				 Constants.STOPPED_VM_STATUS.equalsIgnoreCase(status) ||
				 Constants.STOPPING_VM_STATUS.equalsIgnoreCase(status) ||
				 Constants.STOPPING_ROLE_STATUS.equalsIgnoreCase(status) ||
				 Constants.STOPPED_DEALLOCATED_VM_STATUS.equalsIgnoreCase(status))) {
			return false;
		} else {
			return true;
		}
	}
	
	/** Retrieves count of role instances in a cloud service*/
	public static int getRoleCount(ComputeManagementClient client, String cloudServiceName) throws Exception {
		DeploymentGetResponse response = client.getDeploymentsOperations().getBySlot(cloudServiceName, DeploymentSlot.Production);
		return response.getRoleInstances().size();
	}
	
	/** Retrieves count of virtual machine roles in a azure subscription */
	public static int getVirtualMachineCount(ComputeManagementClient client) throws Exception {
		int vmRoleCount = 0;
		ArrayList<HostedService> hostedServices = null;
		
		try {
			HostedServiceListResponse response = client.getHostedServicesOperations().list();
			hostedServices = response.getHostedServices();
		} catch (Exception e) {
			LOGGER.info("AzureManagementServiceDelegate: getVirtualMachineCount: Got exception while getting hosted services info ,"
					+ " assuming that there are no hosted services "+ e);
			return vmRoleCount;
		}

		for (HostedService hostedService : hostedServices) {
			ArrayList<Role> roles = null;
			try {
				DeploymentGetResponse deploymentResp = client.getDeploymentsOperations().getBySlot(hostedService.getServiceName(), 
						DeploymentSlot.Production);
				roles = deploymentResp.getRoles();
				
				for (Role role : roles) {
					if (role.getRoleType().equals(VirtualMachineRoleType.PersistentVMRole.toString())) {
						vmRoleCount += 1;
					}
				}
				
			} catch (Exception e) {
				continue;
			}
		}
		LOGGER.info("AzureManagementServiceDelegate: getVirtualMachineCount: Virtual machines count "+vmRoleCount);
		return vmRoleCount;
	}
	
	
	/** Shutdowns Azure virtual machine */	
	public static void shutdownVirtualMachine(AzureSlave slave) throws Exception {
		ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);
		VirtualMachineShutdownParameters params = new VirtualMachineShutdownParameters();
		params.setPostShutdownAction(PostShutdownAction.StoppedDeallocated);
		client.getVirtualMachinesOperations().shutdown(slave.getCloudServiceName(), slave.getDeploymentName(), slave.getNodeName(), params);
	}
	
	/** Deletes Azure virtual machine */
	public static void terminateVirtualMachine(AzureSlave slave, boolean sync) throws Exception {
		ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);
		try {
			if (getRoleCount(client, slave.getCloudServiceName()) > 1) {
				if (sync)
					client.getVirtualMachinesOperations().delete(slave.getCloudServiceName(), slave.getDeploymentName(), slave.getNodeName(), true);
				else
					client.getVirtualMachinesOperations().deleteAsync(slave.getCloudServiceName(), slave.getDeploymentName(), slave.getNodeName(), true);
			} else {
				if (confirmVMExists(client, slave.getCloudServiceName(), slave.getDeploymentName(), slave.getNodeName())) {
					if (sync)
						client.getDeploymentsOperations().deleteByName(slave.getCloudServiceName(), slave.getDeploymentName(), true);
					else
						client.getDeploymentsOperations().deleteByNameAsync(slave.getCloudServiceName(), slave.getDeploymentName(), true);
				}
			}
		} catch (ServiceException se) {
			// Check if VM is already deleted 
			if (Constants.ERROR_CODE_RESOURCE_NF.equalsIgnoreCase(se.getErrorCode())) {
				// If VM is already deleted then just ignore exception.
				return;
			}
			
			LOGGER.info("AzureManagementServiceDelegate: terminateVirtualMachine: error code " + se.getErrorCode()+ " Got error while deleting VM");
			throw se;
		}
	}
	
	/** Restarts Azure virtual machine */
	public static void restartVirtualMachine(AzureSlave slave) throws Exception {
		ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);
		client.getVirtualMachinesOperations().restart(slave.getCloudServiceName(), slave.getDeploymentName(), slave.getNodeName());
	}
	
	/** Starts Azure virtual machine */
	public static void startVirtualMachine(AzureSlave slave) throws Exception {
		LOGGER.info("AzureManagementServiceDelegate: startVirtualMachine: "+slave.getNodeName());
		int retryCount = 0;
		boolean successful = false;
		ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);
		
		while (!successful) {
			try {
				client.getVirtualMachinesOperations().start(slave.getCloudServiceName(), slave.getDeploymentName(), slave.getNodeName());
				successful = true; // may be we can just return
			} catch (Exception e) {
				LOGGER.info("AzureManagementServiceDelegate: startVirtualMachine: got exception while starting VM "+ slave.getNodeName()+ ". Will retry again after 30 seconds. Current retry count "+retryCount + " / " + Constants.MAX_PROV_RETRIES + "\n");
				if (retryCount > Constants.MAX_PROV_RETRIES) { 
					throw e;
				} else {
					retryCount++;
					Thread.sleep(30 * 1000); // wait for 30 seconds
				}
			}
		}
	}
	
	/** Gets Virtual Image List **/
	private static List<VirtualMachineOSImage> getVirtualMachineOSImageList(Configuration config) throws Exception {
		List<VirtualMachineOSImage> imageList = new ArrayList<VirtualMachineOSImage>();
		
		ComputeManagementClient client = ComputeManagementService.create(config);		
		VirtualMachineOSImageListResponse response = client.getVirtualMachineOSImagesOperations().list();
		
		ArrayList<VirtualMachineOSImage> osImages = response.getImages();
		
		for (VirtualMachineOSImage image: osImages) {
			imageList.add(image);
		}
		return imageList;
	}
	
	public static Set<String> getVirtualImageFamilyList(Configuration config) throws Exception {
		Set<String> imageFamilies = new HashSet<String>();
		
		for (VirtualMachineOSImage image: getVirtualMachineOSImageList(config)) {
			if (image.getImageFamily() != null && image.getImageFamily().trim().length() != 0) {
				imageFamilies.add(image.getImageFamily());
			}			
		}
		return imageFamilies;
	}
	
	/** Returns virtual machine family list **/
	public static Set<String> getVirtualImageFamilyList(String subscriptionId, String serviceManagementCert, 
			String passPhrase, String serviceManagementURL) throws Exception {
		Configuration config = ServiceDelegateHelper.loadConfiguration(subscriptionId, serviceManagementCert, 
				passPhrase, serviceManagementURL);
		return getVirtualImageFamilyList(config);
	}
	
	/** Checks whether image ID is valid or not **/
	public static String isValidImageID(String imageID, Map<ImageProperties, String> imageProps, Configuration config) {
		try {
			ComputeManagementClient client = ComputeManagementService.create(config);		
			VirtualMachineOSImageGetResponse response = client.getVirtualMachineOSImagesOperations().get(imageID);
			
			if (response != null ) {
				// set image Properties
				if (imageProps != null ) {
					imageProps.put(ImageProperties.NAME, response.getName());
					imageProps.put(ImageProperties.LOCATION, response.getLocation());
					imageProps.put(ImageProperties.OSTYPE, response.getOperatingSystemType());
					if (response.getMediaLinkUri() == null ) {
						imageProps.put(ImageProperties.IMAGETYPE, ImageType.OSIMAGE.name());
					} else {
						imageProps.put(ImageProperties.IMAGETYPE, ImageType.OSIMAGE_CUSTOM.name());
						imageProps.put(ImageProperties.MEDIAURI, response.getMediaLinkUri().toString());
					}
				}
				return response.getName();
			}
		} catch (Exception e) {
			LOGGER.severe("AzureManagementServiceDelegate: isValidImageID: Input might be VM Image or Image Family since ImageID is not valid");
		}
		return null;
	}
	
	/** Checks whether custom image ID is valid or not **/
	public static String isValidCustomImageID(String imageID, Map<ImageProperties, String> imageProps, Configuration config) {
		try {
			ComputeManagementClient client = ComputeManagementService.create(config);		
			VirtualMachineVMImageListResponse response = client.getVirtualMachineVMImagesOperations().list();
			
			for (VirtualMachineVMImage image : response.getVMImages()) {
				if (image.getName().equalsIgnoreCase(imageID)) {
					if (imageProps != null ) {
						imageProps.put(ImageProperties.NAME, image.getName());
						imageProps.put(ImageProperties.LOCATION, getCustomImageLocation(image.getOSDiskConfiguration().getMediaLink(), config));
						
						String OsType = null;
						try {
							OsType = image.getOSDiskConfiguration().getOperatingSystem();
						} catch (Exception e) {
							// In case if any exception, consider OS Type to be linux
							// For custom images it is not clear if operating system attribute will be always set
							OsType = "Linux";
						}
						imageProps.put(ImageProperties.OSTYPE, OsType);
						String vmOSState = image.getOSDiskConfiguration().getOSState();
						if (vmOSState != null && vmOSState.equalsIgnoreCase("Generalized"))
							imageProps.put(ImageProperties.IMAGETYPE, ImageType.VMIMAGE_GENERALIZED.name());
						else
							imageProps.put(ImageProperties.IMAGETYPE, ImageType.VMIMAGE_SPECIALIZED.name());
						imageProps.put(ImageProperties.MEDIAURI, image.getOSDiskConfiguration().getMediaLink().toString());
					}
					return image.getName();
				}
			}
			return null;
		} catch (Exception e) {
			LOGGER.severe("AzureManagementServiceDelegate: isValidCustomImageID: Error occured while checking for custom image validity");
		}
		return null;
	}
	
	private static String getCustomImageLocation(URI uri, Configuration config) throws AzureCloudException {
		String location = null;
		LOGGER.info("AzureManagementServiceDelegate: getCustomImageLocation: mediaLinkURL is "+uri);
		String storageAccountName = uri.getHost().substring(0, uri.getHost().indexOf("."));
		LOGGER.info("AzureManagementServiceDelegate: getCustomImageLocation: storageAccountName "+storageAccountName);
		
		StorageAccountProperties storageProps = StorageServiceDelegate.getStorageAccountProps(config, storageAccountName);
		
		if (storageProps != null) {
			location = storageProps.getLocation();
			LOGGER.info("AzureManagementServiceDelegate: getCustomImageLocation: location is "+location);
		} 
		return location;
	}
	
	private static String getCustomImageStorageAccountName(URI uri) {
		String storageAccountName = null;
		LOGGER.info("AzureManagementServiceDelegate: getCustomImageStorageAccountName: mediaLinkURL is "+uri);
		if (uri != null){
			storageAccountName = uri.getHost().substring(0, uri.getHost().indexOf("."));
		}
		LOGGER.info("AzureManagementServiceDelegate: getCustomImageStorageAccountName: storage account name is "+storageAccountName);
		return storageAccountName;
	}
	
	/** Checks whether image family is valid or not **/
	public static String isValidImageFamily(String imageFamily, Map<ImageProperties, String> imageProperties, Configuration config) {
		VirtualMachineOSImage latestVMImage = null;
		try {
		// Retrieve latest image 
		for (VirtualMachineOSImage image: getVirtualMachineOSImageList(config)) {
			if (imageFamily.equalsIgnoreCase(image.getImageFamily())) {
				if (latestVMImage == null) {
					latestVMImage = image;
				} else if (latestVMImage.getPublishedDate().before(image.getPublishedDate())) {
					latestVMImage = image;
				}
			}
		}
	
		if (latestVMImage != null) {
			if (imageProperties != null ) {
					imageProperties.put(ImageProperties.NAME, latestVMImage.getName());
					imageProperties.put(ImageProperties.LOCATION, latestVMImage.getLocation());
					imageProperties.put(ImageProperties.OSTYPE, latestVMImage.getOperatingSystemType());
					if (latestVMImage.getMediaLinkUri() == null ) {
						imageProperties.put(ImageProperties.IMAGETYPE, ImageType.OSIMAGE.name());
					} else {
						imageProperties.put(ImageProperties.IMAGETYPE, ImageType.OSIMAGE_CUSTOM.name());
						imageProperties.put(ImageProperties.MEDIAURI, latestVMImage.getMediaLinkUri().toString());
					}
			}
			return latestVMImage.getName();
		}
		
		} catch (Exception e) {
			LOGGER.severe("AzureManagementServiceDelegate: isValidImageFamily: Got exception while checking for image family validity "+imageFamily);
		}
		return null;
	}
	
	/** Returns image properties */
	public static Map<ImageProperties, String> getImageProperties(Configuration config, String imageIDOrFamily)  {
		String imageID = null;

		Map<ImageProperties, String> imageProperties = new HashMap<ImageProperties, String>();
		
		// check if is valid image ID
		imageID = isValidImageID(imageIDOrFamily, imageProperties, config);
		if (imageID != null) {
			return imageProperties;
		}
		
		// check if it is valid custom image ID
		imageID = isValidCustomImageID(imageIDOrFamily, imageProperties, config);
		if (imageID != null) {
			return imageProperties;
		}
		
		// check if it is valid image family
		imageID = isValidImageFamily(imageIDOrFamily, imageProperties, config);
		if (imageID != null) {
			return imageProperties;
		}
			
		return null;
	}
	
	/** Returns virtual network site properties */
	private static VirtualNetworkSite getVirtualNetworkSite(Configuration config, String virtualNetworkName)  {
		try {
			NetworkManagementClient client = ServiceDelegateHelper.getNetworkManagementClient(config);
			NetworkListResponse listResponse = client.getNetworksOperations().list();
			
			if (listResponse != null) {
				ArrayList<VirtualNetworkSite> sites = listResponse.getVirtualNetworkSites();
				
				for (VirtualNetworkSite site : sites) {
					if (virtualNetworkName.equalsIgnoreCase(site.getName())) {
						return site;
					}
				}
			}
		} catch (Exception e) {
			LOGGER.severe("AzureManagementServiceDelegate: getVirtualNetworkInfo: Got exception while getting virtual network info "+virtualNetworkName);
		}
		return null;
	}
	
	private static String getVirtualNetworkLocation(Configuration config, VirtualNetworkSite virtualNetworkSite) {
		LOGGER.info("AzureManagementServiceDelegate: getVirtualNetworkLocation: virtualNetworkName is "+virtualNetworkSite.getName());
		
		if (virtualNetworkSite.getAffinityGroup() != null) {
			return getAffinityGroupLocation(config, virtualNetworkSite.getAffinityGroup());
		}/* currently virtual network site does not have location attribute
		
		 else if (virtualNetworkSite.getLocation() != null) {  
			return virtualNetworkSite.getLocation();
		} */
		LOGGER.info("AzureManagementServiceDelegate: getVirtualNetworkLocation: returning null");
		return null;
	}
	
	private static Subnet getSubnet(VirtualNetworkSite virtualNetworkSite, String subnetName) {
		if (AzureUtil.isNotNull(subnetName)) {
			ArrayList<Subnet> subnets = virtualNetworkSite.getSubnets();
			if (subnets != null) {
				for (Subnet subnet : subnets) {
					if (subnet.getName().equalsIgnoreCase(subnetName)) {
						return subnet;
					}
				}
			}
		}
		return null;
	}
	
	private static String getAffinityGroupLocation(Configuration config, String affinityGroup) {
		LOGGER.info("AzureManagementServiceDelegate: getAffinityGroupLocation: affinityGroup is "+affinityGroup);
		ManagementClient client = ServiceDelegateHelper.getManagementClient(config);
		AffinityGroupGetResponse response;
		
		try {
			response = client.getAffinityGroupsOperations().get(affinityGroup);
			return response.getLocation();
		} catch (Exception e) {
			e.printStackTrace();
		} 
		LOGGER.info("AzureManagementServiceDelegate: getAffinityGroupLocation: returning null");
		return null;
	}
    
	/**
	 * Verifies template configuration by making server calls if needed
	 * @throws Exception 
	 */
	public static List<String> verifyTemplate(String subscriptionId, String serviceManagementCert, String passPhrase,
			String serviceManagementURL, String maxVirtualMachinesLimit, String templateName, String labels, String location, String virtualMachineSize,
			String storageAccountName, String noOfParallelJobs, String imageIdOrFamily, String slaveLaunchMethod, String initScript, String adminUserName,
			String adminPassword, String virtualNetworkName, String subnetName, String retentionTimeInMin, String cloudServiceName,String templateStatus,
			String jvmOptions, boolean returnOnSingleError)  {
		
		List<String> errors = new ArrayList<String>();
		Configuration config = null;
		
		// Load configuration
		try {
			config = ServiceDelegateHelper.loadConfiguration(subscriptionId, serviceManagementCert, passPhrase, serviceManagementURL);
		} catch (Exception e) {
			errors.add("Error occured while validating Azure Profile");
			return errors;
		}
		
		// Verify if profile configuration is valid
		String validationResult = verifyAzureProfileConfiguration(config, subscriptionId, serviceManagementCert, serviceManagementURL);
		if (!validationResult.equalsIgnoreCase(Constants.OP_SUCCESS)) {
			errors.add(validationResult);
			// If profile validation failed , no point in validating rest of the field , just return error
			return errors;
		}
		
		//Verify number of parallel jobs
		if (returnOnSingleError) {
			validationResult = verifyNoOfExecutors(noOfParallelJobs);
			addValidationResultIfFailed(validationResult, errors);
			if (returnOnSingleError && errors.size() > 0 ) {
				return errors;
			}
		}
		
		validationResult = verifyRetentionTime(retentionTimeInMin);
		addValidationResultIfFailed(validationResult, errors);
		if (returnOnSingleError && errors.size() > 0 ) {
			return errors;
		}
		
		validationResult = verifyAdminUserName(adminUserName);
		addValidationResultIfFailed(validationResult, errors);
		if (returnOnSingleError && errors.size() > 0 ) {
			return errors;
		}
		
		//verify password
		validationResult = verifyAdminPassword(adminPassword);
		addValidationResultIfFailed(validationResult, errors);
		if (returnOnSingleError && errors.size() > 0 ) {
			return errors;
		}
		
		//verify JVM Options
		validationResult = verifyJvmOptions(jvmOptions);
		addValidationResultIfFailed(validationResult, errors);
		if (returnOnSingleError && errors.size() > 0 ) {
			return errors;
		}
		
		verifyTemplateAsync(config, templateName, maxVirtualMachinesLimit, cloudServiceName, location, 
							imageIdOrFamily, slaveLaunchMethod, storageAccountName, virtualNetworkName, subnetName, errors, returnOnSingleError);
		
		return errors;
	}
	
	private static void verifyTemplateAsync(final Configuration config, final String templateName, final String maxVirtualMachinesLimit,
			final String cloudServiceName, final String location, final String imageIdOrFamily, 
			final String slaveLaunchMethod, final String storageAccountName, final String virtualNetworkName, 
			final String subnetName, List<String> errors, boolean returnOnSingleError ) {
		
		List<Callable<String>> verificationTaskList = new ArrayList<Callable<String>>();
		
		// Callable for max virtual limit
		if (returnOnSingleError) {
			Callable<String> callVerifyMaxVirtualMachineLimit = new Callable<String>() {
				public String call() throws Exception {
					return verifyMaxVirtualMachineLimit(config, maxVirtualMachinesLimit);
	  	        }
			};
			verificationTaskList.add(callVerifyMaxVirtualMachineLimit);
		}
		
		// Callable for cloud service name availability
		Callable<String> callVerifyCloudServiceName = new Callable<String>() {
			public String call() throws Exception {
				return verifyCloudServiceName(config, templateName, cloudServiceName, location);
  	        }
		};
		verificationTaskList.add(callVerifyCloudServiceName);
		
		// Callable for imageOrFamily 
		Callable<String> callVerifyImageIdOrFamily = new Callable<String>() {
			public String call() throws Exception {
				return verifyImageIdOrFamily(config, imageIdOrFamily, location, slaveLaunchMethod, storageAccountName);
  	        }
		};
		verificationTaskList.add(callVerifyImageIdOrFamily);
		
		// Callable for virtual network.
		Callable<String> callVerifyVirtualNetwork = new Callable<String>() {
			public String call() throws Exception {
				return verifyVirtualNetwork(config, virtualNetworkName, subnetName);
  	        }
		};
		verificationTaskList.add(callVerifyVirtualNetwork);
		
		
		
		ExecutorService executorService = Executors.newFixedThreadPool(verificationTaskList.size());
    	
		try {
			List<Future<String>> validationResultList = executorService.invokeAll(verificationTaskList);

		    for(Future<String> validationResult : validationResultList) {
		    
		    	try {
		            // Get will block until time expires or until task completes
		            String result = validationResult.get(60, TimeUnit.SECONDS);
		            addValidationResultIfFailed(result, errors);
		         } catch (ExecutionException executionException) {
		        	 errors.add("Exception occured while validating temaplate "+executionException);
		         } catch (TimeoutException timeoutException) {
		        	 errors.add("Exception occured while validating temaplate "+timeoutException);
		         }
		      }
		} catch (InterruptedException interruptedException) {
			errors.add("Exception occured while validating temaplate "+interruptedException);
		}
	}
	
	private static void addValidationResultIfFailed(String validationResult, List<String> errors) {
		if (!validationResult.equalsIgnoreCase(Constants.OP_SUCCESS)) {
			errors.add(validationResult);
		}
	}
	
	private static String verifyAzureProfileConfiguration(Configuration config, String subscriptionId, 
			String serviceManagementCert, String serviceManagementURL) {
		
		if (isNullOrEmpty(subscriptionId) || isNullOrEmpty(serviceManagementCert)) {
			return Messages.Azure_GC_Template_Val_Profile_Missing();
		} else {
			if(!verifyConfiguration(config).equals(Constants.OP_SUCCESS)) {
				return Messages.Azure_GC_Template_Val_Profile_Err();
			}
		}
		return Constants.OP_SUCCESS;
	}
	
	private static String verifyMaxVirtualMachineLimit(Configuration config, String maxVirtualMachinesLimit) {
	
		boolean considerDefaultVMLimit = false;
		int maxVMs = 0;
		if (isNullOrEmpty(maxVirtualMachinesLimit)) {
			considerDefaultVMLimit = true;
		} else {
			try {
				maxVMs = Integer.parseInt(maxVirtualMachinesLimit);
				
				if (maxVMs <= 0) {
					considerDefaultVMLimit = true;
				}
			} catch (Exception e) {
				considerDefaultVMLimit = true;
			}
		}
		
		ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
		maxVMs = considerDefaultVMLimit? Constants.DEFAULT_MAX_VM_LIMIT : maxVMs;
		try {
			int currentCount = getVirtualMachineCount(client);
			
			if (currentCount < maxVMs) {
				return Constants.OP_SUCCESS;
			} else {
				if (considerDefaultVMLimit) {
					return Messages.Azure_GC_Template_max_VM_Err(currentCount, Constants.DEFAULT_MAX_VM_LIMIT);
				} else {
					return Messages.Azure_GC_Template_max_VM_Err(currentCount, maxVirtualMachinesLimit);
				}
			}
		} catch (Exception e) {
			return ("Exception occured while validating max virtual machines limit value");
		}
	}
	
	private static String verifyCloudServiceName(Configuration config, String templateName, String cloudServiceName, String location) {
		
		if (isNullOrEmpty(templateName)) {
			return Messages.Azure_GC_Template_Null_Or_Empty();
		} else if (isNullOrEmpty(cloudServiceName)) {
			try {
				if (!validateCloudServiceName(config, templateName)) {
					return Messages.Azure_GC_Template_Name_NA(templateName);
				}
			
				if (!doesCloudServiceLocationMatch(config, templateName, location)) {
					return Messages.Azure_GC_Template_CS_LOC_No_Match();
				}
			} catch (Exception e) {
				// Ignore silently???
			}
		} else if (!isNullOrEmpty(cloudServiceName)) {
			
			try {
				if (!validateCloudServiceName(config, cloudServiceName)) {
					return Messages.Azure_GC_Template_CS_NA(cloudServiceName);
				}
			
				if (!doesCloudServiceLocationMatch(config, cloudServiceName, location)) {
					return Messages.Azure_GC_Template_CS_LOC_No_Match();
				}
			} catch (Exception e) {
				// Ignore silently???
			}
		}
		
		return Constants.OP_SUCCESS;
	}
	
	// For future use
	private static String verifyStorageAccountName(Configuration config, String storageAccountName, String location) {
		try {
			if (isNullOrEmpty(storageAccountName)) {
				return Messages.Azure_GC_Template_SA_Null_Or_Empty();
			} else {
				StorageAccountProperties storageProps = StorageServiceDelegate.getStorageAccountProps(config, storageAccountName);
			
				if (location != null && !location.equalsIgnoreCase(storageProps.getLocation())) {
					return Messages.Azure_GC_Template_SA_LOC_No_Match(storageProps.getLocation(), location);
				}
			}
		} catch (Exception e) {
			return "Error: Failed to validate storage account name "+e;
		}
		return Constants.OP_SUCCESS;
	}
	
	public static String verifyNoOfExecutors(String noOfExecutors) {
		try {
			if (isNullOrEmpty(noOfExecutors)) {
				return Messages.Azure_GC_Template_Executors_Null_Or_Empty();
			} else {
				AzureUtil.isPositiveInteger(noOfExecutors);
				return Constants.OP_SUCCESS;
			}
		} catch (IllegalArgumentException e) {
			return Messages.Azure_GC_Template_Executors_Not_Positive(); 
		}
	}
	
	public static String verifyRetentionTime(String retentionTimeInMin) {
		try {
			if (isNullOrEmpty(retentionTimeInMin)) {
				return Messages.Azure_GC_Template_RT_Null_Or_Empty();
			} else {
				AzureUtil.isNonNegativeInteger(retentionTimeInMin);
				return Constants.OP_SUCCESS;
			}
		} catch (IllegalArgumentException e) {
			return Messages.Azure_GC_Template_RT_Not_Positive(); 
		}
	}
	
	private static String verifyImageIdOrFamily(Configuration config, String imageIdOrFamily, String location, String slaveLaunchMethod,
			String storageAccountName) {
			if (isNullOrEmpty(imageIdOrFamily)) {
				return Messages.Azure_GC_Template_ImageFamilyOrID_Null_Or_Empty();
			} else {
				Map<ImageProperties , String> imageProps = getImageProperties(config, imageIdOrFamily.trim());
				
				if (imageProps == null) {
					return Messages.Azure_GC_Template_ImageFamilyOrID_Not_Valid();
				}
				
				String saValidation = "";
				if (AzureUtil.isNotNull(storageAccountName)) {
					ImageType imageType = ImageType.valueOf(imageProps.get(ImageProperties.IMAGETYPE));
					if (ImageType.OSIMAGE.equals(imageType) || slaveLaunchMethod.equalsIgnoreCase(Constants.LAUNCH_METHOD_JNLP)) {
						try {
							StorageAccountProperties storageProps = StorageServiceDelegate.getStorageAccountProps(config, storageAccountName);
						
							if (location != null && !location.equalsIgnoreCase(storageProps.getLocation())) {
								saValidation = "\n "+Messages.Azure_GC_Template_SA_LOC_No_Match(storageProps.getLocation(), location);
							}
						} catch (Exception e) {
							saValidation = "\n Error: Failed to validate storage account name "+e;
						}
					}
				}
				
				String storageLocation = imageProps.get(ImageProperties.LOCATION);
				if (imageProps!= null && storageLocation != null && !storageLocation.contains(location)) {
					return Messages.Azure_GC_Template_ImageFamilyOrID_LOC_No_Match(storageLocation) +saValidation;
				}
				
				if(imageProps!= null && (!Constants.OS_TYPE_WINDOWS.equalsIgnoreCase(imageProps.get(ImageProperties.OSTYPE)) && 
						slaveLaunchMethod.equalsIgnoreCase(Constants.LAUNCH_METHOD_JNLP))) {
					return Messages.Azure_GC_Template_JNLP_Not_Supported() + saValidation;
				}
				
				if (AzureUtil.isNotNull(saValidation)) {
					return saValidation;
				}
			}
			return Constants.OP_SUCCESS;
	}
	
	private static String verifyVirtualNetwork(Configuration config, String virtualNetworkName, String subnetName) {
		if (AzureUtil.isNotNull(virtualNetworkName)) {
			VirtualNetworkSite virtualNetworkSite = getVirtualNetworkSite(config, virtualNetworkName);
		
			if (virtualNetworkSite == null) {
				return Messages.Azure_GC_Template_VirtualNetwork_NotFound(virtualNetworkName);
			}
			
			if (AzureUtil.isNotNull(subnetName)) {
				ArrayList<Subnet> subnets = virtualNetworkSite.getSubnets();
				if (subnets != null) {
					
					boolean found = false;
					for (Subnet subnet : subnets) {
						if (subnet.getName().equalsIgnoreCase(subnetName)) {
							found = true;
							break;
						}
					}
					
					if (!found) {
						return Messages.Azure_GC_Template_subnet_NotFound(subnetName);
					}
				}
			}
		} else if (AzureUtil.isNotNull(subnetName)) {
			return Messages.Azure_GC_Template_VirtualNetwork_Null_Or_Empty();
		}
		return Constants.OP_SUCCESS;
	}
	
	
	private static String verifyAdminUserName(String userName) {
		if (AzureUtil.isNull(userName)) {
			return Messages.Azure_GC_Template_UN_Null_Or_Empty();
		}
		
		if(AzureUtil.isValidUserName(userName)) {
			return Constants.OP_SUCCESS;
		} else {
			return Messages.Azure_GC_UserName_Err();
		}
	}
	
	private static String verifyAdminPassword(String adminPassword) {
		if (AzureUtil.isNull(adminPassword)) {
			return Messages.Azure_GC_Template_PWD_Null_Or_Empty();
		}
		
		if (AzureUtil.isValidPassword(adminPassword)) {
			return Constants.OP_SUCCESS;
		} else {
			return Messages.Azure_GC_Template_PWD_Not_Valid();
		}
	}
	
	private static String verifyJvmOptions(String jvmOptions) {
		if (AzureUtil.isNull(jvmOptions) || AzureUtil.isValidJvmOption(jvmOptions)) {
			return Constants.OP_SUCCESS;
		} else {
			return Messages.Azure_GC_JVM_Option_Err();
		}
	}
	
	
	private static boolean isNullOrEmpty(String value) {
		if (value == null || value.trim().length() == 0) {
			return true;
		} else {
			return false;
		}
	}
	
}
