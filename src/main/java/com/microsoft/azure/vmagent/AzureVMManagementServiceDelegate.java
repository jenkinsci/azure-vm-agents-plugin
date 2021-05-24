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

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.management.exception.ManagementException;
import com.azure.core.util.BinaryData;
import com.azure.core.util.ExpandableStringEnum;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.GalleryImageVersion;
import com.azure.resourcemanager.compute.models.OperatingSystemTypes;
import com.azure.resourcemanager.compute.models.PowerState;
import com.azure.resourcemanager.compute.models.PurchasePlan;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.compute.models.VirtualMachineCustomImage;
import com.azure.resourcemanager.compute.models.VirtualMachineImage;
import com.azure.resourcemanager.compute.models.VirtualMachineOffer;
import com.azure.resourcemanager.compute.models.VirtualMachinePublisher;
import com.azure.resourcemanager.compute.models.VirtualMachineSize;
import com.azure.resourcemanager.compute.models.VirtualMachineSku;
import com.azure.resourcemanager.network.models.Network;
import com.azure.resourcemanager.network.models.NetworkSecurityGroup;
import com.azure.resourcemanager.network.models.PublicIpAddress;
import com.azure.resourcemanager.resources.models.DeploymentMode;
import com.azure.resourcemanager.resources.models.GenericResource;
import com.azure.resourcemanager.storage.models.CheckNameAvailabilityResult;
import com.azure.resourcemanager.storage.models.Reason;
import com.azure.resourcemanager.storage.models.SkuName;
import com.azure.resourcemanager.storage.models.StorageAccount;
import com.azure.resourcemanager.storage.models.StorageAccountKey;
import com.azure.resourcemanager.storage.models.StorageAccountSkuType;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.BlobUrlParts;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.retry.NoRetryStrategy;
import com.microsoft.azure.vmagent.util.*;
import hudson.model.Descriptor.FormException;
import io.jenkins.plugins.azuresdk.HttpClientRetriever;
import jenkins.model.Jenkins;
import jenkins.slaves.JnlpAgentReceiver;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Business delegate class which handles calls to Azure management service SDK.
 *
 * @author Suresh Nallamilli (snallami@gmail.com)
 */
public final class AzureVMManagementServiceDelegate {

    private static final Logger LOGGER = Logger.getLogger(AzureVMManagementServiceDelegate.class.getName());

    private static final String EMBEDDED_TEMPLATE_FILENAME = "/referenceImageTemplate.json";

    private static final String EMBEDDED_TEMPLATE_WITH_SCRIPT_FILENAME = "/referenceImageTemplateWithScript.json";

    private static final String EMBEDDED_TEMPLATE_IMAGE_FILENAME = "/customImageTemplate.json";

    private static final String EMBEDDED_TEMPLATE_IMAGE_WITH_SCRIPT_FILENAME = "/customImageTemplateWithScript.json";

    private static final String EMBEDDED_TEMPLATE_WITH_MANAGED_FILENAME = "/referenceImageTemplateWithManagedDisk.json";

    private static final String EMBEDDED_TEMPLATE_WITH_SCRIPT_MANAGED_FILENAME =
            "/referenceImageTemplateWithScriptAndManagedDisk.json";

    private static final String EMBEDDED_TEMPLATE_IMAGE_WITH_MANAGED_FILENAME =
            "/customImageTemplateWithManagedDisk.json";

    private static final String EMBEDDED_TEMPLATE_IMAGE_WITH_SCRIPT_MANAGED_FILENAME =
            "/customImageTemplateWithScriptAndManagedDisk.json";

    private static final String EMBEDDED_TEMPLATE_IMAGE_ID_WITH_MANAGED_FILENAME =
            "/referenceImageIDTemplateWithManagedDisk.json";

    private static final String EMBEDDED_TEMPLATE_IMAGE_ID_WITH_SCRIPT_MANAGED_FILENAME =
            "/referenceImageIDTemplateWithScriptAndManagedDisk.json";

    private static final String VIRTUAL_NETWORK_TEMPLATE_FRAGMENT_FILENAME = "/virtualNetworkFragment.json";

    private static final String PUBLIC_IP_FRAGMENT_FILENAME = "/publicIPFragment.json";

    private static final Map<String, List<String>> AVAILABLE_ROLE_SIZES = getAvailableRoleSizes();

    private static final Set<String> AVAILABLE_LOCATIONS_STD = getAvailableLocationsStandard();

    private static final Set<String> AVAILABLE_LOCATIONS_CHINA = getAvailableLocationsChina();

    private static final List<String> DEFAULT_VM_SIZES = Arrays.asList(
            "Standard_A0", "Standard_A1", "Standard_A2", "Standard_A3",
            "Standard_A4", "Standard_A5", "Standard_A6", "Standard_A7",
            "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
            "Standard_DS1_v2", "Standard_DS2_v2", "Standard_DS3_v2", "Standard_DS4_v2", "Standard_DS5_v2",
            "Standard_DS11_v2", "Standard_DS12_v2", "Standard_DS13_v2", "Standard_DS14_v2", "Standard_DS15_v2",
            "Standard_DS1", "Standard_DS2", "Standard_DS3", "Standard_DS4",
            "Standard_DS11", "Standard_DS12", "Standard_DS13", "Standard_DS14",
            "Standard_F1s", "Standard_F2s", "Standard_F4s", "Standard_F8s", "Standard_F16s",
            "Standard_D1", "Standard_D2", "Standard_D3", "Standard_D4",
            "Standard_D11", "Standard_D12", "Standard_D13", "Standard_D14",
            "Standard_A1_v2", "Standard_A2m_v2", "Standard_A2_v2", "Standard_A4m_v2",
            "Standard_A4_v2", "Standard_A8m_v2", "Standard_A8_v2", "Standard_D1_v2",
            "Standard_D2_v2", "Standard_D3_v2", "Standard_D4_v2", "Standard_D5_v2",
            "Standard_D11_v2", "Standard_D12_v2", "Standard_D13_v2", "Standard_D14_v2",
            "Standard_D15_v2", "Standard_F1", "Standard_F2", "Standard_F4", "Standard_F8", "Standard_F16");

    public static final Map<String, Map<String, String>> DEFAULT_IMAGE_PROPERTIES = getDefaultImageProperties();

    public static final Map<String, Map<String, String>> PRE_INSTALLED_TOOLS_SCRIPT = getPreInstalledToolsScript();

    private static final String INSTALL_JNLP_WINDOWS_FILENAME = "/scripts/windowsInstallJnlpScript.ps1";

    private static final String INSTALL_GIT_WINDOWS_FILENAME = "/scripts/windowsInstallGitScript.ps1";

    private static final String INSTALL_JAVA_WINDOWS_FILENAME = "/scripts/windowsInstallJavaScript.ps1";

    private static final String INSTALL_MAVEN_WINDOWS_FILENAME = "/scripts/windowsInstallMavenScript.ps1";

    private static final String INSTALL_GIT_UBUNTU_FILENAME = "/scripts/ubuntuInstallGitScript.sh";

    private static final String INSTALL_JAVA_UBUNTU_FILENAME = "/scripts/ubuntuInstallJavaScript.sh";

    private static final String INSTALL_MAVEN_UBUNTU_FILENAME = "/scripts/ubuntuInstallMavenScript.sh";

    private static final String INSTALL_DOCKER_UBUNTU_FILENAME = "/scripts/ubuntuInstallDockerScript.sh";

    private static final String PRE_INSTALL_SSH_FILENAME = "/scripts/sshInit.ps1";

    private final AzureResourceManager azureClient;

    private final String azureCredentialsId;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AzureVMManagementServiceDelegate getInstance(AzureVMCloud cloud) {
        return cloud.getServiceDelegate();
    }

    public static AzureVMManagementServiceDelegate getInstance(AzureResourceManager azureClient, String azureCredentialsId) {
        if (azureClient == null) {
            throw new NullPointerException("the azure client is null!");
        }
        return new AzureVMManagementServiceDelegate(azureClient, azureCredentialsId);
    }

    /**
     * Creates a new deployment of VMs based on the provided template.
     *
     * @param template       Template to deploy
     * @param numberOfAgents Number of agents to create
     * @return The base name for the VMs that were created
     */
    public AzureVMDeploymentInfo createDeployment(AzureVMAgentTemplate template, int numberOfAgents)
            throws AzureCloudException, IOException {
        return createDeployment(
                template,
                numberOfAgents,
                AzureVMAgentCleanUpTask.DeploymentRegistrar.getInstance()
        );
    }

    public AzureVMDeploymentInfo createDeployment(
            AzureVMAgentTemplate template,
            int numberOfAgents,
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar) throws AzureCloudException, IOException {

        InputStream embeddedTemplate = null;
        String scriptUri = null;
        try {
            LOGGER.log(Level.INFO,
                    "AzureVMManagementServiceDelegate: createDeployment: Initializing deployment for agentTemplate {0}",
                    template.getTemplateName());

            Map<String, Object> properties = AzureVMAgentTemplate.getTemplateProperties(template);

            final Date timestamp = new Date(System.currentTimeMillis());
            final String deploymentName = AzureUtil.getDeploymentName(template.getTemplateName(), timestamp);
            final String vmBaseName = AzureUtil.getVMBaseName(
                    template.getTemplateName(), deploymentName, (String) properties.get("osType"), numberOfAgents);
            final String locationName = AzureUtil.getLocationNameByLabel(template.getLocation());
            final String storageAccountName = template.getStorageAccountName();
            final String storageAccountType = template.getStorageAccountType();
            final String diskType = template.getDiskType();
            final boolean ephemeralOSDisk = template.isEphemeralOSDisk();
            final int osDiskSize = template.getOsDiskSize();
            final AzureVMAgentTemplate.AvailabilityTypeClass availabilityType = template.getAvailabilityType();
            final String availabilitySet = availabilityType != null ? availabilityType.getAvailabilitySet() : null;

            if (!template.getResourceGroupName().matches(Constants.DEFAULT_RESOURCE_GROUP_PATTERN)) {
                LOGGER.log(Level.SEVERE,
                        "AzureVMManagementServiceDelegate: createDeployment: "
                                + "ResourceGroup Name {0} is invalid. It should be 1-64 alphanumeric characters",
                        new Object[]{template.getResourceGroupName()});
                throw new Exception("ResourceGroup Name is invalid");
            }
            LOGGER.log(Level.INFO,
                    "AzureVMManagementServiceDelegate: createDeployment:"
                            + " Creating a new deployment {0} with VM base name {1}",
                    new Object[]{deploymentName, vmBaseName});
            final String resourceGroupName = template.getResourceGroupName();
            final String resourceGroupReferenceType = template.getResourceGroupReferenceType();

            String cloudName = template.retrieveAzureCloudReference().getCloudName();
            if (Constants.RESOURCE_GROUP_REFERENCE_TYPE_NEW.equals(resourceGroupReferenceType)) {
                createAzureResourceGroup(azureClient, locationName, resourceGroupName, cloudName);
            }

            //For blob endpoint url in arm template, it's different based on different environments
            //So create StorageAccount and get suffix
            createStorageAccount(azureClient, storageAccountType, storageAccountName, locationName, resourceGroupName, template.getTemplateName(), template.retrieveAzureCloudReference().getCloudTags());
            StorageAccount storageAccount = getStorageAccount(azureClient, storageAccountName, resourceGroupName);
            String blobEndpointSuffix = getBlobEndpointSuffixForTemplate(storageAccount);


            boolean isBasic = template.isTopLevelType(Constants.IMAGE_TOP_LEVEL_BASIC);
            ImageReferenceType referenceType = template.getImageReference().getType();

            final boolean preInstallSshInWindows = properties.get("osType").equals(Constants.OS_TYPE_WINDOWS)
                    && properties.get("agentLaunchMethod").equals(Constants.LAUNCH_METHOD_SSH)
                    && (isBasic || referenceType == ImageReferenceType.REFERENCE
                    || template.getPreInstallSsh());

            final boolean useCustomScriptExtension
                    = preInstallSshInWindows
                    || properties.get("osType").equals(Constants.OS_TYPE_WINDOWS)
                    && !StringUtils.isBlank((String) properties.get("initScript"))
                    && properties.get("agentLaunchMethod").equals(Constants.LAUNCH_METHOD_JNLP);

            // check if a custom image id has been provided otherwise work with publisher and offer
            boolean useManagedDisk = diskType.equals(Constants.DISK_MANAGED);
            String msg;
            String templateLocation;
            boolean useCustomImage = !isBasic && referenceType == ImageReferenceType.CUSTOM;
            if (useCustomScriptExtension) {
                if (useManagedDisk) {
                    msg = "AzureVMManagementServiceDelegate: createDeployment: "
                            + "Use embedded deployment template (with script and managed) {0}";
                    if (useCustomImage) {
                        templateLocation = EMBEDDED_TEMPLATE_IMAGE_WITH_SCRIPT_MANAGED_FILENAME;
                    } else {
                        templateLocation = (referenceType == ImageReferenceType.CUSTOM_IMAGE
                                || referenceType == ImageReferenceType.GALLERY)
                                ? EMBEDDED_TEMPLATE_IMAGE_ID_WITH_SCRIPT_MANAGED_FILENAME
                                : EMBEDDED_TEMPLATE_WITH_SCRIPT_MANAGED_FILENAME;
                    }
                } else {
                    msg = "AzureVMManagementServiceDelegate: createDeployment: "
                            + "Use embedded deployment template (with script) {0}";
                    templateLocation = useCustomImage
                            ? EMBEDDED_TEMPLATE_IMAGE_WITH_SCRIPT_FILENAME
                            : EMBEDDED_TEMPLATE_WITH_SCRIPT_FILENAME;
                }
            } else {
                if (useManagedDisk) {
                    msg = "AzureVMManagementServiceDelegate: createDeployment: "
                            + "Use embedded deployment template (with managed) {0}";
                    if (useCustomImage) {
                        templateLocation = EMBEDDED_TEMPLATE_IMAGE_WITH_MANAGED_FILENAME;
                    } else {
                        templateLocation = (referenceType == ImageReferenceType.CUSTOM_IMAGE
                                || referenceType == ImageReferenceType.GALLERY)
                                ? EMBEDDED_TEMPLATE_IMAGE_ID_WITH_MANAGED_FILENAME
                                : EMBEDDED_TEMPLATE_WITH_MANAGED_FILENAME;
                    }
                } else {
                    msg = "AzureVMManagementServiceDelegate: createDeployment: "
                            + "Use embedded deployment template {0}";
                    templateLocation = useCustomImage
                            ? EMBEDDED_TEMPLATE_IMAGE_FILENAME
                            : EMBEDDED_TEMPLATE_FILENAME;
                }
            }
            LOGGER.log(Level.INFO, msg, templateLocation);
            embeddedTemplate = AzureVMManagementServiceDelegate.class.getResourceAsStream(
                    templateLocation);

            final JsonNode tmp = MAPPER.readTree(embeddedTemplate);

            // Add count variable for loop....
            final ObjectNode count = MAPPER.createObjectNode();
            count.put("type", "int");
            count.put("defaultValue", numberOfAgents);
            ((ObjectNode) tmp.get("parameters")).replace("count", count);

            putVariable(tmp, "vmName", vmBaseName);
            putVariable(tmp, "location", locationName);
            putVariable(tmp, "jenkinsTag", Constants.AZURE_JENKINS_TAG_VALUE);
            putVariable(tmp, "resourceTag", deploymentRegistrar.getDeploymentTag().get());
            putVariable(tmp, "cloudTag", cloudName);
            putVariable(tmp, "osDiskStorageAccountType", template.getOsDiskStorageAccountType());

            // add purchase plan for image if needed in reference configuration
            // Image Configuration has four choices, isBasic->Built-in Image, useCustomImage->Custom User Image
            // getId()->Custom Managed Image, here we need the last one: Image Reference
            if (!isBasic) {
                if (referenceType == ImageReferenceType.REFERENCE) {
                    boolean isImageParameterValid = checkImageParameter(template);
                    if (isImageParameterValid) {
                        String imageVersion = StringUtils.isNotEmpty(template.getImageReference().getVersion())
                                ? template.getImageReference().getVersion() : "latest";
                        VirtualMachineImage image = azureClient.virtualMachineImages().getImage(
                                locationName,
                                template.getImageReference().getPublisher(),
                                template.getImageReference().getOffer(),
                                template.getImageReference().getSku(),
                                imageVersion
                        );
                        if (image != null) {
                            PurchasePlan plan = image.plan();
                            if (plan != null) {
                                ArrayNode resources = (ArrayNode) tmp.get("resources");
                                for (JsonNode resource : resources) {
                                    String type = resource.get("type").asText();
                                    if (type.contains("virtualMachine")) {
                                        ObjectNode planNode = MAPPER.createObjectNode();
                                        planNode.put("name", plan.name());
                                        planNode.put("publisher", plan.publisher());
                                        planNode.put("product", plan.product());
                                        ((ObjectNode) resource).replace("plan", planNode);
                                    }
                                }
                            }
                        } else {
                            LOGGER.log(Level.SEVERE, "Failed to find the image with publisher:{0} offer:{1} sku:{2} " +
                                    "version:{3} when trying to add purchase plan to ARM template", new Object[]{
                                    template.getImageReference().getPublisher(),
                                    template.getImageReference().getOffer(),
                                    template.getImageReference().getSku(),
                                    imageVersion});
                        }
                    }
                } else if (referenceType == ImageReferenceType.CUSTOM_IMAGE) {
                    String id = template.getId();
                    VirtualMachineCustomImage customImage = azureClient.virtualMachineCustomImages().getById(id);
                    if (customImage != null) {
                        Map<String, String> tags = customImage.tags();
                        if (tags != null) {
                            String planInfo = tags.get("PlanInfo");
                            String planProduct = tags.get("PlanProduct");
                            String planPublisher = tags.get("PlanPublisher");

                            if (StringUtils.isNotBlank(planInfo) && StringUtils.isNotBlank(planProduct)
                                    && StringUtils.isNotBlank(planPublisher)) {
                                ArrayNode resources = (ArrayNode) tmp.get("resources");
                                for (JsonNode resource : resources) {
                                    String type = resource.get("type").asText();
                                    if (type.contains("virtualMachine")) {
                                        ObjectNode planNode = MAPPER.createObjectNode();
                                        planNode.put("name", planInfo);
                                        planNode.put("publisher", planPublisher);
                                        planNode.put("product", planProduct);
                                        ((ObjectNode) resource).replace("plan", planNode);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            boolean msiEnabled = template.isEnableMSI();
            boolean uamiEnabled = template.isEnableUAMI();

            boolean osDiskSizeChanged = osDiskSize > 0;
            boolean availabilitySetEnabled = availabilitySet != null;
            if (msiEnabled || uamiEnabled || osDiskSizeChanged || availabilitySetEnabled) {
                ArrayNode resources = (ArrayNode) tmp.get("resources");
                for (JsonNode resource : resources) {
                    String type = resource.get("type").asText();
                    if (type.contains("virtualMachine")) {
                        // Determine if User assigned, System assigned or both
                        // types of identity should be requested.
                        // https://docs.microsoft.com/en-us/rest/api/compute/virtualmachines/createorupdate#resourceidentitytype
                        if (msiEnabled && uamiEnabled) {
                            String uamiID = template.getUamiID();
                            ObjectNode identityNode = MAPPER.createObjectNode();
                            identityNode.put("type", "SystemAssigned, UserAssigned");
                            ObjectNode resourceId = MAPPER.createObjectNode();
                            resourceId.replace(uamiID, MAPPER.createObjectNode());
                            identityNode.replace("userAssignedIdentities", resourceId);

                            ((ObjectNode) resource).replace("identity", identityNode);
                        } else if (msiEnabled) {
                            ObjectNode identityNode = MAPPER.createObjectNode();
                            identityNode.put("type", "systemAssigned");
                            ((ObjectNode) resource).replace("identity", identityNode);
                        } else if (uamiEnabled) {
                            String uamiID = template.getUamiID();
                            ObjectNode identityNode = MAPPER.createObjectNode();
                            identityNode.put("type", "UserAssigned");
                            ObjectNode resourceId = MAPPER.createObjectNode();
                            resourceId.replace(uamiID, MAPPER.createObjectNode());
                            identityNode.replace("userAssignedIdentities", resourceId);

                            ((ObjectNode) resource).replace("identity", identityNode);
                        }

                        if (osDiskSizeChanged) {
                            JsonNode jsonNode = resource.get("properties").get("storageProfile").get("osDisk");
                            ((ObjectNode) jsonNode).replace("diskSizeGB", new IntNode(osDiskSize));
                        }
                        if (availabilitySetEnabled) {
                            ObjectNode availabilitySetNode = MAPPER.createObjectNode();
                            availabilitySetNode.put("id", String.format(
                                    "[resourceId('Microsoft.Compute/availabilitySets', '%s')]", availabilitySet));
                            JsonNode propertiesNode = resource.get("properties");
                            ((ObjectNode) propertiesNode).replace("availabilitySet",
                                    availabilitySetNode);
                        }
                    }
                }
            }

            AzureVMCloud cloud = template.retrieveAzureCloudReference();
            ArrayNode resources = (ArrayNode) tmp.get("resources");
            for (JsonNode resource : resources) {
                injectCustomTag(resource, cloud);
            }

            copyVariableIfNotBlank(tmp, properties, "imageId");
            copyVariableIfNotBlank(tmp, properties, "imagePublisher");
            copyVariableIfNotBlank(tmp, properties, "imageOffer");
            copyVariableIfNotBlank(tmp, properties, "imageSku");
            copyVariableIfNotBlank(tmp, properties, "imageVersion");
            copyVariableIfNotBlank(tmp, properties, "osType");
            putVariable(tmp, "ephemeralOSDisk", Boolean.toString(ephemeralOSDisk));
            putVariableIfNotBlank(tmp, "image", template.getImageReference().getUri());

            // Gallery Image is a special case for custom image, reuse the logic of custom image by replacing the imageId here
            if (referenceType == ImageReferenceType.GALLERY) {
                GalleryImageVersion galleryImageVersion;
                String galleryImageVersionStr = template.getImageReference().getGalleryImageVersion();
                String galleryImageDefinition = template.getImageReference().getGalleryImageDefinition();
                String gallerySubscriptionId = template.getImageReference().getGallerySubscriptionId();
                String galleryResourceGroup = template.getImageReference().getGalleryResourceGroup();
                String galleryName = template.getImageReference().getGalleryName();
                if (StringUtils.isBlank(galleryImageVersionStr) || StringUtils.isBlank(galleryImageDefinition) ||
                        StringUtils.isBlank(galleryResourceGroup) || StringUtils.isBlank(galleryName)) {
                    throw AzureCloudException.create("AzureVMManagementServiceDelegate: createDeployment: "
                            + "one of gallery name, gallery image version, image definition and image resource group "
                            + "is blank.");
                }
                if (Constants.VERSION_LATEST.equals(galleryImageVersionStr)) {
                    galleryImageVersion = getGalleryImageLatestVersion(galleryResourceGroup,
                            galleryName, galleryImageDefinition, gallerySubscriptionId);
                } else {
                    AzureResourceManager client = AzureClientUtil.getClient(azureCredentialsId, gallerySubscriptionId);
                    galleryImageVersion = client.galleryImageVersions()
                            .getByGalleryImage(galleryResourceGroup, galleryName,
                                    galleryImageDefinition, galleryImageVersionStr);
                }
                if (galleryImageVersion == null) {
                    throw AzureCloudException.create("AzureVMManagementServiceDelegate: createDeployment: "
                            + "Can not find the right version for the gallery image.");
                }
                String galleryImageId = galleryImageVersion.id();
                LOGGER.log(Level.INFO, "Create VM with gallery image id {0}", new Object[]{galleryImageId});
                putVariableIfNotBlank(tmp, "imageId", galleryImageId);
            }

            // If using the custom script extension (vs. SSH) to startup the powershell scripts,
            // add variables for that and upload the init script to the storage account
            if (useCustomScriptExtension) {
                putVariable(tmp, "jenkinsServerURL", Jenkins.get().getRootUrl());
                // Calculate the client secrets.  The secrets are based off the machine name,
                ArrayNode clientSecretsNode = ((ObjectNode) tmp.get("variables")).putArray("clientSecrets");
                for (int i = 0; i < numberOfAgents; i++) {
                    clientSecretsNode.add(
                            JnlpAgentReceiver.SLAVE_SECRET.mac(String.format("%s%d", vmBaseName, i)));
                }
                // Upload the startup script to blob storage
                String scriptName = String.format("%s%s", deploymentName, "init.ps1");
                String initScript;
                if (preInstallSshInWindows) {
                    initScript = IOUtils.toString(
                            AzureVMManagementServiceDelegate.class.getResourceAsStream(PRE_INSTALL_SSH_FILENAME),
                            StandardCharsets.UTF_8);
                } else {
                    initScript = (String) properties.get("initScript");
                }
                scriptUri = uploadCustomScript(template, scriptName, initScript);
                putVariable(tmp, "startupScriptURI", scriptUri);
                putVariable(tmp, "startupScriptName", scriptName);

                List<StorageAccountKey> storageKeys = azureClient.storageAccounts()
                        .getByResourceGroup(template.getResourceGroupName(), storageAccountName)
                        .getKeys();
                if (storageKeys.isEmpty()) {
                    throw AzureCloudException.create("AzureVMManagementServiceDelegate: createDeployment: "
                            + "Exception occurred while fetching the storage account key");
                }
                String storageAccountKey = storageKeys.get(0).value();

                final ObjectNode storageAccountKeyNode = MAPPER.createObjectNode();
                storageAccountKeyNode.put("type", "secureString");
                storageAccountKeyNode.put("defaultValue", storageAccountKey);

                // Add the storage account key
                ((ObjectNode) tmp.get("parameters")).replace("storageAccountKey", storageAccountKeyNode);
            }

            putVariable(tmp, "vmSize", template.getVirtualMachineSize());
            // Grab the username/pass
            StandardUsernamePasswordCredentials creds = template.getVMCredentials();

            putVariable(tmp, "adminUsername", creds.getUsername());
            putVariableIfNotBlank(tmp, "storageAccountName", storageAccountName);
            putVariableIfNotBlank(tmp, "storageAccountType", storageAccountType);
            putVariableIfNotBlank(tmp, "blobEndpointSuffix", blobEndpointSuffix);

            // Network properties.  If the vnet name isn't blank then
            // then subnet name can't be either (based on verification rules)
            if (!isBasic && StringUtils.isNotBlank((String) properties.get("virtualNetworkName"))) {
                copyVariableIfNotBlank(tmp, properties, "virtualNetworkName");
                copyVariable(tmp, properties, "subnetName");
                if (StringUtils.isNotBlank((String) properties.get("virtualNetworkResourceGroupName"))) {
                    copyVariable(tmp, properties, "virtualNetworkResourceGroupName");
                } else {
                    putVariable(tmp, "virtualNetworkResourceGroupName", resourceGroupName);
                }
            } else {
                addDefaultVNetResourceNode(tmp, resourceGroupName, cloud);
            }

            if (template.isSpotInstance()) {
                addSpotInstance(tmp);
            }

            if (!(Boolean) properties.get("usePrivateIP")) {
                addPublicIPResourceNode(tmp, cloud);
            }

            if (StringUtils.isNotBlank((String) properties.get("nsgName"))) {
                addNSGNode(tmp, (String) properties.get("nsgName"));
            }

            final ObjectNode parameters = MAPPER.createObjectNode();

            defineParameter(tmp, "adminPassword", "secureString");
            putParameter(parameters, "adminPassword", creds.getPassword().getPlainText());

            // Register the deployment for cleanup
            deploymentRegistrar.registerDeployment(
                    cloudName, template.getResourceGroupName(), deploymentName, scriptUri);
            // Create the deployment

            azureClient.deployments().define(deploymentName)
                    .withExistingResourceGroup(template.getResourceGroupName())
                    .withTemplate(tmp.toString())
                    .withParameters(parameters.toString())
                    .withMode(DeploymentMode.INCREMENTAL)
                    .beginCreate();
            return new AzureVMDeploymentInfo(deploymentName, vmBaseName, numberOfAgents);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AzureVMManagementServiceDelegate: deployment: Unable to deploy", e);
            // Pass the info off to the template so that it can be queued for update.
            template.handleTemplateProvisioningFailure(e.getMessage(), FailureStage.PROVISIONING);
            try {
                removeStorageBlob(new URI(scriptUri), template.getResourceGroupName());
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING,
                        "AzureVMManagementServiceDelegate: deployment: Delete initScript failed: {0}", scriptUri);
            }
            throw AzureCloudException.create(e);
        } finally {
            if (embeddedTemplate != null) {
                embeddedTemplate.close();
            }
        }
    }

    private void addSpotInstance(JsonNode template) {
        ArrayNode resources = (ArrayNode) template.get("resources");
        for (JsonNode resource : resources) {
            String type = resource.get("type").asText();
            if (type.contains("virtualMachine")) {
                ObjectNode properties = (ObjectNode) resource.get("properties");
                properties.put("priority", "Spot");
                properties.put("evictionPolicy", "Delete");
            }
        }
    }

    private void injectCustomTag(JsonNode resource, AzureVMCloud cloud) {
        ObjectNode tagsNode = (ObjectNode) resource.get("tags");
        List<AzureTagPair> cloudTags = cloud.getCloudTags();
        if (cloudTags != null) {
            for (AzureTagPair cloudTag : cloudTags) {
                tagsNode.put(cloudTag.getName(), cloudTag.getValue());
            }
        }
    }

    private boolean checkImageParameter(AzureVMAgentTemplate template) {
        if (StringUtils.isBlank(template.getImageReference().getPublisher())
                || StringUtils.isBlank(template.getImageReference().getOffer())
                || StringUtils.isBlank(template.getImageReference().getSku())) {
            LOGGER.log(Level.SEVERE, "Missing Image Reference information when trying to add purchase plan to ARM template");
            return false;
        }
        return true;
    }

    private GalleryImageVersion getGalleryImageLatestVersion(String galleryResourceGroup, String galleryName,
                                                             String galleryImageDefinition, String gallerySubscriptionId) throws AzureCloudException {

        AzureResourceManager client = AzureClientUtil.getClient(azureCredentialsId, gallerySubscriptionId);
        PagedIterable<GalleryImageVersion> galleryImageVersions = client.galleryImageVersions().listByGalleryImage(galleryResourceGroup, galleryName, galleryImageDefinition);
        GalleryImageVersion latestVersion = null;
        for (GalleryImageVersion galleryImageVersion : galleryImageVersions) {
            if (latestVersion == null) {
                latestVersion = galleryImageVersion;
                continue;
            }

            OffsetDateTime currentPublishedDate = latestVersion.publishingProfile().publishedDate();
            if (galleryImageVersion.publishingProfile().publishedDate().compareTo(currentPublishedDate) > 0) {
                latestVersion = galleryImageVersion;
            }
        }
        return latestVersion;
    }

    private static void putVariable(JsonNode template, String name, String value) {
        ((ObjectNode) template.get("variables")).put(name, value);
    }

    private static void putParameter(ObjectNode template, String name, String value) {
        ObjectNode objectNode = MAPPER.createObjectNode();
        objectNode.put("value", value);

        template.set(name, objectNode);
    }

    private static void defineParameter(JsonNode template, String name, String value) {
        ObjectNode objectNode = MAPPER.createObjectNode();
        objectNode.put("type", value);

        ((ObjectNode) template.get("parameters")).set(name, objectNode);
    }


    private static void putVariableIfNotBlank(JsonNode template, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            putVariable(template, name, value);
        }
    }

    private static void copyVariable(JsonNode template, Map<String, Object> properties, String name) {
        putVariable(template, name, (String) properties.get(name));
    }

    private static void copyVariableIfNotBlank(JsonNode template, Map<String, Object> properties, String name) {
        putVariableIfNotBlank(template, name, (String) properties.get(name));
    }

    private void addPublicIPResourceNode(
            JsonNode template,
            AzureVMCloud cloud) throws IOException {

        final String ipName = "variables('vmName'), copyIndex(), 'IPName'";
        try (InputStream fragmentStream =
                     AzureVMManagementServiceDelegate.class.getResourceAsStream(PUBLIC_IP_FRAGMENT_FILENAME)) {

            final JsonNode publicIPFragment = MAPPER.readTree(fragmentStream);
            injectCustomTag(publicIPFragment, cloud);
            // Add the virtual network fragment
            ((ArrayNode) template.get("resources")).add(publicIPFragment);

            // Because we created/updated this in the template, we need to add the appropriate
            // dependsOn node to the networkInterface and the ipConfigurations properties
            // "[concat('Microsoft.Network/publicIPAddresses/', variables('vmName'), copyIndex(), 'IPName')]"
            // Find the network interfaces node
            ArrayNode resourcesNodes = (ArrayNode) template.get("resources");
            Iterator<JsonNode> resourcesNodesIter = resourcesNodes.elements();
            while (resourcesNodesIter.hasNext()) {
                JsonNode resourcesNode = resourcesNodesIter.next();
                JsonNode typeNode = resourcesNode.get("type");
                if (typeNode == null || !typeNode.asText().equals("Microsoft.Network/networkInterfaces")) {
                    continue;
                }
                // Find the dependsOn node
                ArrayNode dependsOnNode = (ArrayNode) resourcesNode.get("dependsOn");
                // Add to the depends on node.
                dependsOnNode.add("[concat('Microsoft.Network/publicIPAddresses/'," + ipName + ")]");

                //Find the ipConfigurations/ipconfig1 node
                ArrayNode ipConfigurationsNode =
                        (ArrayNode) resourcesNode.get("properties").get("ipConfigurations");
                Iterator<JsonNode> ipConfigNodeIter = ipConfigurationsNode.elements();
                while (ipConfigNodeIter.hasNext()) {
                    JsonNode ipConfigNode = ipConfigNodeIter.next();
                    JsonNode nameNode = ipConfigNode.get("name");
                    if (nameNode == null || !nameNode.asText().equals("ipconfig1")) {
                        continue;
                    }
                    //find the properties node
                    ObjectNode propertiesNode = (ObjectNode) ipConfigNode.get("properties");
                    //add the publicIPAddress node
                    ObjectNode publicIPIdNode = MAPPER.createObjectNode();
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

    private static void addNSGNode(
            JsonNode template,
            String nsgName) {

        ((ObjectNode) template.get("variables")).put("nsgName", nsgName);

        ArrayNode resourcesNodes = (ArrayNode) template.get("resources");
        Iterator<JsonNode> resourcesNodesIter = resourcesNodes.elements();
        while (resourcesNodesIter.hasNext()) {
            JsonNode resourcesNode = resourcesNodesIter.next();
            JsonNode typeNode = resourcesNode.get("type");
            if (typeNode == null || !typeNode.asText().equals("Microsoft.Network/networkInterfaces")) {
                continue;
            }

            ObjectNode nsgNode = MAPPER.createObjectNode();
            nsgNode.put(
                    "id",
                    "[resourceId('Microsoft.Network/networkSecurityGroups', variables('nsgName'))]"
            );

            // Find the properties node
            // We will attach the provided NSG and not check if it's valid because that's done in the verification step
            ObjectNode propertiesNode = (ObjectNode) resourcesNode.get("properties");
            propertiesNode.set("networkSecurityGroup", nsgNode);
            break;
        }
    }


    private void addDefaultVNetResourceNode(
            JsonNode template,
            String resourceGroupName,
            AzureVMCloud cloud) throws IOException {
        InputStream fragmentStream = null;
        try {
            // Add the definition of the vnet and subnet into the template
            final String virtualNetworkName = Constants.DEFAULT_VNET_NAME;
            final String subnetName = Constants.DEFAULT_SUBNET_NAME;
            ((ObjectNode) template.get("variables")).put("virtualNetworkName", virtualNetworkName);
            ((ObjectNode) template.get("variables")).put(
                    "virtualNetworkResourceGroupName", resourceGroupName);
            ((ObjectNode) template.get("variables")).put("subnetName", subnetName);

            // Read the vnet fragment
            fragmentStream = AzureVMManagementServiceDelegate.class.getResourceAsStream(
                    VIRTUAL_NETWORK_TEMPLATE_FRAGMENT_FILENAME);

            final JsonNode virtualNetworkFragment = MAPPER.readTree(fragmentStream);
            injectCustomTag(virtualNetworkFragment, cloud);
            // Add the virtual network fragment
            ((ArrayNode) template.get("resources")).add(virtualNetworkFragment);

            // Because we created/updated this in the template, we need to add the appropriate
            // dependsOn node to the networkInterface
            // Microsoft.Network/virtualNetworks/<vnet name>
            // Find the network interfaces node
            ArrayNode resourcesNodes = (ArrayNode) template.get("resources");
            Iterator<JsonNode> resourcesNodesIter = resourcesNodes.elements();
            while (resourcesNodesIter.hasNext()) {
                JsonNode resourcesNode = resourcesNodesIter.next();
                JsonNode typeNode = resourcesNode.get("type");
                if (typeNode == null || !typeNode.asText().equals("Microsoft.Network/networkInterfaces")) {
                    continue;
                }
                // Find the dependsOn node
                ArrayNode dependsOnNode = (ArrayNode) resourcesNode.get("dependsOn");
                // Add to the depends on node.
                dependsOnNode.add("[concat('Microsoft.Network/virtualNetworks/', variables('virtualNetworkName'))]");
                break;
            }
        } finally {
            if (fragmentStream != null) {
                fragmentStream.close();
            }
        }
    }


    /**
     * Uploads the custom script for a template to blob storage.
     *
     * @param template         Template containing script to upload
     * @param targetScriptName Script to upload
     * @param initScript       Specify initScript
     * @return URI of script
     * @throws AzureCloudException when uploading to blob storage fails
     */
    public String uploadCustomScript(
            AzureVMAgentTemplate template,
            String targetScriptName,
            String initScript) throws AzureCloudException {
        String targetStorageAccount = template.getStorageAccountName();
        String targetStorageAccountType = template.getStorageAccountType();
        String resourceGroupName = template.getResourceGroupName();
        String resourceGroupReferenceType = template.getResourceGroupReferenceType();
        String location = template.getLocation();

        //make sure the resource group and storage account exist
        try {
            if (Constants.RESOURCE_GROUP_REFERENCE_TYPE_NEW.equals(resourceGroupReferenceType)) {
                AzureVMCloud azureVMCloud = template.retrieveAzureCloudReference();
                createAzureResourceGroup(azureClient, location, resourceGroupName, azureVMCloud.getCloudName());
            }

            createStorageAccount(
                    azureClient, targetStorageAccountType, targetStorageAccount, location, resourceGroupName,
                    template.getTemplateName(), template.retrieveAzureCloudReference().getCloudTags()
            );
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Got exception when checking the storage account for custom scripts", e);
        }

        int scriptLength = 0;
        try {
            BlobContainerClient container = getCloudBlobContainer(
                    azureClient, resourceGroupName, targetStorageAccount, Constants.CONFIG_CONTAINER_NAME);
            BlobClient blob = container.getBlobClient(targetScriptName);
            scriptLength = initScript.getBytes(StandardCharsets.UTF_8).length;
            blob.upload(BinaryData.fromString(initScript).toStream(), scriptLength, true);
            return blob.getBlobUrl();
        } catch (Exception e) {
            throw AzureCloudException.create(
                    String.format("Failed to create Page Blob with script's length: %d", scriptLength), e);
        }
    }

    public String uploadCustomScript(
            AzureVMAgentTemplate template,
            String targetScriptName) throws AzureCloudException {
        return uploadCustomScript(template, targetScriptName, template.getInitScript());
    }

    /**
     * Sets properties of virtual machine like IP and ssh port.
     *
     */
    public void setVirtualMachineDetails(
            AzureVMAgent azureAgent, AzureVMAgentTemplate template) throws AzureCloudException {

        VirtualMachine vm =
                azureClient.virtualMachines().getByResourceGroup(template.getResourceGroupName(), azureAgent.getNodeName());

        // Getting the first virtual IP
        final PublicIpAddress publicIP = vm.getPrimaryPublicIPAddress();
        String publicIPStr = "";
        String privateIP = vm.getPrimaryNetworkInterface().primaryPrivateIP();
        String fqdn;
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

        LOGGER.log(Level.INFO, "Azure agent details:\n"
                        + "nodeName{0}\n"
                        + "adminUserName={1}\n"
                        + "shutdownOnIdle={2}\n"
                        + "retentionTimeInMin={3}\n"
                        + "labels={4}",
                new Object[]{azureAgent.getNodeName(), azureAgent.getVMCredentialsId(), azureAgent.isShutdownOnIdle(),
                        azureAgent.getRetentionTimeInMin(), azureAgent.getLabelString()});
    }

    public void attachPublicIP(AzureVMAgent azureAgent, AzureVMAgentTemplate template)
            throws AzureCloudException {

        VirtualMachine vm;
        try {
            vm = azureClient.virtualMachines().getByResourceGroup(template.getResourceGroupName(), azureAgent.getNodeName());
        } catch (Exception e) {
            throw AzureCloudException.create(e);
        }

        LOGGER.log(Level.INFO, "Trying to attach a public IP to the agent {0}", azureAgent.getNodeName());
        if (vm != null) {
            //check if the VM already has a public IP
            if (vm.getPrimaryPublicIPAddress() == null) {
                try {
                    vm.getPrimaryNetworkInterface()
                            .update()
                            .withNewPrimaryPublicIPAddress(
                                    azureClient.publicIpAddresses()
                                            .define(azureAgent.getNodeName() + "IPName")
                                            .withRegion(template.getLocation())
                                            .withExistingResourceGroup(template.getResourceGroupName())
                                            .withLeafDomainLabel(azureAgent.getNodeName())
                            ).apply();
                } catch (Exception e) {
                    throw AzureCloudException.create(e);
                }

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
     * @param vmName            Name of the VM.
     * @param resourceGroupName Resource group of the VM.
     * @return If the virtual machine exists
     */
    private boolean virtualMachineExists(
            String vmName,
            String resourceGroupName) throws AzureCloudException {
        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: virtualMachineExists: check for {0}", vmName);

        try {
            azureClient.virtualMachines().getByResourceGroup(resourceGroupName, vmName);
        } catch (ManagementException e) {
            if (e.getResponse().getStatusCode() == 404) {
                LOGGER.log(Level.INFO,
                        "AzureVMManagementServiceDelegate: virtualMachineExists: {0} doesn't exist",
                        vmName);
                return false;
            }
            throw e;
        } catch (Exception e) {
            throw AzureCloudException.create(e);
        }

        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: virtualMachineExists: {0} exists", vmName);
        return true;
    }

    /**
     * Determines whether a given agent exists.
     *
     * @param agent to check
     * @return True if the agent exists, false otherwise
     */
    public static boolean virtualMachineExists(AzureVMAgent agent) {
        try {
            AzureVMManagementServiceDelegate delegate = agent.getServiceDelegate();
            if (delegate != null) {
                return delegate.virtualMachineExists(agent.getNodeName(), agent.getResourceGroupName());
            } else {
                return false;
            }
        } catch (AzureCloudException e) {
            LOGGER.log(Level.WARNING,
                    "AzureVMManagementServiceDelegate: virtualMachineExists: "
                            + "error while determining whether vm exists",
                    e);
            return false;
        }
    }

    /**
     * Creates Azure agent object with necessary info.
     *
     */
    public AzureVMAgent parseResponse(
            ProvisioningActivity.Id id,
            String vmname,
            String deploymentName,
            AzureVMAgentTemplate template,
            OperatingSystemTypes osType) throws AzureCloudException {

        try {

            LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: parseDeploymentResponse: \n"
                            + "\tfound agent {0}\n"
                            + "\tOS type {1}\n"
                            + "\tnumber of executors {2}",
                    new Object[]{vmname, osType, template.getNoOfParallelJobs()});

            AzureVMCloud azureCloud = template.retrieveAzureCloudReference();

            Map<String, Object> properties = AzureVMAgentTemplate.getTemplateProperties(template);

            VirtualMachine vm =
                    azureClient.virtualMachines().getByResourceGroup(template.getResourceGroupName(), vmname);

            final PublicIpAddress publicIP = vm.getPrimaryPublicIPAddress();
            String privateIP = vm.getPrimaryNetworkInterface().primaryPrivateIP();
            String fqdn;
            if (publicIP == null) {
                fqdn = privateIP;
            } else {
                fqdn = publicIP.fqdn();
            }

            return new AzureVMAgent(
                    id,
                    vmname,
                    template.getTemplateName(),
                    template.getTemplateDesc(),
                    osType,
                    template.getAgentWorkspace(),
                    (int) properties.get("noOfParallelJobs"),
                    template.getUseAgentAlwaysIfAvail(),
                    template.getLabels(),
                    template.retrieveAzureCloudReference().getDisplayName(),
                    template.getCredentialsId(),
                    null,
                    null,
                    (String) properties.get("jvmOptions"),
                    template.isShutdownOnIdle(),
                    false,
                    deploymentName,
                    template.getRetentionStrategy(),
                    (String) properties.get("initScript"),
                    (String) properties.get("terminateScript"),
                    azureCloud.getAzureCredentialsId(),
                    (String) properties.get("agentLaunchMethod"),
                    CleanUpAction.DEFAULT,
                    null,
                    template.getResourceGroupName(),
                    (Boolean) properties.get("executeInitScriptAsRoot"),
                    (Boolean) properties.get("doNotUseMachineIfInitFails"),
                    (Boolean) properties.get("enableMSI"),
                    (Boolean) properties.get("enableUAMI"),
                    (Boolean) properties.get("ephemeralOSDisk"),
                    (String) properties.get("uamiID"),
                    template,
                    fqdn,
                    template.getJavaPath());
        } catch (FormException | IOException e) {
            throw AzureCloudException.create("AzureVMManagementServiceDelegate: parseResponse: "
                    + "Exception occurred while creating agent object", e);
        }
    }

    /**
     * Gets a map of available locations mapping display name -> name (usable in
     * template).
     *
     */
    private static Set<String> getAvailableLocationsStandard() {
        final Set<String> locations = new HashSet<>();
        locations.add("UK South");
        locations.add("UK West");
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
     * <p>
     * TODO: load SKU size from Azure dynamically
     *
     * @return New map
     */
    private static Map<String, List<String>> getAvailableRoleSizes() {
        final Map<String, List<String>> sizes = new HashMap<>();
        sizes.put("East US", Arrays.asList(
                "A10", "A11", "A5", "A6", "A7", "A8", "A9",
                "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1",
                "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2",
                "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2",
                "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2",
                "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2",
                "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2",
                "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2",
                "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s",
                "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4",
                "Standard_F4s", "Standard_F8", "Standard_F8s"));
        sizes.put("West US", Arrays.asList(
                "A10", "A11", "A5", "A6", "A7", "A8", "A9",
                "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2",
                "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2",
                "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2",
                "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2",
                "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2",
                "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2",
                "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2",
                "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s",
                "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s",
                "Standard_G1", "Standard_G2", "Standard_G3", "Standard_G4", "Standard_G5",
                "Standard_GS1", "Standard_GS2", "Standard_GS3", "Standard_GS4", "Standard_GS5"));
        sizes.put("South Central US", Arrays.asList(
                "A10", "A11", "A5", "A6", "A7", "A8", "A9",
                "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2",
                "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2",
                "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2",
                "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2",
                "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2",
                "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2",
                "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s",
                "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s",
                "Standard_F8", "Standard_F8s"));
        sizes.put("Central US", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2",
                "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2",
                "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2",
                "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2",
                "Standard_DS11", "Standard_DS11_v2", "Standard_DS12", "Standard_DS12_v2",
                "Standard_DS13", "Standard_DS13_v2", "Standard_DS14", "Standard_DS14_v2",
                "Standard_DS2", "Standard_DS2_v2", "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2",
                "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s",
                "Standard_F1s", "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s",
                "Standard_F8", "Standard_F8s"));
        sizes.put("North Central US", Arrays.asList(
                "A10", "A11", "A5", "A6", "A7", "A8", "A9",
                "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1_v2", "Standard_DS11_v2", "Standard_DS12_v2", "Standard_DS13_v2", "Standard_DS14_v2",
                "Standard_DS2_v2", "Standard_DS3_v2", "Standard_DS4_v2", "Standard_DS5_v2",
                "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s",
                "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"));
        sizes.put("East US 2", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2",
                "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2",
                "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2",
                "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2",
                "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s",
                "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2",
                "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2",
                "Standard_GS3", "Standard_GS4", "Standard_GS5"));
        sizes.put("North Europe", Arrays.asList(
                "A10", "A11", "A5", "A6", "A7", "A8", "A9",
                "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2",
                "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2",
                "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2",
                "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2",
                "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s",
                "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"));
        sizes.put("West Europe", Arrays.asList(
                "A10", "A11", "A5", "A6", "A7", "A8", "A9",
                "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2",
                "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2",
                "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2",
                "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2",
                "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s",
                "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2",
                "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3",
                "Standard_GS4", "Standard_GS5"));
        sizes.put("Southeast Asia", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2",
                "Standard_D12", "Standard_D12_v2", "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2",
                "Standard_D2", "Standard_D2_v2", "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2",
                "Standard_D5_v2", "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2",
                "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2",
                "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2",
                "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2",
                "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s",
                "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2",
                "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3",
                "Standard_GS4", "Standard_GS5"));
        sizes.put("East Asia", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1",
                "Standard_DS11", "Standard_DS12", "Standard_DS13", "Standard_DS14", "Standard_DS2", "Standard_DS3",
                "Standard_DS4", "Standard_F1", "Standard_F16", "Standard_F2", "Standard_F4", "Standard_F8"));
        sizes.put("Japan West", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2",
                "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2",
                "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2",
                "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2",
                "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s",
                "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"));
        sizes.put("Japan East", Arrays.asList(
                "A10", "A11", "A5", "A6", "A7", "A8", "A9", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2",
                "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2",
                "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2",
                "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2",
                "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s",
                "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"));
        sizes.put("Brazil South", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1_v2", "Standard_DS11_v2", "Standard_DS12_v2", "Standard_DS13_v2", "Standard_DS14_v2",
                "Standard_DS2_v2", "Standard_DS3_v2", "Standard_DS4_v2", "Standard_DS5_v2",
                "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s",
                "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"));
        sizes.put("Australia Southeast", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2",
                "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2",
                "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2",
                "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2",
                "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s",
                "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"));
        sizes.put("Australia East", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2",
                "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2",
                "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2",
                "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2",
                "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s",
                "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2",
                "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3",
                "Standard_GS4", "Standard_GS5"));
        sizes.put("Central India", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1_v2",
                "Standard_D11_v2", "Standard_D12_v2", "Standard_D13_v2", "Standard_D14_v2", "Standard_D2_v2",
                "Standard_D3_v2", "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1_v2", "Standard_DS11_v2",
                "Standard_DS12_v2", "Standard_DS13_v2", "Standard_DS14_v2", "Standard_DS2_v2", "Standard_DS3_v2",
                "Standard_DS4_v2", "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s",
                "Standard_F2", "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"));
        sizes.put("South India", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4",
                "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1_v2", "Standard_D11_v2",
                "Standard_D12_v2", "Standard_D13_v2", "Standard_D14_v2", "Standard_D2_v2", "Standard_D3_v2",
                "Standard_D4_v2", "Standard_D5_v2", "Standard_DS1_v2", "Standard_DS11_v2", "Standard_DS12_v2",
                "Standard_DS13_v2", "Standard_DS14_v2", "Standard_DS2_v2", "Standard_DS3_v2", "Standard_DS4_v2",
                "Standard_DS5_v2", "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2",
                "Standard_F2s", "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s"));
        sizes.put("West India", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4", "Standard_A4",
                "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1", "Standard_D1_v2", "Standard_D11_v2",
                "Standard_D12_v2", "Standard_D13_v2", "Standard_D14_v2", "Standard_D2_v2", "Standard_D3_v2",
                "Standard_D4_v2", "Standard_D5_v2", "Standard_F1", "Standard_F16", "Standard_F2", "Standard_F4",
                "Standard_F8"));

        // China sizes, may not be exact
        sizes.put("China North", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1", "Standard_DS1_v2", "Standard_DS11", "Standard_DS11_v2",
                "Standard_DS12", "Standard_DS12_v2", "Standard_DS13", "Standard_DS13_v2",
                "Standard_DS14", "Standard_DS14_v2", "Standard_DS2", "Standard_DS2_v2",
                "Standard_DS3", "Standard_DS3_v2", "Standard_DS4", "Standard_DS4_v2", "Standard_DS5_v2",
                "Standard_F1", "Standard_F16", "Standard_F16s", "Standard_F1s", "Standard_F2", "Standard_F2s",
                "Standard_F4", "Standard_F4s", "Standard_F8", "Standard_F8s", "Standard_G1", "Standard_G2",
                "Standard_G3", "Standard_G4", "Standard_G5", "Standard_GS1", "Standard_GS2", "Standard_GS3",
                "Standard_GS4", "Standard_GS5"));
        sizes.put("China East", Arrays.asList(
                "A5", "A6", "A7", "Basic_A0", "Basic_A1", "Basic_A2", "Basic_A3", "Basic_A4",
                "Standard_A4", "Standard_A0", "Standard_A3", "Standard_A2", "Standard_A1",
                "Standard_D1", "Standard_D1_v2", "Standard_D11", "Standard_D11_v2", "Standard_D12", "Standard_D12_v2",
                "Standard_D13", "Standard_D13_v2", "Standard_D14", "Standard_D14_v2", "Standard_D2", "Standard_D2_v2",
                "Standard_D3", "Standard_D3_v2", "Standard_D4", "Standard_D4_v2", "Standard_D5_v2",
                "Standard_DS1", "Standard_DS11", "Standard_DS12", "Standard_DS13", "Standard_DS14",
                "Standard_DS2", "Standard_DS3", "Standard_DS4", "Standard_F1", "Standard_F16", "Standard_F2",
                "Standard_F4", "Standard_F8"));

        return sizes;
    }

    private static Map<String, Map<String, String>> getDefaultImageProperties() {
        final Map<String, Map<String, String>> imageProperties = new HashMap<>();
        imageProperties.put(Constants.WINDOWS_SERVER_2016, new HashMap<>());
        imageProperties.put(Constants.WINDOWS_SERVER_2019, new HashMap<>());
        imageProperties.put(Constants.UBUNTU_1604_LTS, new HashMap<>());
        imageProperties.put(Constants.UBUNTU_2004_LTS, new HashMap<>());

        imageProperties(imageProperties, Constants.WINDOWS_SERVER_2016, "MicrosoftWindowsServer", "WindowsServer", "2016-Datacenter", "2016-Datacenter-with-Containers", Constants.OS_TYPE_WINDOWS);
        imageProperties(imageProperties, Constants.WINDOWS_SERVER_2019, "MicrosoftWindowsServer", "WindowsServer", "2019-Datacenter", "2019-Datacenter-with-Containers", Constants.OS_TYPE_WINDOWS);
        imageProperties(imageProperties, Constants.UBUNTU_1604_LTS, "Canonical", "UbuntuServer", "16.04-LTS", "16.04-LTS", Constants.OS_TYPE_LINUX);
        imageProperties(imageProperties, Constants.UBUNTU_2004_LTS, "canonical", "0001-com-ubuntu-server-focal", "20_04-lts-gen2", "20_04-lts-gen2", Constants.OS_TYPE_LINUX);
        return imageProperties;
    }

    private static void imageProperties(
            Map<String, Map<String, String>> imageProperties,
            String imageName,
            String defaultImagePublisher,
            String offer,
            String sku,
            String dockerImageSku,
            String osType
    ) {
        imageProperties.get(imageName).put(Constants.DEFAULT_IMAGE_PUBLISHER, defaultImagePublisher);
        imageProperties.get(imageName).put(Constants.DEFAULT_IMAGE_OFFER, offer);
        imageProperties.get(imageName).put(Constants.DEFAULT_IMAGE_SKU, sku);
        imageProperties.get(imageName).put(Constants.DEFAULT_DOCKER_IMAGE_SKU, dockerImageSku);
        imageProperties.get(imageName).put(Constants.DEFAULT_IMAGE_VERSION, "latest");
        imageProperties.get(imageName).put(Constants.DEFAULT_OS_TYPE, osType);
        imageProperties.get(imageName).put(Constants.DEFAULT_LAUNCH_METHOD, Constants.LAUNCH_METHOD_SSH);
    }

    private static Map<String, Map<String, String>> getPreInstalledToolsScript() {
        final Map<String, Map<String, String>> tools = new HashMap<>();
        tools.put(Constants.WINDOWS_SERVER_2016, new HashMap<>());
        tools.put(Constants.WINDOWS_SERVER_2019, new HashMap<>());
        tools.put(Constants.UBUNTU_1604_LTS, new HashMap<>());
        tools.put(Constants.UBUNTU_2004_LTS, new HashMap<>());
        try {
            windows(Constants.WINDOWS_SERVER_2016, tools);
            windows(Constants.WINDOWS_SERVER_2019, tools);
            ubuntu(Constants.UBUNTU_1604_LTS, tools);
            ubuntu(Constants.UBUNTU_2004_LTS, tools);

            return tools;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "AzureVMManagementServiceDelegate: getPreInstalledToolsScript: "
                            + "Get pre-installed tools script {0} failed.",
                    e);
            return tools;
        }
    }

    private static void ubuntu(String imageName, Map<String, Map<String, String>> tools) throws IOException {
        tools.get(imageName).put(
                Constants.INSTALL_JAVA,
                IOUtils.toString(AzureVMManagementServiceDelegate.class.getResourceAsStream(
                        INSTALL_JAVA_UBUNTU_FILENAME), StandardCharsets.UTF_8));
        tools.get(imageName).put(
                Constants.INSTALL_MAVEN,
                IOUtils.toString(AzureVMManagementServiceDelegate.class.getResourceAsStream(
                        INSTALL_MAVEN_UBUNTU_FILENAME), StandardCharsets.UTF_8));
        tools.get(imageName).put(
                Constants.INSTALL_GIT,
                IOUtils.toString(AzureVMManagementServiceDelegate.class.getResourceAsStream(
                        INSTALL_GIT_UBUNTU_FILENAME), StandardCharsets.UTF_8));
        tools.get(imageName).put(
                Constants.INSTALL_DOCKER,
                IOUtils.toString(AzureVMManagementServiceDelegate.class.getResourceAsStream(
                        INSTALL_DOCKER_UBUNTU_FILENAME), StandardCharsets.UTF_8));
    }

    private static void windows(String imageName, Map<String, Map<String, String>> tools) throws IOException {
        tools.get(imageName).put(
                Constants.INSTALL_JAVA,
                IOUtils.toString(AzureVMManagementServiceDelegate.class.getResourceAsStream(
                        INSTALL_JAVA_WINDOWS_FILENAME), StandardCharsets.UTF_8));
        tools.get(imageName).put(
                Constants.INSTALL_MAVEN,
                IOUtils.toString(AzureVMManagementServiceDelegate.class.getResourceAsStream(
                        INSTALL_MAVEN_WINDOWS_FILENAME), StandardCharsets.UTF_8));
        tools.get(imageName).put(
                Constants.INSTALL_GIT,
                IOUtils.toString(AzureVMManagementServiceDelegate.class.getResourceAsStream(
                        INSTALL_GIT_WINDOWS_FILENAME), StandardCharsets.UTF_8));
        tools.get(imageName).put(
                Constants.INSTALL_JNLP,
                IOUtils.toString(AzureVMManagementServiceDelegate.class.getResourceAsStream(
                        INSTALL_JNLP_WINDOWS_FILENAME), StandardCharsets.UTF_8));
    }

    /**
     * Gets map of Azure datacenter locations which supports Persistent VM role.
     * If it can't fetch the data then it will return a default hardcoded set
     * certificate based auth appears to be required.
     *
     * @return Set of available regions
     */
    public Set<String> getVirtualMachineLocations(String envNameOrUrl) {
        if (envNameOrUrl == null) {
            return null;
        }
        envNameOrUrl = envNameOrUrl.toLowerCase();
        try {
            return LocationCache.getLocation(azureClient, envNameOrUrl);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "AzureVMManagementServiceDelegate: getVirtualMachineLocations: "
                            + "error while fetching the regions {0}. Will return default list ",
                    e);
            if (envNameOrUrl.contains("china")) {
                return AVAILABLE_LOCATIONS_CHINA;
            }
            return AVAILABLE_LOCATIONS_STD;
        }
    }

    /**
     * Gets list of virtual machine sizes. If it can't fetch the data then it will return a default hardcoded list
     *
     * @param location Location to obtain VM sizes for
     * @return List of VM sizes
     */
    public Set<String> getVMSizes(String location) {
        if (location == null || location.isEmpty()) {
            //if the location is not available we'll just return a default list with some of the most common VM sizes
            return new TreeSet<>(DEFAULT_VM_SIZES);
        }
        try {
            Set<String> ret = new TreeSet<>();
            for (VirtualMachineSize vmSize : azureClient.virtualMachines().sizes().listByRegion(location)) {
                ret.add(vmSize.name());
            }
            return ret;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "AzureVMManagementServiceDelegate: getVMSizes: "
                            + "error while fetching the VM sizes {0}. Will return default list ",
                    e);
            return new TreeSet<>(AVAILABLE_ROLE_SIZES.get(location));
        }
    }

    /**
     * Validates certificate configuration.
     *
     * @param resourceGroupName Resource group name.
     * @param maxVMLimit        Max limit of virtual machines.
     * @param timeOut           Timeout of the verification.
     * @return Verification result.
     */
    public String verifyConfiguration(
            String resourceGroupName,
            String maxVMLimit,
            String timeOut) {
        try {
            if (!AzureUtil.isValidTimeOut(timeOut)) {
                return "Invalid Timeout, Should be a positive number, minimum value "
                        + Constants.DEFAULT_DEPLOYMENT_TIMEOUT_SEC;
            }

            if (!AzureUtil.isValidResourceGroupName(resourceGroupName)) {
                return "Error: " + Messages.Azure_GC_Template_ResourceGroupName_Err();
            }

            if (!AzureUtil.isValidMAxVMLimit(maxVMLimit)) {
                return "Invalid Limit, Should be a positive number, e.g. " + Constants.DEFAULT_MAX_VM_LIMIT;
            }

            if (!(AzureUtil.isValidTimeOut(timeOut) && AzureUtil.isValidMAxVMLimit(maxVMLimit)
                    && AzureUtil.isValidResourceGroupName(resourceGroupName))) {
                    return Messages.Azure_GC_Template_Val_Profile_Err();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error validating profile", e);
            return Messages.Azure_GC_Template_Val_Profile_Err();
        }
        return Constants.OP_SUCCESS;
    }

    public static class VMStatus extends ExpandableStringEnum<VMStatus> {
        public static final VMStatus PROVISIONING_OR_DEPROVISIONING = fromString(Constants.PROVISIONING_OR_DEPROVISIONING_VM_STATUS);
        public static final VMStatus UPDATING = fromString(Constants.UPDATING_VM_STATUS);

        public static final VMStatus RUNNING = fromString("PowerState/running");
        public static final VMStatus DEALLOCATING = fromString("PowerState/deallocating");
        public static final VMStatus DEALLOCATED = fromString("PowerState/deallocated");
        public static final VMStatus STARTING = fromString("PowerState/starting");
        public static final VMStatus STOPPED = fromString("PowerState/stopped");
        public static final VMStatus STOPPING = fromString("PowerState/stopping");
        public static final VMStatus UNKNOWN = fromString("PowerState/unknown");

        public static VMStatus fromString(String name) {
            return fromString(name, VMStatus.class);
        }

        public static VMStatus fromPowerState(PowerState powerState) {
            return fromString(powerState.toString(), VMStatus.class);
        }
    }

    /**
     * Gets current status of virtual machine.
     *
     * @param vmName            Virtual machine name.
     * @param resourceGroupName Resource group name.
     * @return Virtual machine status.
     */

    private VMStatus getVirtualMachineStatus(
            String vmName,
            String resourceGroupName) throws AzureCloudException {

        VirtualMachine vm;
        try {
            vm = azureClient.virtualMachines().getByResourceGroup(resourceGroupName, vmName);
        } catch (Exception e) {
            throw AzureCloudException.create(e);
        }

        final String provisioningState = vm.provisioningState();
        if (!provisioningState.equalsIgnoreCase("succeeded")) {
            if (provisioningState.equalsIgnoreCase("updating")) {
                return VMStatus.UPDATING;
            } else {
                return VMStatus.PROVISIONING_OR_DEPROVISIONING;
            }
        } else {
            return VMStatus.fromPowerState(vm.powerState());
        }
    }

    /**
     * Checks if VM is reachable and in a valid state to connect (or getting
     * ready to do so).
     *
     */
    public boolean isVMAliveOrHealthy(AzureVMAgent agent) throws AzureCloudException {
        VMStatus status = getVirtualMachineStatus(agent.getNodeName(), agent.getResourceGroupName());
        // Change to 20 minutes. It takes around 10 minutes in A0.
        final int maxRetryCount = 40;
        int currentRetryCount = 0;
        //When launching Windows via SSH, this function will be executed before extension done.
        //Thus status will be "Updating".
        while (status.equals(VMStatus.UPDATING) && currentRetryCount < maxRetryCount) {
            status = getVirtualMachineStatus(agent.getNodeName(), agent.getResourceGroupName());
            LOGGER.log(Level.INFO,
                    "AzureVMManagementServiceDelegate: isVMAliveOrHealthy: "
                            + "Status is Updating, wait for another 30 seconds");
            final int sleepInMills = 30 * 1000;
            try {
                Thread.sleep(sleepInMills);
            } catch (InterruptedException e) {
                // Ignore
            }
            currentRetryCount++;
        }
        LOGGER.log(Level.INFO,
                "AzureVMManagementServiceDelegate: isVMAliveOrHealthy: status {0}",
                status.toString());
        return !(VMStatus.PROVISIONING_OR_DEPROVISIONING.equals(status)
                || VMStatus.UPDATING.equals(status)
                || VMStatus.DEALLOCATING.equals(status)
                || VMStatus.STOPPED.equals(status)
                || VMStatus.DEALLOCATED.equals(status));
    }

    /**
     * Retrieves count of virtual machine in a azure subscription. This count is
     * based off of the VMs that the current credential set has access to. It
     * also does not deal with the classic, model. So keep this in mind.
     *
     * @return Total VM count
     */
    public int getVirtualMachineCount(String cloudName, String resourceGroupName) {
        try {
            final PagedIterable<VirtualMachine> vms = azureClient.virtualMachines().listByResourceGroup(resourceGroupName);
            int count = 0;
            final AzureUtil.DeploymentTag deployTag = new AzureUtil.DeploymentTag();
            for (VirtualMachine vm : vms) {
                final Map<String, String> tags = vm.tags();
                if (tags.containsKey(Constants.AZURE_RESOURCES_TAG_NAME)
                        && deployTag.isFromSameInstance(
                        new AzureUtil.DeploymentTag(tags.get(Constants.AZURE_RESOURCES_TAG_NAME)))) {
                    if (tags.containsKey(Constants.AZURE_CLOUD_TAG_NAME)) {
                        if (tags.get(Constants.AZURE_CLOUD_TAG_NAME).equals(cloudName)) {
                            count++;
                        }
                    } else {
                        // keep backwards compatibility, omitting the resource created before updates
                        // until all the resources has cloud tag
                        count++;
                    }
                }
            }
            return count;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "AzureVMManagementServiceDelegate: getVirtualMachineCount: Got exception while getting hosted "
                            + "services info, assuming that there are no hosted services {0}", e);
            return 0;
        }
    }

    /**
     * Shutdowns Azure virtual machine.
     *
     */
    public void shutdownVirtualMachine(AzureVMAgent agent) {
        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: shutdownVirtualMachine: called for {0}",
                agent.getNodeName());

        try {
            azureClient.virtualMachines()
                    .getByResourceGroup(agent.getResourceGroupName(), agent.getNodeName()).deallocate();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "AzureVMManagementServiceDelegate: provision: could not terminate or shutdown {0}, {1}",
                    new Object[]{agent.getNodeName(), e});
        }
    }

    /**
     * Deletes Azure virtual machine.
     *
     */
    public static void terminateVirtualMachine(AzureVMAgent agent) throws AzureCloudException {
        AzureVMManagementServiceDelegate delegate = agent.getServiceDelegate();
        if (delegate != null) {
            delegate.terminateVirtualMachine(agent.getNodeName(), agent.getResourceGroupName(), new ExecutionEngine());
        }
    }

    /**
     * Terminates a virtual machine.
     *
     * @param vmName            VM name
     * @param resourceGroupName Resource group containing the VM
     */
    public void terminateVirtualMachine(
            String vmName,
            String resourceGroupName) throws AzureCloudException {
        terminateVirtualMachine(vmName, resourceGroupName, new ExecutionEngine());
    }

    /**
     * Terminates a virtual machine.
     *
     * @param vmName            VM name
     * @param resourceGroupName Resource group containing the VM
     */
    public void terminateVirtualMachine(
            final String vmName,
            final String resourceGroupName,
            ExecutionEngine executionEngine) throws AzureCloudException {
        try {
            if (virtualMachineExists(vmName, resourceGroupName)) {
                List<URI> diskUrisToRemove = new ArrayList<>();
                List<String> diskIdToRemove = new ArrayList<>();
                if (!azureClient.virtualMachines().getByResourceGroup(resourceGroupName, vmName).isManagedDiskEnabled()) {
                    // Mark OS disk for removal
                    diskUrisToRemove.add(new URI(
                            azureClient.virtualMachines()
                                    .getByResourceGroup(resourceGroupName, vmName)
                                    .osUnmanagedDiskVhdUri()));
                } else {
                    diskIdToRemove.add(azureClient.virtualMachines().getByResourceGroup(resourceGroupName, vmName).osDiskId());
                }
                // TODO: Remove data disks or add option to do so?

                // Remove the VM
                LOGGER.log(Level.INFO,
                        "AzureVMManagementServiceDelegate: terminateVirtualMachine: "
                                + "Removing virtual machine {0}",
                        vmName);
                azureClient.virtualMachines().deleteByResourceGroup(resourceGroupName, vmName);

                // Now remove the disks
                for (URI diskUri : diskUrisToRemove) {
                    this.removeStorageBlob(diskUri, resourceGroupName);
                }
                for (String id : diskIdToRemove) {
                    LOGGER.log(Level.INFO,
                            "AzureVMManagementServiceDelegate: terminateVirtualMachine: "
                                    + "Removing managed disk with id: {0}",
                            id);
                    azureClient.disks().deleteById(id);
                }

                //If used managed Disk with custom vhd, we need to delete the temporary image.
                if (!diskIdToRemove.isEmpty()) {
                    removeImage(azureClient, vmName, resourceGroupName);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "AzureVMManagementServiceDelegate: terminateVirtualMachine: while deleting VM", e);
            // Check if VM is already deleted: if VM is already deleted then just ignore exception.
            if (!Constants.ERROR_CODE_RESOURCE_NF.equalsIgnoreCase(e.getMessage())) {
                throw AzureCloudException.create(e);
            }
        } finally {
            LOGGER.log(Level.INFO, "Clean operation starting for {0} NIC and IP", vmName);
            executionEngine.executeAsync((Callable<Void>) () -> {
                removeIPName(resourceGroupName, vmName);
                return null;
            }, new NoRetryStrategy());
        }

    }

    public void removeImage(AzureResourceManager azureClient, String vmName, String resourceGroupName) {
        PagedIterable<VirtualMachineCustomImage> customImages =
                azureClient.virtualMachineCustomImages().listByResourceGroup(resourceGroupName);
        for (VirtualMachineCustomImage image : customImages) {
            String prefix = StringUtils.substringBefore(image.name(), "Image");
            if (StringUtils.contains(vmName, prefix)) {
                LOGGER.log(Level.INFO,
                        "AzureVMManagementServiceDelegate: terminateVirtualMachine: "
                                + "Removing image with name: {0}",
                        image.name());
                azureClient.virtualMachineCustomImages().deleteById(image.id());
            }
        }
    }

    public void removeStorageBlob(URI blobURI, String resourceGroupName) throws Exception {
        // Obtain container, storage account, and blob name
        BlobUrlParts blobUrlParts = BlobUrlParts.parse(blobURI.toURL());
        String storageAccountName = blobUrlParts.getAccountName();
        String containerName = blobUrlParts.getBlobContainerName();
        String blobName = blobUrlParts.getBlobName();

        LOGGER.log(Level.INFO,
                "removeStorageBlob: Removing blob {0}, in container {1} of storage account {2}",
                new Object[]{blobName, containerName, storageAccountName});
        BlobContainerClient container =
                getCloudBlobContainer(azureClient, resourceGroupName, storageAccountName, containerName);
        container.getBlobClient(blobName).delete();
        if (containerName.startsWith("jnk")) {
            PagedIterable<BlobItem> blobs = container.listBlobs();
            if (blobs.iterator().hasNext()) { // the container is not empty
                return;
            }
            // the container is empty and we should delete it
            LOGGER.log(Level.INFO, "removeStorageBlob: Removing empty container ", containerName);
            container.delete();
        }
    }

    /**
     * Remove the IP name.
     *
     */
    public void removeIPName(String resourceGroupName,
                             String vmName) {
        final String nic = vmName + "NIC";
        try {
            LOGGER.log(Level.INFO, "Remove NIC {0}", nic);
            azureClient.networkInterfaces().deleteByResourceGroup(resourceGroupName, nic);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "AzureVMManagementServiceDelegate: removeIPName: while deleting NIC", e);
        }

        final String ip = vmName + "IPName";
        try {
            LOGGER.log(Level.INFO, "Remove IP {0}", ip);
            azureClient.publicIpAddresses().deleteByResourceGroup(resourceGroupName, ip);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "AzureVMManagementServiceDelegate: removeIPName: while deleting IPName", e);
        }
    }

    /**
     * Restarts Azure virtual machine.
     *
     */
    public void restartVirtualMachine(AzureVMAgent agent) throws AzureCloudException {
        try {
            azureClient.virtualMachines()
                    .getByResourceGroup(agent.getResourceGroupName(), agent.getNodeName()).restart();
        } catch (Exception e) {
            throw AzureCloudException.create(e);
        }
    }

    /**
     * Starts Azure virtual machine.
     *
     */
    public void startVirtualMachine(AzureVMAgent agent) throws AzureCloudException {
        LOGGER.log(Level.INFO, "AzureVMManagementServiceDelegate: startVirtualMachine: {0}", agent.getNodeName());
        int retryCount = 0;
        boolean successful = false;

        while (!successful) {
            try {
                azureClient.virtualMachines().getByResourceGroup(agent.getResourceGroupName(), agent.getNodeName()).start();
                successful = true; // may be we can just return
            } catch (Exception e) {
                LOGGER.log(Level.INFO,
                        "AzureVMManagementServiceDelegate: startVirtualMachine: got exception while "
                                + "starting VM {0}. Will retry again after 30 seconds. Current retry count {1} / {2}\n",
                        new Object[]{agent.getNodeName(), retryCount, Constants.MAX_PROV_RETRIES});
                if (retryCount > Constants.MAX_PROV_RETRIES) {
                    throw AzureCloudException.create(e);
                } else {
                    retryCount++;
                    // wait for 30 seconds
                    final int sleepMillis = 30 * 1000;
                    try {
                        Thread.sleep(sleepMillis);
                    } catch (InterruptedException e1) {
                        // ignore
                    }
                }
            }
        }
    }

    /**
     * Returns virtual network site properties.
     *
     */
    public Network getVirtualNetwork(String virtualNetworkName, String resourceGroupName) {
        try {
            return azureClient.networks().getByResourceGroup(resourceGroupName, virtualNetworkName);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AzureVMManagementServiceDelegate: getVirtualNetworkInfo: "
                    + "Got exception while getting virtual network info: ", e);
        }
        return null;
    }

    /**
     * Verifies template configuration by making server calls if needed.
     *
     */
    public List<String> verifyTemplate(
            String templateName,
            String labels,
            String location,
            String virtualMachineSize,
            String storageAccountName,
            String storageAccountType,
            String noOfParallelJobs,
            String imageTopLevelType,
            AzureVMAgentTemplate.ImageReferenceTypeClass imageReferenceType,
            String builtInImage,
            String osType,
            String agentLaunchMethod,
            String initScript,
            String credentialsId,
            String virtualNetworkName,
            String virtualNetworkResourceGroupName,
            String subnetName,
            AzureVMCloudBaseRetentionStrategy retentionStrategy,
            String jvmOptions,
            String resourceGroupName,
            boolean returnOnSingleError,
            boolean usePrivateIP,
            String nsgName) {

        List<String> errors = new ArrayList<>();

        // Load configuration
        try {
            String validationResult;

            // Verify basic info about the template
            //Verify number of parallel jobs

            validationResult = verifyTemplateName(templateName);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            validationResult = verifyNoOfExecutors(noOfParallelJobs);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            validationResult = verifyRetentionTime(retentionStrategy);
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

            validationResult = verifyImageParameters(
                    imageTopLevelType,
                    imageReferenceType,
                    builtInImage,
                    osType
            );
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            verifyTemplateAsync(
                    location,
                    imageTopLevelType,
                    imageReferenceType,
                    builtInImage,
                    storageAccountName,
                    storageAccountType,
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
            errors.add("Error occurred while validating Azure Profile");
        }

        return errors;
    }

    private void verifyTemplateAsync(
            final String location,
            final String imageTopLevelType,
            final AzureVMAgentTemplate.ImageReferenceTypeClass imageReferenceType,
            final String builtInImage,
            final String storageAccountName,
            final String storageAccountType,
            final String virtualNetworkName,
            final String virtualNetworkResourceGroupName,
            final String subnetName,
            final String resourceGroupName,
            List<String> errors,
            final boolean usePrivateIP,
            final String nsgName) {

        List<Callable<String>> verificationTaskList = new ArrayList<>();

        // Callable for virtual network.
        Callable<String> callVerifyVirtualNetwork = () -> verifyVirtualNetwork(
                virtualNetworkName,
                virtualNetworkResourceGroupName,
                subnetName,
                usePrivateIP,
                resourceGroupName);
        verificationTaskList.add(callVerifyVirtualNetwork);

        // Callable for VM image.
        Callable<String> callVerifyVirtualMachineImage = () -> verifyVirtualMachineImage(
                location,
                storageAccountName,
                imageTopLevelType,
                imageReferenceType,
                builtInImage
        );
        verificationTaskList.add(callVerifyVirtualMachineImage);

        // Callable for storage account virtual network.
        Callable<String> callVerifyStorageAccountName = () -> verifyStorageAccountName(
                resourceGroupName, storageAccountName, storageAccountType);
        verificationTaskList.add(callVerifyStorageAccountName);

        // Callable for NSG.
        Callable<String> callVerifyNSG = () -> verifyNSG(resourceGroupName, nsgName);
        verificationTaskList.add(callVerifyNSG);

        try {
            for (Future<String> validationResult : AzureVMCloud.getThreadPool().invokeAll(verificationTaskList)) {
                try {
                    // Get will block until time expires or until task completes
                    final int timeoutInSeconds = 60;
                    String result = validationResult.get(timeoutInSeconds, TimeUnit.SECONDS);
                    addValidationResultIfFailed(result, errors);
                } catch (ExecutionException executionException) {
                    errors.add("Exception occurred while validating temaplate " + executionException);
                } catch (TimeoutException timeoutException) {
                    errors.add("Exception occurred while validating template " + timeoutException);
                } catch (Exception others) {
                    errors.add(others.getMessage() + others);
                }
            }
        } catch (InterruptedException interruptedException) {
            errors.add("Exception occurred while validating template " + interruptedException);
        }
    }

    private static void addValidationResultIfFailed(String validationResult, List<String> errors) {
        if (!validationResult.equalsIgnoreCase(Constants.OP_SUCCESS)) {
            errors.add(validationResult);
        }
    }

    public String verifyTemplateName(String templateName) {
        // See reserved name: https://docs.microsoft.com/en-us/azure/azure-resource-manager/resource-manager-reserved-resource-name
        if (StringUtils.lowerCase(templateName).contains("login")
                || StringUtils.lowerCase(templateName).contains("microsoft")
                || StringUtils.lowerCase(templateName).contains("windows")
                || StringUtils.lowerCase(templateName).contains("xbox")) {
            return Messages.Azure_GC_Template_Name_Reserved();
        }
        return Constants.OP_SUCCESS;
    }

    public static String verifyNoOfExecutors(String noOfExecutors) {
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

    public static String verifyRetentionTime(AzureVMCloudBaseRetentionStrategy retentionStrategy) {
        try {
            if (retentionStrategy == null) {
                return Messages.Azure_GC_Template_RT_Null_Or_Empty();
            } else {
                return Constants.OP_SUCCESS;
            }
        } catch (IllegalArgumentException e) {
            return Messages.Azure_GC_Template_RT_Not_Positive();
        }
    }

    public String verifyVirtualNetwork(
            String virtualNetworkName,
            String virtualNetworkResourceGroupName,
            String subnetName,
            boolean usePrivateIP,
            String resourceGroupName) {
        if (StringUtils.isNotBlank(virtualNetworkName)) {
            String finalResourceGroupName = resourceGroupName;
            if (StringUtils.isNotBlank(virtualNetworkResourceGroupName)) {
                finalResourceGroupName = virtualNetworkResourceGroupName;
            }
            Network virtualNetwork = getVirtualNetwork(virtualNetworkName, finalResourceGroupName);
            if (virtualNetwork == null) {
                return Messages.Azure_GC_Template_VirtualNetwork_NotFound(virtualNetworkName, finalResourceGroupName);
            }

            if (StringUtils.isBlank(subnetName)) {
                return Messages.Azure_GC_Template_subnet_Empty();
            } else {
                if (virtualNetwork.subnets().get(subnetName) == null) {
                    return Messages.Azure_GC_Template_subnet_NotFound(subnetName);
                }
            }
        } else if (StringUtils.isNotBlank(subnetName) || usePrivateIP) {
            return Messages.Azure_GC_Template_VirtualNetwork_Null_Or_Empty();
        }
        return Constants.OP_SUCCESS;
    }

    public String verifyVirtualMachineImage(
            String locationLabel,
            String storageAccountName,
            String imageTopLevelType,
            AzureVMAgentTemplate.ImageReferenceTypeClass imageReference,
            String builtInImage
    ) {
        if (imageTopLevelType == null || imageTopLevelType.equals(Constants.IMAGE_TOP_LEVEL_BASIC)) {
            if (StringUtils.isNotBlank(builtInImage)) {
                // As imageTopLevelType have to be null before save the template,
                // so the verifyImageParameters always return success.
                return Constants.OP_SUCCESS;
            } else {
                return Messages.Azure_GC_Template_BuiltIn_Not_Valid();
            }
        } else if ((imageReference.getType() == ImageReferenceType.UNKNOWN && StringUtils.isNotBlank(imageReference.getUri()))
                || imageReference.getType() == ImageReferenceType.CUSTOM) {
            try {
                // Custom image verification.  We must verify that the VM image
                // storage account is the same as the target storage account.
                // The URI for he storage account should be https://<storageaccountname>.
                // Parse that out and verify against the image storageAccountName

                // Check that the image string is a URI by attempting to create
                // a URI
                final URI u;
                try {
                    u = URI.create(imageReference.getUri());
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
        } else if (imageReference.getType() == ImageReferenceType.CUSTOM_IMAGE) {
            // checkExistenceById would be better here but it returns HTTP 405
            try {
                GenericResource r = azureClient.genericResources().getById(imageReference.getId());
                if (r == null) {
                    return Messages.Azure_GC_Template_ImageID_Not_Valid();
                }
                return Constants.OP_SUCCESS;
            } catch (Exception e) {
                return Messages.Azure_GC_Template_ImageID_Not_Valid();
            }
        } else if (imageReference.getType() == ImageReferenceType.GALLERY) {
            try {
                AzureResourceManager client = AzureClientUtil.getClient(azureCredentialsId, imageReference.getGallerySubscriptionId());
                if (Constants.VERSION_LATEST.equals(imageReference.getGalleryImageVersion())) {
                    PagedIterable<GalleryImageVersion> galleryImageVersions = client.galleryImageVersions()
                            .listByGalleryImage(imageReference.getGalleryResourceGroup(), imageReference.getGalleryName(), imageReference.getGalleryImageDefinition());
                    if (!galleryImageVersions.iterator().hasNext()) {
                        return Messages.Azure_GC_Template_Gallery_Image_Not_Found();
                    }
                } else {
                    GalleryImageVersion galleryImage = client.galleryImageVersions()
                            .getByGalleryImage(
                                    imageReference.getGalleryResourceGroup(),
                                    imageReference.getGalleryName(),
                                    imageReference.getGalleryImageDefinition(),
                                    imageReference.getGalleryImageVersion()
                            );
                    if (galleryImage == null) {
                        return Messages.Azure_GC_Template_Gallery_Image_Not_Found();
                    }
                }
            } catch (AzureCloudException ex) {
                return ex.getMessage();
            } catch (Exception e) {
                return Messages.Azure_GC_Template_Gallery_Image_Not_Found();
            }
            return Constants.OP_SUCCESS;
        } else {
            try {
                final String locationName = AzureUtil.getLocationNameByLabel(locationLabel);
                PagedIterable<VirtualMachinePublisher> publishers =
                        azureClient.virtualMachineImages().publishers().listByRegion(locationName);
                for (VirtualMachinePublisher publisher : publishers) {
                    if (!publisher.name().equalsIgnoreCase(imageReference.getPublisher())) {
                        continue;
                    }
                    for (VirtualMachineOffer offer : publisher.offers().list()) {
                        if (!offer.name().equalsIgnoreCase(imageReference.getOffer())) {
                            continue;
                        }
                        for (VirtualMachineSku sku : offer.skus().list()) {
                            if (!sku.name().equalsIgnoreCase(imageReference.getSku())) {
                                continue;
                            }
                            PagedIterable<VirtualMachineImage> images = sku.images().list();
                            if ((imageReference.getVersion().equalsIgnoreCase("latest")
                                    || StringUtils.isEmpty(imageReference.getVersion())) && images.stream().iterator().hasNext()) {
                                //the empty check is here to maintain backward compatibility
                                return Constants.OP_SUCCESS;
                            }
                            for (VirtualMachineImage vmImage : images) {
                                if (vmImage.version().equalsIgnoreCase(imageReference.getVersion())) {
                                    return Constants.OP_SUCCESS;
                                }
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

    public String verifyStorageAccountName(
            String resourceGroupName,
            String storageAccountName,
            String storageAccountType) {
        boolean isAvailable = false;
        try {
            if (StringUtils.isBlank(storageAccountType)) {
                return Messages.Azure_GC_Template_SA_Type_Null_Or_Empty();
            }

            CheckNameAvailabilityResult checkResult =
                    azureClient.storageAccounts().checkNameAvailability(storageAccountName);
            isAvailable = checkResult.isAvailable();
            if (!isAvailable && Reason.ACCOUNT_NAME_INVALID.equals(checkResult.reason())) {
                return Messages.Azure_GC_Template_SA_Not_Valid();
            } else if (!isAvailable) {
                /*if it's not available we need to check if it's already in our resource group*/
                StorageAccount checkAccount =
                        azureClient.storageAccounts().getByResourceGroup(resourceGroupName, storageAccountName);
                if (null == checkAccount) {
                    return Messages.Azure_GC_Template_SA_Already_Exists();
                } else {
                    /*if the storage account is already in out resource group, check whether they are the same type*/
                    if (checkAccount.skuType().name().toString().equalsIgnoreCase(storageAccountType)) {
                        return Constants.OP_SUCCESS;
                    } else {
                        return Messages.Azure_GC_Template_SA_Type_Not_Match(
                                storageAccountType, checkAccount.skuType().name().toString());
                    }
                }
            } else {
                return Constants.OP_SUCCESS;
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Verification failed for storage account name", e);
            return Messages.Azure_GC_Template_SA_Already_Exists();
        }
    }

    private static String verifyAdminPassword(String adminPassword) {
        if (StringUtils.isBlank(adminPassword)) {
            return Messages.Azure_GC_Template_PWD_Null_Or_Empty();
        }

        if (AzureUtil.isValidPassword(adminPassword)) {
            return Constants.OP_SUCCESS;
        } else {
            return Messages.Azure_GC_Template_PWD_Not_Valid();
        }
    }

    private static String verifyJvmOptions(String jvmOptions) {
        if (StringUtils.isBlank(jvmOptions) || AzureUtil.isValidJvmOption(jvmOptions)) {
            return Constants.OP_SUCCESS;
        } else {
            return Messages.Azure_GC_JVM_Option_Err();
        }
    }

    /**
     * Verify the validity of the image parameters (does not verify actual
     * values).
     */
    private static String verifyImageParameters(
            String imageTopLevelType,
            AzureVMAgentTemplate.ImageReferenceTypeClass imageReference,
            String builtInImage,
            String osType
    ) {
        if (imageTopLevelType == null || imageTopLevelType.equals(Constants.IMAGE_TOP_LEVEL_BASIC)) {
            // As imageTopLevelType have to be null before save the template,
            // so the verifyImageParameters always return success.
            if (StringUtils.isNotBlank(builtInImage)) {
                return Constants.OP_SUCCESS;
            } else {
                return Messages.Azure_GC_Template_BuiltIn_Not_Valid();
            }
        } else {
            if ((imageReference.getType() == ImageReferenceType.UNKNOWN
                    && (StringUtils.isNotBlank(imageReference.getUri()) && StringUtils.isNotBlank(osType)))
                    || imageReference.getType() == ImageReferenceType.CUSTOM) {
                // Check that the image string is a URI by attempting to create a URI
                try {
                    URI.create(imageReference.getUri());
                } catch (Exception e) {
                    return Messages.Azure_GC_Template_ImageURI_Not_Valid();
                }
                return Constants.OP_SUCCESS;
            } else if (imageReference.getType() == ImageReferenceType.CUSTOM_IMAGE &&
                    StringUtils.isNotBlank(imageReference.getId())) {
                return Constants.OP_SUCCESS;
            } else if (imageReference.getType() == ImageReferenceType.REFERENCE
                    && StringUtils.isNotBlank(imageReference.getPublisher())
                    && StringUtils.isNotBlank(imageReference.getOffer())
                    && StringUtils.isNotBlank(imageReference.getSku())
                    && StringUtils.isNotBlank(imageReference.getVersion())) {
                return Constants.OP_SUCCESS;
            } else if (imageReference.getType() == ImageReferenceType.GALLERY
                    && StringUtils.isNotBlank(imageReference.getGalleryName())
                    && StringUtils.isNotBlank(imageReference.getGalleryImageDefinition())
                    && StringUtils.isNotBlank(imageReference.getGalleryImageVersion())
                    && StringUtils.isNotBlank(imageReference.getGalleryResourceGroup())) {
                return Constants.OP_SUCCESS;
            } else {
                return Messages.Azure_GC_Template_ImageReference_Not_Valid(
                        "Image parameters should not be blank.");
            }
        }
    }

    public String verifyNSG(
            String resourceGroupName,
            String nsgName) {
        if (StringUtils.isNotBlank(nsgName)) {
            try {
                NetworkSecurityGroup nsg = azureClient.networkSecurityGroups().getByResourceGroup(resourceGroupName, nsgName);
                if (nsg == null) {
                    return Messages.Azure_GC_Template_NSG_NotFound(nsgName);
                }
            } catch (Exception e) {
                return Messages.Azure_GC_Template_NSG_NotFound(nsgName);
            }
        }
        return Constants.OP_SUCCESS;
    }

    /**
     * Create Azure resource Group.
     *
     */
    private void createAzureResourceGroup(
            AzureResourceManager azureClient, String locationName, String resourceGroupName,
            String cloudName) throws AzureCloudException {
        try {
            azureClient.resourceGroups()
                    .define(resourceGroupName)
                    .withRegion(locationName)
                    .withTag(Constants.AZURE_JENKINS_TAG_NAME, Constants.AZURE_JENKINS_TAG_VALUE)
                    .withTag(Constants.AZURE_CLOUD_TAG_NAME, cloudName)
                    .create();
        } catch (Exception e) {
            throw AzureCloudException.create(
                    String.format(
                            " Failed to create resource group with group name %s, location %s",
                            resourceGroupName, locationName),
                    e);
        }
    }

    /**
     * Create storage Account.
     *
     */
    private void createStorageAccount(
            AzureResourceManager azureClient,
            String targetStorageAccountType,
            String targetStorageAccount,
            String location,
            String resourceGroupName,
            String templateName, List<AzureTagPair> cloudTags
    ) throws AzureCloudException {
        try {
            // Get storage account before creating.
            // Reuse existing to prevent failure.
            try {
                azureClient.storageAccounts().getByResourceGroup(resourceGroupName, targetStorageAccount);
            } catch (ManagementException e) {
                if (e.getResponse().getStatusCode() == 404) {
                    SkuName skuName = SkuName.fromString(targetStorageAccountType);
                    azureClient.storageAccounts().define(targetStorageAccount)
                            .withRegion(location)
                            .withExistingResourceGroup(resourceGroupName)
                            .withTags(getTags(templateName, cloudTags))
                            .withSku(StorageAccountSkuType.fromSkuName(skuName))
                            .create();
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw AzureCloudException.create(
                    String.format(
                            "Failed to create storage account with account name %s, location %s, "
                                    + "resourceGroupName %s",
                            targetStorageAccount, location, resourceGroupName),
                    e);
        }
    }

    private Map<String, String> getTags(String templateName, List<AzureTagPair> cloudTags) {
        List<AzureTagPair> tmpTags = cloudTags;
        if (tmpTags == null) {
            tmpTags = new ArrayList<>();
        }

        Map<String, String> tags = tmpTags
                .stream()
                .collect(Collectors.toMap(AzureTagPair::getName, AzureTagPair::getValue));

        tags.put(Constants.AZURE_JENKINS_TAG_NAME, Constants.AZURE_JENKINS_TAG_VALUE);
        tags.put(Constants.AZURE_TEMPLATE_TAG_NAME, templateName);
        return tags;
    }

    /**
     * Get StorageAccount by resourceGroup name and storageAccount name.
     *
     * @return StorageAccount object
     */
    private StorageAccount getStorageAccount(
            AzureResourceManager azureClient, String targetStorageAccount, String resourceGroupName)
            throws AzureCloudException {
        try {
            return azureClient.storageAccounts().getByResourceGroup(resourceGroupName, targetStorageAccount);
        } catch (Exception e) {
            throw AzureCloudException.create(e);
        }
    }

    /**
     * Get the blob endpoint suffix for , it's like ".blob.core.windows.net/" for public azure
     * or ".blob.core.chinacloudapi.cn" for Azure China.
     *
     */
    public static String getBlobEndpointSuffixForTemplate(StorageAccount storageAccount) {
        return getBlobEndPointSuffix(
                storageAccount, Constants.BLOB, Constants.BLOB_ENDPOINT_PREFIX, Constants.FWD_SLASH);
    }

    /**
     * Get the blob endpoint suffix for constructing CloudStorageAccount  , it's like "core.windows.net"
     * or "core.chinacloudapi.cn" for AzureChina.
     *
     */
    public static String getBlobEndpointSuffixForCloudStorageAccount(StorageAccount storageAccount) {
        return getBlobEndPointSuffix(storageAccount, Constants.BLOB_ENDPOINT_SUFFIX_STARTKEY, "", "");
    }

    /**
     * Get the blob endpoint
     *
     * @param startKey       uses to get the start position of sub string,
     *                       if it's null or empty then whole input string will be used
     * @param prefix         the prefix of substring will be added, if it's null or empty then it will not be added'
     * @param suffix         the suffix will be append to substring if substring doesn't contain it,
     *                       if it's null or empty then it will not be added
     * @return endpointSuffix
     */
    private static String getBlobEndPointSuffix(
            StorageAccount storageAccount, String startKey, String prefix, String suffix) {
        String endpointSuffix = null;
        if (storageAccount != null) {
            String blobUri = storageAccount.endPoints().primary().blob().toLowerCase();
            endpointSuffix = getSubString(blobUri, startKey, prefix, suffix);
        }

        return endpointSuffix;
    }


    /**
     * Get substring with startKey,  endSuffix and prefix.
     *
     * @param startKey startKey used to get the start position of string,
     *                 if it's null or empty then whole input string will be used
     * @param prefix   the prefix of substring will be added, if it's null or empty then it will not be added'
     * @param suffix   the suffix will be append to substring if substring doesn't contain it,
     *                 if it's null or empty then it will not be added
     */
    private static String getSubString(String uri, String startKey, String prefix, String suffix) {
        String subString = null;
        if (StringUtils.isNotBlank(uri)) {
            if (StringUtils.isNotEmpty(startKey) && uri.contains(startKey)) {
                subString = uri.substring(uri.indexOf(startKey));
            } else {
                subString = uri;
            }
            subString = StringUtils.isNotEmpty(prefix) ? prefix + subString : subString;
            if (StringUtils.isNotEmpty(suffix)
                    && subString.lastIndexOf(suffix) < subString.length() - suffix.length()) {
                subString = subString + suffix;
            }
        }
        return subString;
    }

    /**
     * Get CloudStorageAccount.
     *
     * @return CloudStorageAccount object
     */
    public static BlobServiceClient getCloudStorageAccount(StorageAccount storageAccount) throws AzureCloudException {
        List<StorageAccountKey> storageKeys = storageAccount.getKeys();
        if (storageKeys.isEmpty()) {
            throw AzureCloudException.create("AzureVMManagementServiceDelegate: uploadCustomScript: "
                    + "Exception occurred while fetching the storage account key");
        }

        String storageAccountKey = storageKeys.get(0).value();
        String blobSuffix = storageAccount.endPoints().primary().blob().toLowerCase();
        LOGGER.log(Level.INFO,
                "AzureVMManagementServiceDelegate: getCloudStorageAccount: "
                        + "the suffix for construct CloudStorageCloud is {0}",
                blobSuffix);
        if (StringUtils.isEmpty(blobSuffix)) {
            throw AzureCloudException.create("AzureVMManagementServiceDelegate: getCloudStorageAccount:"
                    + "Exception occurred while getting blobSuffix, it's empty'");
        }
        try {
            return new BlobServiceClientBuilder()
                    .credential(new StorageSharedKeyCredential(storageAccount.name(), storageAccountKey))
                    .endpoint(blobSuffix)
                    .httpClient(HttpClientRetriever.get())
                    .buildClient();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "AzureVMManagementServiceDelegate: GetCloudStorageAccount: "
                            + "unable to get CloudStorageAccount with storage account {0} and blob Suffix {1}",
                    new Object[]{storageAccount.name(), blobSuffix});
            throw AzureCloudException.create(e);
        }
    }

    /**
     * Get CloudBlobContainer.
     *
     */
    public static BlobContainerClient getCloudBlobContainer(
            BlobServiceClient account, String containerName) throws AzureCloudException {
        try {
            BlobContainerClient blobContainerClient = account.getBlobContainerClient(containerName);
            if (!blobContainerClient.exists()) {
                blobContainerClient.create();
            }
            return blobContainerClient;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "AzureVMManagementServiceDelegate: getCloudBlobContainer: "
                            + "unable to get CloudStorageAccount with container name {0}",
                    new Object[]{containerName});
            throw AzureCloudException.create(e);
        }
    }

    public BlobContainerClient getCloudBlobContainer(
            AzureResourceManager azureClient, String resourceGroupName, String targetStorageAccount, String blobContainerName)
            throws AzureCloudException {

        StorageAccount storageAccount;
        try {
            storageAccount = azureClient.storageAccounts()
                    .getByResourceGroup(resourceGroupName, targetStorageAccount);
        } catch (Exception e) {
            throw AzureCloudException.create(e);
        }

        BlobServiceClient account = getCloudStorageAccount(storageAccount);
        return getCloudBlobContainer(account, blobContainerName);
    }

    private AzureVMManagementServiceDelegate(AzureResourceManager azureClient, String azureCredentialsId) {
        this.azureClient = azureClient;
        this.azureCredentialsId = azureCredentialsId;
    }
}
