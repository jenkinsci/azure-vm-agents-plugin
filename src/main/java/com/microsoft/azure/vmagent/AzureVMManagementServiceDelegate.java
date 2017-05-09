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
package com.microsoft.azure.vmagent;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import hudson.model.Descriptor.FormException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.azure.CloudException;
import com.microsoft.azure.PagedList;
import com.microsoft.azure.vmagent.Messages;

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

import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.exceptions.UnrecoverableCloudException;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.OperatingSystemTypes;
import com.microsoft.azure.management.compute.PowerState;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.compute.VirtualMachineImage;
import com.microsoft.azure.management.compute.VirtualMachineOffer;
import com.microsoft.azure.management.compute.VirtualMachinePublisher;
import com.microsoft.azure.management.compute.VirtualMachineSize;
import com.microsoft.azure.management.compute.VirtualMachineSku;
import com.microsoft.azure.management.network.Network;
import com.microsoft.azure.management.network.NetworkSecurityGroup;
import com.microsoft.azure.management.network.PublicIpAddress;
import com.microsoft.azure.management.resources.DeploymentMode;
import com.microsoft.azure.management.storage.SkuName;
import com.microsoft.azure.management.resources.Provider;
import com.microsoft.azure.management.resources.ProviderResourceType;
import com.microsoft.azure.management.resources.fluentcore.arm.Region;
import com.microsoft.azure.management.storage.StorageAccount;
import com.microsoft.azure.management.storage.StorageAccountKey;
import com.microsoft.azure.vmagent.retry.ExponentialRetryStrategy;
import com.microsoft.azure.vmagent.retry.NoRetryStrategy;
import com.microsoft.azure.storage.AccessCondition;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.util.AzureCredentials.ServicePrincipal;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.ExecutionEngine;
import com.microsoft.azure.vmagent.util.FailureStage;
import com.microsoft.azure.vmagent.util.TokenCache;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
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

    private static final String PUBLIC_IP_FRAGMENT_FILENAME = "/publicIPFragment.json";

    private static final String IMAGE_CUSTOM_REFERENCE = "custom";

    private static final Map<String, List<String>> AVAILABLE_ROLE_SIZES = getAvailableRoleSizes();

    private static final Set<String> AVAILABLE_LOCATIONS_STD = getAvailableLocationsStandard();

    private static final Set<String> AVAILABLE_LOCATIONS_CHINA = getAvailableLocationsChina();

    private static final List<String> DEFAULT_VM_SIZES = Arrays.asList(new String[]{"Standard_A0","Standard_A1","Standard_A2","Standard_A3","Standard_A5","Standard_A4","Standard_A6","Standard_A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","Standard_DS1_v2","Standard_DS2_v2","Standard_DS3_v2","Standard_DS4_v2","Standard_DS5_v2","Standard_DS11_v2","Standard_DS12_v2","Standard_DS13_v2","Standard_DS14_v2","Standard_DS15_v2","Standard_DS1","Standard_DS2","Standard_DS3","Standard_DS4","Standard_DS11","Standard_DS12","Standard_DS13","Standard_DS14","Standard_F1s","Standard_F2s","Standard_F4s","Standard_F8s","Standard_F16s","Standard_D1","Standard_D2","Standard_D3","Standard_D4","Standard_D11","Standard_D12","Standard_D13","Standard_D14","Standard_A1_v2","Standard_A2m_v2","Standard_A2_v2","Standard_A4m_v2","Standard_A4_v2","Standard_A8m_v2","Standard_A8_v2","Standard_D1_v2","Standard_D2_v2","Standard_D3_v2","Standard_D4_v2","Standard_D5_v2","Standard_D11_v2","Standard_D12_v2","Standard_D13_v2","Standard_D14_v2","Standard_D15_v2","Standard_F1","Standard_F2","Standard_F4","Standard_F8","Standard_F16"});

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
        return AzureVMManagementServiceDelegate.createDeployment(
                template,
                numberOfAgents,
                TokenCache.getInstance(template.getAzureCloud().getServicePrincipal()),
                new AzureVMAgentCleanUpTask.DeploymentRegistrar()
                );
    }

    public static AzureVMDeploymentInfo createDeployment(final AzureVMAgentTemplate template, final int numberOfAgents, TokenCache tokenCache, AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar)
            throws AzureCloudException, IOException {

        InputStream embeddedTemplate = null;
        try {
            LOGGER.log(Level.INFO,
                    "AzureVMManagementServiceDelegate: createDeployment: Initializing deployment for agentTemplate {0}",
                    template.getTemplateName());

            final Azure azureClient = tokenCache.getAzureClient();

            final Date timestamp = new Date(System.currentTimeMillis());
            final String deploymentName = AzureUtil.getDeploymentName(template.getTemplateName(), timestamp);
            final String vmBaseName = AzureUtil.getVMBaseName(template.getTemplateName(), deploymentName, template.getOsType(), numberOfAgents);
            final String locationName = getLocationName(template.getLocation());
            final String storageAccountName = template.getStorageAccountName();
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

            createAzureResourceGroup(azureClient, locationName, resourceGroupName);
            //For blob endpoint url in arm template, it's different based on different environments
            //So create StorageAccount and get suffix
            createStorageAccount(azureClient, storageAccountName, locationName, resourceGroupName);
            StorageAccount storageAccount = getStorageAccount(azureClient, storageAccountName, resourceGroupName);
            String blobEndpointSuffix = getBlobEndpointSuffixForTemplate(storageAccount);
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
            ObjectNode.class.cast(tmp.get("variables")).put("jenkinsTag", Constants.AZURE_JENKINS_TAG_VALUE);
            ObjectNode.class.cast(tmp.get("variables")).put("resourceTag", deploymentRegistrar.getDeploymentTag().get());

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
                String scriptUri = uploadCustomScript(template, scriptName, tokenCache);
                ObjectNode.class.cast(tmp.get("variables")).put("startupScriptURI", scriptUri);
                ObjectNode.class.cast(tmp.get("variables")).put("startupScriptName", scriptName);

                List<StorageAccountKey> storageKeys = azureClient.storageAccounts()
                        .getByGroup(template.getResourceGroupName(), storageAccountName)
                        .getKeys();
                if(storageKeys.isEmpty()) {
                    throw new AzureCloudException("AzureVMManagementServiceDelegate: createDeployment: "
                        + "Exception occured while fetching the storage account key");
                }
                String storageAccountKey = storageKeys.get(0).value();

                final ObjectNode storageAccountKeyNode = mapper.createObjectNode();
                storageAccountKeyNode.put("type", "secureString");
                storageAccountKeyNode.put("defaultValue", storageAccountKey);

                // Add the storage account key
                ObjectNode.class.cast(tmp.get("parameters")).replace("storageAccountKey", storageAccountKeyNode);
            }

            ObjectNode.class.cast(tmp.get("variables")).put("vmSize", template.getVirtualMachineSize());
            // Grab the username/pass
            StandardUsernamePasswordCredentials creds = template.getVMCredentials();

            ObjectNode.class.cast(tmp.get("variables")).put("adminUsername", creds.getUsername());
            ObjectNode.class.cast(tmp.get("variables")).put("adminPassword", creds.getPassword().getPlainText());

            if (StringUtils.isNotBlank(storageAccountName)) {
                ObjectNode.class.cast(tmp.get("variables")).put("storageAccountName", storageAccountName);
            }

            if(StringUtils.isNotBlank(blobEndpointSuffix)){
                ObjectNode.class.cast(tmp.get("variables")).put("blobEndpointSuffix", blobEndpointSuffix);
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
                AddDefaultVNetResourceNode(tmp, mapper, resourceGroupName);
            }

            if (!template.getUsePrivateIP()) {
                AddPublicIPResourceNode(tmp, mapper);
            }

            if (StringUtils.isNotBlank(template.getNsgName())) {
                AddNSGNode(tmp, mapper, template.getNsgName());
            }

            // Register the deployment for cleanup
            deploymentRegistrar.registerDeployment(template.getAzureCloud().name, template.getResourceGroupName(), deploymentName);
            // Create the deployment
            azureClient.deployments().define(deploymentName)
                    .withExistingResourceGroup(template.getResourceGroupName())
                    .withTemplate(tmp.toString())
                    .withParameters("{}")
                    .withMode(DeploymentMode.INCREMENTAL)
                    .beginCreate();
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
        }
    }

    private static void AddPublicIPResourceNode(
            final JsonNode template,
            final ObjectMapper mapper) throws IOException {

        final String ipName = "variables('vmName'), copyIndex(), 'IPName'";
        try (InputStream fragmentStream = AzureVMManagementServiceDelegate.class.getResourceAsStream(PUBLIC_IP_FRAGMENT_FILENAME)) {

            final JsonNode publicIPFragment = mapper.readTree(fragmentStream);
            // Add the virtual network fragment
            ArrayNode.class.cast(template.get("resources")).add(publicIPFragment);

            // Because we created/updated this in the template, we need to add the appropriate
            // dependsOn node to the networkInterface and the ipConfigurations properties
            // "[concat('Microsoft.Network/publicIPAddresses/', variables('vmName'), copyIndex(), 'IPName')]"
            // Find the network interfaces node
            ArrayNode resourcesNodes = ArrayNode.class.cast(template.get("resources"));
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
                dependsOnNode.add("[concat('Microsoft.Network/publicIPAddresses/'," + ipName + ")]");

                //Find the ipConfigurations/ipconfig1 node
                ArrayNode ipConfigurationsNode = ArrayNode.class.cast(resourcesNode.get("properties").get("ipConfigurations"));
                Iterator<JsonNode> ipConfigNodeIter = ipConfigurationsNode.elements();
                while (ipConfigNodeIter.hasNext()) {
                    JsonNode ipConfigNode = ipConfigNodeIter.next();
                    JsonNode nameNode = ipConfigNode.get("name");
                    if (nameNode == null || !nameNode.asText().equals("ipconfig1")) {
                        continue;
                    }
                    //find the properties node
                    ObjectNode propertiesNode = ObjectNode.class.cast(ipConfigNode.get("properties"));
                    //add the publicIPAddress node
                    ObjectNode publicIPIdNode = mapper.createObjectNode();
                    publicIPIdNode.put("id", "[resourceId('Microsoft.Network/publicIPAddresses', concat("
                            + ipName
                            + "))]");
                    // propertiesNode.putObject("publicIPAddress").put
                    propertiesNode.set("publicIPAddress", publicIPIdNode);
                    break;
                }
                break;
            }
        }
    }

    private static void AddNSGNode(
            final JsonNode template,
            final ObjectMapper mapper,
            final String nsgName) throws IOException {

        ObjectNode.class.cast(template.get("variables")).put("nsgName", nsgName);

        ArrayNode resourcesNodes = ArrayNode.class.cast(template.get("resources"));
        Iterator<JsonNode> resourcesNodesIter = resourcesNodes.elements();
        while (resourcesNodesIter.hasNext()) {
            JsonNode resourcesNode = resourcesNodesIter.next();
            JsonNode typeNode = resourcesNode.get("type");
            if (typeNode == null || !typeNode.asText().equals("Microsoft.Network/networkInterfaces")) {
                continue;
            }

            ObjectNode nsgNode = mapper.createObjectNode();
            nsgNode.put(
                    "id",
                    "[resourceId('Microsoft.Network/networkSecurityGroups', variables('nsgName'))]"
            );

            // Find the properties node
            // We will attach the provided NSG and not check if it's valid because that's done in the verification step
            ObjectNode propertiesNode = ObjectNode.class.cast(resourcesNode.get("properties"));
            propertiesNode.set("networkSecurityGroup", nsgNode);
            break;
        }
    }



    private static void AddDefaultVNetResourceNode(
            final JsonNode template,
            final ObjectMapper mapper,
            final String resourceGroupName) throws IOException {
        InputStream fragmentStream = null;
        try {
            // Add the definition of the vnet and subnet into the template
            final String virtualNetworkName = Constants.DEFAULT_VNET_NAME;
            final String subnetName = Constants.DEFAULT_SUBNET_NAME;
            ObjectNode.class.cast(template.get("variables")).put("virtualNetworkName", virtualNetworkName);
            ObjectNode.class.cast(template.get("variables")).put("virtualNetworkResourceGroupName", resourceGroupName);
            ObjectNode.class.cast(template.get("variables")).put("subnetName", subnetName);

            // Read the vnet fragment
            fragmentStream = AzureVMManagementServiceDelegate.class.getResourceAsStream(VIRTUAL_NETWORK_TEMPLATE_FRAGMENT_FILENAME);

            final JsonNode virtualNetworkFragment = mapper.readTree(fragmentStream);
            // Add the virtual network fragment
            ArrayNode.class.cast(template.get("resources")).add(virtualNetworkFragment);

            // Because we created/updated this in the template, we need to add the appropriate
            // dependsOn node to the networkInterface
            // Microsoft.Network/virtualNetworks/<vnet name>
            // Find the network interfaces node
            ArrayNode resourcesNodes = ArrayNode.class.cast(template.get("resources"));
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
        } catch (Exception e) {
            throw e;
        } finally {
            if (fragmentStream != null) {
                fragmentStream.close();
            }
        }
    }

    /**
     * Uploads the custom script for a template to blob storage
     *
     * @param template Template containing script to upload
     * @param targetScriptName Script to upload
     * @param tokenCache TokenCache
     * @return URI of script
     * @throws java.lang.Exception
     */
    public static String uploadCustomScript(final AzureVMAgentTemplate template, final String targetScriptName, TokenCache tokenCache) throws Exception {
        String targetStorageAccount = template.getStorageAccountName();
        String resourceGroupName = template.getResourceGroupName();
        String location = template.getLocation();
        final Azure azureClient = tokenCache.getAzureClient();

        //make sure the resource group and storage account exist
        createAzureResourceGroup(azureClient, location, resourceGroupName);
        try {
            createStorageAccount(azureClient, targetStorageAccount, location, resourceGroupName);
        } catch (Exception e) {
            LOGGER.log(Level.INFO, e.getMessage());
        }
        CloudBlobContainer container = getCloudBlobContainer(azureClient, resourceGroupName, targetStorageAccount, Constants.CONFIG_CONTAINER_NAME);
        container.createIfNotExists();
        CloudBlockBlob blob = container.getBlockBlobReference(targetScriptName);
        String scriptText = template.getInitScript();
        blob.uploadText(scriptText, "UTF-8", AccessCondition.generateEmptyCondition(), null, null);
        return blob.getUri().toString();
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

        final Azure azureClient = TokenCache.getInstance(template.getAzureCloud().getServicePrincipal()).getAzureClient();
        VirtualMachine vm = azureClient.virtualMachines().getByGroup(template.getResourceGroupName(), azureAgent.getNodeName());

        // Getting the first virtual IP
        final PublicIpAddress publicIP = vm.getPrimaryPublicIpAddress();
        String publicIPStr = "";
        String privateIP = vm.getPrimaryNetworkInterface().primaryPrivateIp();
        String fqdn = "";
        if (publicIP == null) {
            fqdn = privateIP;
            LOGGER.log(Level.INFO, "The Azure agent doesn't have a public IP. Will use the private IP");
        } else {
            fqdn = publicIP.fqdn();
            publicIPStr = publicIP.ipAddress();
        }
        azureAgent.setPublicDNSName(fqdn);
        azureAgent.setSshPort(Constants.DEFAULT_SSH_PORT);
        azureAgent.setPublicIP(publicIPStr);
        azureAgent.setPrivateIP(privateIP);

        LOGGER.log(Level.INFO, "Azure agent details:\nnodeName{0}\nadminUserName={1}\nshutdownOnIdle={2}\nretentionTimeInMin={3}\nlabels={4}",
                new Object[]{azureAgent.getNodeName(), azureAgent.getVMCredentialsId(), azureAgent.isShutdownOnIdle(),
                    azureAgent.getRetentionTimeInMin(), azureAgent.getLabelString()});
    }

    public static void attachPublicIP(final AzureVMAgent azureAgent, final AzureVMAgentTemplate template) throws Exception {
        final Azure azureClient = TokenCache.getInstance(template.getAzureCloud().getServicePrincipal()).getAzureClient();
        final VirtualMachine vm = azureClient.virtualMachines().getByGroup(template.getResourceGroupName(), azureAgent.getNodeName());
        LOGGER.log(Level.INFO, "Trying to attach a public IP to the agent {0}", azureAgent.getNodeName());
        if (vm != null) {
            //check if the VM already has a public IP
            if (vm.getPrimaryPublicIpAddress() == null) {
                vm.getPrimaryNetworkInterface()
                        .update()
                        .withNewPrimaryPublicIpAddress(
                                azureClient.publicIpAddresses()
                                        .define(azureAgent.getNodeName() + "IPName")
                                        .withRegion(template.getLocation())
                                        .withExistingResourceGroup(template.getResourceGroupName())
                                        .withLeafDomainLabel(azureAgent.getNodeName())
                        ).apply();

                setVirtualMachineDetails(azureAgent, template);
            } else {
                LOGGER.log(Level.INFO, "Agent {0} already has a public IP", azureAgent.getNodeName());
            }
        } else {
            LOGGER.log(Level.WARNING, "Could not find agent {0} in Azure", azureAgent.getNodeName());
        }
    }

    /**
     * Determines whether a virtual machine exists.
     *
     * @param configuration Configuration for the subscription
     * @param vmName Name of the VM.
     * @param resourceGroupName Resource group of the VM.
     * @return
     */
    private static boolean virtualMachineExists(final ServicePrincipal servicePrincipal, final String vmName, final String resourceGroupName) {
        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: virtualMachineExists: check for {0}", vmName);

        final Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();
        VirtualMachine vm = azureClient.virtualMachines().getByGroup(resourceGroupName, vmName);
        if(vm != null) {
            LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: virtualMachineExists: {0} exists", vmName);
            return true;
        } else {
            LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: virtualMachineExists: {0} doesn't exist", vmName);
            return false;
        }
    }

    /**
     * Determines whether a given agent exists.
     *
     * @param agent to check
     * @return True if the agent exists, false otherwise
     */
    public static boolean virtualMachineExists(final AzureVMAgent agent) {
        try {
            return virtualMachineExists(agent.getServicePrincipal(), agent.getNodeName(), agent.getResourceGroupName());
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
            final OperatingSystemTypes osType) throws AzureCloudException {
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

    /**
     * Gets a map of available locations mapping display name -> name (usable in
     * template)
     *
     * @return
     */
    private static Set<String> getAvailableLocationsStandard() {
        final Set<String> locations = new HashSet<>();
        locations.add("East US");
        locations.add("West US");
        locations.add("South Central US");
        locations.add("Central US");
        locations.add("North Central US");
        locations.add("North Europe");
        locations.add("West Europe");
        locations.add("Southeast Asia");
        locations.add("East Asia");
        locations.add("Japan West");
        locations.add("Japan East");
        locations.add("Brazil South");
        locations.add("Australia Southeast");
        locations.add("Australia East");
        locations.add("Central India");
        locations.add("South India");
        locations.add("West India");
        return locations;
    }

    private static Set<String> getAvailableLocationsChina() {
        final Set<String> locations = new HashSet<>();
        locations.add("China North");
        locations.add("China East");
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
        sizes.put("East US", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("West US", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"}));
        sizes.put("South Central US", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("Central US", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("North Central US", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1_v2", "Standard_DS11_v2", "Standard_DS12_v2", "Standard_DS13_v2", "Standard_DS14_v2", "Standard_DS2_v2", "Standard_DS3_v2", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("East US 2", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"}));
        sizes.put("North Europe", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("West Europe", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"}));
        sizes.put("Southeast Asia", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"}));
        sizes.put("East Asia", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS11", "Standard_DS12", "Standard_DS13", "Standard_DS14", "Standard_DS2", "Standard_DS3", "Standard_DS4", "Standard_F1", "Standard_F16", "Standard_F2", "Standard_F4", "Standard_F8"}));
        sizes.put("Japan West", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("Japan East", Arrays.asList(new String[]{"A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("Brazil South", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1_v2", "Standard_DS11_v2", "Standard_DS12_v2", "Standard_DS13_v2", "Standard_DS14_v2", "Standard_DS2_v2", "Standard_DS3_v2", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("Australia Southeast", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("Australia East", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"}));
        sizes.put("Central India", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1_v2", "Standard_D11_v2", "Standard_D12_v2", "Standard_D13_v2", "Standard_D14_v2", "Standard_D2_v2", "Standard_D3_v2", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1_v2", "Standard_DS11_v2", "Standard_DS12_v2", "Standard_DS13_v2", "Standard_DS14_v2", "Standard_DS2_v2", "Standard_DS3_v2", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("South India", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1_v2", "Standard_D11_v2", "Standard_D12_v2", "Standard_D13_v2", "Standard_D14_v2", "Standard_D2_v2", "Standard_D3_v2", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1_v2", "Standard_DS11_v2", "Standard_DS12_v2", "Standard_DS13_v2", "Standard_DS14_v2", "Standard_DS2_v2", "Standard_DS3_v2", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"}));
        sizes.put("West India", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1_v2", "Standard_D11_v2", "Standard_D12_v2", "Standard_D13_v2", "Standard_D14_v2", "Standard_D2_v2", "Standard_D3_v2", "Standard_D4_v2", "Standard_D5_v2", "Standard_F1", "Standard_F16", "Standard_F2", "Standard_F4", "Standard_F8"}));

        // China sizes, may not be exact
        sizes.put("China North", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"}));
        sizes.put("China East", Arrays.asList(new String[]{"A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS11", "Standard_DS12", "Standard_DS13", "Standard_DS14", "Standard_DS2", "Standard_DS3", "Standard_DS4", "Standard_F1", "Standard_F16", "Standard_F2", "Standard_F4", "Standard_F8"}));

        return sizes;
    }

    /**
     * Gets map of Azure datacenter locations which supports Persistent VM role.
     * If it can't fetch the data then it will return a default hardcoded set
     * certificate based auth appears to be required.
     * @param servicePrincipal Service Principal
     * @return Set of available regions
     */
    public static Set<String> getVirtualMachineLocations(AzureCredentials.ServicePrincipal servicePrincipal) {
        try {
            final Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();
            Set<String> regions = new HashSet<>();
            PagedList<Provider> providers = azureClient.providers().list();
            for (Provider provider : providers) {
                List<ProviderResourceType> resourceTypes = provider.resourceTypes();
                for (ProviderResourceType resourceType : resourceTypes) {
                    if (!resourceType.resourceType().equalsIgnoreCase("virtualMachines")) {
                        continue;
                    }

                    for (String location: resourceType.locations()) {
                        if (!regions.contains(location)) {
                            try {
                                if (!azureClient.virtualMachines().sizes().listByRegion(location).isEmpty()) {
                                    regions.add(location);
                                }
                            } catch (Exception e){
                                //some of the provider regions might not be valid for other API calls. The SDK call will throw an exception instead of returning an emtpy list
                            }
                        }
                    }

                }
            }
            return regions;
        } catch (Exception e) {
            LOGGER.log(Level.INFO,
                    "AzureVMManagementServiceDelegate: getVirtualMachineLocations: error while fetching the regions {0}. Will return default list ", e);
            if (servicePrincipal != null && servicePrincipal.getServiceManagementURL() != null && servicePrincipal.getServiceManagementURL().toLowerCase().contains("china")) {
                return AVAILABLE_LOCATIONS_CHINA;
            }
            return AVAILABLE_LOCATIONS_STD;
        }
    }

    /**
     * Gets list of virtual machine sizes. If it can't fetch the data then it will return a default hardcoded list
     *
     * @param servicePrincipal Service Principal
     * @param location Location to obtain VM sizes for
     * @return List of VM sizes
     */
    public static List<String> getVMSizes(AzureCredentials.ServicePrincipal servicePrincipal, final String location) {
        if (location == null || location.isEmpty()) {
            //if the location is not available we'll just return a default list with some of the most common VM sizes
            return DEFAULT_VM_SIZES;
        }
        try {
            List<String> ret = new ArrayList<>();
            final Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();
            PagedList<VirtualMachineSize> vmSizes = azureClient.virtualMachines().sizes().listByRegion(location);
            for (VirtualMachineSize vmSize : vmSizes) {
                ret.add(vmSize.name());
            }
            return ret;
        } catch (Exception e) {
            LOGGER.log(Level.INFO,
                    "AzureVMManagementServiceDelegate: getVMSizes: error while fetching the VM sizes {0}. Will return default list ", e);
            return AVAILABLE_ROLE_SIZES.get(location);
        }
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
            final ServicePrincipal servicePrincipal,
            final String resourceGroupName, final String maxVMLimit, final String timeOut) {
        try {
            if(!AzureUtil.isValidTimeOut(timeOut))
                return "Invalid Timeout, Should be a positive number, minimum value "+Constants.DEFAULT_DEPLOYMENT_TIMEOUT_SEC;

            if(!AzureUtil.isValidResourceGroupName(resourceGroupName))
                return "Error: "+Messages.Azure_GC_Template_ResourceGroupName_Err();

            if(!AzureUtil.isValidMAxVMLimit(maxVMLimit))
                return "Invalid Limit, Should be a positive number, e.g. "+Constants.DEFAULT_MAX_VM_LIMIT;

            if (AzureUtil.isValidTimeOut(timeOut) && AzureUtil.isValidMAxVMLimit(maxVMLimit)
                    && AzureUtil.isValidResourceGroupName(resourceGroupName)) {

                String result = verifyConfiguration(servicePrincipal, resourceGroupName);
                if (!result.matches(Constants.OP_SUCCESS))
                    return Messages.Azure_GC_Template_Val_Profile_Err();
            }
        }catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error validating profile", e);
            return Messages.Azure_GC_Template_Val_Profile_Err();
        }
        return Constants.OP_SUCCESS;
    }

    public static String verifyConfiguration(final ServicePrincipal servicePrincipal, final String resourceGroupName) {

        Callable<String> task = new Callable<String>() {

            @Override
            public String call() throws Exception {
                final Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();
                azureClient.storageAccounts().getByGroup(resourceGroupName, "CI_SYSTEM");
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

    private static class VMStatus extends PowerState {
        public static final VMStatus PROVISIONING_OR_DEPROVISIONING = new VMStatus(Constants.PROVISIONING_OR_DEPROVISIONING_VM_STATUS);
        public VMStatus(PowerState p) {
            super(p.toString());
        }
        public VMStatus(String value) {
            super(value);
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
    private static VMStatus getVirtualMachineStatus(final ServicePrincipal servicePrincipal, final String vmName, final String resourceGroupName)
            throws Exception {
        final Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();
        final VirtualMachine vm = azureClient.virtualMachines().getByGroup(resourceGroupName, vmName);
        final String provisioningState = vm.provisioningState();
        if (!provisioningState.equalsIgnoreCase("succeeded")) {
            return new VMStatus(VMStatus.PROVISIONING_OR_DEPROVISIONING);
        } else {
            return new VMStatus(vm.powerState());
        }
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
        VMStatus status = getVirtualMachineStatus(agent.getServicePrincipal(), agent.getNodeName(), agent.getResourceGroupName());
        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: isVMAliveOrHealthy: status {0}", status.toString());
        return !(VMStatus.PROVISIONING_OR_DEPROVISIONING.equals(status)
                || VMStatus.DEALLOCATING .equals(status)
                || VMStatus.STOPPED.equals(status)
                || VMStatus.DEALLOCATED .equals(status));
    }

    /**
     * Retrieves count of virtual machine in a azure subscription. This count is
     * based off of the VMs that the current credential set has access to. It
     * also does not deal with the classic, model. So keep this in mind.
     *
     * @param servicePrincipal
     * @param resourceGroupName
     * @return Total VM count
     */
    public static int getVirtualMachineCount(final ServicePrincipal servicePrincipal, final String resourceGroupName) {
        try {
            final Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();
            final PagedList<VirtualMachine> vms = azureClient.virtualMachines().listByGroup(resourceGroupName);
            int count = 0;
            final AzureUtil.DeploymentTag deployTag = new AzureUtil.DeploymentTag();
            for (VirtualMachine vm : vms) {
                final Map<String,String> tags = vm.tags();
                if ( tags.containsKey(Constants.AZURE_RESOURCES_TAG_NAME) &&
                     deployTag.isFromSameInstance(new AzureUtil.DeploymentTag(tags.get(Constants.AZURE_RESOURCES_TAG_NAME)))) {
                    count++;
                }
            }
            return count;
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
            TokenCache.getInstance(agent.getServicePrincipal()).getAzureClient().virtualMachines()
                    .getByGroup(agent.getResourceGroupName(), agent.getNodeName())
                    .powerOff();
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: provision: could not terminate or shutdown {0}, {1}",
                    new Object[]{agent.getNodeName(), e});
        }
    }

    /**
     * Deletes Azure virtual machine.
     *
     * @param agent
     * @throws Exception
     */
    public static void terminateVirtualMachine(final AzureVMAgent agent) throws Exception {
        terminateVirtualMachine(agent.getServicePrincipal(), agent.getNodeName(), agent.getResourceGroupName(), new ExecutionEngine());
    }

    /**
     * Terminates a virtual machine
     *
     * @param servicePrincipal Azure ServicePrincipal
     * @param vmName VM name
     * @param resourceGroupName Resource group containing the VM
     * @throws Exception
     */
    public static void terminateVirtualMachine(
            final ServicePrincipal servicePrincipal,
            final String vmName,
            final String resourceGroupName) throws Exception {
        terminateVirtualMachine(servicePrincipal, vmName, resourceGroupName, new ExecutionEngine());
    }

    /**
     * Terminates a virtual machine
     *
     * @param servicePrincipal Azure ServicePrincipal
     * @param vmName VM name
     * @param resourceGroupName Resource group containing the VM
     * @param executionEngine
     * @throws Exception
     */
    public static void terminateVirtualMachine(
            final ServicePrincipal servicePrincipal,
            final String vmName,
            final String resourceGroupName,
            ExecutionEngine executionEngine) throws Exception {
        try {
            try {
                if (virtualMachineExists(servicePrincipal, vmName, resourceGroupName)) {
                    final Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();
                    List<URI> diskUrisToRemove = new ArrayList<>();
                    // Mark OS disk for removal
                    diskUrisToRemove.add(new URI(azureClient.virtualMachines().getByGroup(resourceGroupName, vmName).osUnmanagedDiskVhdUri()));
                    // TODO: Remove data disks or add option to do so?

                    // Remove the VM
                    LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: terminateVirtualMachine: Removing virtual machine {0}", vmName);
                    azureClient.virtualMachines().deleteByGroup(resourceGroupName, vmName);

                    // Now remove the disks
                    for (URI diskUri : diskUrisToRemove) {
                        AzureVMManagementServiceDelegate.removeStorageBlob(azureClient, diskUri, resourceGroupName);
                    }
                }
            } catch (Exception e) {
                 LOGGER.log(Level.INFO,
                        "AzureVMManagementServiceDelegate: terminateVirtualMachine: while deleting VM", e);
                 // Check if VM is already deleted: if VM is already deleted then just ignore exception.
                 if (!Constants.ERROR_CODE_RESOURCE_NF.equalsIgnoreCase(e.getMessage())) {
                    throw e;
            }
            } finally {
                LOGGER.log(Level.INFO, "Clean operation starting for {0} NIC and IP", vmName);
                executionEngine.executeAsync(new Callable<Void>() {

                    @Override
                    public Void call() throws Exception {
                        removeIPName(servicePrincipal, resourceGroupName, vmName);
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

    public static void removeStorageBlob(final Azure azureClient, final URI blobURI, final String resourceGroupName) throws Exception {
         // Obtain container, storage account, and blob name
        String storageAccountName = blobURI.getHost().split("\\.")[0];
        String containerName = PathUtility.getContainerNameFromUri(blobURI, false);
        String blobName = PathUtility.getBlobNameFromURI(blobURI, false);

        LOGGER.log(Level.INFO, "removeStorageBlob: Removing disk blob {0}, in container {1} of storage account {2}",
                new Object[]{blobName, containerName, storageAccountName});
        CloudBlobContainer container = getCloudBlobContainer(azureClient, resourceGroupName, storageAccountName, containerName);
        container.getBlockBlobReference(blobName).deleteIfExists();
        if (containerName.startsWith("jnk")) {
            Iterable<ListBlobItem> blobs = container.listBlobs();
            if (blobs.iterator().hasNext()) { // the container is not empty
                return;
            }
            // the container is empty and we should delete it
            LOGGER.log(Level.INFO, "removeStorageBlob: Removing empty container ", containerName);
            container.delete();
        }
    }

    /**
     * Remove the IP name
     *
     * @param servicePrincipal
     * @param resourceGroupName
     * @param vmName
     * @throws java.io.IOException
     *
     */
    public static void removeIPName(final ServicePrincipal servicePrincipal,
            final String resourceGroupName, final String vmName) throws CloudException, IOException {
        final Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();

        final String nic = vmName + "NIC";
        try {
            LOGGER.log(Level.INFO, "Remove NIC {0}", nic);
            azureClient.networkInterfaces().deleteByGroup(resourceGroupName, nic);
        } catch (Exception ignore) {
            LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: removeIPName: while deleting NIC", ignore);
        }

        final String ip = vmName + "IPName";
        try {
            LOGGER.log(Level.INFO, "Remove IP {0}", ip);
            azureClient.publicIpAddresses().deleteByGroup(resourceGroupName, ip);
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
        TokenCache.getInstance(agent.getServicePrincipal()).getAzureClient()
                .virtualMachines()
                .getByGroup(agent.getResourceGroupName(),agent.getNodeName())
                .restart();
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

        final Azure azureClient = TokenCache.getInstance(agent.getServicePrincipal()).getAzureClient();

        while (!successful) {
            try {
                azureClient.virtualMachines().getByGroup(agent.getResourceGroupName(), agent.getNodeName()).start();
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
     * @param servicePrincipal
     * @param virtualNetworkName
     * @param resourceGroupName
     * @return
     */
    public static Network getVirtualNetwork(
            final ServicePrincipal servicePrincipal, final String virtualNetworkName, final String resourceGroupName) {
        try {
            final Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();
            return azureClient.networks().getByGroup(resourceGroupName, virtualNetworkName);
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
        try {
            return Region.findByLabelOrName(location).name();
        } catch (Exception e) {
            return null;
        }
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
     * @param referenceType
     * @param isCustomReferenceUsed
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
     * @param usePrivateIP
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
            final AzureVMAgentTemplate.ImageReferenceType referenceType,
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
            final boolean returnOnSingleError,
            final boolean usePrivateIP,
            final String nsgName) {

        List<String> errors = new ArrayList<String>();

        // Load configuration
        try {
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

            validationResult = verifyImageParameters(referenceType, image, osType, imagePublisher, imageOffer, imageSku, imageVersion);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            validationResult = verifyLocation(location, servicePrincipal.getServiceManagementURL());
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            verifyTemplateAsync(
                    servicePrincipal,
                    location,
                    referenceType,
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
                    errors,
                    usePrivateIP,
                    nsgName
            );

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error validating template", e);
            errors.add("Error occured while validating Azure Profile");
        }

        return errors;
    }

    private static void verifyTemplateAsync(
            final ServicePrincipal servicePrincipal,
            final String location,
            final AzureVMAgentTemplate.ImageReferenceType referenceType,
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
            final List<String> errors,
            final boolean usePrivateIP,
            final String nsgName) {

        List<Callable<String>> verificationTaskList = new ArrayList<Callable<String>>();

        // Callable for virtual network.
        Callable<String> callVerifyVirtualNetwork = new Callable<String>() {

            @Override
            public String call() throws Exception {
                return verifyVirtualNetwork(servicePrincipal, virtualNetworkName, virtualNetworkResourceGroupName, subnetName, usePrivateIP, resourceGroupName);
            }
        };
        verificationTaskList.add(callVerifyVirtualNetwork);

        // Callable for VM image.
        Callable<String> callVerifyVirtualMachineImage = new Callable<String>() {

            @Override
            public String call() throws Exception {
                return verifyVirtualMachineImage(servicePrincipal,
                        location, storageAccountName, referenceType, image, imagePublisher, imageOffer, imageSku, imageVersion);
            }
        };
        verificationTaskList.add(callVerifyVirtualMachineImage);

        // Callable for storage account virtual network.
        Callable<String> callVerifyStorageAccountName = new Callable<String>() {

            @Override
            public String call() throws Exception {
                return verifyStorageAccountName(servicePrincipal, resourceGroupName, storageAccountName);
            }
        };
        verificationTaskList.add(callVerifyStorageAccountName);

        // Callable for NSG.
        Callable<String> callVerifyNSG = new Callable<String>() {

            @Override
            public String call() throws Exception {
                return verifyNSG(servicePrincipal, resourceGroupName, nsgName);
            }
        };
        verificationTaskList.add(callVerifyNSG);

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

    public static String verifyVirtualNetwork(
            final ServicePrincipal servicePrincipal,
            final String virtualNetworkName,
            final String virtualNetworkResourceGroupName,
            final String subnetName,
            final boolean usePrivateIP,
            final String resourceGroupName) {
        if (StringUtils.isNotBlank(virtualNetworkName)) {
            String finalResourceGroupName =  resourceGroupName;
            if (StringUtils.isNotBlank(virtualNetworkResourceGroupName)) {
                finalResourceGroupName = virtualNetworkResourceGroupName;
            }
            Network virtualNetwork = getVirtualNetwork(servicePrincipal, virtualNetworkName, finalResourceGroupName);
            if (virtualNetwork == null) {
                return Messages.Azure_GC_Template_VirtualNetwork_NotFound(virtualNetworkName, finalResourceGroupName);
            }

            if (StringUtils.isBlank(subnetName)) {
                return Messages.Azure_GC_Template_subnet_Empty();
            } else {
                if (virtualNetwork.subnets().get(subnetName) == null)
                    return Messages.Azure_GC_Template_subnet_NotFound(subnetName);
            }
        } else if (StringUtils.isNotBlank(subnetName) || usePrivateIP) {
            return Messages.Azure_GC_Template_VirtualNetwork_Null_Or_Empty();
        }
        return Constants.OP_SUCCESS;
    }

    public static String verifyVirtualMachineImage(
            final ServicePrincipal servicePrincipal,
            final String location,
            final String storageAccountName,
            final AzureVMAgentTemplate.ImageReferenceType referenceType,
            final String image,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion) {
        if ( (referenceType == AzureVMAgentTemplate.ImageReferenceType.UNKNOWN && StringUtils.isNotBlank(image)) ||
               referenceType == AzureVMAgentTemplate.ImageReferenceType.CUSTOM ) {
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
                    return Messages.Azure_GC_Template_ImageURI_Wrong_Storage_Account();
                }
                return Constants.OP_SUCCESS;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Invalid virtual machine image", e);
                return Messages.Azure_GC_Template_ImageURI_Not_Valid();
            }
        } else {
            try {
                List<VirtualMachinePublisher> publishers = TokenCache.getInstance(servicePrincipal).getAzureClient().virtualMachineImages().publishers().listByRegion(getLocationName(location));
                for (VirtualMachinePublisher  publisher : publishers) {
                    if (!publisher.name().equalsIgnoreCase(imagePublisher)) {
                        continue;
                    }
                    for (VirtualMachineOffer offer : publisher.offers().list()) {
                        if (!offer.name().equalsIgnoreCase(imageOffer)) {
                            continue;
                        }
                        for (VirtualMachineSku sku: offer.skus().list()) {
                            if (!sku.name().equalsIgnoreCase(imageSku)) {
                                continue;
                            }
                            PagedList<VirtualMachineImage> images = sku.images().list();
                            if ((imageVersion.equalsIgnoreCase("latest") || StringUtils.isEmpty(imageVersion) ) && images.size() > 0) { //the empty check is here to maintain backward compatibility
                                return Constants.OP_SUCCESS;
                            }
                            for (VirtualMachineImage vmImage : images) {
                                if (vmImage.version().equalsIgnoreCase(imageVersion))
                                    return Constants.OP_SUCCESS;
                            }
                            return Messages.Azure_GC_Template_ImageReference_Not_Valid("Invalid image version");
                        }
                        return Messages.Azure_GC_Template_ImageReference_Not_Valid("Invalid SKU");
                    }
                    return Messages.Azure_GC_Template_ImageReference_Not_Valid("Invalid publisher");
                }
                return Messages.Azure_GC_Template_ImageReference_Not_Valid("Invalid region");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Invalid virtual machine image", e);
                return Messages.Azure_GC_Template_ImageReference_Not_Valid(e.getMessage());
            }
        }
    }

    public static String verifyStorageAccountName(
            final ServicePrincipal servicePrincipal,
            final String resourceGroupName,
            final String storageAccountName) {
        boolean isAvailable = false;
        try {
            Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();
            //if it's not available we need to check if it's already in our resource group
            isAvailable = azureClient.storageAccounts().checkNameAvailability(storageAccountName).isAvailable();
            if ( !isAvailable && null == azureClient.storageAccounts().getByGroup(resourceGroupName, storageAccountName) ) {
                    return Messages.Azure_GC_Template_SA_Already_Exists();
            } else {
                return Constants.OP_SUCCESS;
            }
        } catch (Exception e) {
            LOGGER.log(Level.INFO, e.getMessage());
            if (!isAvailable)
                return Messages.Azure_GC_Template_SA_Already_Exists();
            else
                return Messages.Azure_GC_Template_SA_Cant_Validate();
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
            final AzureVMAgentTemplate.ImageReferenceType referenceType,
            final String image,
            final String osType,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion) {
        if ( (referenceType == AzureVMAgentTemplate.ImageReferenceType.UNKNOWN && (StringUtils.isNotBlank(image) && StringUtils.isNotBlank(osType)) ) ||
                referenceType == AzureVMAgentTemplate.ImageReferenceType.CUSTOM) {
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

    public static String verifyNSG(
            final ServicePrincipal servicePrincipal,
            final String resourceGroupName,
            final String nsgName) {
        if (StringUtils.isNotBlank(nsgName)) {
            final Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();
            NetworkSecurityGroup nsg = azureClient.networkSecurityGroups().getByGroup(resourceGroupName, nsgName);
            if (nsg == null) {
                return Messages.Azure_GC_Template_NSG_NotFound(nsgName);
            }
        }
        return Constants.OP_SUCCESS;
    }

    /**
     * Create Azure resource Group
     *
     * @param azureClient
     * @param locationName
     * @param resourceGroupName
     * @return
     */
    private static void createAzureResourceGroup(Azure azureClient, String locationName, String resourceGroupName) throws AzureCloudException {
        try{
            azureClient.resourceGroups()
            .define(resourceGroupName)
            .withRegion(locationName)
            .create();
        } catch(Exception e) {
            LOGGER.log(Level.SEVERE, e.getMessage());
            throw new AzureCloudException(String.format(" Failed to create resource group with group name %s, location %s",
            resourceGroupName, locationName), e);
        }
    }

    /**
     * Create storage Account
     *
     * @param azureClient
     * @param targetStorageAccount
     * @param location
     * @param resourceGroupName
     * @return
     */
    private static void createStorageAccount(Azure azureClient, String targetStorageAccount, String location, String resourceGroupName) throws AzureCloudException{
        try {
            azureClient.storageAccounts().define(targetStorageAccount)
                    .withRegion(location)
                    .withExistingResourceGroup(resourceGroupName)
                    .withSku(SkuName.STANDARD_LRS)
                    .create();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e.getMessage());
            throw new AzureCloudException(String.format("Failed to create storage account with account name %s, location %s, resourceGroupName %s",
                    targetStorageAccount, location, resourceGroupName), e);
        }
    }

    /**
     * Get StorageAccount by resourceGroup name and storageAccount name
     *
     * @param azureClient
     * @param storageAccountName
     * @param resourceGroupName
     *
     * @return StorageAccount object
     */
    private static StorageAccount getStorageAccount(Azure azureClient, String targetStorageAccount, String resourceGroupName){
        return azureClient.storageAccounts().getByGroup(resourceGroupName, targetStorageAccount);
    }

    /**
     * Get the blob endpoint suffix for , it's like ".blob.core.windows.net/" for public azure
     * or ".blob.core.chinacloudapi.cn" for Azure China
     *
     * @param storageAccount
     * @return endpointSuffix
     */
    public static String getBlobEndpointSuffixForTemplate(StorageAccount storageAccount){
        return getBlobEndPointSuffix(storageAccount, Constants.BLOB, Constants.BLOB_ENDPOINT_PREFIX, Constants.FWD_SLASH);
    }

    /**
     * Get the blob endpoint suffix for constructing CloudStorageAccount  , it's like "core.windows.net"
     * or "core.chinacloudapi.cn" for AzureChina
     *
     * @param storageAccount
     * @return endpointSuffix
     */
    public static String getBlobEndpointSuffixForCloudStorageAccount(StorageAccount storageAccount){
        return getBlobEndPointSuffix(storageAccount, Constants.BLOB_ENDPOINT_SUFFIX_STARTKEY, "", "");
    }

    /**
     * Get the blob endpoint substring with prefix and suffix
     *
     * @param storageAccount
     * @param startKey uses to get the start position of sub string, if it's null or empty then whole input string will be used
     * @param prefix the prefix of substring will be added, if it's null or empty then it will not be added'
     * @param suffix the suffix will be append to substring if substring doesn't contain it,if it's null or empty then it will not be added
     * @return endpointSuffix
     */
    private static String getBlobEndPointSuffix(StorageAccount storageAccount, String startKey, String prefix, String suffix)
    {
        String endpointSuffix = null;
        if(storageAccount != null){
            String blobUri = storageAccount.endPoints().primary().blob().toLowerCase();
            endpointSuffix = getSubString(blobUri, startKey, prefix, suffix);
        }

        return endpointSuffix;
    }


    /**
     * Get substring with startKey,  endSuffix and prefix
     *
     * @param startKey startKey used to get the start position of string, if it's null or empty then whole input string will be used
     * @param prefix the prefix of substring will be added, if it's null or empty then it will not be added'
     * @param suffix the suffix will be append to substring if substring doesn't contain it,if it's null or empty then it will not be added
     * @return
     */
    private static String getSubString(String uri, String startKey, String prefix, String suffix){
        String subString = null;
        if(StringUtils.isNotBlank(uri)){
            if(StringUtils.isNotEmpty(startKey) && uri.indexOf(startKey) >= 0){
                subString = uri.substring(uri.indexOf(startKey));
            } else {
                subString = uri;
            }
            subString = StringUtils.isNotEmpty(prefix) ? prefix + subString : subString;
            if(StringUtils.isNotEmpty(suffix) && subString.lastIndexOf(suffix) < subString.length() - suffix.length()){
                subString = subString + suffix;
            }
        }
        return subString;
    }

    /**
     * Get CloudStorageAccount
     *
     * @param storageAccount
     * @return CloudStorageAccount object
     */
    public static CloudStorageAccount getCloudStorageAccount(StorageAccount storageAccount) throws AzureCloudException{
        List<StorageAccountKey> storageKeys =storageAccount.getKeys();
        if(storageKeys.isEmpty()) {
            throw new AzureCloudException("AzureVMManagementServiceDelegate: uploadCustomScript: "
                    + "Exception occured while fetching the storage account key");
        }

        String storageAccountKey = storageKeys.get(0).value();
        String blobSuffix = getBlobEndpointSuffixForCloudStorageAccount(storageAccount);
        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: getCloudStorageAccount: the suffix for contruct CloudStorageCloud is {0}",blobSuffix);
        if(StringUtils.isEmpty(blobSuffix)){
            throw new AzureCloudException("AzureVMManagementServiceDelegate: getCloudStorageAccount:"
                    + "Exception occured while getting blobSuffix, it's empty'");
        }
        try {
            return new CloudStorageAccount(new StorageCredentialsAccountAndKey(storageAccount.name(), storageAccountKey), false, blobSuffix);
        } catch (Exception e){
            LOGGER.log(Level.SEVERE, "AzureVMManagementServiceDelegate: GetCloudStorageAccount: unable to get CloudStorageAccount with storage account {0} and blob Suffix {1}",
                    new Object[]{storageAccount.name(), blobSuffix});
            throw new AzureCloudException(e);
        }
    }

    /**
     * Get CloudBlobContainer
     *
     * @param account
     * @param containerName
     * @return CloudBlobContainer
     */
    public static CloudBlobContainer getCloudBlobContainer(CloudStorageAccount account, String containerName) throws AzureCloudException{
        try {
            return account.createCloudBlobClient().getContainerReference(containerName);
        } catch (Exception e){
            LOGGER.log(Level.SEVERE, "AzureVMManagementServiceDelegate: getCloudBlobContainer: unable to get CloudStorageAccount with container name {1}",
                    new Object[]{containerName});
            throw new AzureCloudException(e);
        }
    }

    public static CloudBlobContainer getCloudBlobContainer(Azure azureClient, String resourceGroupName, String targetStorageAccount, String blobContanerName) throws AzureCloudException{
        StorageAccount storageAccount = azureClient.storageAccounts()
            .getByGroup(resourceGroupName, targetStorageAccount);
        CloudStorageAccount account = getCloudStorageAccount(storageAccount);
        return getCloudBlobContainer(account, blobContanerName);
    }
}
