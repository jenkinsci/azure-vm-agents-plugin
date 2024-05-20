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
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.AvailabilitySet;
import com.azure.resourcemanager.compute.models.DiskSkuTypes;
import com.azure.resourcemanager.storage.models.SkuName;
import com.azure.resourcemanager.storage.models.StorageAccount;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.jcraft.jsch.OpenSSHConfig;
import com.microsoft.azure.util.AzureBaseCredentials;
import com.microsoft.azure.util.AzureCredentialUtil;
import com.microsoft.azure.vmagent.builders.AdvancedImage;
import com.microsoft.azure.vmagent.builders.AdvancedImageBuilder;
import com.microsoft.azure.vmagent.builders.Availability;
import com.microsoft.azure.vmagent.builders.AvailabilityBuilder;
import com.microsoft.azure.vmagent.builders.BuiltInImage;
import com.microsoft.azure.vmagent.builders.BuiltInImageBuilder;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.launcher.AzureComputerLauncher;
import com.microsoft.azure.vmagent.launcher.AzureInboundLauncher;
import com.microsoft.azure.vmagent.launcher.AzureSSHLauncher;
import com.microsoft.azure.vmagent.util.AzureClientHolder;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.FailureStage;
import com.microsoft.jenkins.credentials.AzureResourceManagerCache;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.RetentionStrategy;
import hudson.util.FormApply;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.verb.POST;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class defines the configuration of Azure instance templates.
 *
 * @author Suresh Nallamilli
 */
public class AzureVMAgentTemplate implements Describable<AzureVMAgentTemplate>, Serializable {
    private static final long serialVersionUID = 1574325692L;

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
        private boolean galleryImageSpecialized;
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
            this.type = determineType();
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

        public boolean getGalleryImageSpecialized() {
            return galleryImageSpecialized;
        }
        @DataBoundSetter
        public void setGalleryImageSpecialized(boolean galleryImageSpecialized) {
            this.galleryImageSpecialized = galleryImageSpecialized;
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
        public AvailabilityTypeClass(String availabilitySet) {
            this.availabilitySet = Util.fixEmpty(availabilitySet);
        }

        private AvailabilityTypeClass() {
            // used for readResolve to maintain compatibility
        }

        public String getAvailabilitySet() {
            return availabilitySet;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            AvailabilityTypeClass that = (AvailabilityTypeClass) o;
            return Objects.equals(availabilitySet, that.availabilitySet);
        }

        @Override
        public int hashCode() {
            return Objects.hash(availabilitySet);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentTemplate.class.getName());

    private static final int GEN_STORAGE_ACCOUNT_UID_LENGTH = 22;

    // General Configuration
    private final String templateName;

    private final String templateDesc;

    private final String labels;

    private final String location;

    private AvailabilityTypeClass availabilityType;

    private final String virtualMachineSize;

    private String storageAccountNameReferenceType;

    private transient String storageAccountName;

    private String diskType;

    private boolean ephemeralOSDisk;

    private boolean encryptionAtHost;

    private int osDiskSize;

    private String newStorageAccountName;

    private String existingStorageAccountName;

    private String storageAccountType;

    private String osDiskStorageAccountType;

    private final int noOfParallelJobs;

    private Node.Mode usageMode;

    private boolean shutdownOnIdle;

    // Image Configuration
    private String imageTopLevelType;

    private ImageReferenceTypeClass imageReference;

    private String builtInImage;

    private boolean installGit;

    private boolean installMaven;

    private boolean installDocker;

    private final String osType;

    private transient String agentLaunchMethod;

    private AzureComputerLauncher launcher;

    private transient boolean preInstallSsh;

    private transient String sshConfig;

    private final String initScript;

    private final String terminateScript;

    private final String credentialsId;

    private final String agentWorkspace;

    private int retentionTimeInMin;

    private String virtualNetworkName;

    private String virtualNetworkResourceGroupName;

    private String subnetName;

    private boolean usePrivateIP;

    private boolean spotInstance;

    private boolean acceleratedNetworking;

    private final String nsgName;

    private final String jvmOptions;

    private String remotingOptions;

    // Indicates whether the template is disabled.
    // If disabled, will not attempt to verify or use
    private boolean templateDisabled;

    private String templateStatusDetails;

    private transient AzureVMCloud azureCloud;

    private transient Set<LabelAtom> labelDataSet;

    private boolean templateVerified;

    private transient ProvisionStrategy templateProvisionStrategy;

    private boolean executeInitScriptAsRoot;

    private boolean doNotUseMachineIfInitFails;

    private boolean enableMSI;

    private boolean enableUAMI;

    private String uamiID;

    private String javaPath;

    private RetentionStrategy<?> retentionStrategy;

    private int maximumDeploymentSize;

    private List<AzureTagPair> tags;

    private int maxVirtualMachinesLimit;

    private String licenseType;

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
    private transient String availabilitySet;


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
            String noOfParallelJobs,
            Node.Mode usageMode,
            String osType,
            String imageTopLevelType,
            ImageReferenceTypeClass imageReference,
            AzureComputerLauncher launcher,
            String initScript,
            String terminateScript,
            String credentialsId,
            String virtualNetworkName,
            String virtualNetworkResourceGroupName,
            String subnetName,
            String nsgName,
            String agentWorkspace,
            String jvmOptions,
            RetentionStrategy<?> retentionStrategy,
            boolean executeInitScriptAsRoot,
            boolean doNotUseMachineIfInitFails
    ) {
        this.templateName = templateName;
        this.templateDesc = templateDesc;
        this.labels = labels;
        this.location = location;
        this.availabilityType = availabilityType;
        if (availabilityType == null) {
            this.availabilityType = new AvailabilityTypeClass();
        }
        this.virtualMachineSize = virtualMachineSize;
        this.storageAccountType = storageAccountType;
        this.storageAccountName = getStorageAccountName(
                storageAccountNameReferenceType, newStorageAccountName, existingStorageAccountName);
        this.newStorageAccountName = newStorageAccountName;
        this.existingStorageAccountName = existingStorageAccountName;
        this.storageAccountNameReferenceType = storageAccountNameReferenceType;
        this.diskType = diskType;

        if (StringUtils.isBlank(noOfParallelJobs) || !noOfParallelJobs.matches(Constants.REG_EX_DIGIT)
                || noOfParallelJobs.
                trim().equals("0")) {
            this.noOfParallelJobs = 1;
        } else {
            this.noOfParallelJobs = Integer.parseInt(noOfParallelJobs);
        }
        setUsageMode(usageMode);
        this.imageTopLevelType = imageTopLevelType;
        this.imageReference = imageReference;
        if (imageReference == null) {
            this.imageReference = new ImageReferenceTypeClass();
        }
        this.osType = osType;
        this.initScript = initScript;
        this.terminateScript = terminateScript;
        this.launcher = launcher;
        this.credentialsId = credentialsId;
        this.virtualNetworkName = virtualNetworkName;
        this.virtualNetworkResourceGroupName = virtualNetworkResourceGroupName;
        this.subnetName = subnetName;
        this.nsgName = nsgName;
        this.agentWorkspace = agentWorkspace;
        this.jvmOptions = jvmOptions;
        this.executeInitScriptAsRoot = executeInitScriptAsRoot;
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
        this.templateStatusDetails = "";

        // Reset the template verification status.
        this.templateProvisionStrategy = new ProvisionStrategy();
        this.retentionStrategy = retentionStrategy;

        // Forms data which is not persisted
        labelDataSet = Label.parse(labels);

        this.tags = new ArrayList<>();
    }

    @DataBoundSetter
    public void setBuiltInImage(String builtInImage) {
        this.builtInImage = builtInImage;
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
    public boolean getGalleryImageSpecialized() {
        return imageReference != null ? imageReference.getGalleryImageSpecialized() : false;
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

    public int getMaxVirtualMachinesLimit() {
        return maxVirtualMachinesLimit;
    }

    @DataBoundSetter
    public void setMaxVirtualMachinesLimit(int maxVirtualMachinesLimit) {
        this.maxVirtualMachinesLimit = maxVirtualMachinesLimit;
    }

    public String getJavaPath() {
        return javaPath;
    }

    @DataBoundSetter
    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath;
    }

    public String getRemotingOptions() {
        return remotingOptions;
    }

    @DataBoundSetter
    public void setRemotingOptions(String remotingOptions) {
        this.remotingOptions = remotingOptions;
    }

    public boolean isSpotInstance() {
        return spotInstance;
    }

    @DataBoundSetter
    public void setSpotInstance(boolean spotInstance) {
        this.spotInstance = spotInstance;
    }

    public boolean isAcceleratedNetworking() {
        return acceleratedNetworking;
    }

    @DataBoundSetter
    public void setAcceleratedNetworking(boolean acceleratedNetworking) {
        this.acceleratedNetworking = acceleratedNetworking;
    }

    public List<AzureTagPair> getTags() {
        return tags;
    }

    @DataBoundSetter
    public void setTags(List<AzureTagPair> tags) {
        this.tags = tags;
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
        boolean isSSH = template.getLauncher() instanceof AzureSSHLauncher;
        String agentLaunchMethod = isSSH ? Constants.LAUNCH_METHOD_SSH : Constants.LAUNCH_METHOD_JNLP;
        templateProperties.put("agentLaunchMethod",
                isBasic ? defaultProperties.get(Constants.DEFAULT_LAUNCH_METHOD) : agentLaunchMethod);
        if (isSSH) {
            templateProperties.put("sshConfig",
                    isBasic ? "" : ((AzureSSHLauncher) template.getLauncher()).getSshConfig());
        }
        templateProperties.put("initScript",
                isBasic ? getBasicInitScript(template) : template.getInitScript());
        templateProperties.put("terminateScript",
                isBasic ? "" : template.getTerminateScript());
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
        templateProperties.put("enableUAMI",
                isBasic ? false : template.isEnableUAMI());
        templateProperties.put("ephemeralOSDisk",
                isBasic ? false : template.isEphemeralOSDisk());
        templateProperties.put("encryptionAtHost",
                isBasic ? false : template.isEncryptionAtHost());
        templateProperties.put("uamiID",
                isBasic ? "" : template.getUamiID());

        return templateProperties;
    }

    public static String getBasicInitScript(AzureVMAgentTemplate template) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            String builtInImage = template.getBuiltInImage();
            stringBuilder.append(
                    AzureVMManagementServiceDelegate.PRE_INSTALLED_TOOLS_SCRIPT
                            .get(builtInImage).get(Constants.INSTALL_JAVA));
            if (template.isInstallMaven()) {
                stringBuilder.append(getSeparator(template.getOsType()));
                stringBuilder.append(
                        AzureVMManagementServiceDelegate.PRE_INSTALLED_TOOLS_SCRIPT
                                .get(builtInImage).get(Constants.INSTALL_MAVEN));
            }
            if (template.isInstallGit()) {
                stringBuilder.append(getSeparator(template.getOsType()));
                stringBuilder.append(
                        AzureVMManagementServiceDelegate.PRE_INSTALLED_TOOLS_SCRIPT
                                .get(builtInImage).get(Constants.INSTALL_GIT));
            }
            if ((builtInImage.equals(Constants.UBUNTU_1604_LTS)
                   || builtInImage.equals(Constants.UBUNTU_2004_LTS)
                   || builtInImage.equals(Constants.UBUNTU_2204_LTS))
                    && template.isInstallDocker()) {
                stringBuilder.append(getSeparator(template.getOsType()));
                stringBuilder.append(
                        AzureVMManagementServiceDelegate.PRE_INSTALLED_TOOLS_SCRIPT
                                .get(builtInImage).get(Constants.INSTALL_DOCKER)
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

        if (StringUtils.isNotBlank(agentLaunchMethod)) {
            if (agentLaunchMethod.equalsIgnoreCase(Constants.LAUNCH_METHOD_SSH)) {
                AzureSSHLauncher azureSSHLauncher = new AzureSSHLauncher();

                if (StringUtils.isNotBlank(sshConfig)) {
                    azureSSHLauncher.setSshConfig(sshConfig);
                }
                if (preInstallSsh) {
                    azureSSHLauncher.setPreInstallSsh(preInstallSsh);
                }
                launcher = azureSSHLauncher;

            } else if (agentLaunchMethod.equalsIgnoreCase(Constants.LAUNCH_METHOD_JNLP)) {
                launcher = new AzureInboundLauncher();
            }
            agentLaunchMethod = null;
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

        if (availabilityType == null) {
            availabilityType = new AvailabilityTypeClass();
        }

        if (StringUtils.isNotBlank(image)) {
            imageReference.uri = image;
        }

        if (StringUtils.isNotBlank(imageId)) {
            imageReference.id = imageId;
        }

        if (StringUtils.isNotBlank(imagePublisher)) {
            imageReference.publisher = imagePublisher;
        }

        if (StringUtils.isNotBlank(imageOffer)) {
            imageReference.offer = imageOffer;
        }

        if (StringUtils.isNotBlank(imageSku)) {
            imageReference.sku = imageSku;
        }

        if (StringUtils.isNotBlank(imageVersion)) {
            imageReference.version = imageVersion;
        }

        if (StringUtils.isNotBlank(galleryName)) {
            imageReference.galleryName = galleryName;
        }

        if (StringUtils.isNotBlank(galleryImageDefinition)) {
            imageReference.galleryImageDefinition = galleryImageDefinition;
        }

        if (StringUtils.isNotBlank(galleryImageVersion)) {
            imageReference.galleryImageVersion = galleryImageVersion;
        }

        if (StringUtils.isNotBlank(gallerySubscriptionId)) {
            imageReference.gallerySubscriptionId = gallerySubscriptionId;
        }

        if (StringUtils.isNotBlank(galleryResourceGroup)) {
            imageReference.galleryResourceGroup = galleryResourceGroup;
        }

        if (imageReference.type == null) {
            imageReference.type = imageReference.determineType();
        }

        if (StringUtils.isNotBlank(availabilitySet)) {
            availabilityType.availabilitySet = availabilitySet;
        }

        if (tags == null) {
            this.tags = new ArrayList<>();
        }

        if (StringUtils.isBlank(imageTopLevelType)) {
            if (imageReference != null && (StringUtils.isNotBlank(imageReference.getUri())
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

    public String getOsDiskStorageAccountType() {
        if (StringUtils.isBlank(osDiskStorageAccountType)) {
            return getStorageAccountType();
        }
        return osDiskStorageAccountType;
    }

    @DataBoundSetter
    public void setOsDiskStorageAccountType(String osDiskStorageAccountType) {
        this.osDiskStorageAccountType = osDiskStorageAccountType;
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

    public boolean isEphemeralOSDisk() {
        return ephemeralOSDisk;
    }

    @DataBoundSetter
    public void setEphemeralOSDisk(boolean ephemeralOSDisk) {
        this.ephemeralOSDisk = ephemeralOSDisk;
    }

    public boolean isEncryptionAtHost() {
        return encryptionAtHost;
    }

    @DataBoundSetter
    public void setEncryptionAtHost(boolean encryptionAtHost) {
        this.encryptionAtHost = encryptionAtHost;
    }

    @DataBoundSetter
    public void setOsDiskSize(int osDiskSize) {
        this.osDiskSize = osDiskSize;
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

    public Node.Mode getUsageMode() {
        return usageMode == null ? Node.Mode.NORMAL : usageMode;
    }

    public boolean isStorageAccountNameReferenceTypeEquals(String type) {
        if (this.storageAccountNameReferenceType == null && type.equalsIgnoreCase("new")) {
            return true;
        }
        return type != null && type.equalsIgnoreCase(this.storageAccountNameReferenceType);
    }

    @DataBoundSetter
    public void setUsageMode(Node.Mode usageMode) {
        this.usageMode = usageMode;
    }

    @DataBoundSetter
    public void setShutdownOnIdle(boolean shutdownOnIdle) {
        this.shutdownOnIdle = shutdownOnIdle;
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

    @DataBoundSetter
    public void setInstallGit(boolean installGit) {
        this.installGit = installGit;
    }

    @DataBoundSetter
    public void setInstallMaven(boolean installMaven) {
        this.installMaven = installMaven;
    }

    @DataBoundSetter
    public void setInstallDocker(boolean installDocker) {
        this.installDocker = installDocker;
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

    public String getInitScript() {
        return initScript;
    }

    public String getTerminateScript() {
        return terminateScript;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public StandardUsernameCredentials getVMCredentials() throws AzureCloudException {
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

    @DataBoundSetter
    public void setUsePrivateIP(boolean usePrivateIP) {
        this.usePrivateIP = usePrivateIP;
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

    @DataBoundSetter
    public void setTemplateDisabled(boolean templateDisabled) {
        this.templateDisabled = templateDisabled;
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

    public String getResourceGroupReferenceType() {
        return retrieveAzureCloudReference().getResourceGroupReferenceType();
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

    @DataBoundSetter
    public void setEnableMSI(boolean enableMSI) {
        this.enableMSI = enableMSI;
    }

    public boolean isEnableMSI() {
        return enableMSI;
    }

    @DataBoundSetter
    public void setEnableUAMI(boolean enableUAMI) {
        this.enableUAMI = enableUAMI;
    }

    public boolean isEnableUAMI() {
        return enableUAMI;
    }

    @DataBoundSetter
    public void setUamiID(String uamiID) {
        this.uamiID = uamiID;
    }

    public String getUamiID() {
        return uamiID;
    }

    public AzureComputerLauncher getLauncher() {
        return launcher;
    }

    @DataBoundSetter
    public void setDoNotUseMachineIfInitFails(boolean doNotUseMachineIfInitFails) {
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
    }

    public AdvancedImage getAdvancedImageInside() {
        boolean isSSH = launcher instanceof AzureSSHLauncher;
        boolean preInstallSshLocal = launcher != null
                && isSSH && ((AzureSSHLauncher) launcher).isPreInstallSsh();
        String sshConfigLocal = launcher != null
                && isSSH ? ((AzureSSHLauncher) launcher).getSshConfig() : null;
        return new AdvancedImageBuilder()
                .withCustomImage(imageReference.uri)
                .withCustomManagedImage(imageReference.id)
                .withGalleryImage(
                        imageReference.galleryName,
                        imageReference.galleryImageDefinition,
                        imageReference.galleryImageVersion,
                        imageReference.galleryImageSpecialized,
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
                .withLaunchMethod(isSSH ? Constants.LAUNCH_METHOD_SSH : Constants.LAUNCH_METHOD_JNLP)
                .withPreInstallSsh(preInstallSshLocal)
                .withSshConfig(sshConfigLocal)
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
                .withEnableUAMI(isEnableUAMI())
                .withGetUamiID(getUamiID())
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
        return Jenkins.get().getDescriptor(getClass());
    }

    public Set<LabelAtom> getLabelDataSet() {
        return labelDataSet;
    }

    public RetentionStrategy getRetentionStrategy() {
        return retentionStrategy;
    }

    public int getMaximumDeploymentSize() {
        return maximumDeploymentSize;
    }

    @DataBoundSetter
    public void setMaximumDeploymentSize(int maximumDeploymentSize) {
        this.maximumDeploymentSize = maximumDeploymentSize;
    }

    public String getLicenseType() {
        return licenseType;
    }

    @DataBoundSetter
    public void setLicenseType(String licenseType) {
        this.licenseType = licenseType;
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
                launcher,
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

    /**
     * Deletes the template.
     */
    @POST
    public HttpResponse doDoDelete(@AncestorInPath AzureVMCloud azureVMCloud) throws IOException {
        Jenkins j = Jenkins.get();
        j.checkPermission(Jenkins.ADMINISTER);
        if (azureVMCloud == null) {
            throw new IllegalStateException("Cloud could not be found");
        }
        azureVMCloud.removeTemplate(this);
        j.save();
        // take the user back.
        return new HttpRedirect("../../templates");
    }

    @POST
    public HttpResponse doConfigSubmit(StaplerRequest req, @AncestorInPath AzureVMCloud azureVMCloud)
            throws IOException, ServletException, Descriptor.FormException {
        Jenkins j = Jenkins.get();
        j.checkPermission(Jenkins.ADMINISTER);
        if (azureVMCloud == null) {
            throw new IllegalStateException("Cloud could not be found");
        }
        azureVMCloud.removeTemplate(this);
        AzureVMAgentTemplate newTemplate = reconfigure(req, req.getSubmittedForm());
        if (StringUtils.isBlank(newTemplate.getTemplateName())) {
            throw new Descriptor.FormException("Template name is mandatory", "templateName");
        }
        boolean templateNameExists = azureCloud.templateNameExists(newTemplate.getTemplateName());
        if (templateNameExists) {
            throw new Descriptor.FormException("Agent template name must be unique", "templateName");
        }

        azureVMCloud.addTemplate(newTemplate);
        j.save();
        // take the user back.
        return FormApply.success("../../templates");
    }

    private AzureVMAgentTemplate reconfigure(@NonNull final StaplerRequest req, JSONObject form)
            throws Descriptor.FormException {
        if (form == null) {
            return null;
        }
        return getDescriptor().newInstance(req, form);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<AzureVMAgentTemplate> {

        @Override
        @NonNull
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

        @POST
        public ListBoxModel doFillVirtualMachineSizeItems(
                @QueryParameter("cloudName") String cloudName,
                @QueryParameter String location) {
            Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);

            String azureCredentialsId = getAzureCredentialsIdFromCloud(cloudName);

            ListBoxModel model = new ListBoxModel();
            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }
            Set<String> vmSizes = AzureClientHolder.getDelegate(azureCredentialsId).getVMSizes(location);
            for (String vmSize : vmSizes) {
                model.add(vmSize);
            }
            return model;
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            StandardListBoxModel model = new StandardListBoxModel();
            Jenkins context = Jenkins.get();
            if (!context.hasPermission(Jenkins.ADMINISTER)) {
                return model.includeCurrentValue(credentialsId);
            }

            return model
                    .includeAs(ACL.SYSTEM, context, SSHUserPrivateKey.class)
                    .includeAs(ACL.SYSTEM, context, StandardUsernamePasswordCredentials.class);
        }

        @POST
        public ListBoxModel doFillOsTypeItems() throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.OS_TYPE_LINUX);
            model.add(Constants.OS_TYPE_WINDOWS);
            return model;
        }

        @POST
        public ListBoxModel doFillLicenseTypeItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.LICENSE_TYPE_CLASSIC);
            model.add(Constants.LICENSE_TYPE_WINDOWS_CLIENT);
            model.add(Constants.LICENSE_TYPE_WINDOWS_SERVER);
            return model;
        }

        private String getAzureCredentialsIdFromCloud(String cloudName) {
            AzureVMCloud cloud = getAzureCloud(cloudName);

            if (cloud != null) {
                return cloud.getAzureCredentialsId();
            }

            return null;
        }

        private AzureVMCloud getAzureCloud(String cloudName) {
            Cloud cloud = Jenkins.get().getCloud(cloudName);

            if (cloud instanceof AzureVMCloud) {
                return (AzureVMCloud) cloud;
            }

            return null;
        }

        @POST
        public ListBoxModel doFillLocationItems(
                @QueryParameter("cloudName") String cloudName
        ) {
            Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);

            String azureCredentialsId = getAzureCredentialsIdFromCloud(cloudName);

            ListBoxModel model = new ListBoxModel();
            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }

            AzureBaseCredentials credential = AzureCredentialUtil.getCredential(null, azureCredentialsId);
            if (credential != null) {
                String envName = credential.getAzureEnvironmentName();
                String managementEndpoint = credential.getManagementEndpoint();
                AzureVMManagementServiceDelegate delegate = AzureClientHolder.getDelegate(azureCredentialsId);
                Set<String> locations = delegate
                        .getVirtualMachineLocations(managementEndpoint != null ? managementEndpoint : envName);
                if (locations != null) {
                    Set<String> sortedLocations = new TreeSet<>(locations);
                    for (String location : sortedLocations) {
                        model.add(location);
                    }
                }
            }

            return model;
        }

        @SuppressWarnings("unused") // Used by jelly
        @Restricted(DoNotUse.class) // Used by jelly
        public AzureComputerLauncher getDefaultComputerLauncher() {
            return new AzureSSHLauncher();
        }

        @POST
        public ListBoxModel doFillAvailabilitySetItems(
                @QueryParameter("cloudName") String cloudName,
                @RelativePath("..") @QueryParameter String location) {
            Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);

            ListBoxModel model = new ListBoxModel();
            model.add("--- Select Availability Set in current resource group and location ---", "");

            AzureVMCloud cloud = getAzureCloud(cloudName);
            if (cloud == null) {
                return model;
            }

            String azureCredentialsId = cloud.getAzureCredentialsId();
            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }

            String resourceGroupReferenceType = cloud.getResourceGroupReferenceType();
            String newResourceGroupName = cloud.getNewResourceGroupName();
            String existingResourceGroupName = cloud.getExistingResourceGroupName();


            try {
                AzureResourceManager azureClient = AzureResourceManagerCache.get(azureCredentialsId);
                String resourceGroupName = AzureVMCloud.getResourceGroupName(
                        resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
                PagedIterable<AvailabilitySet> availabilitySets = azureClient.availabilitySets()
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

        @POST
        public ListBoxModel doFillStorageAccountTypeItems(@QueryParameter String virtualMachineSize) {
            ListBoxModel model = new ListBoxModel();
            model.add("--- Select Storage Account Type ---", "");

            model.add(SkuName.STANDARD_LRS.toString());

            /*As introduced in Azure Docs, the size contains 'S' supports premium storage*/
            if (virtualMachineSize.matches(".*_[a-zA-Z]([0-9]+[aAMm]?[Ss]|[Ss][0-9]+).*")) {
                model.add(SkuName.PREMIUM_LRS.toString());
            }
            return model;
        }

        @POST
        public ListBoxModel doFillOsDiskStorageAccountTypeItems(@QueryParameter String virtualMachineSize) {
            ListBoxModel model = new ListBoxModel();
            model.add("--- Select Storage Account Type ---", "");

            model.add(DiskSkuTypes.STANDARD_LRS.toString());
            model.add(DiskSkuTypes.STANDARD_SSD_LRS.toString());

            /*As introduced in Azure Docs, the size contains 'S' supports premium storage*/
            if (virtualMachineSize.matches(".*_[a-zA-Z]([0-9]+[aAMm]?[Ss]|[Ss][0-9]+).*")) {
                model.add(DiskSkuTypes.PREMIUM_LRS.toString());
            }
            return model;
        }

        @POST
        public ListBoxModel doFillUsageModeItems() throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            for (Node.Mode m : hudson.Functions.getNodeModes()) {
                model.add(m.getDescription(), m.getName());
            }
            return model;
        }

        @POST
        public ListBoxModel doFillExistingStorageAccountNameItems(
                @QueryParameter("cloudName") String cloudName,
                @QueryParameter String storageAccountType) {
            Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);

            AzureVMCloud cloud = getAzureCloud(cloudName);
            ListBoxModel model = new ListBoxModel();
            if (cloud == null) {
                return model;
            }
            String azureCredentialsId = cloud.getAzureCredentialsId();

            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }
            String resourceGroupReferenceType = cloud.getResourceGroupReferenceType();
            String newResourceGroupName = cloud.getNewResourceGroupName();
            String existingResourceGroupName = cloud.getExistingResourceGroupName();

            try {
                AzureResourceManager azureClient = AzureResourceManagerCache.get(azureCredentialsId);

                String resourceGroupName = AzureVMCloud.getResourceGroupName(
                        resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
                PagedIterable<StorageAccount> storageAccountList =
                        azureClient.storageAccounts().listByResourceGroup(resourceGroupName);
                for (StorageAccount storageAccount : storageAccountList) {
                    if (storageAccount.skuType().name().toString().equalsIgnoreCase(storageAccountType)) {
                        model.add(storageAccount.name());
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Cannot list storage account: ", e);
            }
            return model;
        }

        @POST
        public ListBoxModel doFillBuiltInImageItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.UBUNTU_2204_LTS);
            model.add(Constants.UBUNTU_2004_LTS);
            model.add(Constants.UBUNTU_1604_LTS);
            model.add(Constants.WINDOWS_SERVER_2022);
            model.add(Constants.WINDOWS_SERVER_2019);
            model.add(Constants.WINDOWS_SERVER_2016);
            return model;
        }

        @POST
        public FormValidation doCheckStorageAccountType(
                @QueryParameter String value,
                @QueryParameter String diskType) {
            if (Constants.DISK_MANAGED.equals(diskType) && SkuName.PREMIUM_LRS.toString().equals(value)) {
                return FormValidation.warning(Messages.Azure_GC_Template_StorageAccountType());
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckSshConfig(
                @QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }

            if (!StringUtils.isBlank(value)) {
                try {
                    OpenSSHConfig.parse(value);
                } catch (IOException e) {
                    return FormValidation.warningWithMarkup(Messages.Ssh_Config_Invalid());
                }
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckInitScript(
                @QueryParameter String value,
                @QueryParameter String agentLaunchMethod) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.warningWithMarkup(Messages.Azure_GC_InitScript_Warn_Msg());
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckStorageAccountName(@QueryParameter String value) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.ok(Messages.SA_Blank_Create_New());
            }
            return FormValidation.ok();
        }

        @POST
        public ListBoxModel doFillDiskTypeItems() {
            ListBoxModel model = new ListBoxModel();
            model.add("Managed Disk", Constants.DISK_MANAGED);
            model.add("Unmanaged Disk", Constants.DISK_UNMANAGED);
            return model;
        }

        @POST
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
        @POST
        public FormValidation doCheckTemplateName(
                @QueryParameter String value,
                @QueryParameter boolean templateDisabled,
                @QueryParameter String osType) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }

            List<FormValidation> errors = new ArrayList<>();
            // Check whether the template name is valid, and then check
            // whether it would be shortened on VM creation.

            if (StringUtils.isBlank(value)) {
                return FormValidation.ok();
            }

            if (!AzureUtil.isValidTemplateName(value)) {
                errors.add(FormValidation.error(Messages.Azure_GC_Template_Name_Not_Valid()));
            }

            if (templateDisabled) {
                errors.add(FormValidation.warning(Messages.Azure_GC_TemplateStatus_Warn_Msg()));
            }

            if (!errors.isEmpty()) {
                return FormValidation.aggregate(errors);
            }

            return FormValidation.ok();
        }

        @POST
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

        @POST
        public FormValidation doCheckAdminPassword(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }

            if (StringUtils.isNotBlank(value)) {
                if (AzureUtil.isValidPassword(value)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(Messages.Azure_GC_Password_Err());
                }
            }
            return FormValidation.ok();
        }

        @POST
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

        @POST
        public FormValidation doVerifyConfiguration(
                @QueryParameter String cloudName,
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
                @RelativePath("..") @QueryParameter String sshConfig,
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

            AzureVMCloud cloud = (AzureVMCloud) Jenkins.get().getCloud(cloudName);

            String storageAccountName = getStorageAccountName(
                    storageAccountNameReferenceType, newStorageAccountName, existingStorageAccountName);
            if (storageAccountName.trim().isEmpty()) {
                storageAccountName = AzureVMAgentTemplate.generateUniqueStorageAccountName(
                        cloud.getResourceGroupName(), templateName);
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
                            + "sshConfig: {20};\n\t"
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
                            cloud.getResourceGroupName(),
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
                            sshConfig,
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
            String result = AzureClientHolder.getDelegate(cloud.getAzureCredentialsId())
                    .verifyConfiguration(cloud.getResourceGroupName(), String.valueOf(cloud.getDeploymentTimeout()));
            if (!result.equals(Constants.OP_SUCCESS)) {
                return FormValidation.error(result);
            }

            AzureSSHLauncher azureComputerLauncher = new AzureSSHLauncher();
            if (StringUtils.isNotBlank(sshConfig)) {
                azureComputerLauncher.setSshConfig(sshConfig);
            }

            final List<String> errors = AzureClientHolder.getDelegate(cloud.getAzureCredentialsId()).verifyTemplate(
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
                    azureComputerLauncher,
                    initScript,
                    credentialsId,
                    virtualNetworkName,
                    virtualNetworkResourceGroupName,
                    subnetName,
                    new AzureVMCloudRetensionStrategy(0),
                    jvmOptions,
                    cloud.getResourceGroupName(),
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

            String uid = Base64.getEncoder().encodeToString(md.digest());
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
