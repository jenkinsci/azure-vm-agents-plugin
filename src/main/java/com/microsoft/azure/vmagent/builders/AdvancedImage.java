package com.microsoft.azure.vmagent.builders;


public class AdvancedImage {

    private String imageReferenceType;

    private String image;

    private String osType;

    private String imageId;

    private String imagePublisher;

    private String imageOffer;

    private String imageSku;

    private String imageVersion;

    private String galleryName;

    private String galleryImageDefinition;

    private String galleryImageVersion;

    private boolean galleryImageSpecialized;

    private String gallerySubscriptionId;

    private String galleryResourceGroup;

    private String agentLaunchMethod;

    private boolean preInstallSsh;

    private String initScript;

    private String terminateScript;

    private boolean executeInitScriptAsRoot;

    private boolean doNotUseMachineIfInitFails;

    private boolean enableMSI;

    private boolean enableUAMI;

    private String uamiID;

    private String virtualNetworkName;

    private String virtualNetworkResourceGroupName;

    private String subnetName;

    private boolean usePrivateIP;

    private String nsgName;

    private String jvmOptions;

    private String noOfParallelJobs;

    private boolean templateDisabled;

    public AdvancedImage(String imageReferenceType,
                         String image,
                         String osType,
                         String imageId,
                         String imagePublisher,
                         String imageOffer,
                         String imageSku,
                         String imageVersion,
                         String galleryName,
                         String galleryImageDefinition,
                         String galleryImageVersion,
                         boolean galleryImageSpecialized,
                         String gallerySubscriptionId,
                         String galleryResourceGroup,
                         String agentLaunchMethod,
                         boolean preInstallSsh,
                         String initScript,
                         String terminateScript,
                         boolean executeInitScriptAsRoot,
                         boolean doNotUseMachineIfInitFails,
                         boolean enableMSI,
                         boolean enableUAMI,
                         String uamiID,
                         String virtualNetworkName,
                         String virtualNetworkResourceGroupName,
                         String subnetName,
                         boolean usePrivateIP,
                         String nsgName,
                         String jvmOptions,
                         String noOfParallelJobs,
                         boolean templateDisabled) {
        this.imageReferenceType = imageReferenceType;
        this.image = image;
        this.osType = osType;
        this.imageId = imageId;
        this.imagePublisher = imagePublisher;
        this.imageOffer = imageOffer;
        this.imageSku = imageSku;
        this.imageVersion = imageVersion;
        this.galleryName = galleryName;
        this.galleryImageDefinition = galleryImageDefinition;
        this.galleryImageVersion = galleryImageVersion;
        this.galleryImageSpecialized = galleryImageSpecialized;
        this.gallerySubscriptionId = gallerySubscriptionId;
        this.galleryResourceGroup = galleryResourceGroup;
        this.agentLaunchMethod = agentLaunchMethod;
        this.preInstallSsh = preInstallSsh;
        this.initScript = initScript;
        this.terminateScript = terminateScript;
        this.executeInitScriptAsRoot = executeInitScriptAsRoot;
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
        this.enableMSI = enableMSI;
        this.enableUAMI = enableUAMI;
        this.uamiID = uamiID;
        this.virtualNetworkName = virtualNetworkName;
        this.virtualNetworkResourceGroupName = virtualNetworkResourceGroupName;
        this.subnetName = subnetName;
        this.usePrivateIP = usePrivateIP;
        this.nsgName = nsgName;
        this.jvmOptions = jvmOptions;
        this.noOfParallelJobs = noOfParallelJobs;
        this.templateDisabled = templateDisabled;
    }

    public String getImageReferenceType() {
        return imageReferenceType;
    }

    public String getImage() {
        return image;
    }

    public String getOsType() {
        return osType;
    }

    public String getImageId() {
        return imageId;
    }

    public String getImagePublisher() {
        return imagePublisher;
    }

    public String getImageOffer() {
        return imageOffer;
    }

    public String getImageSku() {
        return imageSku;
    }

    public String getImageVersion() {
        return imageVersion;
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

    public String getGallerySubscriptionId() {
        return gallerySubscriptionId;
    }

    public String getGalleryResourceGroup() {
        return galleryResourceGroup;
    }

    public String getAgentLaunchMethod() {
        return agentLaunchMethod;
    }

    public boolean isPreInstallSsh() {
        return preInstallSsh;
    }

    public String getInitScript() {
        return initScript;
    }

    public String getTerminateScript() {
        return terminateScript;
    }

    public boolean isExecuteInitScriptAsRoot() {
        return executeInitScriptAsRoot;
    }

    public boolean isDoNotUseMachineIfInitFails() {
        return doNotUseMachineIfInitFails;
    }

    public boolean isEnableMSI() {
        return enableMSI;
    }

    public boolean isEnableUAMI() {
        return enableUAMI;
    }

    public String getUamiID() {
        return uamiID;
    }

    public String getVirtualNetworkName() {
        return virtualNetworkName;
    }

    public String getVirtualNetworkResourceGroupName() {
        return virtualNetworkResourceGroupName;
    }

    public String getSubnetName() {
        return subnetName;
    }

    public boolean isUsePrivateIP() {
        return usePrivateIP;
    }

    public String getNsgName() {
        return nsgName;
    }

    public String getJvmOptions() {
        return jvmOptions;
    }

    public String getNoOfParallelJobs() {
        return noOfParallelJobs;
    }

    public boolean isTemplateDisabled() {
        return templateDisabled;
    }
}
