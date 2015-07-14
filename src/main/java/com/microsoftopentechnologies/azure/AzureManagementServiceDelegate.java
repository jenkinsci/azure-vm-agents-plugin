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

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
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
import com.microsoft.windowsazure.management.compute.models.VirtualMachineOSImageGetResponse;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineOSImageListResponse;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineOSImageListResponse.VirtualMachineOSImage;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineRoleType;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineShutdownParameters;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineVMImageListResponse;
import com.microsoft.windowsazure.management.compute.models.VirtualMachineVMImageListResponse.VirtualMachineVMImage;
import com.microsoft.windowsazure.management.models.AffinityGroupGetResponse;
import com.microsoft.windowsazure.management.models.LocationsListResponse;
import com.microsoft.windowsazure.management.models.LocationsListResponse.Location;
import com.microsoft.windowsazure.management.models.RoleSizeListResponse;
import com.microsoft.windowsazure.management.models.RoleSizeListResponse.RoleSize;
import com.microsoft.windowsazure.management.network.NetworkManagementClient;
import com.microsoft.windowsazure.management.network.models.NetworkListResponse;
import com.microsoft.windowsazure.management.network.models.NetworkListResponse.Subnet;
import com.microsoft.windowsazure.management.network.models.NetworkListResponse.VirtualNetworkSite;
import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.retry.ExponentialRetryStrategy;
import com.microsoftopentechnologies.azure.util.AzureUtil;
import com.microsoftopentechnologies.azure.util.Constants;
import com.microsoftopentechnologies.azure.util.ExecutionEngine;
import com.microsoftopentechnologies.azure.util.FailureStage;
import com.microsoft.windowsazure.management.compute.ComputeManagementClient;
import com.microsoft.windowsazure.management.storage.StorageManagementClient;
import com.microsoft.windowsazure.management.storage.StorageManagementService;
import com.microsoft.windowsazure.management.storage.models.CheckNameAvailabilityResponse;
import com.microsoft.windowsazure.management.storage.models.StorageAccount;
import com.microsoft.windowsazure.management.storage.models.StorageAccountCreateParameters;
import com.microsoft.windowsazure.management.storage.models.StorageAccountListResponse;
import com.microsoft.windowsazure.management.storage.models.StorageAccountProperties;
import java.util.EnumMap;
import java.util.logging.Level;
import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;

/**
 * Business delegate class which handles calls to Azure management service SDK.
 *
 * @author Suresh Nallamilli (snallami@gmail.com)
 *
 */
public class AzureManagementServiceDelegate {

    private static final Logger LOGGER = Logger.getLogger(AzureManagementServiceDelegate.class.getName());

    /**
     * Handles virtual machine creation in azure as per the configuration in slave template.
     *
     * @param template Slave template configuration as defined in global configuration.
     * @return instance of Azure slave which holds necessary details of virtual machine and cloud details.
     * @throws AzureCloudException throws exception in case of any error during virtual machine creation.
     */
    public static AzureSlave createVirtualMachine(final AzureSlaveTemplate template) throws AzureCloudException {
        AzureSlave slave = null;

        try {
            LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: createVirtualMachine: Initializing create virtual "
                    + "machine request for slaveTemaple {0}", template.getTemplateName());

            AzureCloud azureCloud = template.getAzureCloud();

            // Load configuration
            Configuration config = ServiceDelegateHelper.loadConfiguration(
                    azureCloud.getSubscriptionId(),
                    azureCloud.getNativeClientId(),
                    azureCloud.getOauth2TokenEndpoint(),
                    azureCloud.getAzureUsername(),
                    azureCloud.getAzurePassword(),
                    azureCloud.getServiceManagementURL());
            ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);

            // Get image properties
            Map<ImageProperties, String> imageProperties = getImageProperties(config, template.getImageIdOrFamily().
                    trim());
            LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: createVirtualMachine: imageProperties {0}",
                    imageProperties);

            // Get virtual network location
            VirtualNetworkSite virtualNetworkSite = getVirtualNetworkSite(config, template.getVirtualNetworkName());
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
            String customImageStorageAccountName = null;
            if (mediaURI != null) {
                customImageStorageAccountName = getCustomImageStorageAccountName(new URI(imageProperties.get(
                        ImageProperties.MEDIAURI)));
            }

            if (customImageStorageAccountName != null) {
                template.setStorageAccountName(customImageStorageAccountName);
            }

            // create storage account if required
            if (customImageStorageAccountName == null && StringUtils.isBlank(template.getStorageAccountName())) {
                try {
                    String storageAccountName = createStorageAccount(config, resourceLocation, affinityGroupName);
                    template.setStorageAccountName(storageAccountName);
                } catch (Exception e) {
                    template.handleTemplateStatus(
                            "Pre-Provisioning Failure: Exception occured while creating storage account. Root cause: "
                            + e.getMessage(), FailureStage.PREPROVISIONING, null);
                    throw new AzureCloudException(
                            "Pre-Provisioning Failure: Exception occured while creating storage account. "
                            + "Root cause: " + e.getMessage());
                }
            }

            // Get cloud service name
            String cloudServiceName = StringUtils.isNotBlank(template.getCloudServiceName()) ? template.
                    getCloudServiceName() : template.getTemplateName();
            String deploymentName = getExistingDeploymentName(config, cloudServiceName);
            OperationStatusResponse response = null;
            boolean successful = false;
            int retryCount = 0;

            // Check if cloud service exists or not , if not create new one
            if ((createCloudServiceIfNotExists(config, cloudServiceName, resourceLocation, affinityGroupName))
                    || (deploymentName == null)) {
                deploymentName = cloudServiceName;
                LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: createVirtualMachine: "
                        + "Creating deployment {0} for cloud service {1}",
                        new Object[] { deploymentName, cloudServiceName });

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
                            template.handleTemplateStatus(
                                    "Provisioning Failure: Not able to create virtual machine even after 20 retries "
                                    + ex, FailureStage.PROVISIONING, null);
                            throw new AzureCloudException(
                                    "AzureManagementServiceDelegate: createVirtualMachine: "
                                    + "Unable to create virtual machine " + ex.getMessage());
                        }
                    }
                }

                // Parse deployment response and create node/slave oject
                slave = parseDeploymentResponse(cloudServiceName, template, params);
            } else {
                LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: createVirtualMachine: "
                        + "Creating VM for cloud service {0}", cloudServiceName);
                VirtualMachineCreateParameters params = createVirtualMachineParams(
                        config, cloudServiceName, imageProperties, template);

                while (retryCount < Constants.MAX_PROV_RETRIES && !successful) {
                    retryCount++;

                    try {
                        client.getVirtualMachinesOperations().create(cloudServiceName, deploymentName, params);
                        successful = true;
                    } catch (Exception ex) {
                        handleProvisioningException(ex, template, deploymentName);

                        if (retryCount == Constants.MAX_PROV_RETRIES) {
                            template.handleTemplateStatus(
                                    "Provisioning Failure: Not able to create virtual machine even after 20 retries "
                                    + ex, FailureStage.PROVISIONING, null);
                            throw new AzureCloudException(
                                    "AzureManagementServiceDelegate: createVirtualMachine: "
                                    + "Unable to create virtual machine " + ex.getMessage());
                        }

                    }
                }

                slave = parseResponse(cloudServiceName, deploymentName, template, params);
            }

            // Reset template status if call is successful after retries
            if (successful && Constants.TEMPLATE_STATUS_DISBALED.equals(template.getTemplateStatus())) {
                template.setTemplateStatus(Constants.TEMPLATE_STATUS_ACTIVE);
                template.setTemplateStatusDetails("");
            }
            LOGGER.log(Level.INFO, "Successfully created virtual machine in cloud service {0}", slave);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AzureManagementServiceDelegate: createVirtualMachine: "
                    + "Unable to create virtual machine due to {0}", e.getMessage());
            throw new AzureCloudException(
                    "AzureManagementServiceDelegate: createVirtualMachine: "
                    + "Unable to create virtual machine due to " + e.getMessage());
        }
        return slave;
    }

    /**
     * Handle provisioning errors.
     *
     * @param ex
     * @param template
     * @param deploymentName
     * @throws AzureCloudException
     */
    public static void handleProvisioningException(
            final Exception ex, final AzureSlaveTemplate template, final String deploymentName)
            throws AzureCloudException {
        // conflict error - wait for 1 minute and try again
        if (AzureUtil.isConflictError(ex.getMessage())) {
            if (AzureUtil.isDeploymentAlreadyOccupied(ex.getMessage())) {
                LOGGER.info("AzureManagementServiceDelegate: handleProvisioningServiceException: "
                        + "Deployment already occupied");

                // Throw exception so that in retry this will go through
                throw new AzureCloudException(
                        "Provisioning Failure: Exception occured while creating virtual machine. Root cause: " + ex.
                        getMessage());
            } else {
                LOGGER.info("AzureManagementServiceDelegate: handleProvisioningServiceException: "
                        + "conflict error: waiting for a minute and will try again");
                try {
                    Thread.sleep(60 * 1000);
                } catch (InterruptedException e) {
                    //ignore
                }
            }
        } else if (AzureUtil.isBadRequestOrForbidden(ex.getMessage())) {
            LOGGER.info(
                    "AzureManagementServiceDelegate: handleProvisioningServiceException: Got bad request or forbidden");
            // no point in retrying
            template.handleTemplateStatus(
                    "Provisioning Failure: Exception occured while creating virtual machine. Root cause: " + ex.
                    getMessage(), FailureStage.PROVISIONING, null);
            throw new AzureCloudException(
                    "Provisioning Failure: Exception occured while creating virtual machine. Root cause: " + ex.
                    getMessage());
        } else if (AzureUtil.isDeploymentNotFound(ex.getMessage(), deploymentName)) {
            LOGGER.info("AzureManagementServiceDelegate: handleProvisioningServiceException: Deployment not found");

            // Throw exception so that in retry this will go through
            throw new AzureCloudException(
                    "Provisioning Failure: Exception occured while creating virtual machine. Root cause: " + ex.
                    getMessage());
        } else {
            LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: handleProvisioningException: {0}", ex);
            // set template status to disabled so that jenkins won't provision more slaves
            template.handleTemplateStatus(
                    "Provisioning Failure: Exception occured while creating virtual machine. Root cause: " + ex.
                    getMessage(), FailureStage.PROVISIONING, null);
            // wait for 10 seconds and then retry
            try {
                Thread.sleep(10 * 1000);
            } catch (InterruptedException e) {
                //ignore
            }
        }
    }

    /**
     * Retrieves production slot deployment name for cloud service.
     *
     * @param config
     * @param cloudServiceName
     * @return
     */
    private static String getExistingDeploymentName(final Configuration config, final String cloudServiceName) {
        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);

        try {
            DeploymentGetResponse response = client.getDeploymentsOperations().getBySlot(cloudServiceName,
                    DeploymentSlot.Production);

            if (response.getRoleInstances().size() > 0) {
                return response.getName();
            } else {
                return null;
            }

        } catch (Exception e) {
            // No need to check for specific exceptions since anyway final provisioning will fail
            LOGGER.info("AzureManagementServiceDelegate: "
                    + "Got exception while getting deployment name which may indicates there are no deployments");
            return null;
        }
    }

    /**
     * Adds custom script extension
     *
     * @param config Azure cloud config object
     * @param roleName rolename
     * @param cloudServiceName cloud service name
     * @param template slave template configuration
     * @return List of ResourceExtensionReference
     * @throws AzureCloudException
     */
    public static List<ResourceExtensionReference> handleCustomScriptExtension(
            final Configuration config,
            final String roleName,
            final String cloudServiceName,
            final AzureSlaveTemplate template) throws AzureCloudException {

        try {
            StorageManagementClient client = ServiceDelegateHelper.getStorageManagementClient(config);
            String storageAccountKey = client.getStorageAccountsOperations().getKeys(template.getStorageAccountName()).
                    getPrimaryKey();
            // upload init script.
            String fileName = cloudServiceName + roleName + "initscript.ps1";

            String jnlpSecret = JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(roleName);

            final String initScript;
            if (StringUtils.isBlank(template.getInitScript())) {
                // Move this to a file
                initScript = AzureUtil.DEFAULT_INIT_SCRIPT;
            } else {
                initScript = template.getInitScript();
            }

            // upload file to storage.
            String blobURL = StorageServiceDelegate.uploadConfigFileToStorage(config, template.getStorageAccountName(),
                    storageAccountKey,
                    client.getBaseUri().toString(), fileName, initScript);

            // Get jenkins server url as configured in global configuration.
            String jenkinsServerURL = Jenkins.getInstance().getRootUrl();
            if (jenkinsServerURL != null && !jenkinsServerURL.endsWith(Constants.FWD_SLASH)) {
                jenkinsServerURL = jenkinsServerURL + Constants.FWD_SLASH;
            }
            LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: handleCustomScriptExtension: "
                    + "Jenkins server url {0}", jenkinsServerURL);
            // set custom script extension in role
            return addResourceExtenions(roleName, template.getStorageAccountName(), storageAccountKey,
                    Constants.CONFIG_CONTAINER_NAME, blobURL, fileName, jenkinsServerURL, jnlpSecret);
        } catch (Exception e) {
            throw new AzureCloudException(
                    "AzureManagementServiceDelegate: handleCustomScriptExtension: "
                    + "Exception occured while adding custom extension", e);
        }

    }

    /**
     * Adds BGInfo and CustomScript extension
     *
     * @param roleName
     * @param storageAccountName
     * @param storageAccountKey
     * @param containerName
     * @param blobURL
     * @param fileName
     * @param jenkinsServerURL
     * @param jnlpSecret
     * @return
     * @throws Exception
     */
    public static List<ResourceExtensionReference> addResourceExtenions(
            final String roleName,
            final String storageAccountName,
            final String storageAccountKey,
            final String containerName,
            final String blobURL,
            final String fileName,
            final String jenkinsServerURL,
            final String jnlpSecret) throws Exception {
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
        String sasURL = StorageServiceDelegate.generateSASURL(storageAccountName, storageAccountKey, containerName,
                blobURL);

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

    /**
     * JSON string custom script public config value
     *
     * @param sasURL
     * @param fileName
     * @param jenkinsServerURL
     * @param vmName
     * @param jnlpSecret
     * @return
     * @throws Exception
     */
    public static String getCustomScriptPublicConfigValue(
            final String sasURL,
            final String fileName,
            final String jenkinsServerURL,
            final String vmName,
            final String jnlpSecret) throws Exception {
        JsonFactory factory = new JsonFactory();
        StringWriter stringWriter = new StringWriter();
        JsonGenerator json = factory.createJsonGenerator(stringWriter);

        json.writeStartObject();
        json.writeArrayFieldStart("fileUris");
        json.writeString(sasURL);
        json.writeEndArray();
        json.writeStringField("commandToExecute", "powershell -ExecutionPolicy Unrestricted -file " + fileName
                + " " + jenkinsServerURL + " " + vmName + " " + jnlpSecret + "  " + " 2>>c:\\error.log");
        json.writeEndObject();
        json.close();
        return stringWriter.toString();
    }

    /**
     * JSON string for custom script private config value.
     *
     * @param storageAccountName
     * @param storageAccountKey
     * @return
     * @throws Exception
     */
    public static String getCustomScriptPrivateConfigValue(
            final String storageAccountName,
            final String storageAccountKey)
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
     *
     * @param azureSlave
     * @param template
     * @throws Exception
     */
    public static void setVirtualMachineDetails(
            final AzureSlave azureSlave,
            final AzureSlaveTemplate template) throws Exception {
        AzureCloud azureCloud = template.getAzureCloud();
        Configuration config = ServiceDelegateHelper.loadConfiguration(
                azureCloud.getSubscriptionId(),
                azureCloud.getNativeClientId(),
                azureCloud.getOauth2TokenEndpoint(),
                azureCloud.getAzureUsername(),
                azureCloud.getAzurePassword(),
                azureCloud.getServiceManagementURL());
        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
        DeploymentGetResponse response = client.getDeploymentsOperations().getByName(azureSlave.getCloudServiceName(),
                azureSlave.getDeploymentName());

        // Getting the first virtual IP
        azureSlave.setPublicDNSName(response.getVirtualIPAddresses().get(0).getAddress().getHostAddress());

        List<RoleInstance> instances = response.getRoleInstances();
        for (RoleInstance roleInstance : instances) {
            if (roleInstance.getRoleName().equals(azureSlave.getNodeName())) {
                List<InstanceEndpoint> endPoints = roleInstance.getInstanceEndpoints();

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

    public static boolean isVirtualMachineExists(final AzureSlave slave) {
        LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: isVirtualMachineExists: VM name {0}",
                slave.getDisplayName());
        try {
            ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);
            client.getVirtualMachinesOperations().get(slave.getCloudServiceName(), slave.getDeploymentName(), slave.
                    getNodeName());
        } catch (ServiceException se) {
            if (se.getError().getCode().equals(Constants.ERROR_CODE_RESOURCE_NF)) {
                LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: isVirtualMachineExists: VM name {0}",
                        slave.getDisplayName());
                return false;
            }
            //For rest of the errors just assume vm exists
        } catch (Exception e) {
            //For rest of the errors just assume vm exists
        }
        return true;
    }

    public static boolean confirmVMExists(
            final ComputeManagementClient client,
            final String cloudServiceName,
            final String deploymentName,
            final String VMName)
            throws ServiceException, Exception {

        LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: confirmVirtualMachineExists: VM name {0}", VMName);
        try {
            client.getVirtualMachinesOperations().get(cloudServiceName, deploymentName, VMName);
            return true;
        } catch (ServiceException se) {
            if (Constants.ERROR_CODE_RESOURCE_NF.equalsIgnoreCase(se.getError().getCode())) {
                return false;
            }
        } catch (Exception e) {
            throw e;
        }
        return false;
    }

    /**
     * Creates Azure slave object with necessary info
     *
     * @param response
     * @param cloudServiceName
     * @param template
     * @param params
     * @return
     * @throws AzureCloudException
     */
    private static AzureSlave parseDeploymentResponse(
            final String cloudServiceName,
            final AzureSlaveTemplate template,
            final VirtualMachineCreateDeploymentParameters params) throws AzureCloudException {
        String osType = "Windows";

        for (ConfigurationSet configSet : params.getRoles().get(0).getConfigurationSets()) {
            if (configSet.getConfigurationSetType().equals(ConfigurationSetTypes.LINUXPROVISIONINGCONFIGURATION)) {
                osType = "Linux";
                break;
            }
        }

        LOGGER.log(Level.INFO,
                "AzureManagementServiceDelegate: parseDeploymentResponse: found slave OS type as {0}", osType);
        AzureCloud azureCloud = template.getAzureCloud();

        try {
            LOGGER.log(Level.INFO,
                    "AzureManagementServiceDelegate: parseDeploymentResponse: no of executors {0}", template.
                    getNoOfParallelJobs());

            return new AzureSlave(params.getRoles().get(0).getRoleName(), template.getTemplateName(),
                    template.getTemplateDesc(), osType, template.getSlaveWorkSpace(),
                    template.getNoOfParallelJobs(),
                    template.getUseSlaveAlwaysIfAvail(), template.getLabels(),
                    template.getAzureCloud().getDisplayName(),
                    template.getAdminUserName(), null,
                    null, template.getAdminPassword(),
                    template.getJvmOptions(), template.isShutdownOnIdle(),
                    cloudServiceName, params.getName(),
                    template.getRetentionTimeInMin(), template.getInitScript(), azureCloud.getSubscriptionId(),
                    azureCloud.getNativeClientId(),
                    azureCloud.getOauth2TokenEndpoint(),
                    azureCloud.getAzureUsername(),
                    azureCloud.getAzurePassword(),
                    azureCloud.getServiceManagementURL(), template.getSlaveLaunchMethod(), false);
        } catch (FormException e) {
            throw new AzureCloudException("AzureManagementServiceDelegate: parseDeploymentResponse: "
                    + "Exception occured while creating slave object", e);
        } catch (IOException e) {
            throw new AzureCloudException("AzureManagementServiceDelegate: parseDeploymentResponse: "
                    + "Exception occured while creating slave object", e);
        }
    }

    /**
     * Creates Azure slave object with necessary info.
     *
     * @param cloudServiceName
     * @param deploymentName
     * @param template
     * @param params
     * @return
     * @throws AzureCloudException
     */
    private static AzureSlave parseResponse(
            final String cloudServiceName,
            final String deploymentName,
            final AzureSlaveTemplate template,
            final VirtualMachineCreateParameters params) throws AzureCloudException {
        try {
            String osType = "Windows";
            for (ConfigurationSet configSet : params.getConfigurationSets()) {
                if (configSet.getConfigurationSetType().equals(ConfigurationSetTypes.LINUXPROVISIONINGCONFIGURATION)) {
                    osType = "Linux";
                    break;
                }
            }

            LOGGER.log(Level.INFO,
                    "AzureManagementServiceDelegate: parseResponse: found slave OS type as {0}", osType);
            LOGGER.log(Level.INFO,
                    "AzureManagementServiceDelegate: parseDeploymentResponse: no of executors {0}", template.
                    getNoOfParallelJobs());
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
                    template.getRetentionTimeInMin(), template.getInitScript(),
                    azureCloud.getSubscriptionId(),
                    azureCloud.getNativeClientId(),
                    azureCloud.getOauth2TokenEndpoint(),
                    azureCloud.getAzureUsername(),
                    azureCloud.getAzurePassword(),
                    azureCloud.getServiceManagementURL(), template.getSlaveLaunchMethod(),
                    false);
        } catch (FormException e) {
            throw new AzureCloudException("AzureManagementServiceDelegate: parseResponse: "
                    + "Exception occured while creating slave object", e);
        } catch (IOException e) {
            throw new AzureCloudException("AzureManagementServiceDelegate: parseResponse: "
                    + "Exception occured while creating slave object", e);
        }
    }

    /**
     * Creates new cloud service if cloud service is does not exists.
     *
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
    private static boolean createCloudServiceIfNotExists(
            final Configuration config,
            final String cloudServiceName,
            final String location,
            final String affinityGroupName) throws IOException,
            ServiceException, ParserConfigurationException, SAXException, AzureCloudException, URISyntaxException,
            InterruptedException,
            ExecutionException, TransformerException {
        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);

        // check if cloud service already exists in subscription
        if (checkIfCloudServiceExists(client, cloudServiceName)) {
            LOGGER.info(
                    "AzureManagementServiceDelegate: createCloudServiceIfNotExists: Cloud service already exists , no need to create one");
            return false;
        }

        // Check if cloud service name is available
        if (!checkIfCloudServiceNameAvailable(client, cloudServiceName)) {
            LOGGER.log(Level.SEVERE, "AzureManagementServiceDelegate: createCloudServiceIfNotExists: "
                    + "Cloud service Name {0} is not available", cloudServiceName);
            LOGGER.severe(
                    "AzureManagementServiceDelegate: createCloudServiceIfNotExists: Please retry by configuring a different cloudservice name");
            throw new AzureCloudException(
                    "AzureManagementServiceDelegate: createCloudServiceIfNotExists: Cloud Service Name is not available, try configuring a different name in global configuration");
        }

        // Create new cloud service
        LOGGER.log(Level.INFO, "AzureManagementServiceDelegate - createCloudServiceIfNotExists: "
                + "creating new cloud service{0}", cloudServiceName);
        HostedServiceCreateParameters params = new HostedServiceCreateParameters();
        params.setServiceName(cloudServiceName);
        params.setLabel(cloudServiceName);
        if (affinityGroupName == null) {
            params.setLocation(location);
        } else {
            params.setAffinityGroup(affinityGroupName);
        }
        client.getHostedServicesOperations().create(params);

        LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: createCloudServiceIfNotExists: "
                + "Created new cloud service with name {0}", cloudServiceName);
        return true;
    }

    /**
     * Checks if cloud service already exists in the subscription.
     *
     * @param client ComputeManagementClient
     * @param cloudServiceName cloud service name
     * @return true if cloud service exists else returns false
     */
    private static boolean checkIfCloudServiceExists(
            final ComputeManagementClient client, final String cloudServiceName) {
        boolean exists;

        try {
            // Ideally need to list hosted services , iterate and compare but i find below 
            // approach is faster.
            client.getHostedServicesOperations().get(cloudServiceName);
            exists = true;
        } catch (Exception e) {
            LOGGER.info("AzureManagementServiceDelegate: checkIfCloudServiceExists: "
                    + "Cloud service doesnot exists in subscription, need to create one");
            exists = false;
        }

        return exists;
    }

    /**
     * Checks if cloud service already exists in the subscription.
     *
     * @param config ComputeManagementClient
     * @param cloudServiceName cloud service name
     * @return true if cloud service exists else returns false
     */
    private static boolean doesCloudServiceLocationMatch(
            final Configuration config, final String cloudServiceName, final String location)
            throws Exception {
        boolean locationMatches = false;

        try {
            ComputeManagementClient client = ComputeManagementService.create(config);
            HostedServiceGetResponse resp = client.getHostedServicesOperations().get(cloudServiceName);
            HostedServiceProperties props = resp.getProperties();
            if (location != null && props.getLocation().equalsIgnoreCase(location)) {
                return true;
            }
        } catch (Exception e) {
            LOGGER.info("AzureManagementServiceDelegate: doesCloudServiceLocationMatch: "
                    + "Got exception while checking for cloud service location");
            throw e;
        }

        return locationMatches;
    }

    /**
     * Checks if cloud service name is available.
     *
     * @param client
     * @param cloudServiceName
     * @return
     * @throws Exception
     */
    private static boolean isCloudServiceNameAvailable(
            final ComputeManagementClient client, final String cloudServiceName)
            throws Exception {
        boolean exists;

        try {
            exists = client.getHostedServicesOperations().checkNameAvailability(cloudServiceName).isAvailable();
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: isCloudServiceNameAvailable: "
                    + "Cloud service name is not valid or available {0}", e.getMessage());
            exists = false;
        }

        return exists;
    }

    public static boolean validateCloudServiceName(
            final Configuration config, final String cloudServiceName) throws Exception {
        ComputeManagementClient client = ComputeManagementService.create(config);

        // Check if cloud service name already exists in subscription or if name is available
        return checkIfCloudServiceExists(client, cloudServiceName) || isCloudServiceNameAvailable(client,
                cloudServiceName);
    }

    /**
     * Checks if cloud service name is available.
     *
     * @param client ComputeManagementClient
     * @param cloudServiceName cloudServiceName
     * @return
     */
    public static boolean checkIfCloudServiceNameAvailable(
            final ComputeManagementClient client, final String cloudServiceName) {
        boolean exists;

        try {
            exists = client.getHostedServicesOperations().checkNameAvailability(cloudServiceName).isAvailable();
        } catch (Exception e) {
            LOGGER.severe(
                    "AzureManagementServiceDelegate: checkIfCloudServiceNameAvailable: Cloud service doesnot exists in subscription, need to create one");
            // Incase of exception, ignoring silently and returning false.
            exists = false;
        }

        return exists;
    }

    /**
     * Forms virtual machine deployment parameters
     *
     * @param config Azure cloud configuration object
     * @param cloudServiceName cloud service name
     * @param deploymentName deployment name
     * @param template slave template definition
     * @return VirtualMachineCreateDeploymentParameters
     * @throws Exception
     */
    private static VirtualMachineCreateDeploymentParameters createVirtualMachineDeploymentParams(
            final Configuration config,
            final String cloudServiceName,
            final String deploymentName,
            final Map<ImageProperties, String> imageProperties,
            final AzureSlaveTemplate template) throws AzureCloudException {
        VirtualMachineCreateDeploymentParameters parameters = new VirtualMachineCreateDeploymentParameters();
        parameters.setLabel(deploymentName);
        parameters.setName(deploymentName);
        // Persistent VM Role always needs to be created in production slot.
        parameters.setDeploymentSlot(DeploymentSlot.Production);

        List<Role> roles = new ArrayList<Role>();
        parameters.setRoles(roles);

        Role role = new Role();
        roles.add(role);

        String virtualMachineName = Constants.VM_NAME_PREFIX + getCurrentDate();

        List<ConfigurationSet> configurationSets = new ArrayList<ConfigurationSet>();
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
            role.setOSVirtualHardDisk(getOSVirtualHardDisk(config, template, cloudServiceName, virtualMachineName,
                    imageProperties));
        }

        String osType = imageProperties.get(ImageProperties.OSTYPE);
        // If image OS type is windows and launch method is JNLP then custom script extension needs to be enabled , 
        // so that init script can run after provisioning
        if (Constants.OS_TYPE_WINDOWS.equalsIgnoreCase(osType) && Constants.LAUNCH_METHOD_JNLP.equalsIgnoreCase(
                template.getSlaveLaunchMethod())) {
            role.setResourceExtensionReferences(
                    handleCustomScriptExtension(config, virtualMachineName, cloudServiceName, template));
        }

        if (!ImageType.VMIMAGE_SPECIALIZED.equals(imageType)) {
            // set OS configuration params
            configurationSets.add(getOSConfigurationSet(template, virtualMachineName, osType));
        }
        // set Network configuration set
        configurationSets.add(getNetworkConfigurationSet(osType, template));

        // set virtual network name
        if (StringUtils.isNotBlank(template.getVirtualNetworkName())) {
            parameters.setVirtualNetworkName(template.getVirtualNetworkName().trim());
        }
        return parameters;
    }

    /**
     * Forms virtual machine deployment parameters
     *
     * @param config Azure cloud configuration object
     * @param cloudServiceName cloud service name
     * @param template slave template definition
     * @return VirtualMachineCreateParameters
     * @throws Exception
     */
    private static VirtualMachineCreateParameters createVirtualMachineParams(
            final Configuration config,
            final String cloudServiceName,
            final Map<ImageProperties, String> imageProperties,
            final AzureSlaveTemplate template) throws AzureCloudException {
        VirtualMachineCreateParameters params = new VirtualMachineCreateParameters();
        String virtualMachineName = Constants.VM_NAME_PREFIX + getCurrentDate();

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
            params.setOSVirtualHardDisk(getOSVirtualHardDisk(
                    config, template, cloudServiceName, virtualMachineName, imageProperties));
        }

        String osType = imageProperties.get(ImageProperties.OSTYPE);
        // If image OS type is windows and launch method is JNLP then custom script extension needs 
        // to be enabled , so that init script can run after provisioning
        if (Constants.OS_TYPE_WINDOWS.equalsIgnoreCase(osType) && Constants.LAUNCH_METHOD_JNLP.equalsIgnoreCase(
                template.getSlaveLaunchMethod())) {
            params.setResourceExtensionReferences(handleCustomScriptExtension(config, virtualMachineName,
                    cloudServiceName, template));
        }

        if (!ImageType.VMIMAGE_SPECIALIZED.equals(imageType)) {
            // set OS configuration params
            configurationSets.add(getOSConfigurationSet(template, virtualMachineName, osType));
        }
        // set Network configuration set
        configurationSets.add(getNetworkConfigurationSet(osType, template));

        return params;
    }

    /**
     * Prepares OSVirtualHardDisk object.
     *
     * @param config
     * @param template
     * @param cloudServiceName
     * @param virtualMachineName
     * @param imageProperties
     * @return
     * @throws AzureCloudException
     */
    private static OSVirtualHardDisk getOSVirtualHardDisk(
            final Configuration config,
            final AzureSlaveTemplate template,
            final String cloudServiceName,
            final String virtualMachineName,
            final Map<ImageProperties, String> imageProperties) throws AzureCloudException {
        OSVirtualHardDisk osDisk = new OSVirtualHardDisk();
        ImageType imageType = ImageType.valueOf(imageProperties.get(ImageProperties.IMAGETYPE));

        if (ImageType.OSIMAGE.equals(imageType)) {
            String blobURI = StorageServiceDelegate.getStorageAccountURI(config, template.getStorageAccountName(),
                    Constants.BLOB);
            URI mediaLinkUriValue = null;

            try {
                mediaLinkUriValue = new URI(blobURI + Constants.CI_SYSTEM + Constants.FWD_SLASH + cloudServiceName + "-"
                        + virtualMachineName + getCurrentDate() + ".vhd");
            } catch (URISyntaxException e) {
                throw new AzureCloudException(
                        "AzureManagementServiceDelegate: createVirtualMachineDeploymentParams: Exception occured while "
                        + "forming media link URI ", e);
            }

            osDisk.setMediaLink(mediaLinkUriValue);
        }
        osDisk.setSourceImageName(imageProperties.get(ImageProperties.NAME));
        return osDisk;
    }

    /**
     * Prepares OS specific configuration.
     *
     * @param template
     * @param virtualMachineName
     * @param osType
     * @return
     * @throws AzureCloudException
     */
    private static ConfigurationSet getOSConfigurationSet(
            final AzureSlaveTemplate template, final String virtualMachineName, final String osType)
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

    /**
     * Prepares OS specific configuration.
     *
     * @param osType
     * @param template
     * @return
     */
    private static ConfigurationSet getNetworkConfigurationSet(final String osType, final AzureSlaveTemplate template) {
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

        if (StringUtils.isNotBlank(template.getVirtualNetworkName()) && StringUtils.isNotBlank(template.getSubnetName())) {
            ArrayList<String> subnetNames = new ArrayList<String>();
            subnetNames.add(template.getSubnetName().trim());

            networkConfigset.setSubnetNames(subnetNames);
        }

        return networkConfigset;
    }

    /**
     * Returns current date in MMddhhmmss.
     *
     * @return
     */
    private static String getCurrentDate() {
        Format formatter = new SimpleDateFormat("MMddhhmmss");
        return formatter.format(new Date(System.currentTimeMillis()));
    }

    /**
     * Gets list of Azure datacenter locations which supports Persistent VM role.
     *
     * @param subscriptionId
     * @param nativeClientId
     * @param oauth2TokenEndpoint
     * @param azureUsername
     * @param azurePassword
     * @param serviceManagementURL
     * @return
     * @throws IOException
     * @throws ServiceException
     * @throws ParserConfigurationException
     * @throws SAXException
     */
    public static List<String> getVirtualMachineLocations(
            final String subscriptionId,
            final String nativeClientId,
            final String oauth2TokenEndpoint,
            final String azureUsername,
            final String azurePassword,
            final String serviceManagementURL)
            throws IOException, ServiceException, ParserConfigurationException, SAXException {
        Configuration config = ServiceDelegateHelper.loadConfiguration(subscriptionId, nativeClientId,
                oauth2TokenEndpoint, azureUsername, azurePassword, serviceManagementURL);

        return getVirtualMachineLocations(config);
    }

    /**
     * Gets list of Azure datacenter locations which supports Persistent VM role.
     *
     * @param config
     * @return
     * @throws IOException
     * @throws ServiceException
     * @throws ParserConfigurationException
     * @throws SAXException
     */
    public static List<String> getVirtualMachineLocations(final Configuration config)
            throws IOException, ServiceException, ParserConfigurationException, SAXException {
        List<String> locations = new ArrayList<String>();
        ManagementClient managementClient = ServiceDelegateHelper.getManagementClient(config);
        LocationsListResponse listResponse = managementClient.getLocationsOperations().list();

        for (Location location : listResponse) {
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

    public static List<String> getStorageAccountsInfo(final Configuration config) throws Exception {
        List<String> storageAccounts = new ArrayList<String>();
        StorageManagementClient client = StorageManagementService.create(config);

        StorageAccountListResponse response = client.getStorageAccountsOperations().list();
        for (StorageAccount sa : response.getStorageAccounts()) {
            storageAccounts.add(sa.getName() + " (" + sa.getProperties().getLocation() + ")");
        }
        return storageAccounts;
    }

    public static String createStorageAccount(
            final Configuration config, final String location, final String affinityGroupName) throws Exception {
        LOGGER.log(Level.INFO, "AzureManagemenServiceDelegate: createStorageAccount: location {0} affinityGroup {1}",
                new Object[] { location, affinityGroupName });

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
            throw new Exception(
                    "AzureManagemenServiceDelegate: createStorageAccount: Not able to create storage account due to "
                    + response.getStatus());
        }
    }

    private static boolean isStorageAccountNameAvailable(final Configuration config, final String name)
            throws IOException, ServiceException, ParserConfigurationException, SAXException {
        LOGGER.log(Level.INFO, "AzureManagemenServiceDelegate: isStorageAccountNameAvailable: name {0}", name);

        StorageManagementClient client = StorageManagementService.create(config);
        CheckNameAvailabilityResponse response = client.getStorageAccountsOperations().checkNameAvailability(name);
        final boolean available = response.isAvailable();

        LOGGER.log(Level.INFO, "AzureManagemenServiceDelegate - isStorageAccountNameAvailable: "
                + "name {0} availability {1}", new Object[] { name, available });
        return available;

    }

    private static String randomString(final int StringLength) {
        String allowedCharacters = "abcdefghijklmnopqrstuvwxyz";
        Random rand = new Random();
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < StringLength; i++) {
            buf.append(allowedCharacters.charAt(rand.nextInt(allowedCharacters.length())));
        }
        return buf.toString();
    }

    /**
     * Gets list of virtual machine sizes.
     *
     * @param subscriptionId
     * @param nativeClientId
     * @param oauth2TokenEndpoint
     * @param azureUsername
     * @param azurePassword
     * @param serviceManagementURL
     * @return
     * @throws IOException
     * @throws ServiceException
     * @throws ParserConfigurationException
     * @throws SAXException
     */
    public static List<String> getVMSizes(
            final String subscriptionId,
            final String nativeClientId,
            final String oauth2TokenEndpoint,
            final String azureUsername,
            final String azurePassword,
            final String serviceManagementURL)
            throws IOException, ServiceException, ParserConfigurationException, SAXException {
        Configuration config = ServiceDelegateHelper.
                loadConfiguration(subscriptionId, nativeClientId, oauth2TokenEndpoint, azureUsername, azurePassword,
                        serviceManagementURL);

        return getVMSizes(config);
    }

    /**
     * Gets list of virtual machine sizes.
     *
     * @param config
     * @return
     * @throws IOException
     * @throws ServiceException
     * @throws ParserConfigurationException
     * @throws SAXException
     */
    public static List<String> getVMSizes(final Configuration config)
            throws IOException, ServiceException, ParserConfigurationException, SAXException {
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
     * Validates certificate configuration.
     *
     * @param subscriptionId
     * @param nativeClientId
     * @param oauth2TokenEndpoint
     * @param azureUsername
     * @param azurePassword
     * @param serviceManagementURL
     * @return
     */
    public static String verifyConfiguration(
            final String subscriptionId,
            final String nativeClientId,
            final String oauth2TokenEndpoint,
            final String azureUsername,
            final String azurePassword,
            final String serviceManagementURL) {
        try {
            Configuration config = ServiceDelegateHelper.loadConfiguration(subscriptionId, nativeClientId,
                    oauth2TokenEndpoint, azureUsername, azurePassword, serviceManagementURL);
            return verifyConfiguration(config);
        } catch (Exception e) {
            return "Failure: Exception occured while validating subscription configuration" + e;
        }
    }

    public static String verifyConfiguration(final Configuration config) {
        Callable<String> task = new Callable<String>() {

            @Override
            public String call() throws Exception {
                ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
                client.getHostedServicesOperations().checkNameAvailability("CI_SYSTEM");
                return Constants.OP_SUCCESS;
            }
        };

        try {
            return ExecutionEngine.executeWithRetry(task,
                    new ExponentialRetryStrategy(3 /* Max. retries */, 2/* Max
                     * wait interval between retries */));
        } catch (AzureCloudException e) {
            return "Failure: Exception occured while validating subscription configuration" + e;
        }
    }

    /**
     * Gets current status of virtual machine
     *
     * @param config
     * @param cloudServiceName
     * @param slot
     * @param VMName
     * @return
     * @throws Exception
     */
    public static String getVirtualMachineStatus(
            final Configuration config, final String cloudServiceName, final DeploymentSlot slot, final String VMName)
            throws Exception {
        String status = StringUtils.EMPTY;
        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
        List<RoleInstance> roleInstances = client.getDeploymentsOperations().getBySlot(cloudServiceName,
                DeploymentSlot.Production).getRoleInstances();
        for (RoleInstance instance : roleInstances) {
            if (instance.getRoleName().equals(VMName)) {
                status = instance.getInstanceStatus();
                break;
            }
        }
        return status;
    }

    /**
     * Checks if VM is reachable and in a valid state to connect.
     *
     * @param slave
     * @return
     * @throws Exception
     */
    public static boolean isVMAliveOrHealthy(final AzureSlave slave) throws Exception {
        Configuration config = ServiceDelegateHelper.loadConfiguration(
                slave.getSubscriptionId(),
                slave.getNativeClientId(),
                slave.getOauth2TokenEndpoint(),
                slave.getAzureUsername(),
                slave.getAzurePassword(),
                slave.getManagementURL());
        String status = getVirtualMachineStatus(
                config, slave.getCloudServiceName(), DeploymentSlot.Production, slave.getNodeName());
        LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: isVMAliveOrHealthy: status {0}", status);
        // if VM status is DeletingVM/StoppedVM/StoppingRole/StoppingVM then consider VM to be not healthy

        return !(Constants.DELETING_VM_STATUS.equalsIgnoreCase(status)
                || Constants.STOPPED_VM_STATUS.equalsIgnoreCase(status)
                || Constants.STOPPING_VM_STATUS.equalsIgnoreCase(status)
                || Constants.STOPPING_ROLE_STATUS.equalsIgnoreCase(status)
                || Constants.STOPPED_DEALLOCATED_VM_STATUS.equalsIgnoreCase(status));
    }

    /**
     * Retrieves count of role instances in a cloud service.
     *
     * @param client
     * @param cloudServiceName
     * @return
     * @throws Exception
     */
    public static int getRoleCount(final ComputeManagementClient client, final String cloudServiceName)
            throws Exception {
        return client.getDeploymentsOperations().
                getBySlot(cloudServiceName, DeploymentSlot.Production).getRoleInstances().size();
    }

    /**
     * Retrieves count of virtual machine roles in a azure subscription.
     *
     * @param client
     * @return
     * @throws Exception
     */
    public static int getVirtualMachineCount(final ComputeManagementClient client) throws Exception {
        int vmRoleCount = 0;

        try {
            HostedServiceListResponse response = client.getHostedServicesOperations().list();

            for (HostedService hostedService : response.getHostedServices()) {
                try {
                    DeploymentGetResponse deploymentResp = client.getDeploymentsOperations().getBySlot(hostedService.
                            getServiceName(), DeploymentSlot.Production);
                    for (Role role : deploymentResp.getRoles()) {
                        if (role.getRoleType().equals(VirtualMachineRoleType.PersistentVMRole.toString())) {
                            vmRoleCount += 1;
                        }
                    }

                } catch (Exception e) {
                    continue;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.INFO,
                    "AzureManagementServiceDelegate: getVirtualMachineCount: Got exception while getting hosted "
                    + "services info, assuming that there are no hosted services {0}", e);
        }
        LOGGER.log(Level.INFO,
                "AzureManagementServiceDelegate: getVirtualMachineCount: Virtual machines count {0}", vmRoleCount);
        return vmRoleCount;
    }

    /**
     * Shutdowns Azure virtual machine.
     *
     * @param slave
     * @throws Exception
     */
    public static void shutdownVirtualMachine(final AzureSlave slave) throws Exception {
        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);
        VirtualMachineShutdownParameters params = new VirtualMachineShutdownParameters();
        params.setPostShutdownAction(PostShutdownAction.StoppedDeallocated);
        client.getVirtualMachinesOperations().shutdown(slave.getCloudServiceName(), slave.getDeploymentName(), slave.
                getNodeName(), params);
    }

    /**
     * Deletes Azure virtual machine.
     *
     * @param slave
     * @param sync
     * @throws Exception
     */
    public static void terminateVirtualMachine(final AzureSlave slave, final boolean sync) throws Exception {
        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);
        try {
            if (getRoleCount(client, slave.getCloudServiceName()) > 1) {
                if (sync) {
                    client.getVirtualMachinesOperations().delete(
                            slave.getCloudServiceName(), slave.getDeploymentName(), slave.getNodeName(), true);
                } else {
                    client.getVirtualMachinesOperations().deleteAsync(slave.getCloudServiceName(), slave.
                            getDeploymentName(), slave.getNodeName(), true);
                }
            } else {
                if (confirmVMExists(
                        client, slave.getCloudServiceName(), slave.getDeploymentName(), slave.getNodeName())) {
                    if (sync) {
                        client.getDeploymentsOperations().deleteByName(slave.getCloudServiceName(), slave.
                                getDeploymentName(), true);
                    } else {
                        client.getDeploymentsOperations().deleteByNameAsync(slave.getCloudServiceName(), slave.
                                getDeploymentName(), true);
                    }
                }
            }
        } catch (ServiceException se) {
            // Check if VM is already deleted 
            if (Constants.ERROR_CODE_RESOURCE_NF.equalsIgnoreCase(se.getError().getCode())) {
                // If VM is already deleted then just ignore exception.
                return;
            }

            LOGGER.log(Level.INFO, "AzureManagementServiceDelegate - terminateVirtualMachine: "
                    + "error code {0} Got error while deleting VM", se.getError().getCode());
            throw se;
        }
    }

    /**
     * Restarts Azure virtual machine.
     *
     * @param slave
     * @throws Exception
     */
    public static void restartVirtualMachine(final AzureSlave slave) throws Exception {
        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);
        client.getVirtualMachinesOperations().restart(slave.getCloudServiceName(), slave.getDeploymentName(), slave.
                getNodeName());
    }

    /**
     * Starts Azure virtual machine.
     *
     * @param slave
     * @throws Exception
     */
    public static void startVirtualMachine(final AzureSlave slave) throws Exception {
        LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: startVirtualMachine: {0}", slave.getNodeName());
        int retryCount = 0;
        boolean successful = false;
        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);

        while (!successful) {
            try {
                client.getVirtualMachinesOperations().start(slave.getCloudServiceName(), slave.getDeploymentName(),
                        slave.getNodeName());
                successful = true; // may be we can just return
            } catch (Exception e) {
                LOGGER.log(Level.INFO, "AzureManagementServiceDelegate - startVirtualMachine: got exception while "
                        + "starting VM {0}. Will retry again after 30 seconds. Current retry count {1} / {2}\n",
                        new Object[] { slave.getNodeName(), retryCount, Constants.MAX_PROV_RETRIES });
                if (retryCount > Constants.MAX_PROV_RETRIES) {
                    throw e;
                } else {
                    retryCount++;
                    Thread.sleep(30 * 1000); // wait for 30 seconds
                }
            }
        }
    }

    /**
     * Gets Virtual Image List.
     *
     * @param config
     * @return
     * @throws Exception
     */
    private static List<VirtualMachineOSImage> getVirtualMachineOSImageList(
            final Configuration config) throws Exception {
        List<VirtualMachineOSImage> imageList = new ArrayList<VirtualMachineOSImage>();

        ComputeManagementClient client = ComputeManagementService.create(config);
        VirtualMachineOSImageListResponse response = client.getVirtualMachineOSImagesOperations().list();

        List<VirtualMachineOSImage> osImages = response.getImages();

        for (VirtualMachineOSImage image : osImages) {
            imageList.add(image);
        }
        return imageList;
    }

    public static Set<String> getVirtualImageFamilyList(final Configuration config) throws Exception {
        Set<String> imageFamilies = new HashSet<String>();

        for (VirtualMachineOSImage image : getVirtualMachineOSImageList(config)) {
            if (image.getImageFamily() != null && image.getImageFamily().trim().length() != 0) {
                imageFamilies.add(image.getImageFamily());
            }
        }
        return imageFamilies;
    }

    /**
     * Returns virtual machine family list.
     *
     * @param subscriptionId
     * @param nativeClientId
     * @param oauth2TokenEndpoint
     * @param azureUsername
     * @param azurePassword
     * @param serviceManagementURL
     * @return
     * @throws Exception
     */
    public static Set<String> getVirtualImageFamilyList(
            final String subscriptionId,
            final String nativeClientId,
            final String oauth2TokenEndpoint,
            final String azureUsername,
            final String azurePassword,
            final String serviceManagementURL)
            throws Exception {

        Configuration config = ServiceDelegateHelper.loadConfiguration(
                subscriptionId,
                nativeClientId,
                oauth2TokenEndpoint,
                azureUsername,
                azurePassword,
                serviceManagementURL);
        return getVirtualImageFamilyList(config);
    }

    /**
     * Checks whether image ID is valid or not.
     *
     * @param imageID
     * @param imageProps
     * @param config
     * @return
     */
    public static String isValidImageID(
            final String imageID,
            final Map<ImageProperties, String> imageProps,
            final Configuration config) {
        try {
            ComputeManagementClient client = ComputeManagementService.create(config);
            VirtualMachineOSImageGetResponse response = client.getVirtualMachineOSImagesOperations().get(imageID);

            if (response != null) {
                // set image Properties
                if (imageProps != null) {
                    imageProps.put(ImageProperties.NAME, response.getName());
                    imageProps.put(ImageProperties.LOCATION, response.getLocation());
                    imageProps.put(ImageProperties.OSTYPE, response.getOperatingSystemType());
                    if (response.getMediaLinkUri() == null) {
                        imageProps.put(ImageProperties.IMAGETYPE, ImageType.OSIMAGE.name());
                    } else {
                        imageProps.put(ImageProperties.IMAGETYPE, ImageType.OSIMAGE_CUSTOM.name());
                        imageProps.put(ImageProperties.MEDIAURI, response.getMediaLinkUri().toString());
                    }
                }
                return response.getName();
            }
        } catch (Exception e) {
            LOGGER.severe("AzureManagementServiceDelegate: isValidImageID: "
                    + "Input might be VM Image or Image Family since ImageID is not valid");
        }
        return null;
    }

    /**
     * Checks whether custom image ID is valid or not.
     *
     * @param imageID
     * @param imageProps
     * @param config
     * @return
     */
    public static String isValidCustomImageID(
            final String imageID, final Map<ImageProperties, String> imageProps, final Configuration config) {
        try {
            ComputeManagementClient client = ComputeManagementService.create(config);
            VirtualMachineVMImageListResponse response = client.getVirtualMachineVMImagesOperations().list();

            for (VirtualMachineVMImage image : response.getVMImages()) {
                if (image.getName().equalsIgnoreCase(imageID)) {
                    if (imageProps != null) {
                        imageProps.put(ImageProperties.NAME, image.getName());
                        imageProps.put(ImageProperties.LOCATION, getCustomImageLocation(image.getOSDiskConfiguration().
                                getMediaLink(), config));

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
                        if (vmOSState != null && vmOSState.equalsIgnoreCase("Generalized")) {
                            imageProps.put(ImageProperties.IMAGETYPE, ImageType.VMIMAGE_GENERALIZED.name());
                        } else {
                            imageProps.put(ImageProperties.IMAGETYPE, ImageType.VMIMAGE_SPECIALIZED.name());
                        }
                        imageProps.put(ImageProperties.MEDIAURI, image.getOSDiskConfiguration().getMediaLink().
                                toString());
                    }
                    return image.getName();
                }
            }
            return null;
        } catch (Exception e) {
            LOGGER.severe("AzureManagementServiceDelegate: isValidCustomImageID: "
                    + "Error occured while checking for custom image validity");
        }
        return null;
    }

    private static String getCustomImageLocation(final URI uri, final Configuration config) throws AzureCloudException {
        String location = null;
        LOGGER.log(Level.INFO,
                "AzureManagementServiceDelegate: getCustomImageLocation: mediaLinkURL is {0}", uri);
        String storageAccountName = uri.getHost().substring(0, uri.getHost().indexOf("."));
        LOGGER.log(Level.INFO,
                "AzureManagementServiceDelegate: getCustomImageLocation: storageAccountName {0}", storageAccountName);

        StorageAccountProperties storageProps = StorageServiceDelegate.
                getStorageAccountProps(config, storageAccountName);

        if (storageProps != null) {
            location = storageProps.getLocation();
            LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: getCustomImageLocation: location is {0}", location);
        }
        return location;
    }

    private static String getCustomImageStorageAccountName(URI uri) {
        String storageAccountName = null;
        LOGGER.log(Level.INFO,
                "AzureManagementServiceDelegate: getCustomImageStorageAccountName: mediaLinkURL is {0}", uri);
        if (uri != null) {
            storageAccountName = uri.getHost().substring(0, uri.getHost().indexOf("."));
        }
        LOGGER.log(Level.INFO,
                "AzureManagementServiceDelegate: getCustomImageStorageAccountName: storage account name is {0}",
                storageAccountName);
        return storageAccountName;
    }

    /**
     * Checks whether image family is valid or not.
     *
     * @param imageFamily
     * @param imageProperties
     * @param config
     * @return
     */
    public static String isValidImageFamily(
            final String imageFamily,
            final Map<ImageProperties, String> imageProperties,
            final Configuration config) {
        VirtualMachineOSImage latestVMImage = null;
        try {
            // Retrieve latest image 
            for (VirtualMachineOSImage image : getVirtualMachineOSImageList(config)) {
                if (imageFamily.equalsIgnoreCase(image.getImageFamily())) {
                    if (latestVMImage == null) {
                        latestVMImage = image;
                    } else if (latestVMImage.getPublishedDate().before(image.getPublishedDate())) {
                        latestVMImage = image;
                    }
                }
            }

            if (latestVMImage != null) {
                if (imageProperties != null) {
                    imageProperties.put(ImageProperties.NAME, latestVMImage.getName());
                    imageProperties.put(ImageProperties.LOCATION, latestVMImage.getLocation());
                    imageProperties.put(ImageProperties.OSTYPE, latestVMImage.getOperatingSystemType());
                    if (latestVMImage.getMediaLinkUri() == null) {
                        imageProperties.put(ImageProperties.IMAGETYPE, ImageType.OSIMAGE.name());
                    } else {
                        imageProperties.put(ImageProperties.IMAGETYPE, ImageType.OSIMAGE_CUSTOM.name());
                        imageProperties.put(ImageProperties.MEDIAURI, latestVMImage.getMediaLinkUri().toString());
                    }
                }
                return latestVMImage.getName();
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AzureManagementServiceDelegate - isValidImageFamily: "
                    + "Got exception while checking for image family validity {0}", imageFamily);
        }
        return null;
    }

    /**
     * Returns image properties.
     *
     * @param config
     * @param imageIDOrFamily
     * @return
     */
    public static Map<ImageProperties, String> getImageProperties(
            final Configuration config, final String imageIDOrFamily) {

        Map<ImageProperties, String> imageProperties = new EnumMap<ImageProperties, String>(ImageProperties.class);

        // check if is valid image ID
        String imageID = isValidImageID(imageIDOrFamily, imageProperties, config);
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

    /**
     * Returns virtual network site properties.
     *
     * @param config
     * @param virtualNetworkName
     * @return
     */
    private static VirtualNetworkSite getVirtualNetworkSite(
            final Configuration config, final String virtualNetworkName) {
        try {
            NetworkManagementClient client = ServiceDelegateHelper.getNetworkManagementClient(config);
            NetworkListResponse listResponse = client.getNetworksOperations().list();

            if (listResponse != null) {
                List<VirtualNetworkSite> sites = listResponse.getVirtualNetworkSites();

                for (VirtualNetworkSite site : sites) {
                    if (virtualNetworkName.equalsIgnoreCase(site.getName())) {
                        return site;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AzureManagementServiceDelegate - getVirtualNetworkInfo: "
                    + "Got exception while getting virtual network info {0}", virtualNetworkName);
        }
        return null;
    }

    private static String getVirtualNetworkLocation(
            final Configuration config, final VirtualNetworkSite virtualNetworkSite) {
        LOGGER.log(Level.INFO, "AzureManagementServiceDelegate - getVirtualNetworkLocation: virtualNetworkName is {0}",
                virtualNetworkSite.getName());

        if (virtualNetworkSite.getAffinityGroup() != null) {
            return getAffinityGroupLocation(config, virtualNetworkSite.getAffinityGroup());
        }/* currently virtual network site does not have location attribute
         *
         * else if (virtualNetworkSite.getLocation() != null) {
         * return virtualNetworkSite.getLocation();
         * } */

        LOGGER.info("AzureManagementServiceDelegate: getVirtualNetworkLocation: returning null");
        return null;
    }

    private static Subnet getSubnet(final VirtualNetworkSite virtualNetworkSite, final String subnetName) {
        if (StringUtils.isNotBlank(subnetName)) {
            List<Subnet> subnets = virtualNetworkSite.getSubnets();
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

    private static String getAffinityGroupLocation(final Configuration config, final String affinityGroup) {
        LOGGER.log(Level.INFO,
                "AzureManagementServiceDelegate: getAffinityGroupLocation: affinityGroup is {0}", affinityGroup);
        ManagementClient client = ServiceDelegateHelper.getManagementClient(config);
        AffinityGroupGetResponse response;

        try {
            response = client.getAffinityGroupsOperations().get(affinityGroup);
            return response.getLocation();
        } catch (Exception e) {
            // ignore
        }
        LOGGER.info("AzureManagementServiceDelegate: getAffinityGroupLocation: returning null");
        return null;
    }

    /**
     * Verifies template configuration by making server calls if needed.
     *
     * @param subscriptionId
     * @param nativeClientId
     * @param oauth2TokenEndpoint
     * @param azureUsername
     * @param azurePassword
     * @param serviceManagementURL
     * @param maxVirtualMachinesLimit
     * @param templateName
     * @param labels
     * @param location
     * @param virtualMachineSize
     * @param storageAccountName
     * @param noOfParallelJobs
     * @param imageIdOrFamily
     * @param slaveLaunchMethod
     * @param initScript
     * @param adminUserName
     * @param adminPassword
     * @param virtualNetworkName
     * @param subnetName
     * @param retentionTimeInMin
     * @param cloudServiceName
     * @param templateStatus
     * @param jvmOptions
     * @param returnOnSingleError
     * @return
     */
    public static List<String> verifyTemplate(
            final String subscriptionId,
            final String nativeClientId,
            final String oauth2TokenEndpoint,
            final String azureUsername,
            final String azurePassword,
            final String serviceManagementURL,
            final String maxVirtualMachinesLimit,
            final String templateName,
            final String labels,
            final String location,
            final String virtualMachineSize,
            final String storageAccountName,
            final String noOfParallelJobs,
            final String imageIdOrFamily,
            final String slaveLaunchMethod,
            final String initScript,
            final String adminUserName,
            final String adminPassword,
            final String virtualNetworkName,
            final String subnetName,
            final String retentionTimeInMin,
            final String cloudServiceName,
            final String templateStatus,
            final String jvmOptions,
            final boolean returnOnSingleError) {

        List<String> errors = new ArrayList<String>();
        Configuration config = null;

        // Load configuration
        try {
            config = ServiceDelegateHelper.loadConfiguration(subscriptionId, nativeClientId, oauth2TokenEndpoint,
                    azureUsername, azurePassword, serviceManagementURL);

            // Verify if profile configuration is valid
            String validationResult = verifyAzureProfileConfiguration(config, subscriptionId, nativeClientId,
                    oauth2TokenEndpoint, azureUsername, azurePassword);
            if (!validationResult.equalsIgnoreCase(Constants.OP_SUCCESS)) {
                errors.add(validationResult);
                // If profile validation failed , no point in validating rest of the field , just return error
                return errors;
            }

            //Verify number of parallel jobs
            if (returnOnSingleError) {
                validationResult = verifyNoOfExecutors(noOfParallelJobs);
                addValidationResultIfFailed(validationResult, errors);
                if (returnOnSingleError && errors.size() > 0) {
                    return errors;
                }
            }

            validationResult = verifyRetentionTime(retentionTimeInMin);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            validationResult = verifyAdminUserName(adminUserName);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            //verify password
            validationResult = verifyAdminPassword(adminPassword);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            //verify JVM Options
            validationResult = verifyJvmOptions(jvmOptions);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            verifyTemplateAsync(config, templateName, maxVirtualMachinesLimit, cloudServiceName, location,
                    imageIdOrFamily, slaveLaunchMethod, storageAccountName, virtualNetworkName, subnetName, errors,
                    returnOnSingleError);

        } catch (Exception e) {
            errors.add("Error occured while validating Azure Profile");
        }

        return errors;
    }

    private static void verifyTemplateAsync(
            final Configuration config,
            final String templateName,
            final String maxVirtualMachinesLimit,
            final String cloudServiceName,
            final String location,
            final String imageIdOrFamily,
            final String slaveLaunchMethod,
            final String storageAccountName,
            final String virtualNetworkName,
            final String subnetName,
            final List<String> errors,
            final boolean returnOnSingleError) {

        List<Callable<String>> verificationTaskList = new ArrayList<Callable<String>>();

        // Callable for max virtual limit
        if (returnOnSingleError) {
            Callable<String> callVerifyMaxVirtualMachineLimit = new Callable<String>() {

                @Override
                public String call() throws Exception {
                    return verifyMaxVirtualMachineLimit(config, maxVirtualMachinesLimit);
                }
            };
            verificationTaskList.add(callVerifyMaxVirtualMachineLimit);
        }

        // Callable for cloud service name availability
        Callable<String> callVerifyCloudServiceName = new Callable<String>() {

            @Override
            public String call() throws Exception {
                return verifyCloudServiceName(config, templateName, cloudServiceName, location);
            }
        };
        verificationTaskList.add(callVerifyCloudServiceName);

        // Callable for imageOrFamily 
        Callable<String> callVerifyImageIdOrFamily = new Callable<String>() {

            @Override
            public String call() throws Exception {
                return verifyImageIdOrFamily(config, imageIdOrFamily, location, slaveLaunchMethod, storageAccountName);
            }
        };
        verificationTaskList.add(callVerifyImageIdOrFamily);

        // Callable for virtual network.
        Callable<String> callVerifyVirtualNetwork = new Callable<String>() {

            @Override
            public String call() throws Exception {
                return verifyVirtualNetwork(config, virtualNetworkName, subnetName);
            }
        };
        verificationTaskList.add(callVerifyVirtualNetwork);

        ExecutorService executorService = Executors.newFixedThreadPool(verificationTaskList.size());

        try {
            for (Future<String> validationResult : executorService.invokeAll(verificationTaskList)) {
                try {
                    // Get will block until time expires or until task completes
                    String result = validationResult.get(60, TimeUnit.SECONDS);
                    addValidationResultIfFailed(result, errors);
                } catch (ExecutionException executionException) {
                    errors.add("Exception occured while validating temaplate " + executionException);
                } catch (TimeoutException timeoutException) {
                    errors.add("Exception occured while validating temaplate " + timeoutException);
                }
            }
        } catch (InterruptedException interruptedException) {
            errors.add("Exception occured while validating temaplate " + interruptedException);
        }
    }

    private static void addValidationResultIfFailed(final String validationResult, final List<String> errors) {
        if (!validationResult.equalsIgnoreCase(Constants.OP_SUCCESS)) {
            errors.add(validationResult);
        }
    }

    private static String verifyAzureProfileConfiguration(
            final Configuration config,
            final String subscriptionId,
            final String nativeClientId,
            final String oauth2TokenEndpoint,
            final String azureUsername,
            final String azurePassword) {

        if (StringUtils.isBlank(subscriptionId) || StringUtils.isBlank(nativeClientId)
                || StringUtils.isBlank(oauth2TokenEndpoint) || StringUtils.isBlank(azureUsername)
                || StringUtils.isBlank(azurePassword)) {

            return Messages.Azure_GC_Template_Val_Profile_Missing();
        } else {
            if (!verifyConfiguration(config).equals(Constants.OP_SUCCESS)) {
                return Messages.Azure_GC_Template_Val_Profile_Err();
            }
        }
        return Constants.OP_SUCCESS;
    }

    private static String verifyMaxVirtualMachineLimit(
            final Configuration config, final String maxVirtualMachinesLimit) {

        boolean considerDefaultVMLimit = false;
        int maxVMs = 0;
        if (StringUtils.isBlank(maxVirtualMachinesLimit)) {
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
        maxVMs = considerDefaultVMLimit ? Constants.DEFAULT_MAX_VM_LIMIT : maxVMs;
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

    private static String verifyCloudServiceName(
            final Configuration config,
            final String templateName,
            final String cloudServiceName,
            final String location) {

        if (StringUtils.isBlank(templateName)) {
            return Messages.Azure_GC_Template_Null_Or_Empty();
        } else if (StringUtils.isBlank(cloudServiceName)) {
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
        } else if (!StringUtils.isBlank(cloudServiceName)) {

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
    private static String verifyStorageAccountName(
            final Configuration config, final String storageAccountName, final String location) {
        try {
            if (StringUtils.isBlank(storageAccountName)) {
                return Messages.Azure_GC_Template_SA_Null_Or_Empty();
            } else {
                StorageAccountProperties storageProps = StorageServiceDelegate.getStorageAccountProps(config,
                        storageAccountName);

                if (location != null && !location.equalsIgnoreCase(storageProps.getLocation())) {
                    return Messages.Azure_GC_Template_SA_LOC_No_Match(storageProps.getLocation(), location);
                }
            }
        } catch (Exception e) {
            return "Error: Failed to validate storage account name " + e;
        }
        return Constants.OP_SUCCESS;
    }

    public static String verifyNoOfExecutors(final String noOfExecutors) {
        try {
            if (StringUtils.isBlank(noOfExecutors)) {
                return Messages.Azure_GC_Template_Executors_Null_Or_Empty();
            } else {
                AzureUtil.isPositiveInteger(noOfExecutors);
                return Constants.OP_SUCCESS;
            }
        } catch (IllegalArgumentException e) {
            return Messages.Azure_GC_Template_Executors_Not_Positive();
        }
    }

    public static String verifyRetentionTime(final String retentionTimeInMin) {
        try {
            if (StringUtils.isBlank(retentionTimeInMin)) {
                return Messages.Azure_GC_Template_RT_Null_Or_Empty();
            } else {
                AzureUtil.isNonNegativeInteger(retentionTimeInMin);
                return Constants.OP_SUCCESS;
            }
        } catch (IllegalArgumentException e) {
            return Messages.Azure_GC_Template_RT_Not_Positive();
        }
    }

    private static String verifyImageIdOrFamily(
            final Configuration config,
            final String imageIdOrFamily,
            final String location,
            final String slaveLaunchMethod,
            final String storageAccountName) {
        if (StringUtils.isBlank(imageIdOrFamily)) {
            return Messages.Azure_GC_Template_ImageFamilyOrID_Null_Or_Empty();
        } else {
            Map<ImageProperties, String> imageProps = getImageProperties(config, imageIdOrFamily.trim());

            if (imageProps == null) {
                return Messages.Azure_GC_Template_ImageFamilyOrID_Not_Valid();
            }

            String saValidation = StringUtils.EMPTY;
            if (StringUtils.isNotBlank(storageAccountName)) {
                ImageType imageType = ImageType.valueOf(imageProps.get(ImageProperties.IMAGETYPE));
                if (ImageType.OSIMAGE.equals(imageType) || slaveLaunchMethod.equalsIgnoreCase(
                        Constants.LAUNCH_METHOD_JNLP)) {
                    try {
                        StorageAccountProperties storageProps = StorageServiceDelegate.getStorageAccountProps(config,
                                storageAccountName);

                        if (location != null && !location.equalsIgnoreCase(storageProps.getLocation())) {
                            saValidation = "\n " + Messages.
                                    Azure_GC_Template_SA_LOC_No_Match(storageProps.getLocation(), location);
                        }
                    } catch (Exception e) {
                        saValidation = "\n Error: Failed to validate storage account name " + e;
                    }
                }
            }

            String storageLocation = imageProps.get(ImageProperties.LOCATION);
            if (storageLocation != null && !storageLocation.contains(location)) {
                return Messages.Azure_GC_Template_ImageFamilyOrID_LOC_No_Match(storageLocation) + saValidation;
            }

            if (!Constants.OS_TYPE_WINDOWS.equalsIgnoreCase(imageProps.
                    get(ImageProperties.OSTYPE)) && slaveLaunchMethod.equalsIgnoreCase(Constants.LAUNCH_METHOD_JNLP)) {
                return Messages.Azure_GC_Template_JNLP_Not_Supported() + saValidation;
            }

            if (StringUtils.isNotBlank(saValidation)) {
                return saValidation;
            }
        }
        return Constants.OP_SUCCESS;
    }

    private static String verifyVirtualNetwork(
            final Configuration config,
            final String virtualNetworkName,
            final String subnetName) {
        if (StringUtils.isNotBlank(virtualNetworkName)) {
            VirtualNetworkSite virtualNetworkSite = getVirtualNetworkSite(config, virtualNetworkName);

            if (virtualNetworkSite == null) {
                return Messages.Azure_GC_Template_VirtualNetwork_NotFound(virtualNetworkName);
            }

            if (StringUtils.isNotBlank(subnetName)) {
                List<Subnet> subnets = virtualNetworkSite.getSubnets();
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
        } else if (StringUtils.isNotBlank(subnetName)) {
            return Messages.Azure_GC_Template_VirtualNetwork_Null_Or_Empty();
        }
        return Constants.OP_SUCCESS;
    }

    private static String verifyAdminUserName(final String userName) {
        if (StringUtils.isBlank(userName)) {
            return Messages.Azure_GC_Template_UN_Null_Or_Empty();
        }

        if (AzureUtil.isValidUserName(userName)) {
            return Constants.OP_SUCCESS;
        } else {
            return Messages.Azure_GC_UserName_Err();
        }
    }

    private static String verifyAdminPassword(final String adminPassword) {
        if (StringUtils.isBlank(adminPassword)) {
            return Messages.Azure_GC_Template_PWD_Null_Or_Empty();
        }

        if (AzureUtil.isValidPassword(adminPassword)) {
            return Constants.OP_SUCCESS;
        } else {
            return Messages.Azure_GC_Template_PWD_Not_Valid();
        }
    }

    private static String verifyJvmOptions(final String jvmOptions) {
        if (StringUtils.isBlank(jvmOptions) || AzureUtil.isValidJvmOption(jvmOptions)) {
            return Constants.OP_SUCCESS;
        } else {
            return Messages.Azure_GC_JVM_Option_Err();
        }
    }

}
