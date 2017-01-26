/*
 Copyright 2016 Microsoft, Inc.
 
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
package com.microsoft.azure;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.fasterxml.jackson.databind.JsonNode;
import hudson.model.Descriptor.FormException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.microsoft.azure.management.compute.ComputeManagementClient;
import com.microsoft.azure.management.compute.VirtualMachineImageOperations;
import com.microsoft.azure.management.compute.VirtualMachineOperations;
import com.microsoft.azure.management.compute.models.VirtualMachineGetResponse;
import com.microsoft.azure.management.compute.models.InstanceViewStatus;
import com.microsoft.azure.management.compute.models.StorageProfile;
import com.microsoft.azure.management.compute.models.VirtualMachineImageGetParameters;
import com.microsoft.azure.management.compute.models.VirtualMachineListResponse;
import com.microsoft.azure.management.network.NetworkResourceProviderClient;
import com.microsoft.azure.management.network.NetworkResourceProviderService;
import com.microsoft.azure.management.network.models.NetworkInterface;
import com.microsoft.azure.management.network.models.NetworkInterfaceGetResponse;
import com.microsoft.azure.management.network.models.PublicIpAddress;
import com.microsoft.azure.management.network.models.PublicIpAddressGetResponse;
import com.microsoft.azure.management.network.models.Subnet;
import com.microsoft.azure.management.network.models.VirtualNetwork;
import com.microsoft.azure.management.network.models.VirtualNetworkListResponse;
import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.models.Deployment;
import com.microsoft.azure.management.resources.models.DeploymentMode;
import com.microsoft.azure.management.resources.models.DeploymentProperties;
import com.microsoft.azure.management.resources.models.ResourceGroup;
import com.microsoft.azure.management.storage.StorageManagementClient;
import com.microsoft.azure.management.storage.StorageManagementService;
import com.microsoft.azure.management.storage.models.StorageAccount;
import com.microsoft.azure.management.storage.models.StorageAccountKeys;
import com.microsoft.azure.management.storage.models.StorageAccountListResponse;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.core.PathUtility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.exception.ServiceException;
import com.microsoft.azure.exceptions.AzureCloudException;
import com.microsoft.azure.exceptions.UnrecoverableCloudException;
import com.microsoft.azure.management.resources.models.ResourceGroupCreateOrUpdateResult;
import com.microsoft.azure.management.storage.models.AccountType;
import com.microsoft.azure.management.storage.models.CheckNameAvailabilityResponse;
import com.microsoft.azure.management.storage.models.StorageAccountCreateParameters;
import com.microsoft.azure.management.storage.models.StorageAccountGetPropertiesResponse;
import com.microsoft.azure.retry.ExponentialRetryStrategy;
import com.microsoft.azure.retry.NoRetryStrategy;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.util.AzureUserAgentFilter;
import com.microsoft.azure.util.AzureUtil;
import com.microsoft.azure.util.CleanUpAction;
import com.microsoft.azure.util.Constants;
import com.microsoft.azure.util.ExecutionEngine;
import com.microsoft.azure.util.FailureStage;
import hudson.security.ACL;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.logging.Level;
import jenkins.model.Jenkins;
import jenkins.slaves.JnlpSlaveAgentProtocol;

import org.apache.commons.lang.StringUtils;

/**
 * Business delegate class which handles calls to Azure management service SDK.
 *
 * @author Suresh Nallamilli (snallami@gmail.com)
 *
 */
public class AzureVMManagementServiceDelegate {

    private static final Logger LOGGER = Logger.getLogger(AzureVMManagementServiceDelegate.class.getName());

    private static final String EMBEDDED_TEMPLATE_FILENAME = "/referenceImageTemplate.json";

    private static final String EMBEDDED_TEMPLATE_WITH_SCRIPT_FILENAME = "/referenceImageTemplateWithScript.json";

    private static final String EMBEDDED_TEMPLATE_IMAGE_FILENAME = "/customImageTemplate.json";

    private static final String EMBEDDED_TEMPLATE_IMAGE_WITH_SCRIPT_FILENAME = "/customImageTemplateWithScript.json";

    private static final String VIRTUAL_NETWORK_TEMPLATE_FRAGMENT_FILENAME = "/virtualNetworkFragment.json";

    private static final String IMAGE_CUSTOM_REFERENCE = "custom";

    private static final Map<String, List<String>> AVAILABLE_ROLE_SIZES = getAvailableRoleSizes();

    private static final Map<String, String> AVAILABLE_LOCATIONS_STD = getAvailableLocationsStandard();

    private static final Map<String, String> AVAILABLE_LOCATIONS_CHINA = getAvailableLocationsChina();

    private static final Map<String, String> AVAILABLE_LOCATIONS_ALL = getAvailableLocationsAll();

    /**
     * Creates a new deployment of VMs based on the provided template
     *
     * @param template Template to deploy
     * @param numberOfAgents Number of agents to create
     * @return The base name for the VMs that were created
     * @throws AzureCloudException
     * @throws java.io.IOException
     */
    public static AzureVMDeploymentInfo createDeployment(final AzureVMAgentTemplate template, final int numberOfAgents)
            throws AzureCloudException, IOException {

        InputStream embeddedTemplate = null;
        InputStream fragmentStream = null;
        try {
            LOGGER.log(Level.INFO,
                    "AzureVMManagementServiceDelegate: createDeployment: Initializing deployment for agentTemplate {0}",
                    template.getTemplateName());

            Configuration config = ServiceDelegateHelper.getConfiguration(template);
            final ResourceManagementClient client = ServiceDelegateHelper.getResourceManagementClient(config);

            final Date timestamp = new Date(System.currentTimeMillis());
            final String deploymentName = AzureUtil.getDeploymentName(template.getTemplateName(), timestamp);
            final String vmBaseName = AzureUtil.getVMBaseName(template.getTemplateName(), deploymentName, template.getOsType(), numberOfAgents);
            final String locationName = getLocationName(template.getLocation());
            if (!template.getResourceGroupName().matches(Constants.DEFAULT_RESOURCE_GROUP_PATTERN)) {
                LOGGER.log(Level.SEVERE,
                        "AzureVMManagementServiceDelegate: createDeployment: ResourceGroup Name {0} is invalid. It should be 1-64 alphanumeric characters",
                        new Object[]{template.getResourceGroupName()});
                throw new Exception("ResourceGroup Name is invalid");
            }
            final String resourceGroupName = template.getResourceGroupName();
            LOGGER.log(Level.INFO,
                    "AzureVMManagementServiceDelegate: createDeployment: Creating a new deployment {0} with VM base name {1}",
                    new Object[]{deploymentName, vmBaseName});

            client.getResourceGroupsOperations().createOrUpdate(
                    resourceGroupName,
                    new ResourceGroup(locationName));

            final Deployment deployment = new Deployment();
            final DeploymentProperties properties = new DeploymentProperties();
            deployment.setProperties(properties);

            final boolean useCustomScriptExtension
                    = template.getOsType().equals(Constants.OS_TYPE_WINDOWS) && !StringUtils.isBlank(template.getInitScript())
                    && template.getAgentLaunchMethod().equals(Constants.LAUNCH_METHOD_JNLP);

            // check if a custom image id has been provided otherwise work with publisher and offer
            if (template.getImageReferenceType().equals(IMAGE_CUSTOM_REFERENCE)) {
                if (useCustomScriptExtension) {
                    LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: createDeployment: Use embedded deployment template {0}", EMBEDDED_TEMPLATE_IMAGE_WITH_SCRIPT_FILENAME);
                    embeddedTemplate
                            = AzureVMManagementServiceDelegate.class.getResourceAsStream(EMBEDDED_TEMPLATE_IMAGE_WITH_SCRIPT_FILENAME);
                } else {
                    LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: createDeployment: Use embedded deployment template (with script) {0}", EMBEDDED_TEMPLATE_IMAGE_FILENAME);
                    embeddedTemplate
                            = AzureVMManagementServiceDelegate.class.getResourceAsStream(EMBEDDED_TEMPLATE_IMAGE_FILENAME);
                }
            } else {
                if (useCustomScriptExtension) {
                    LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: createDeployment: Use embedded deployment template (with script) {0}", EMBEDDED_TEMPLATE_WITH_SCRIPT_FILENAME);
                    embeddedTemplate
                            = AzureVMManagementServiceDelegate.class.getResourceAsStream(EMBEDDED_TEMPLATE_WITH_SCRIPT_FILENAME);
                } else {
                    LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: createDeployment: Use embedded deployment template {0}", EMBEDDED_TEMPLATE_FILENAME);
                    embeddedTemplate
                            = AzureVMManagementServiceDelegate.class.getResourceAsStream(EMBEDDED_TEMPLATE_FILENAME);
                }
            }

            final ObjectMapper mapper = new ObjectMapper();
            final JsonNode tmp = mapper.readTree(embeddedTemplate);

            // Add count variable for loop....
            final ObjectNode count = mapper.createObjectNode();
            count.put("type", "int");
            count.put("defaultValue", numberOfAgents);
            ObjectNode.class.cast(tmp.get("parameters")).replace("count", count);

            ObjectNode.class.cast(tmp.get("variables")).put("vmName", vmBaseName);
            ObjectNode.class.cast(tmp.get("variables")).put("location", locationName);

            if (StringUtils.isNotBlank(template.getImagePublisher())) {
                ObjectNode.class.cast(tmp.get("variables")).put("imagePublisher", template.getImagePublisher());
            }

            if (StringUtils.isNotBlank(template.getImageOffer())) {
                ObjectNode.class.cast(tmp.get("variables")).put("imageOffer", template.getImageOffer());
            }

            if (StringUtils.isNotBlank(template.getImageSku())) {
                ObjectNode.class.cast(tmp.get("variables")).put("imageSku", template.getImageSku());
            }

            if (StringUtils.isNotBlank(template.getOsType())) {
                ObjectNode.class.cast(tmp.get("variables")).put("osType", template.getOsType());
            }

            if (StringUtils.isNotBlank(template.getImage())) {
                ObjectNode.class.cast(tmp.get("variables")).put("image", template.getImage());
            }

            // If using the custom script extension (vs. SSH) to startup the powershell scripts,
            // add variables for that and upload the init script to the storage account
            if (useCustomScriptExtension) {
                ObjectNode.class.cast(tmp.get("variables")).put("jenkinsServerURL", Jenkins.getInstance().getRootUrl());
                // Calculate the client secrets.  The secrets are based off the machine name, 
                ArrayNode clientSecretsNode = ObjectNode.class.cast(tmp.get("variables")).putArray("clientSecrets");
                for (int i = 0; i < numberOfAgents; i++) {
                    clientSecretsNode.add(
                            JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(String.format("%s%d", vmBaseName, i)));
                }
                // Upload the startup script to blob storage
                String scriptName = String.format("%s%s", deploymentName, "init.ps1");
                String scriptUri = uploadCustomScript(template, scriptName);
                ObjectNode.class.cast(tmp.get("variables")).put("startupScriptURI", scriptUri);
                ObjectNode.class.cast(tmp.get("variables")).put("startupScriptName", scriptName);

                String storageAccountKey = ServiceDelegateHelper.getStorageManagementClient(config).getStorageAccountsOperations().listKeys(
                        template.getResourceGroupName(), template.getStorageAccountName())
                        .getStorageAccountKeys().getKey1();

                final ObjectNode storageAccountKeyNode = mapper.createObjectNode();
                storageAccountKeyNode.put("type", "secureString");
                storageAccountKeyNode.put("defaultValue", storageAccountKey);

                // Add the storage account key
                ObjectNode.class.cast(tmp.get("parameters")).replace("storageAccountKey", storageAccountKeyNode);
            }

            ObjectNode.class.cast(tmp.get("variables")).put("vmSize", template.getVirtualMachineSize());
            // Grab the username/pass
            StandardUsernamePasswordCredentials creds = AzureUtil.getCredentials(template.getCredentialsId());

            ObjectNode.class.cast(tmp.get("variables")).put("adminUsername", creds.getUsername());
            ObjectNode.class.cast(tmp.get("variables")).put("adminPassword", creds.getPassword().getPlainText());

            if (StringUtils.isNotBlank(template.getStorageAccountName())) {
                ObjectNode.class.cast(tmp.get("variables")).put("storageAccountName", template.getStorageAccountName());
            }

            // Network properties.  If the vnet name isn't blank then
            // then subnet name can't be either (based on verification rules)
            if (StringUtils.isNotBlank(template.getVirtualNetworkName())) {
                ObjectNode.class.cast(tmp.get("variables")).put("virtualNetworkName", template.getVirtualNetworkName());
                ObjectNode.class.cast(tmp.get("variables")).put("subnetName", template.getSubnetName());
                if (StringUtils.isNotBlank(template.getVirtualNetworkResourceGroupName())) {
                    ObjectNode.class.cast(tmp.get("variables")).put("virtualNetworkResourceGroupName", template.getVirtualNetworkResourceGroupName());
                } else {
                    ObjectNode.class.cast(tmp.get("variables")).put("virtualNetworkResourceGroupName", resourceGroupName);
                }
            } else {
                // Add the definition of the vnet and subnet into the template
                final String virtualNetworkName = Constants.DEFAULT_VNET_NAME;
                final String subnetName = Constants.DEFAULT_SUBNET_NAME;
                ObjectNode.class.cast(tmp.get("variables")).put("virtualNetworkName", virtualNetworkName);
                ObjectNode.class.cast(tmp.get("variables")).put("subnetName", subnetName);
                ObjectNode.class.cast(tmp.get("variables")).put("virtualNetworkResourceGroupName", resourceGroupName);

                // Read the vnet fragment
                fragmentStream = AzureVMManagementServiceDelegate.class.getResourceAsStream(VIRTUAL_NETWORK_TEMPLATE_FRAGMENT_FILENAME);

                final JsonNode virtualNetworkFragment = mapper.readTree(fragmentStream);
                // Add the virtual network fragment
                ArrayNode.class.cast(tmp.get("resources")).add(virtualNetworkFragment);

                // Because we created/updated this in the template, we need to add the appropriate
                // dependsOn node to the networkInterface
                // Microsoft.Network/virtualNetworks/<vnet name>
                // Find the network interfaces node
                ArrayNode resourcesNodes = ArrayNode.class.cast(tmp.get("resources"));
                Iterator<JsonNode> resourcesNodesIter = resourcesNodes.elements();
                while (resourcesNodesIter.hasNext()) {
                    JsonNode resourcesNode = resourcesNodesIter.next();
                    JsonNode typeNode = resourcesNode.get("type");
                    if (typeNode == null || !typeNode.asText().equals("Microsoft.Network/networkInterfaces")) {
                        continue;
                    }
                    // Find the dependsOn node
                    ArrayNode dependsOnNode = ArrayNode.class.cast(resourcesNode.get("dependsOn"));
                    // Add to the depends on node.
                    dependsOnNode.add("[concat('Microsoft.Network/virtualNetworks/', variables('virtualNetworkName'))]");
                    break;
                }
            }

            // Deployment ....
            properties.setMode(DeploymentMode.Incremental);
            properties.setTemplate(tmp.toString());

            // Register the deployment for cleanup
            AzureVMAgentCleanUpTask.registerDeployment(template.getAzureCloud().name, template.getResourceGroupName(), deploymentName);
            // Create the deployment
            client.getDeploymentsOperations().createOrUpdate(template.getResourceGroupName(), deploymentName, deployment);

            return new AzureVMDeploymentInfo(deploymentName, vmBaseName, numberOfAgents);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AzureVMManagementServiceDelegate: deployment: Unable to deploy", e);
            // Pass the info off to the template so that it can be queued for update.
            template.handleTemplateProvisioningFailure(e.getMessage(), FailureStage.PROVISIONING);
            throw new AzureCloudException(e);
        }
        finally {
            if (embeddedTemplate != null)
                embeddedTemplate.close();
            if (fragmentStream != null)
                fragmentStream.close();
        }
    }

    /**
     * Uploads the custom script for a template to blob storage
     *
     * @param template Template containing script to upload
     * @return URI of script
     */
    private static String uploadCustomScript(final AzureVMAgentTemplate template, final String targetScriptName) throws Exception {
        Configuration config = ServiceDelegateHelper.getConfiguration(template);

        String targetStorageAccount = template.getStorageAccountName();
        String resourceGroupName = template.getResourceGroupName();
        String location = template.getLocation();

        //make sure the resource group and storage account exist
        final ResourceManagementClient rmClient = ServiceDelegateHelper.getResourceManagementClient(config);
        final StorageManagementClient storageClient = ServiceDelegateHelper.getStorageManagementClient(config);

        rmClient.getResourceGroupsOperations().createOrUpdate(resourceGroupName, new ResourceGroup(location));

        try {
            StorageAccountCreateParameters createParams = new StorageAccountCreateParameters();
            createParams.setLocation(location);
            createParams.setAccountType(AccountType.StandardLRS);
            storageClient.getStorageAccountsOperations().create(resourceGroupName, targetStorageAccount, createParams);
        } catch (Exception e) {
            LOGGER.log(Level.INFO, e.getMessage());
        }
        // Get the storage account name and key
        String storageAccountKey = storageClient.getStorageAccountsOperations().listKeys(resourceGroupName, targetStorageAccount)
                .getStorageAccountKeys().getKey1();
        String scriptText = template.getInitScript();

        String blobURL = StorageServiceDelegate.uploadFileToStorage(
                config, targetStorageAccount, storageAccountKey,
                storageClient.getBaseUri().toString(), resourceGroupName, Constants.CONFIG_CONTAINER_NAME,
                targetScriptName, scriptText.getBytes("UTF-8"));
        return blobURL;
    }

    /**
     * Sets properties of virtual machine like IP and ssh port
     *
     * @param azureAgent
     * @param template
     * @throws Exception
     */
    public static void setVirtualMachineDetails(
            final AzureVMAgent azureAgent, final AzureVMAgentTemplate template) throws Exception {
        final Configuration config = ServiceDelegateHelper.getConfiguration(template);

        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);

        final VirtualMachineGetResponse vm
                = client.getVirtualMachinesOperations().get(template.getResourceGroupName(), azureAgent.getNodeName());

        final String ipRef = vm.getVirtualMachine().getNetworkProfile().getNetworkInterfaces().get(0).
                getReferenceUri();

        final NetworkInterface netIF = NetworkResourceProviderService.create(config)
                .withRequestFilterFirst(new AzureUserAgentFilter())
                .getNetworkInterfacesOperations().get(
                        template.getResourceGroupName(),
                        ipRef.substring(ipRef.lastIndexOf("/") + 1, ipRef.length())).
                getNetworkInterface();

        final String nicRef = netIF.getIpConfigurations().get(0).getPublicIpAddress().getId();

        final PublicIpAddress pubIP = NetworkResourceProviderService.create(config)
                .withRequestFilterFirst(new AzureUserAgentFilter())
                .getPublicIpAddressesOperations().get(
                        template.getResourceGroupName(),
                        nicRef.substring(nicRef.lastIndexOf("/") + 1, nicRef.length())).
                getPublicIpAddress();

        // Getting the first virtual IP
        azureAgent.setPublicDNSName(pubIP.getDnsSettings().getFqdn());
        azureAgent.setSshPort(Constants.DEFAULT_SSH_PORT);

        LOGGER.log(Level.INFO, "Azure agent details:\nnodeName{0}\nadminUserName={1}\nshutdownOnIdle={2}\nretentionTimeInMin={3}\nlabels={4}",
                new Object[]{azureAgent.getNodeName(), azureAgent.getVMCredentialsId(), azureAgent.isShutdownOnIdle(),
                    azureAgent.getRetentionTimeInMin(), azureAgent.getLabelString()});
    }

    /**
     * Determines whether a virtual machine exists.
     *
     * @param configuration Configuration for the subscription
     * @param vmName Name of the VM.
     * @param resourceGroupName Resource group of the VM.
     * @return
     */
    private static boolean virtualMachineExists(final Configuration config, final String vmName, final String resourceGroupName) {
        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: virtualMachineExists: check for {0}", vmName);

        try {
            final ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
            client.getVirtualMachinesOperations().get(resourceGroupName, vmName);
            LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: virtualMachineExists: {0} exists", vmName);
            return true;
        } catch (ServiceException se) {
            if (Constants.ERROR_CODE_RESOURCE_NF.equalsIgnoreCase(se.getError().getCode())) {
                LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: virtualMachineExists: {0} doesn't exist", vmName);
                return false;
            }
        } catch (Exception e) {
            //For rest of the errors just assume vm exists
        }

        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: virtualMachineExists: {0} may exist", vmName);
        return true;
    }

    /**
     * Determines whether a given agent exists.
     *
     * @param agent to check
     * @return True if the agent exists, false otherwise
     */
    public static boolean virtualMachineExists(final AzureVMAgent agent) {
        try {
            Configuration config = ServiceDelegateHelper.getConfiguration(agent);
            return virtualMachineExists(config, agent.getNodeName(), agent.getResourceGroupName());
        } catch (Exception e) {
            LOGGER.log(Level.INFO,
                    "AzureVMManagementServiceDelegate: virtualMachineExists: error while determining whether vm exists", e);
            return false;
        }
    }

    /**
     * Creates Azure agent object with necessary info.
     *
     * @param vmname
     * @param deploymentName
     * @param template
     * @param osType
     * @return
     * @throws AzureCloudException
     */
    public static AzureVMAgent parseResponse(
            final String vmname,
            final String deploymentName,
            final AzureVMAgentTemplate template,
            final String osType) throws AzureCloudException {
        try {

            LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: parseDeploymentResponse: \n"
                    + "\tfound agent {0}\n"
                    + "\tOS type {1}\n"
                    + "\tnumber of executors {2}",
                    new Object[]{vmname, osType, template.getNoOfParallelJobs()});

            AzureVMCloud azureCloud = template.getAzureCloud();

            return new AzureVMAgent(
                    vmname,
                    template.getTemplateName(),
                    template.getTemplateDesc(),
                    osType,
                    template.getAgentWorkspace(),
                    template.getNoOfParallelJobs(),
                    template.getUseAgentAlwaysIfAvail(),
                    template.getLabels(),
                    template.getAzureCloud().getDisplayName(),
                    template.getCredentialsId(),
                    null,
                    null,
                    template.getJvmOptions(),
                    template.isShutdownOnIdle(),
                    false,
                    deploymentName,
                    template.getRetentionTimeInMin(),
                    template.getInitScript(),
                    azureCloud.getAzureCredentialsId(),
                    azureCloud.getServicePrincipal(),
                    template.getAgentLaunchMethod(),
                    CleanUpAction.DEFAULT,
                    null,
                    template.getResourceGroupName(),
                    template.getExecuteInitScriptAsRoot(),
                    template.getDoNotUseMachineIfInitFails());
        } catch (FormException e) {
            throw new AzureCloudException("AzureVMManagementServiceDelegate: parseResponse: "
                    + "Exception occured while creating agent object", e);
        } catch (IOException e) {
            throw new AzureCloudException("AzureVMManagementServiceDelegate: parseResponse: "
                    + "Exception occured while creating agent object", e);
        }
    }

    public static List<String> getStorageAccountsInfo(final Configuration config) throws Exception {
        List<String> storageAccounts = new ArrayList<String>();
        StorageManagementClient client = StorageManagementService.create(config)
                .withRequestFilterFirst(new AzureUserAgentFilter());

        StorageAccountListResponse response = client.getStorageAccountsOperations().list();
        for (StorageAccount sa : response.getStorageAccounts()) {
            storageAccounts.add(sa.getName() + " (" + sa.getLocation() + ")");
        }
        return storageAccounts;
    }

    /**
     * Gets a map of available locations mapping display name -> name (usable in
     * template)
     *
     * @return
     */
    private static Map<String, String> getAvailableLocationsStandard() {
        final Map<String, String> locations = new HashMap<String, String>();
        locations.put("East US", "eastus");
        locations.put("West US", "westus");
        locations.put("South Central US", "southcentralus");
        locations.put("Central US", "centralus");
        locations.put("North Central US", "northcentralus");
        locations.put("North Europe", "northeurope");
        locations.put("West Europe", "westeurope");
        locations.put("Southeast Asia", "southeastasia");
        locations.put("East Asia", "eastasia");
        locations.put("Japan West", "japanwest");
        locations.put("Japan East", "japaneast");
        locations.put("Brazil South", "brazilsouth");
        locations.put("Australia Southeast", "australiasoutheast");
        locations.put("Australia East", "australiaeast");
        locations.put("Central India", "centralindia");
        locations.put("South India", "southindia");
        locations.put("West India", "westindia");
        return locations;
    }

    private static Map<String, String> getAvailableLocationsChina() {
        final Map<String, String> locations = new HashMap<String, String>();
        locations.put("China North", "chinanorth");
        locations.put("China East", "chinaeast");
        return locations;
    }

    private static Map<String, String> getAvailableLocationsAll() {
        final Map<String, String> locations = new HashMap<String, String>();
        locations.putAll(getAvailableLocationsStandard());
        locations.putAll(getAvailableLocationsChina());
        return locations;
    }

    /**
     * Creates a map containing location -> vm role size list. This is hard
     * coded and should be removed eventually once a transition to the 1.0.0 SDK
     * is made
     *
     * @return New map
     */
    private static Map<String, List<String>> getAvailableRoleSizes() {
        final Map<String, List<String>> sizes = new HashMap<String, List<String>>();
        sizes.put("East US", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("West US", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"}));
        sizes.put("South Central US", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("Central US", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("North Central US", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1_v2", "Standard_DS11_v2", "Standard_DS12_v2", "Standard_DS13_v2", "Standard_DS14_v2", "Standard_DS2_v2", "Standard_DS3_v2", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("East US 2", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"}));
        sizes.put("North Europe", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("West Europe", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"}));
        sizes.put("Southeast Asia", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"}));
        sizes.put("East Asia", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS11", "Standard_DS12", "Standard_DS13", "Standard_DS14", "Standard_DS2", "Standard_DS3", "Standard_DS4", "Standard_F1", "Standard_F16", "Standard_F2", "Standard_F4", "Standard_F8"}));
        sizes.put("Japan West", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("Japan East", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("Brazil South", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1_v2", "Standard_DS11_v2", "Standard_DS12_v2", "Standard_DS13_v2", "Standard_DS14_v2", "Standard_DS2_v2", "Standard_DS3_v2", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("Australia Southeast", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("Australia East", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"}));
        sizes.put("Central India", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1_v2", "Standard_D11_v2", "Standard_D12_v2", "Standard_D13_v2", "Standard_D14_v2", "Standard_D2_v2", "Standard_D3_v2", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1_v2", "Standard_DS11_v2", "Standard_DS12_v2", "Standard_DS13_v2", "Standard_DS14_v2", "Standard_DS2_v2", "Standard_DS3_v2", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("South India", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1_v2", "Standard_D11_v2", "Standard_D12_v2", "Standard_D13_v2", "Standard_D14_v2", "Standard_D2_v2", "Standard_D3_v2", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1_v2", "Standard_DS11_v2", "Standard_DS12_v2", "Standard_DS13_v2", "Standard_DS14_v2", "Standard_DS2_v2", "Standard_DS3_v2", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("West India", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1_v2", "Standard_D11_v2", "Standard_D12_v2", "Standard_D13_v2", "Standard_D14_v2", "Standard_D2_v2", "Standard_D3_v2", "Standard_D4_v2", "Standard_D5_v2", "Standard_F1", "Standard_F16", "Standard_F2", "Standard_F4", "Standard_F8"}));

        // China sizes, may not be exact
        sizes.put("China North", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"}));
        sizes.put("China East", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "ExtraLarge", "ExtraSmall", "Large", "Medium", "Small", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS11", "Standard_DS12", "Standard_DS13", "Standard_DS14", "Standard_DS2", "Standard_DS3", "Standard_DS4", "Standard_F1", "Standard_F16", "Standard_F2", "Standard_F4", "Standard_F8"}));

        return sizes;
    }

    /**
     * Gets map of Azure datacenter locations which supports Persistent VM role.
     * Today this is hardcoded pulling from the array, because the old form of
     * certificate based auth appears to be required.
     */
    public static Map<String, String> getVirtualMachineLocations(String serviceManagementUrl) {
        if (serviceManagementUrl != null && serviceManagementUrl.toLowerCase().contains("china")) {
            return AVAILABLE_LOCATIONS_CHINA;
        }
        return AVAILABLE_LOCATIONS_STD;
    }

    /**
     * Gets list of virtual machine sizes. Currently hardcoded because the old
     * vm size API does not support the new method of authentication
     *
     * @param location Location to obtain VM sizes for
     */
    public static List<String> getVMSizes(final String location) {
        return AVAILABLE_ROLE_SIZES.get(location);
    }

    /**
     * Validates certificate configuration.
     *
     * @param servicePrincipal
     * @param resourceGroupName
     * @param maxVMLimit
     * @param timeout
     * @return
     */
    public static String verifyConfiguration(
            final AzureCredentials.ServicePrincipal servicePrincipal,
            final String resourceGroupName, final String maxVMLimit, final String timeOut) {
        try {
            Configuration config = ServiceDelegateHelper.loadConfiguration(servicePrincipal);
            
            if(!AzureUtil.isValidTimeOut(timeOut))
                return "Invalid Timeout, Should be a positive number, minimum value "+Constants.DEFAULT_DEPLOYMENT_TIMEOUT_SEC;
            
            if(!AzureUtil.isValidResourceGroupName(resourceGroupName))
                return "Error: "+Messages.Azure_GC_Template_ResourceGroupName_Err();
            
            if(!AzureUtil.isValidMAxVMLimit(maxVMLimit))
                return "Invalid Limit, Should be a positive number, e.g. "+Constants.DEFAULT_MAX_VM_LIMIT;
            
            if (AzureUtil.isValidTimeOut(timeOut) && AzureUtil.isValidMAxVMLimit(maxVMLimit) 
                    && AzureUtil.isValidResourceGroupName(resourceGroupName)) {
                
                String result = verifyConfiguration(config, resourceGroupName);
                if (!result.matches(Constants.OP_SUCCESS))
                    return Messages.Azure_GC_Template_Val_Profile_Err();
            }
        }catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error validating profile", e);
            return Messages.Azure_GC_Template_Val_Profile_Err();
        }
        return Constants.OP_SUCCESS;
    }

    public static String verifyConfiguration(final Configuration config, final String resourceGroupName) {

        Callable<String> task = new Callable<String>() {

            @Override
            public String call() throws Exception {
                ServiceDelegateHelper.getStorageManagementClient(config).getStorageAccountsOperations().
                        checkNameAvailability("CI_SYSTEM");
                return Constants.OP_SUCCESS;
            }
        };

        try {
            return ExecutionEngine.executeWithRetry(task,
                    new ExponentialRetryStrategy(
                            3, //Max retries
                            2 // Max wait interval between retries
                    ));
        } catch (AzureCloudException e) {
            LOGGER.log(Level.SEVERE, "Error validating configuration", e);
            return "Failure: Exception occured while validating subscription configuration " + e;
        }
    }

    /**
     * Gets current status of virtual machine
     *
     * @param config
     * @param vmName
     * @return
     * @throws Exception
     */
    public static String getVirtualMachineStatus(final Configuration config, final String vmName, final String resourceGroupName)
            throws Exception {
        String powerstatus = StringUtils.EMPTY;
        String provisioning = StringUtils.EMPTY;

        final VirtualMachineGetResponse vm = ServiceDelegateHelper.getComputeManagementClient(config).
                getVirtualMachinesOperations().getWithInstanceView(resourceGroupName, vmName);

        for (InstanceViewStatus instanceStatus : vm.getVirtualMachine().getInstanceView().getStatuses()) {
            if (instanceStatus.getCode().startsWith("ProvisioningState/")) {
                provisioning = instanceStatus.getCode().replace("ProvisioningState/", "");
            }
            if (instanceStatus.getCode().startsWith("PowerState/")) {
                powerstatus = instanceStatus.getCode().replace("PowerState/", "");
            }
        }

        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: getVirtualMachineStatus:\n\tPowerState: {0}\n\tProvisioning: {1}",
                new Object[]{powerstatus, provisioning});

        return "succeeded".equalsIgnoreCase(provisioning)
                ? powerstatus.toUpperCase() : Constants.PROVISIONING_OR_DEPROVISIONING_VM_STATUS;
    }

    /**
     * Checks if VM is reachable and in a valid state to connect (or getting
     * ready to do so).
     *
     * @param agent
     * @return
     * @throws Exception
     */
    public static boolean isVMAliveOrHealthy(final AzureVMAgent agent) throws Exception {
        Configuration config = ServiceDelegateHelper.getConfiguration(agent);
        String status = getVirtualMachineStatus(config, agent.getNodeName(), agent.getResourceGroupName());
        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: isVMAliveOrHealthy: status {0}", status);
        return !(Constants.PROVISIONING_OR_DEPROVISIONING_VM_STATUS.equalsIgnoreCase(status)
                || Constants.STOPPING_VM_STATUS.equalsIgnoreCase(status)
                || Constants.STOPPED_VM_STATUS.equalsIgnoreCase(status)
                || Constants.DEALLOCATED_VM_STATUS.equalsIgnoreCase(status));
    }

    /**
     * Retrieves count of virtual machine in a azure subscription. This count is
     * based off of the VMs that the current credential set has access to. It
     * also does not deal with the classic, model. So keep this in mind.
     *
     * @param config Subscription configuration
     * @return Total VM count
     * @throws Exception
     */
    public static int getVirtualMachineCount(final Configuration config, final String resourceGroupName) throws Exception {
        try {
            ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
            VirtualMachineOperations vmOperations = client.getVirtualMachinesOperations();
            VirtualMachineListResponse response = vmOperations.list(resourceGroupName);
            int totalVms = response.getVirtualMachines().size();
            String nextLink = response.getNextLink();
            while (nextLink != null) {
                response = vmOperations.listNext(nextLink);
                totalVms += response.getVirtualMachines().size();
                nextLink = response.getNextLink();
            }
            return totalVms;
        } catch (Exception e) {
            LOGGER.log(Level.INFO,
                    "AzureVMManagementServiceDelegate: getVirtualMachineCount: Got exception while getting hosted "
                    + "services info, assuming that there are no hosted services {0}", e);
            return 0;
        }
    }

    /**
     * Shutdowns Azure virtual machine.
     *
     * @param agent
     * @throws Exception
     */
    public static void shutdownVirtualMachine(final AzureVMAgent agent) {
        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: shutdownVirtualMachine: called for {0}",
                agent.getNodeName());

        try {
            ServiceDelegateHelper.getComputeManagementClient(agent).
                    getVirtualMachinesOperations().powerOff(agent.getResourceGroupName(), agent.getNodeName());
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: provision: could not terminate or shutdown {0}, {1}",
                    new Object[]{agent.getNodeName(), e});
        }
    }

    /**
     * Deletes Azure virtual machine.
     *
     * @param agent
     * @param doSynchronousTermination
     * @throws Exception
     */
    public static void terminateVirtualMachine(final AzureVMAgent agent) throws Exception {
        final Configuration config = ServiceDelegateHelper.getConfiguration(agent);
        terminateVirtualMachine(config, agent.getNodeName(), agent.getResourceGroupName());
    }

    /**
     * Terminates a virtual machine
     *
     * @param config Azure configuration
     * @param vmName VM name
     * @param resourceGroupName Resource group containing the VM
     * @throws Exception
     */
    public static void terminateVirtualMachine(final Configuration config, final String vmName,
            final String resourceGroupName) throws Exception {
        try {
            try {
                if (virtualMachineExists(config, vmName, resourceGroupName)) {
                    final ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);

                    List<URI> diskUrisToRemove = new ArrayList<URI>();
                    StorageProfile storageProfile
                            = client.getVirtualMachinesOperations().get(resourceGroupName, vmName).getVirtualMachine().getStorageProfile();
                    // Remove the OS disks
                    diskUrisToRemove.add(new URI(storageProfile.getOSDisk().getVirtualHardDisk().getUri()));
                    // TODO: Remove data disks or add option to do so?

                    // Remove the VM
                    LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: terminateVirtualMachine: Removing virtual machine {0}", vmName);
                    client.getVirtualMachinesOperations().delete(resourceGroupName, vmName);

                    // Now remove the disks
                    for (URI diskUri : diskUrisToRemove) {
                        // Obtain container, storage account, and blob name
                        String storageAccountName = diskUri.getHost().split("\\.")[0];
                        String containerName = PathUtility.getContainerNameFromUri(diskUri, false);
                        String blobName = PathUtility.getBlobNameFromURI(diskUri, false);

                        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: terminateVirtualMachine: Removing disk blob {0}, in container {1} of storage account {2}",
                                new Object[]{blobName, containerName, storageAccountName});
                        final StorageManagementClient storageClient = ServiceDelegateHelper.getStorageManagementClient(config);
                        StorageAccountKeys storageKeys
                                = storageClient.getStorageAccountsOperations().listKeys(resourceGroupName, storageAccountName).getStorageAccountKeys();
                        URI blobURI = storageClient.getStorageAccountsOperations().getProperties(resourceGroupName, storageAccountName).getStorageAccount().getPrimaryEndpoints().getBlob();
                        CloudBlobContainer container = StorageServiceDelegate.getBlobContainerReference(
                                storageAccountName, storageKeys.getKey1(), blobURI.toString(), containerName);
                        container.getBlockBlobReference(blobName).deleteIfExists();
                    }

                    // Also remove the init script (if it exists)
                }
            } catch (ExecutionException ee) {
                LOGGER.log(Level.INFO,
                        "AzureVMManagementServiceDelegate: terminateVirtualMachine: while deleting VM", ee);

                if (!(ee.getCause() instanceof IllegalArgumentException)) {
                    throw ee;
                }

                // assume VM is no longer available
            } catch (ServiceException se) {
                LOGGER.log(Level.INFO,
                        "AzureVMManagementServiceDelegate: terminateVirtualMachine: while deleting VM", se);

                // Check if VM is already deleted: if VM is already deleted then just ignore exception.
                if (!Constants.ERROR_CODE_RESOURCE_NF.equalsIgnoreCase(se.getError().getCode())) {
                    throw se;
                }
            } finally {
                LOGGER.log(Level.INFO, "Clean operation starting for {0} NIC and IP", vmName);
                ExecutionEngine.executeAsync(new Callable<Void>() {

                    @Override
                    public Void call() throws Exception {
                        removeIPName(config, resourceGroupName, vmName);
                        return null;
                    }
                }, new NoRetryStrategy());
            }
        } catch (UnrecoverableCloudException uce) {
            LOGGER.log(Level.INFO,
                    "AzureVMManagementServiceDelegate: terminateVirtualMachine: unrecoverable exception deleting VM",
                    uce);
        }
    }

    /**
     * Remove the IP name
     *
     * @param config
     * @param resourceGroupName
     * @param vmName
     * @throws AzureCloudException We probably should record and pass in NIC/IP
     * names. Also, if we go away from 1 public IP address per system, then we
     * will need to update this.
     *
     */
    private static void removeIPName(final Configuration config,
            final String resourceGroupName, final String vmName) throws AzureCloudException {
        final NetworkResourceProviderClient client = ServiceDelegateHelper.getNetworkManagementClient(config);

        final String nic = vmName + "NIC";
        try {
            LOGGER.log(Level.INFO, "Remove NIC {0}", nic);
            final NetworkInterfaceGetResponse obj
                    = client.getNetworkInterfacesOperations().get(resourceGroupName, nic);

            if (obj == null) {
                LOGGER.log(Level.INFO, "NIC {0} already deprovisioned", nic);
            } else {
                client.getNetworkInterfacesOperations().delete(resourceGroupName, nic);
            }
        } catch (Exception ignore) {
            LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: removeIPName: while deleting NIC", ignore);
        }

        final String ip = vmName + "IPName";
        try {
            LOGGER.log(Level.INFO, "Remove IP {0}", ip);
            final PublicIpAddressGetResponse obj
                    = client.getPublicIpAddressesOperations().get(resourceGroupName, ip);
            if (obj == null) {
                LOGGER.log(Level.INFO, "IP {0} already deprovisioned", ip);
            } else {
                client.getPublicIpAddressesOperations().delete(resourceGroupName, ip);
            }
        } catch (Exception ignore) {
            LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: removeIPName: while deleting IPName", ignore);
        }
    }

    /**
     * Restarts Azure virtual machine.
     *
     * @param agent
     * @throws Exception
     */
    public static void restartVirtualMachine(final AzureVMAgent agent) throws Exception {
        ServiceDelegateHelper.getComputeManagementClient(agent).getVirtualMachinesOperations().
                restart(agent.getResourceGroupName(), agent.getNodeName());
    }

    /**
     * Starts Azure virtual machine.
     *
     * @param agent
     * @throws Exception
     */
    public static void startVirtualMachine(final AzureVMAgent agent) throws Exception {
        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: startVirtualMachine: {0}", agent.getNodeName());
        int retryCount = 0;
        boolean successful = false;

        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(agent);

        while (!successful) {
            try {
                client.getVirtualMachinesOperations().start(agent.getResourceGroupName(), agent.getNodeName());
                successful = true; // may be we can just return
            } catch (Exception e) {
                LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: startVirtualMachine: got exception while "
                        + "starting VM {0}. Will retry again after 30 seconds. Current retry count {1} / {2}\n",
                        new Object[]{agent.getNodeName(), retryCount, Constants.MAX_PROV_RETRIES});
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
     * Returns virtual network site properties.
     *
     * @param config
     * @param virtualNetworkName
     * @return
     */
    private static VirtualNetwork getVirtualNetwork(
            final Configuration config, final String virtualNetworkName, final String resourceGroupName) {
        try {
            final NetworkResourceProviderClient client = ServiceDelegateHelper.getNetworkManagementClient(config);

            final VirtualNetworkListResponse listResponse
                    = client.getVirtualNetworksOperations().list(resourceGroupName);

            if (listResponse != null) {
                for (VirtualNetwork vnet : listResponse.getVirtualNetworks()) {
                    if (virtualNetworkName.equalsIgnoreCase(vnet.getName())) {
                        return vnet;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AzureVMManagementServiceDelegate: getVirtualNetworkInfo: "
                    + "Got exception while getting virtual network info {0}", virtualNetworkName);
        }
        return null;
    }

    /**
     * Gets a final location name from a display name location.
     *
     * @param location
     * @return
     */
    private static String getLocationName(String location) {
        if (AVAILABLE_LOCATIONS_ALL.containsKey(location)) {
            return AVAILABLE_LOCATIONS_ALL.get(location);
        }

        return null;
    }

    /**
     * Verifies template configuration by making server calls if needed.
     *
     * @param servicePrincipal
     * @param templateName
     * @param labels
     * @param location
     * @param virtualMachineSize
     * @param storageAccountName
     * @param noOfParallelJobs
     * @param image
     * @param osType
     * @param imagePublisher
     * @param imageOffer
     * @param imageSku
     * @param imageVersion
     * @param agentLaunchMethod
     * @param initScript
     * @param credentialsId
     * @param virtualNetworkName
     * @param virtualNetworkResourceGroupName
     * @param subnetName
     * @param retentionTimeInMin
     * @param jvmOptions
     * @param returnOnSingleError
     * @param resourceGroupName
     * @return
     */
    public static List<String> verifyTemplate(
            final AzureCredentials.ServicePrincipal servicePrincipal,
            final String templateName,
            final String labels,
            final String location,
            final String virtualMachineSize,
            final String storageAccountName,
            final String noOfParallelJobs,
            final String image,
            final String osType,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion,
            final String agentLaunchMethod,
            final String initScript,
            final String credentialsId,
            final String virtualNetworkName,
            final String virtualNetworkResourceGroupName,
            final String subnetName,
            final String retentionTimeInMin,
            final String jvmOptions,
            final String resourceGroupName,
            final boolean returnOnSingleError) {

        List<String> errors = new ArrayList<String>();
        Configuration config = null;

        // Load configuration
        try {
            config = ServiceDelegateHelper.loadConfiguration(servicePrincipal);
            String validationResult;

            // Verify basic info about the template
            //Verify number of parallel jobs
            validationResult = verifyNoOfExecutors(noOfParallelJobs);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            validationResult = verifyRetentionTime(retentionTimeInMin);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            //verify password
            String adminPassword = "";
            try {
                StandardUsernamePasswordCredentials creds = AzureUtil.getCredentials(credentialsId);
                adminPassword = creds.getPassword().getPlainText();
            } catch (AzureCloudException e) {
                LOGGER.log(Level.SEVERE, "Could not load the VM credentials", e);
            }

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

            validationResult = verifyImageParameters(image, osType, imagePublisher, imageOffer, imageSku, imageVersion);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            validationResult = verifyLocation(location, servicePrincipal.serviceManagementURL);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            verifyTemplateAsync(
                    config,
                    location,
                    image,
                    imagePublisher,
                    imageOffer,
                    imageSku,
                    imageVersion,
                    storageAccountName,
                    virtualNetworkName,
                    virtualNetworkResourceGroupName,
                    subnetName,
                    resourceGroupName,
                    errors
            );

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error validating template", e);
            errors.add("Error occured while validating Azure Profile");
        }

        return errors;
    }

    private static void verifyTemplateAsync(
            final Configuration config,
            final String location,
            final String image,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion,
            final String storageAccountName,
            final String virtualNetworkName,
            final String virtualNetworkResourceGroupName,
            final String subnetName,
            final String resourceGroupName,
            final List<String> errors) {

        List<Callable<String>> verificationTaskList = new ArrayList<Callable<String>>();

        // Callable for virtual network.
        Callable<String> callVerifyVirtualNetwork = new Callable<String>() {

            @Override
            public String call() throws Exception {
                return verifyVirtualNetwork(config, virtualNetworkName, subnetName, virtualNetworkResourceGroupName, resourceGroupName);
            }
        };
        verificationTaskList.add(callVerifyVirtualNetwork);

        // Callable for VM image.
        Callable<String> callVerifyVirtualMachineImage = new Callable<String>() {

            @Override
            public String call() throws Exception {
                return verifyVirtualMachineImage(config,
                        location, storageAccountName, image, imagePublisher, imageOffer, imageSku, imageVersion);
            }
        };
        verificationTaskList.add(callVerifyVirtualMachineImage);

        // Callable for storage account virtual network.
        Callable<String> callVerifyStorageAccountName = new Callable<String>() {

            @Override
            public String call() throws Exception {
                return verifyStorageAccountName(config, resourceGroupName, storageAccountName);
            }
        };
        verificationTaskList.add(callVerifyStorageAccountName);
        
        try {
            for (Future<String> validationResult : AzureVMCloud.getThreadPool().invokeAll(verificationTaskList)) {
                try {
                    // Get will block until time expires or until task completes
                    String result = validationResult.get(60, TimeUnit.SECONDS);
                    addValidationResultIfFailed(result, errors);
                } catch (ExecutionException executionException) {
                    errors.add("Exception occured while validating temaplate " + executionException);
                } catch (TimeoutException timeoutException) {
                    errors.add("Exception occured while validating temaplate " + timeoutException);
                } catch (Exception others) {
                    errors.add(others.getMessage() + others);
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

    private static String verifyVirtualNetwork(
            final Configuration config,
            final String virtualNetworkName,
            final String subnetName,
            final String virtualNetworkResourceGroupName,
            final String resourceGroupName) {
        if (StringUtils.isNotBlank(virtualNetworkName)) {
            String finalResourceGroupName = resourceGroupName;
            if (StringUtils.isNotBlank(virtualNetworkResourceGroupName)) {
                finalResourceGroupName = virtualNetworkResourceGroupName;
            }
            VirtualNetwork virtualNetwork = getVirtualNetwork(config, virtualNetworkName, finalResourceGroupName);

            if (virtualNetwork == null) {
                return Messages.Azure_GC_Template_VirtualNetwork_NotFound(virtualNetworkName, finalResourceGroupName);
            }

            if (StringUtils.isNotBlank(subnetName)) {
                List<Subnet> subnets = virtualNetwork.getSubnets();
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

    private static String verifyVirtualMachineImage(
            final Configuration config,
            final String location,
            final String storageAccountName,
            final String image,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion) {
        if (StringUtils.isNotBlank(image)) {
            try {
                // Custom image verification.  We must verify that the VM image
                // storage account is the same as the target storage account.
                // The URI for he storage account should be https://<storageaccountname>.
                // Parse that out and verify agaisnt the image storageAccountName

                // Check that the image string is a URI by attempting to create
                // a URI
                final URI u;
                try {
                    u = URI.create(image);
                } catch (Exception e) {
                    return Messages.Azure_GC_Template_ImageURI_Not_Valid();
                }
                String host = u.getHost();
                // storage account name is the first element of the host
                int firstDot = host.indexOf('.');
                if (firstDot == -1) {
                    // This in an unexpected URI
                    return Messages.Azure_GC_Template_ImageURI_Not_Valid();
                }
                String uriStorageAccount = host.substring(0, firstDot);
                if (!uriStorageAccount.equals(storageAccountName)) {
                    return Messages.Azure_GC_Template_ImageURI_Not_Valid();
                }
                return Constants.OP_SUCCESS;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Invalid virtual machine image", e);
                return Messages.Azure_GC_Template_ImageURI_Not_Valid();
            }
        } else {
            try {
                final VirtualMachineImageOperations client
                        = ServiceDelegateHelper.getComputeManagementClient(config).getVirtualMachineImagesOperations();

                final VirtualMachineImageGetParameters params = new VirtualMachineImageGetParameters();
                params.setLocation(getLocationName(location));
                params.setPublisherName(imagePublisher);
                params.setOffer(imageOffer);
                params.setSkus(imageSku);
                // The image version in Azure is an encoded date.  'latest' (our default)
                // is not valid in this API request, though can be used in the
                // the image template.  If the image version is 'latest', clear
                // to the empty string
                if (imageVersion.equalsIgnoreCase("latest")) {
                    params.setVersion("");
                } else {
                    params.setVersion(imageVersion);
                }

                client.get(params);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Invalid virtual machine image", e);
                return Messages.Azure_GC_Template_ImageReference_Not_Valid(e.getMessage());
            }
            return Constants.OP_SUCCESS;
        }
    }

    private static String verifyStorageAccountName(
            final Configuration config,
            final String resourceGroupName,
            final String storageAccountName) {

        final StorageManagementClient storageClient = ServiceDelegateHelper.getStorageManagementClient(config);

        try {
            CheckNameAvailabilityResponse response = storageClient.getStorageAccountsOperations().checkNameAvailability(storageAccountName);
            if(!response.isNameAvailable()) {
                // The storage account already exists. We need to check it's not in the resource group we want to use
                try {
                    StorageAccountGetPropertiesResponse rgResponse = storageClient.getStorageAccountsOperations().getProperties(resourceGroupName, storageAccountName);
                    if(rgResponse.getStatusCode() == 200)
                        return Constants.OP_SUCCESS;
                } catch (ServiceException e) {
                    return Messages.Azure_GC_Template_SA_Already_Exists();
                }
                return Messages.Azure_GC_Template_SA_Already_Exists();
            }
        } catch (Exception e) {
            LOGGER.log(Level.INFO, e.getMessage());
            return Messages.Azure_GC_Template_SA_Cant_Validate();
        }
        return Constants.OP_SUCCESS;
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

    /**
     * Check the location. This location is the display name.
     *
     * @param location
     * @return
     */
    private static String verifyLocation(final String location, final String serviceManagementURL) {
        String locationName = getLocationName(location);
        if (locationName != null) {
            return Constants.OP_SUCCESS;
        } else {
            return Messages.Azure_GC_Template_LOC_Not_Found();
        }
    }

    /**
     * Verify the validity of the image parameters (does not verify actual
     * values)
     *
     * @param image
     * @param osType
     * @param imagePublisher
     * @param imageOffer
     * @param imageSku
     * @param imageVersion
     * @return
     */
    private static String verifyImageParameters(
            final String image,
            final String osType,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion) {
        if ((StringUtils.isNotBlank(image) && StringUtils.isNotBlank(osType))) {
            // Check that the image string is a URI by attempting to create
            // a URI
            final URI u;
            try {
                URI.create(image);
            } catch (Exception e) {
                Messages.Azure_GC_Template_ImageURI_Not_Valid();
            }
            return Constants.OP_SUCCESS;
        } else if (StringUtils.isNotBlank(imagePublisher)
                && StringUtils.isNotBlank(imageOffer)
                && StringUtils.isNotBlank(imageSku)
                && StringUtils.isNotBlank(imageVersion)) {
            return Constants.OP_SUCCESS;
        } else {
            return Messages.Azure_GC_Template_ImageReference_Not_Valid("Image parameters should not be blank.");
        }
    }
}
