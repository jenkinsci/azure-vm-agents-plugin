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

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.AvailabilitySet;
import com.microsoft.azure.management.storage.SkuName;
import com.microsoft.azure.management.storage.StorageAccount;
import com.microsoft.azure.vmagent.builders.AdvancedImage;
import com.microsoft.azure.vmagent.builders.AdvancedImageBuilder;
import com.microsoft.azure.vmagent.builders.Availability;
import com.microsoft.azure.vmagent.builders.AvailabilityBuilder;
import com.microsoft.azure.vmagent.builders.BuiltInImage;
import com.microsoft.azure.vmagent.builders.BuiltInImageBuilder;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.util.AzureClientHolder;
import com.microsoft.azure.vmagent.util.AzureClientUtil;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.FailureStage;
import com.microsoft.jenkins.azurecommons.core.credentials.TokenCredentialData;
import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.slaves.RetentionStrategy;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.servlet.ServletException;
import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class defines the configuration of Azure instance templates.
 *
 * @author Suresh Nallamilli
 */
public class AzureVMAgentTemplate implements Describable<AzureVMAgentTemplate>, Serializable {
    private static final long serialVersionUID = 1574325691L;

    public static class ImageReferenceTypeClass implements Serializable {
        private String uri;
        private String id;
        private String publisher;
        private String offer;
        private String sku;
        private String version;
        private String galleryName;
        private String galleryImageDefinition;
        private String galleryImageVersion;
        private String gallerySubscriptionId;
        private String galleryResourceGroup;
        private ImageReferenceType type;

        @DataBoundConstructor
        public ImageReferenceTypeClass(
                String uri,
                String id,
                String publisher,
                String offer,
                String sku,
                String version,
                String galleryName,
                String galleryImageDefinition,
                String galleryImageVersion,
                String gallerySubscriptionId,
                String galleryResourceGroup) {
            this.uri = uri;
            this.id = id;
            this.publisher = publisher;
            this.offer = offer;
            this.sku = sku;
            this.version = version;
            this.galleryName = galleryName;
            this.galleryImageDefinition = galleryImageDefinition;
            this.galleryImageVersion = galleryImageVersion;
            this.gallerySubscriptionId = gallerySubscriptionId;
            this.galleryResourceGroup = galleryResourceGroup;

            this.type = determineType();
        }

        private ImageReferenceTypeClass() {
            // used for readResolve to maintain compatibility
        }

        private ImageReferenceType determineType() {
            if (Util.fixEmpty(uri) != null) {
                return ImageReferenceType.CUSTOM;
            }
            if (Util.fixEmpty(id) != null) {
                return ImageReferenceType.CUSTOM_IMAGE;
            }
            if (Util.fixEmpty(galleryName) != null) {
                return ImageReferenceType.GALLERY;
            }
            return ImageReferenceType.REFERENCE;
        }

        public ImageReferenceType getType() {
            return type;
        }

        public String getUri() {
            return uri;
        }

        public String getId() {
            return id;
        }

        public String getPublisher() {
            return publisher;
        }

        public String getOffer() {
            return offer;
        }

        public String getSku() {
            return sku;
        }

        public String getVersion() {
            return version;
        }

        public String getGalleryName() {
            return galleryName;
        }

        public String getGalleryImageDefinition() {
            return galleryImageDefinition;
        }

        public String getGalleryImageVersion() {
            return galleryImageVersion;
        }

        public String getGallerySubscriptionId() {
            return gallerySubscriptionId;
        }

        public String getGalleryResourceGroup() {
            return galleryResourceGroup;
        }
    }

    public static class AvailabilityTypeClass implements Serializable {
        private String availabilitySet;

        @DataBoundConstructor
        public  AvailabilityTypeClass(String availabilitySet) {
            this.availabilitySet = Util.fixEmpty(availabilitySet);
        }

        public String getAvailabilitySet() {
            return availabilitySet;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentTemplate.class.getName());

    private static final int GEN_STORAGE_ACCOUNT_UID_LENGTH = 22;

    // General Configuration
    private final String templateName;

    private final String templateDesc;

    private final String labels;

    private final String location;

    private final AvailabilityTypeClass availabilityType;

    private final String virtualMachineSize;

    private String storageAccountNameReferenceType;

    private transient String storageAccountName;

    private String diskType;

    private int osDiskSize;

    private String newStorageAccountName;

    private String existingStorageAccountName;

    private String storageAccountType;

    private final int noOfParallelJobs;

    private Node.Mode usageMode;

    private final boolean shutdownOnIdle;

    // Image Configuration
    private String imageTopLevelType;

    private ImageReferenceTypeClass imageReference;

    private String builtInImage;

    private boolean installGit;

    private boolean installMaven;

    private boolean installDocker;

    private final String osType;

    private final String agentLaunchMethod;

    private boolean preInstallSsh;

    private final String initScript;

    private final String credentialsId;

    private final String agentWorkspace;

    private int retentionTimeInMin;

    private String virtualNetworkName;

    private String virtualNetworkResourceGroupName;

    private String subnetName;

    private boolean usePrivateIP;

    private final String nsgName;

    private final String jvmOptions;

    // Indicates whether the template is disabled.
    // If disabled, will not attempt to verify or use
    private final boolean templateDisabled;

    private String templateStatusDetails;

    private transient AzureVMCloud azureCloud;

    private transient Set<LabelAtom> labelDataSet;

    private boolean templateVerified;

    private transient ProvisionStrategy templateProvisionStrategy;

    private boolean executeInitScriptAsRoot;

    private boolean doNotUseMachineIfInitFails;

    private final boolean enableMSI;

    private RetentionStrategy retentionStrategy;


    // deprecated fields
    private transient boolean isInstallDocker;
    private transient boolean isInstallMaven;
    private transient boolean isInstallGit;

    private transient String image;
    private transient String imageId;
    private transient String imagePublisher;
    private transient String imageOffer;
    private transient String imageSku;
    private transient String imageVersion;

    private transient String galleryName;
    private transient String galleryImageDefinition;
    private transient String galleryImageVersion;
    private transient String gallerySubscriptionId;
    private transient String galleryResourceGroup;


    @DataBoundConstructor
    public AzureVMAgentTemplate(
            String templateName,
            String templateDesc,
            String labels,
            String location,
            AvailabilityTypeClass availabilityType,
            String virtualMachineSize,
            String storageAccountNameReferenceType,
            String storageAccountType,
            String newStorageAccountName,
            String existingStorageAccountName,
            String diskType,
            int osDiskSize,
            String noOfParallelJobs,
            String usageMode,
            String builtInImage,
            boolean installGit,
            boolean installMaven,
            boolean installDocker,
            String osType,
            String imageTopLevelType,
            ImageReferenceTypeClass imageReference,
            String agentLaunchMethod,
            boolean preInstallSsh,
            String initScript,
            String credentialsId,
            String virtualNetworkName,
            String virtualNetworkResourceGroupName,
            String subnetName,
            boolean usePrivateIP,
            String nsgName,
            String agentWorkspace,
            String jvmOptions,
            RetentionStrategy retentionStrategy,
            boolean shutdownOnIdle,
            boolean templateDisabled,
            boolean executeInitScriptAsRoot,
            boolean doNotUseMachineIfInitFails,
            boolean enableMSI) {
        this.templateName = templateName;
        this.templateDesc = templateDesc;
        this.labels = labels;
        this.location = location;
        this.availabilityType = availabilityType;
        this.virtualMachineSize = virtualMachineSize;
        this.storageAccountType = storageAccountType;
        this.storageAccountName = getStorageAccountName(
                storageAccountNameReferenceType, newStorageAccountName, existingStorageAccountName);
        this.newStorageAccountName = newStorageAccountName;
        this.existingStorageAccountName = existingStorageAccountName;
        this.storageAccountNameReferenceType = storageAccountNameReferenceType;
        this.diskType = diskType;
        this.osDiskSize = osDiskSize;

        if (StringUtils.isBlank(noOfParallelJobs) || !noOfParallelJobs.matches(Constants.REG_EX_DIGIT)
                || noOfParallelJobs.
                trim().equals("0")) {
            this.noOfParallelJobs = 1;
        } else {
            this.noOfParallelJobs = Integer.parseInt(noOfParallelJobs);
        }
        setUsageMode(usageMode);
        this.imageTopLevelType = imageTopLevelType;
        this.builtInImage = builtInImage;
        this.installDocker = installDocker;
        this.installGit = installGit;
        this.installMaven = installMaven;
        this.imageReference = imageReference;
        this.osType = osType;
        this.shutdownOnIdle = shutdownOnIdle;
        this.initScript = initScript;
        this.agentLaunchMethod = agentLaunchMethod;
        this.preInstallSsh = preInstallSsh;
        this.credentialsId = credentialsId;
        this.virtualNetworkName = virtualNetworkName;
        this.virtualNetworkResourceGroupName = virtualNetworkResourceGroupName;
        this.subnetName = subnetName;
        this.usePrivateIP = usePrivateIP;
        this.nsgName = nsgName;
        this.agentWorkspace = agentWorkspace;
        this.jvmOptions = jvmOptions;
        this.executeInitScriptAsRoot = executeInitScriptAsRoot;
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
        this.enableMSI = enableMSI;
        this.templateDisabled = templateDisabled;
        this.templateStatusDetails = "";

        // Reset the template verification status.
        this.templateProvisionStrategy = new ProvisionStrategy();
        this.retentionStrategy = retentionStrategy;

        // Forms data which is not persisted
        labelDataSet = Label.parse(labels);
    }

    /**
     * Used by jelly for loading the data, not written to.
     */
    @Restricted(NoExternalUse.class)
    public String getGallerySubscriptionId() {
        return imageReference != null ? imageReference.getGallerySubscriptionId() : null;
    }

    /**
     * Used by jelly for loading the data, not written to.
     */
    @Restricted(NoExternalUse.class)
    public String getGalleryResourceGroup() {
        return imageReference != null ? imageReference.getGalleryResourceGroup() : null;
    }

    /**
     * Used by jelly for loading the data, not written to.
     */
    @Restricted(NoExternalUse.class)
    public String getGalleryName() {
        return imageReference != null ? imageReference.getGalleryName() : null;
    }

    /**
     * Used by jelly for loading the data, not written to.
     */
    @Restricted(NoExternalUse.class)
    public String getGalleryImageDefinition() {
        return imageReference != null ? imageReference.getGalleryImageDefinition() : null;
    }

    /**
     * Used by jelly for loading the data, not written to.
     */
    @Restricted(NoExternalUse.class)
    public String getGalleryImageVersion() {
        return imageReference != null ? imageReference.getGalleryImageVersion() : null;
    }

    /**
     * Used by jelly for loading the data, not written to.
     */
    @Restricted(NoExternalUse.class)
    public String getId() {
        return imageReference != null ? imageReference.getId() : null;
    }

    /**
     * Used by jelly for loading the data, not written to.
     */
    @Restricted(NoExternalUse.class)
    public String getUri() {
        return imageReference != null ? imageReference.getUri() : null;
    }

    /**
     * Used by jelly for loading the data, not written to.
     */
    @Restricted(NoExternalUse.class)
    public String getPublisher() {
        return imageReference != null ? imageReference.getPublisher() : null;
    }

    /**
     * Used by jelly for loading the data, not written to.
     */
    @Restricted(NoExternalUse.class)
    public String getOffer() {
        return imageReference != null ? imageReference.getOffer() : null;
    }

    /**
     * Used by jelly for loading the data, not written to.
     */
    @Restricted(NoExternalUse.class)
    public String getSku() {
        return imageReference != null ? imageReference.getSku() : null;
    }

    /**
     * Used by jelly for loading the data, not written to.
     */
    @Restricted(NoExternalUse.class)
    public String getVersion() {
        return imageReference != null ? imageReference.getVersion() : null;
    }

    /**
     * Used by jelly for loading the data, not written to.
     */
    @Restricted(NoExternalUse.class)
    public String getAvailabilitySet() {
        return availabilityType != null ? availabilityType.getAvailabilitySet() : null;
    }

    public static Map<String, Object> getTemplateProperties(AzureVMAgentTemplate template) {
        Map<String, Object> templateProperties = new HashMap<>();
        String builtInImage = template.getBuiltInImage();
        Map<String, String> defaultProperties =
                AzureVMManagementServiceDelegate.DEFAULT_IMAGE_PROPERTIES.get(builtInImage);
        boolean isBasic = template.isTopLevelType(Constants.IMAGE_TOP_LEVEL_BASIC);
        String imageSkuName =
                template.isInstallDocker() ? Constants.DEFAULT_DOCKER_IMAGE_SKU : Constants.DEFAULT_IMAGE_SKU;

        templateProperties.put("imageId",
                isBasic ? defaultProperties.get(Constants.DEFAULT_IMAGE_ID)
                        : template.getImageReference().getId());
        templateProperties.put("imagePublisher",
                isBasic ? defaultProperties.get(Constants.DEFAULT_IMAGE_PUBLISHER)
                        : template.getImageReference().getPublisher());
        templateProperties.put("imageOffer",
                isBasic ? defaultProperties.get(Constants.DEFAULT_IMAGE_OFFER)
                        : template.getImageReference().getOffer());
        templateProperties.put("imageSku",
                isBasic ? defaultProperties.get(imageSkuName) : template.getImageReference().getSku());
        templateProperties.put("imageVersion",
                isBasic ? defaultProperties.get(Constants.DEFAULT_IMAGE_VERSION)
                        : template.getImageReference().getVersion());
        templateProperties.put("galleryName",
                isBasic ? defaultProperties.get(Constants.DEFAULT_GALLERY_NAME)
                        : template.getImageReference().getGalleryName());
        templateProperties.put("galleryImageDefinition",
                isBasic ? defaultProperties.get(Constants.DEFAULT_GALLERY_IMAGE_DEFINITION)
                        : template.getImageReference().getGalleryImageDefinition());
        templateProperties.put("galleryImageVersion",
                isBasic ? defaultProperties.get(Constants.DEFAULT_GALLERY_IMAGE_VERSION)
                        : template.getImageReference().getGalleryImageVersion());
        templateProperties.put("gallerySubscriptionId",
                isBasic ? defaultProperties.get(Constants.DEFAULT_GALLERY_SUBSCRIPTION_ID)
                : template.getImageReference().getGallerySubscriptionId());
        templateProperties.put("galleryResourceGroup",
                isBasic ? defaultProperties.get(Constants.DEFAULT_GALLERY_RESOURCE_GROUP)
                        : template.getImageReference().getGalleryResourceGroup());
        templateProperties.put("osType",
                isBasic ? defaultProperties.get(Constants.DEFAULT_OS_TYPE) : template.getOsType());
        templateProperties.put("agentLaunchMethod",
                isBasic ? defaultProperties.get(Constants.DEFAULT_LAUNCH_METHOD) : template.getAgentLaunchMethod());
        templateProperties.put("initScript",
                isBasic ? getBasicInitScript(template) : template.getInitScript());
        templateProperties.put("virtualNetworkName",
                isBasic ? "" : template.getVirtualNetworkName());
        templateProperties.put("virtualNetworkResourceGroupName",
                isBasic ? "" : template.getVirtualNetworkResourceGroupName());
        templateProperties.put("subnetName",
                isBasic ? "" : template.getSubnetName());
        templateProperties.put("usePrivateIP",
                isBasic ? false : template.getUsePrivateIP());
        templateProperties.put("nsgName",
                isBasic ? "" : template.getNsgName());
        templateProperties.put("jvmOptions",
                isBasic ? "" : template.getJvmOptions());
        templateProperties.put("noOfParallelJobs",
                isBasic ? 1 : template.getNoOfParallelJobs());
        templateProperties.put("templateDisabled",
                isBasic ? false : template.isTemplateDisabled());
        templateProperties.put("executeInitScriptAsRoot",
                isBasic ? true : template.getExecuteInitScriptAsRoot());
        templateProperties.put("doNotUseMachineIfInitFails",
                isBasic ? true : template.getDoNotUseMachineIfInitFails());
        templateProperties.put("enableMSI",
                isBasic ? false : template.isEnableMSI());

        return templateProperties;
    }

    public static String getBasicInitScript(AzureVMAgentTemplate template) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            stringBuilder.append(
                    AzureVMManagementServiceDelegate.PRE_INSTALLED_TOOLS_SCRIPT
                            .get(template.getBuiltInImage()).get(Constants.INSTALL_JAVA));
            if (template.isInstallMaven()) {
                stringBuilder.append(getSeparator(template.getOsType()));
                stringBuilder.append(
                        AzureVMManagementServiceDelegate.PRE_INSTALLED_TOOLS_SCRIPT
                                .get(template.getBuiltInImage()).get(Constants.INSTALL_MAVEN));
            }
            if (template.isInstallGit()) {
                stringBuilder.append(getSeparator(template.getOsType()));
                stringBuilder.append(
                        AzureVMManagementServiceDelegate.PRE_INSTALLED_TOOLS_SCRIPT
                                .get(template.getBuiltInImage()).get(Constants.INSTALL_GIT));
            }
            if (template.getBuiltInImage().equals(Constants.UBUNTU_1604_LTS) && template.isInstallDocker()) {
                stringBuilder.append(getSeparator(template.getOsType()));
                stringBuilder.append(
                        AzureVMManagementServiceDelegate.PRE_INSTALLED_TOOLS_SCRIPT
                                .get(template.getBuiltInImage()).get(Constants.INSTALL_DOCKER)
                                .replace("${ADMIN}",
                                        template.getVMCredentials().getUsername()));
            }
            return stringBuilder.toString();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "AzureVMTemplate: getBasicInitScript: Get pre-installed tools script {0} failed.",
                    e);
            return stringBuilder.toString();
        }
    }

    public static String getSeparator(String osType) {
        if (osType.equals(Constants.OS_TYPE_WINDOWS)) {
            return "\r\n";
        } else {
            return "\n";
        }
    }

    @SuppressWarnings("unused") // called by stapler in jelly
    public boolean isType(String type) {
        if (type == null || imageReference == null) {
            return false;
        }

        return imageReference.type.getName().equals(type);
    }

    public boolean isTopLevelType(String type) {
        if (this.imageTopLevelType == null && type.equals(Constants.IMAGE_TOP_LEVEL_BASIC)) {
            return true;
        }
        return type != null && type.equalsIgnoreCase(this.imageTopLevelType);
    }

    @SuppressWarnings("ConstantConditions") // fields are assigned by xstream
    private Object readResolve() {
        labelDataSet = Label.parse(labels);
        templateProvisionStrategy = new ProvisionStrategy();

        if (StringUtils.isBlank(storageAccountType)) {
            storageAccountType = SkuName.STANDARD_LRS.toString();
        }

        if (StringUtils.isBlank(newStorageAccountName) && StringUtils.isBlank(existingStorageAccountName)
                && StringUtils.isNotBlank(storageAccountName)) {
            newStorageAccountName = storageAccountName;
            storageAccountNameReferenceType = "new";
        }
        storageAccountName = getStorageAccountName(
                storageAccountNameReferenceType, newStorageAccountName, existingStorageAccountName);

        if (StringUtils.isBlank(diskType)) {
            diskType = Constants.DISK_UNMANAGED;
        }

        if (retentionStrategy == null) {
            retentionStrategy = new AzureVMCloudRetensionStrategy(0);
        }

        if (isInstallDocker) {
            installDocker = true;
        }

        if (isInstallGit) {
            installGit = true;
        }

        if (isInstallMaven) {
            installMaven = true;
        }

        if (imageReference == null) {
            imageReference = new ImageReferenceTypeClass();
        }

        if (Util.fixNull(image) != null) {
            imageReference.uri = image;
        }

        if (Util.fixNull(imageId) != null) {
            imageReference.id = imageId;
        }

        if (Util.fixNull(imagePublisher) != null) {
            imageReference.publisher = imagePublisher;
        }

        if (Util.fixNull(imageOffer) != null) {
            imageReference.offer = imageOffer;
        }

        if (Util.fixNull(imageSku) != null) {
            imageReference.sku = imageSku;
        }

        if (Util.fixNull(imageVersion) != null) {
            imageReference.version = imageVersion;
        }

        if (Util.fixNull(galleryName) != null) {
            imageReference.galleryName = galleryName;
        }

        if (Util.fixNull(galleryImageDefinition) != null) {
            imageReference.galleryImageDefinition = galleryImageDefinition;
        }

        if (Util.fixNull(galleryImageVersion) != null) {
            imageReference.galleryImageVersion = galleryImageVersion;
        }

        if (Util.fixNull(gallerySubscriptionId) != null) {
            imageReference.gallerySubscriptionId = gallerySubscriptionId;
        }

        if (Util.fixNull(galleryResourceGroup) != null) {
            imageReference.galleryResourceGroup = galleryResourceGroup;
        }

        if (imageReference.type == null) {
            imageReference.type = imageReference.determineType();
        }

        if (StringUtils.isBlank(imageTopLevelType)) {
            if (imageReference != null  && (StringUtils.isNotBlank(imageReference.getUri())
                    || StringUtils.isNotBlank(imageReference.getId())
                    || StringUtils.isNotBlank(imageReference.getOffer())
                    || StringUtils.isNotBlank(imageReference.getSku())
                    || StringUtils.isNotBlank(imageReference.getPublisher()))) {
                imageTopLevelType = Constants.IMAGE_TOP_LEVEL_ADVANCED;
            } else {
                imageTopLevelType = Constants.IMAGE_TOP_LEVEL_BASIC;
            }
            builtInImage = Constants.WINDOWS_SERVER_2016;
        }

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

    public String getStorageAccountType() {
        return StringUtils.isBlank(storageAccountType) ? SkuName.STANDARD_LRS.toString() : storageAccountType;
    }

    public String getStorageAccountName() {
        return storageAccountName;
    }

    public static String getStorageAccountName(String type, String newName, String existingName) {
        //type maybe null in this version, so we can guess according to whether newName is blank or not
        if (StringUtils.isBlank(type) && StringUtils.isNotBlank(newName)
                || StringUtils.isNotBlank(type) && type.equalsIgnoreCase("new")) {
            return newName;
        }
        return existingName;
    }

    public String getDiskType() {
        return diskType;
    }

    public int getOsDiskSize() {
        return osDiskSize;
    }

    public String getStorageAccountNameReferenceType() {
        return storageAccountNameReferenceType;
    }

    public void setStorageAccountName(String storageAccountName) {
        this.storageAccountName = storageAccountName;
    }

    public String getNewStorageAccountName() {
        return newStorageAccountName;
    }

    public String getExistingStorageAccountName() {
        return existingStorageAccountName;
    }

    public Node.Mode getUseAgentAlwaysIfAvail() {
        return (usageMode == null) ? Node.Mode.NORMAL : usageMode;
    }

    public boolean isStorageAccountNameReferenceTypeEquals(String type) {
        if (this.storageAccountNameReferenceType == null && type.equalsIgnoreCase("new")) {
            return true;
        }
        return type != null && type.equalsIgnoreCase(this.storageAccountNameReferenceType);
    }

    public String getUsageMode() {
        return getUseAgentAlwaysIfAvail().getDescription();
    }

    public void setUsageMode(String mode) {
        Node.Mode val = Node.Mode.NORMAL;
        for (Node.Mode m : hudson.Functions.getNodeModes()) {
            if (mode.equalsIgnoreCase(m.getDescription())) {
                val = m;
                break;
            }
        }
        this.usageMode = val;
    }

    public boolean isShutdownOnIdle() {
        return shutdownOnIdle;
    }

    public AvailabilityTypeClass getAvailabilityType() {
        return availabilityType;
    }

    public ImageReferenceTypeClass getImageReference() {
        return imageReference;
    }

    public String getImageTopLevelType() {
        return imageTopLevelType;
    }

    public String getBuiltInImage() {
        return builtInImage;
    }

    public boolean isInstallGit() {
        return installGit;
    }

    public boolean isInstallMaven() {
        return installMaven;
    }

    public boolean isInstallDocker() {
        return installDocker;
    }

    public String getOsType() {
        return osType;
    }

    public boolean getPreInstallSsh() {
        return preInstallSsh;
    }

    public String getInitScript() {
        return initScript;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public StandardUsernamePasswordCredentials getVMCredentials() throws AzureCloudException {
        return AzureUtil.getCredentials(credentialsId);
    }

    public String getVirtualNetworkName() {
        return virtualNetworkName;
    }

    public void setVirtualNetworkName(String virtualNetworkName) {
        this.virtualNetworkName = virtualNetworkName;
    }

    public String getVirtualNetworkResourceGroupName() {
        return this.virtualNetworkResourceGroupName;
    }

    public String getSubnetName() {
        return subnetName;
    }

    public void setSubnetName(String subnetName) {
        this.subnetName = subnetName;
    }

    public boolean getUsePrivateIP() {
        return usePrivateIP;
    }

    public String getNsgName() {
        return nsgName;
    }

    public String getAgentWorkspace() {
        return agentWorkspace;
    }

    public String getJvmOptions() {
        return jvmOptions;
    }

    public AzureVMCloud retrieveAzureCloudReference() {
        return azureCloud;
    }

    public void addAzureCloudReference(AzureVMCloud cloud) {
        azureCloud = cloud;
        if (StringUtils.isBlank(storageAccountName)) {
            storageAccountName = AzureVMAgentTemplate.generateUniqueStorageAccountName(
                    azureCloud.getResourceGroupName(), templateName);
            newStorageAccountName = storageAccountName;
            //if storageAccountNameReferenceType equals existing, we help to choose new directly
            storageAccountNameReferenceType = "new";
        }
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

    public String getAgentLaunchMethod() {
        return agentLaunchMethod;
    }

    public ProvisionStrategy getTemplateProvisionStrategy() {
        return templateProvisionStrategy;
    }

    public void setTemplateProvisionStrategy(ProvisionStrategy templateProvisionStrategy) {
        this.templateProvisionStrategy = templateProvisionStrategy;
    }

    /**
     * Returns true if this template is disabled and cannot be used, false
     * otherwise.
     *
     * @return True/false
     */
    public boolean isTemplateDisabled() {
        return this.templateDisabled;
    }

    /**
     * Is the template set up and verified?
     *
     * @return True if the template is set up and verified, false otherwise.
     */
    public boolean isTemplateVerified() {
        return templateVerified;
    }

    /**
     * Set the template verification status.
     *
     * @param isValid True for verified + valid, false otherwise.
     */
    public void setTemplateVerified(boolean isValid) {
        templateVerified = isValid;
    }

    public String getTemplateStatusDetails() {
        return templateStatusDetails;
    }

    public void setTemplateStatusDetails(String templateStatusDetails) {
        this.templateStatusDetails = templateStatusDetails;
    }

    public String getResourceGroupName() {
        // Allow overriding?
        return retrieveAzureCloudReference().getResourceGroupName();
    }

    public int getRetentionTimeInMin() {
        return retentionTimeInMin;
    }

    public boolean getExecuteInitScriptAsRoot() {
        return executeInitScriptAsRoot;
    }

    public void setExecuteInitScriptAsRoot(boolean executeAsRoot) {
        executeInitScriptAsRoot = executeAsRoot;
    }

    public boolean getDoNotUseMachineIfInitFails() {
        return doNotUseMachineIfInitFails;
    }

    public boolean isEnableMSI() {
        return enableMSI;
    }

    public void setDoNotUseMachineIfInitFails(boolean doNotUseMachineIfInitFails) {
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
    }

    public AdvancedImage getAdvancedImageInside() {
        return new AdvancedImageBuilder()
                .withCustomImage(imageReference.uri)
                .withCustomManagedImage(imageReference.id)
                .withGalleryImage(
                        imageReference.galleryName,
                        imageReference.galleryImageDefinition,
                        imageReference.galleryImageVersion,
                        imageReference.gallerySubscriptionId,
                        imageReference.galleryResourceGroup
                )
                .withReferenceImage(
                        imageReference.publisher,
                        imageReference.offer,
                        imageReference.sku,
                        imageReference.version
                )
                .withNumberOfExecutors(String.valueOf(getNoOfParallelJobs()))
                .withOsType(getOsType())
                .withLaunchMethod(getAgentLaunchMethod())
                .withPreInstallSsh(getPreInstallSsh())
                .withInitScript(getInitScript())
                .withVirtualNetworkName(getVirtualNetworkName())
                .withVirtualNetworkResourceGroupName(getVirtualNetworkResourceGroupName())
                .withSubnetName(getSubnetName())
                .withUsePrivateIP(getUsePrivateIP())
                .withNetworkSecurityGroupName(getNsgName())
                .withJvmOptions(getJvmOptions())
                .withDisableTemplate(isTemplateDisabled())
                .withRunScriptAsRoot(getExecuteInitScriptAsRoot())
                .withDoNotUseMachineIfInitFails(getDoNotUseMachineIfInitFails())
                .withEnableMSI(isEnableMSI())
                .build();
    }

    public BuiltInImage getBuiltInImageInside() {
        return new BuiltInImageBuilder().withBuiltInImageName(getBuiltInImage())
                .withInstallGit(isInstallGit())
                .withInstallDocker(isInstallDocker())
                .withInstallMaven(isInstallMaven())
                .build();
    }

    public Availability getAvailabilityInside() {
        return new AvailabilityBuilder().withAvailabilitySet(getAvailabilityType().getAvailabilitySet())
                .build();
    }

    @SuppressWarnings("unchecked")
    public Descriptor<AzureVMAgentTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public Set<LabelAtom> getLabelDataSet() {
        return labelDataSet;
    }

    public RetentionStrategy getRetentionStrategy() {
        return retentionStrategy;
    }

    /**
     * Provision new agents using this template.
     *
     * @param listener       Not used
     * @param numberOfAgents Number of agents to provision
     * @return New deployment info if the provisioning was successful.
     * @throws Exception May throw if provisioning was not successful.
     */
    public AzureVMDeploymentInfo provisionAgents(TaskListener listener, int numberOfAgents) throws Exception {
        return getServiceDelegate().createDeployment(this, numberOfAgents);
    }

    private AzureVMManagementServiceDelegate getServiceDelegate() {
        return retrieveAzureCloudReference().getServiceDelegate();
    }

    /**
     * If provisioning failed, handle the status and queue the template for
     * verification.
     *
     * @param message     Failure message
     * @param failureStep Stage that failure occurred
     */
    public void handleTemplateProvisioningFailure(String message, FailureStage failureStep) {
        // Set as failed, waiting for the next interval
        templateProvisionStrategy.failure();
        // Set the details so that it's easier to see what's going on from the configuration UI.
        setTemplateStatusDetails(message);
    }

    /**
     * Verify that this template is correct and can be allocated.
     *
     * @return Empty list if this template is valid, list of errors otherwise
     * @throws Exception On Error
     */
    public List<String> verifyTemplate() throws Exception {
        return getServiceDelegate().verifyTemplate(
                templateName,
                labels,
                location,
                virtualMachineSize,
                storageAccountName,
                storageAccountType,
                noOfParallelJobs + "",
                imageTopLevelType,
                imageReference,
                builtInImage,
                osType,
                agentLaunchMethod,
                initScript,
                credentialsId,
                virtualNetworkName,
                virtualNetworkResourceGroupName,
                subnetName,
                (AzureVMCloudBaseRetentionStrategy) retentionStrategy,
                jvmOptions,
                getResourceGroupName(),
                true,
                usePrivateIP,
                nsgName);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<AzureVMAgentTemplate> {

        @Override
        public String getDisplayName() {
            return "";
        }

        public List<Descriptor<RetentionStrategy<?>>> getAzureVMRetentionStrategy() {
            List<Descriptor<RetentionStrategy<?>>> list = new ArrayList<>();
            list.add(AzureVMCloudRetensionStrategy.DESCRIPTOR);
            list.add(AzureVMCloudPoolRetentionStrategy.DESCRIPTOR);
            list.add(AzureVMCloudOnceRetentionStrategy.DESCRIPTOR);
            return list;
        }

        public ListBoxModel doFillVirtualMachineSizeItems(
                @RelativePath("..") @QueryParameter String azureCredentialsId,
                @QueryParameter String location)

                throws IOException, ServletException {

            ListBoxModel model = new ListBoxModel();
            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }
            List<String> vmSizes = AzureClientHolder.getDelegate(azureCredentialsId).getVMSizes(location);

            if (vmSizes != null) {
                for (String vmSize : vmSizes) {
                    model.add(vmSize);
                }
            }
            return model;
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item owner) {
            // when configuring the job, you only want those credentials that are available to ACL.SYSTEM selectable
            // as we cannot select from a user's credentials unless they are the only user submitting the build
            // (which we cannot assume) thus ACL.SYSTEM is correct here.
            return new StandardListBoxModel().withAll(
                    CredentialsProvider.lookupCredentials(
                            StandardUsernamePasswordCredentials.class,
                            owner,
                            ACL.SYSTEM,
                            Collections.<DomainRequirement>emptyList()));
        }

        public ListBoxModel doFillOsTypeItems() throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.OS_TYPE_LINUX);
            model.add(Constants.OS_TYPE_WINDOWS);
            return model;
        }

        public ListBoxModel doFillLocationItems(@RelativePath("..") @QueryParameter String azureCredentialsId) {
            ListBoxModel model = new ListBoxModel();

            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }
            final TokenCredentialData token = AzureClientUtil.getToken(azureCredentialsId);
            if (token != null) {
                String envName = token.getAzureEnvironmentName();
                String managementEndpoint = token.getManagementEndpoint();
                Set<String> locations = AzureClientHolder.getDelegate(azureCredentialsId)
                        .getVirtualMachineLocations(managementEndpoint != null ? managementEndpoint : envName);
                if (locations != null) {
                    for (String location : locations) {
                        model.add(location);
                    }
                }
            }

            return model;
        }

        public String doFillAvailabilityTypeValueItems() {
            return null;
        }

        public ListBoxModel doFillAvailabilitySetItems(
                @RelativePath("../..") @QueryParameter String azureCredentialsId,
                @RelativePath("../..") @QueryParameter String resourceGroupReferenceType,
                @RelativePath("../..") @QueryParameter String newResourceGroupName,
                @RelativePath("../..") @QueryParameter String existingResourceGroupName,
                @RelativePath("..") @QueryParameter String location) {
            ListBoxModel model = new ListBoxModel();
            model.add("--- Select Availability Set in current resource group and location ---", "");
            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }

            //resourceGroupReferenceType passed wrong value in 2.60.1-LTS, we won't use this value until bug resolved.
            resourceGroupReferenceType = null;

            try {
                Azure azureClient = AzureClientHolder.get(azureCredentialsId);
                String resourceGroupName = AzureVMCloud.getResourceGroupName(
                        resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
                PagedList<AvailabilitySet> availabilitySets = azureClient.availabilitySets()
                        .listByResourceGroup(resourceGroupName);
                for (AvailabilitySet set : availabilitySets) {
                    String label = set.region().label();
                    if (label.equals(location)) {
                        model.add(set.name());
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Cannot list availability set: ", e);
            }
            return model;
        }

        public ListBoxModel doFillStorageAccountTypeItems(
                @QueryParameter String virtualMachineSize)
                throws IOException, ServletException {

            ListBoxModel model = new ListBoxModel();
            model.add("--- Select Storage Account Type ---", "");

            model.add(SkuName.STANDARD_LRS.toString());

            /*As introduced in Azure Docs, the size contains 'S' supports premium storage*/
            if (virtualMachineSize.matches(".*_[a-zA-Z]([0-9]+[Mm]?[Ss]|[Ss][0-9]+).*")) {
                model.add(SkuName.PREMIUM_LRS.toString());
            }
            return model;
        }


        public ListBoxModel doFillUsageModeItems() throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            for (Node.Mode m : hudson.Functions.getNodeModes()) {
                model.add(m.getDescription());
            }
            return model;
        }

        public ListBoxModel doFillAgentLaunchMethodItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.LAUNCH_METHOD_SSH);
            model.add(Constants.LAUNCH_METHOD_JNLP);

            return model;
        }

        public ListBoxModel doFillExistingStorageAccountNameItems(
                @RelativePath("..") @QueryParameter String azureCredentialsId,
                @RelativePath("..") @QueryParameter String resourceGroupReferenceType,
                @RelativePath("..") @QueryParameter String newResourceGroupName,
                @RelativePath("..") @QueryParameter String existingResourceGroupName,
                @QueryParameter String storageAccountType) throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }
            //resourceGroupReferenceType passed wrong value in 2.60.1-LTS, we won't use this value until bug resolved.
            resourceGroupReferenceType = null;

            try {
                Azure azureClient = AzureClientHolder.get(azureCredentialsId);

                String resourceGroupName = AzureVMCloud.getResourceGroupName(
                        resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
                List<StorageAccount> storageAccountList =
                        azureClient.storageAccounts().listByResourceGroup(resourceGroupName);
                for (StorageAccount storageAccount : storageAccountList) {
                    if (storageAccount.sku().name().toString().equalsIgnoreCase(storageAccountType)) {
                        model.add(storageAccount.name());
                    }
                }

            } catch (NullPointerException e) {
                // Do nothing
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Cannot list storage account: ", e);
            } finally {
                return model;
            }
        }

        public ListBoxModel doFillBuiltInImageItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.WINDOWS_SERVER_2016);
            model.add(Constants.UBUNTU_1604_LTS);
            return model;
        }

        public FormValidation doCheckInitScript(
                @QueryParameter String value,
                @QueryParameter String agentLaunchMethod) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.warningWithMarkup(Messages.Azure_GC_InitScript_Warn_Msg());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckStorageAccountName(@QueryParameter String value) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.ok(Messages.SA_Blank_Create_New());
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillDiskTypeItems() {
            ListBoxModel model = new ListBoxModel();
            model.add("Managed Disk", Constants.DISK_MANAGED);
            model.add("Unmanaged Disk", Constants.DISK_UNMANAGED);
            return model;
        }

        public String doFillImageReferenceTypeValueItems() {
            return null;
        }

        public FormValidation doAgentLaunchMethod(@QueryParameter String value) {
            if (Constants.LAUNCH_METHOD_JNLP.equals(value)) {
                return FormValidation.warning(Messages.Azure_GC_LaunchMethod_Warn_Msg());
            }
            return FormValidation.ok();
        }

        /**
         * Check the template's name. Name must conform to restrictions on VM
         * naming
         *
         * @param value            Current name
         * @param templateDisabled Is the template disabled
         * @param osType           OS type
         * @return The validation result
         */
        public FormValidation doCheckTemplateName(
                @QueryParameter String value,
                @QueryParameter boolean templateDisabled,
                @QueryParameter String osType) {
            List<FormValidation> errors = new ArrayList<>();
            // Check whether the template name is valid, and then check
            // whether it would be shortened on VM creation.
            if (!AzureUtil.isValidTemplateName(value)) {
                errors.add(FormValidation.error(Messages.Azure_GC_Template_Name_Not_Valid()));
            }

            if (templateDisabled) {
                errors.add(FormValidation.warning(Messages.Azure_GC_TemplateStatus_Warn_Msg()));
            }

            if (errors.size() > 0) {
                return FormValidation.aggregate(errors);
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckNoOfParallelJobs(@QueryParameter String value) {
            if (StringUtils.isNotBlank(value)) {
                String result = AzureVMManagementServiceDelegate.verifyNoOfExecutors(value);

                if (result.equalsIgnoreCase(Constants.OP_SUCCESS)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(result);
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckAdminPassword(@QueryParameter String value) {
            if (StringUtils.isNotBlank(value)) {
                if (AzureUtil.isValidPassword(value)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(Messages.Azure_GC_Password_Err());
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckJvmOptions(@QueryParameter String value) {
            if (StringUtils.isNotBlank(value)) {
                if (AzureUtil.isValidJvmOption(value)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(Messages.Azure_GC_JVM_Option_Err());
                }
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doVerifyConfiguration(
                @RelativePath("..") @QueryParameter String azureCredentialsId,
                @RelativePath("..") @QueryParameter String resourceGroupReferenceType,
                @RelativePath("..") @QueryParameter String newResourceGroupName,
                @RelativePath("..") @QueryParameter String existingResourceGroupName,
                @RelativePath("..") @QueryParameter String maxVirtualMachinesLimit,
                @RelativePath("..") @QueryParameter String deploymentTimeout,
                @QueryParameter String templateName,
                @QueryParameter String labels,
                @QueryParameter String location,
                @QueryParameter String virtualMachineSize,
                @QueryParameter String storageAccountNameReferenceType,
                @QueryParameter String newStorageAccountName,
                @QueryParameter String existingStorageAccountName,
                @QueryParameter String storageAccountType,
                @QueryParameter String noOfParallelJobs,
                @QueryParameter String imageTopLevelType,
                @QueryParameter String builtInImage,
                @QueryParameter("uri") String imageUri,
                @QueryParameter String osType,
                @QueryParameter("id") String imageId,
                @QueryParameter("publisher") String imagePublisher,
                @QueryParameter("offer") String imageOffer,
                @QueryParameter("sku") String imageSku,
                @QueryParameter("version") String imageVersion,
                @QueryParameter String galleryName,
                @QueryParameter String galleryImageDefinition,
                @QueryParameter String galleryImageVersion,
                @QueryParameter String gallerySubscriptionId,
                @QueryParameter String galleryResourceGroup,
                @QueryParameter String agentLaunchMethod,
                @QueryParameter String initScript,
                @QueryParameter String credentialsId,
                @QueryParameter String virtualNetworkName,
                @QueryParameter String virtualNetworkResourceGroupName,
                @QueryParameter String subnetName,
                @QueryParameter boolean usePrivateIP,
                @QueryParameter String nsgName,
                @QueryParameter String jvmOptions) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            ImageReferenceTypeClass image = new ImageReferenceTypeClass(
                    imageUri,
                    imageId,
                    imagePublisher,
                    imageOffer,
                    imageSku,
                    imageVersion,
                    galleryName,
                    galleryImageDefinition,
                    galleryImageVersion,
                    gallerySubscriptionId,
                    galleryResourceGroup
            );

            String resourceGroupName = AzureVMCloud.getResourceGroupName(
                    resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
            String storageAccountName = getStorageAccountName(
                    storageAccountNameReferenceType, newStorageAccountName, existingStorageAccountName);
            if (storageAccountName.trim().isEmpty()) {
                storageAccountName = AzureVMAgentTemplate.generateUniqueStorageAccountName(
                        resourceGroupName, templateName);
            }

            LOGGER.log(Level.INFO,
                    "Verify configuration:\n\t{0}{1}{2}{3}"
                            + "resourceGroupName: {4};\n\t"
                            + "templateName: {5};\n\t"
                            + "labels: {6};\n\t"
                            + "location: {7};\n\t"
                            + "virtualMachineSize: {8};\n\t"
                            + "storageAccountName: {9};\n\t"
                            + "noOfParallelJobs: {10};\n\t"
                            + "imageTopLevelType: {11};\n\t"
                            + "builtInImage: {12};\n\t"
                            + "image: {13};\n\t"
                            + "osType: {14};\n\t"
                            + "id: {15};\n\t"
                            + "publisher: {16};\n\t"
                            + "offer: {17};\n\t"
                            + "sku: {18};\n\t"
                            + "version: {19};\n\t"
                            + "agentLaunchMethod: {20};\n\t"
                            + "initScript: {21};\n\t"
                            + "credentialsId: {22};\n\t"
                            + "virtualNetworkName: {23};\n\t"
                            + "virtualNetworkResourceGroupName: {24};\n\t"
                            + "subnetName: {25};\n\t"
                            + "privateIP: {26};\n\t"
                            + "nsgName: {27};\n\t"
                            + "jvmOptions: {28};\n\t"
                            + "galleryName: {29}\n\t"
                            + "galleryImageDefinition: {30}\n\t"
                            + "galleryImageVersion: {31}\n\t"
                            + "galleryResourceGroup: {32}\n\t"
                            + "gallerySubscriptionId: {33}",
                    new Object[]{
                            "",
                            "",
                            "",
                            "",
                            resourceGroupName,
                            templateName,
                            labels,
                            location,
                            virtualMachineSize,
                            storageAccountName,
                            noOfParallelJobs,
                            imageTopLevelType,
                            builtInImage,
                            imageUri,
                            osType,
                            imageId,
                            imagePublisher,
                            imageOffer,
                            imageSku,
                            imageVersion,
                            agentLaunchMethod,
                            initScript,
                            credentialsId,
                            virtualNetworkName,
                            virtualNetworkResourceGroupName,
                            subnetName,
                            usePrivateIP,
                            nsgName,
                            jvmOptions,
                            galleryName,
                            galleryImageDefinition,
                            galleryImageVersion,
                            galleryResourceGroup,
                            gallerySubscriptionId});

            // First validate the subscription info.  If it is not correct,
            // then we can't validate the
            String result = AzureClientHolder.getDelegate(azureCredentialsId).verifyConfiguration(resourceGroupName,
                    maxVirtualMachinesLimit, deploymentTimeout);
            if (!result.equals(Constants.OP_SUCCESS)) {
                return FormValidation.error(result);
            }

            final List<String> errors = AzureClientHolder.getDelegate(azureCredentialsId).verifyTemplate(
                    templateName,
                    labels,
                    location,
                    virtualMachineSize,
                    storageAccountName,
                    storageAccountType,
                    noOfParallelJobs,
                    imageTopLevelType,
                    image,
                    builtInImage,
                    osType,
                    agentLaunchMethod,
                    initScript,
                    credentialsId,
                    virtualNetworkName,
                    virtualNetworkResourceGroupName,
                    subnetName,
                    new AzureVMCloudRetensionStrategy(0),
                    jvmOptions,
                    resourceGroupName,
                    false,
                    usePrivateIP,
                    nsgName);

            if (errors.size() > 0) {
                StringBuilder errorString = new StringBuilder(Messages.Azure_GC_Template_Error_List()).append("\n");

                for (int i = 0; i < errors.size(); i++) {
                    errorString.append(i + 1).append(": ").append(errors.get(i)).append("\n");
                }

                return FormValidation.error(errorString.toString());

            } else {
                return FormValidation.ok(Messages.Azure_Template_Config_Success());
            }
        }

        public String getDefaultNoOfExecutors() {
            return "1";
        }
    }

    public void setVirtualMachineDetails(AzureVMAgent agent) throws Exception {
        getServiceDelegate().setVirtualMachineDetails(agent, this);
    }

    public static String generateUniqueStorageAccountName(String resourceGroupName,
                                                          String templateName) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            if (null != templateName) {
                md.update(templateName.getBytes(StandardCharsets.UTF_8));
            }
            if (null != resourceGroupName) {
                md.update(resourceGroupName.getBytes(StandardCharsets.UTF_8));
            }

            String uid = DatatypeConverter.printBase64Binary(md.digest());
            uid = uid.substring(0, GEN_STORAGE_ACCOUNT_UID_LENGTH);
            uid = uid.toLowerCase();
            uid = uid.replaceAll("[^a-z0-9]", "a");
            return "jn" + uid;
        } catch (NoSuchAlgorithmException e) {
            LOGGER.log(Level.WARNING,
                    "Could not generate UID from the resource group name. "
                            + "Will fallback on using the resource group name.",
                    e);
            return "";
        }
    }
}
