package com.microsoft.azure.vmagent.builders;


import com.microsoft.azure.vmagent.ImageReferenceType;
import com.microsoft.azure.vmagent.util.Constants;

public class AdvancedImageFluent<T extends AdvancedImageFluent<T>> {

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

    public AdvancedImageFluent() {
        this.imageReferenceType = ImageReferenceType.REFERENCE.getName();
        this.imageVersion = "latest";
        this.osType = Constants.OS_TYPE_LINUX;
        this.agentLaunchMethod = Constants.LAUNCH_METHOD_SSH;
        this.preInstallSsh = true;
        this.executeInitScriptAsRoot = true;
        this.doNotUseMachineIfInitFails = true;
        this.enableMSI = false;
        this.enableUAMI = false;
        this.uamiID = "";
        this.usePrivateIP = false;
        this.noOfParallelJobs = "1";
        this.templateDisabled = false;
    }

    //CHECKSTYLE:OFF
    public T withCustomImage(String imageUrl) {
        this.imageReferenceType = ImageReferenceType.CUSTOM.getName();
        this.image = imageUrl;
        return (T) this;
    }

    public T withCustomManagedImage(String imageId) {
        this.imageReferenceType = ImageReferenceType.CUSTOM_IMAGE.getName();
        this.imageId = imageId;
        return (T) this;
    }

    public T withReferenceImage(String imagePublisher,
                                String imageOffer,
                                String imageSku,
                                String imageVersion) {
        this.imageReferenceType = ImageReferenceType.REFERENCE.getName();
        this.imagePublisher = imagePublisher;
        this.imageOffer = imageOffer;
        this.imageSku = imageSku;
        this.imageVersion = imageVersion;
        return (T) this;
    }

    public T withGalleryImage(String galleryName,
                              String galleryImageDefinition,
                              String galleryImageVersion,
                              String gallerySubscriptionId,
                              String galleryResourceGroup) {
        this.imageReferenceType = ImageReferenceType.GALLERY.getName();
        this.galleryName = galleryName;
        this.galleryImageDefinition = galleryImageDefinition;
        this.galleryImageVersion = galleryImageVersion;
        this.gallerySubscriptionId = gallerySubscriptionId;
        this.galleryResourceGroup = galleryResourceGroup;
        return (T) this;
    }

    public T withOsType(String osType) {
        this.osType = osType;
        return (T) this;
    }

    public T withLaunchMethod(String launchMethod) {
        this.agentLaunchMethod = launchMethod;
        return (T) this;
    }

    public T withPreInstallSsh(boolean preInstallSsh) {
        this.preInstallSsh = preInstallSsh;
        return (T) this;
    }

    public T withInitScript(String initScript) {
        this.initScript = initScript;
        return (T) this;
    }

    public T withTerminateScript(String terminateScript) {
        this.terminateScript = terminateScript;
        return (T) this;
    }

    public T withRunScriptAsRoot(boolean executeInitScriptAsRoot) {
        this.executeInitScriptAsRoot = executeInitScriptAsRoot;
        return (T) this;
    }

    public T withDoNotUseMachineIfInitFails(boolean doNotUseMachineIfInitFails) {
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
        return (T) this;
    }

    public T withEnableMSI(boolean enableMSI) {
        this.enableMSI = enableMSI;
        return (T) this;
    }    
    
    public T withEnableUAMI(boolean enableUAMI) {
        this.enableUAMI = enableUAMI;
        return (T) this;
    }
    
    public T withGetUamiID(String uamiID) {
        this.uamiID = uamiID;
        return (T) this;
    }

    public T withVirtualNetworkName(String virtualNetworkName) {
        this.virtualNetworkName = virtualNetworkName;
        return (T) this;
    }

    public T withVirtualNetworkResourceGroupName(String virtualNetworkResourceGroupName) {
        this.virtualNetworkResourceGroupName = virtualNetworkResourceGroupName;
        return (T) this;
    }

    public T withSubnetName(String subnetName) {
        this.subnetName = subnetName;
        return (T) this;
    }

    public T withUsePrivateIP(boolean usePrivateIP) {
        this.usePrivateIP = usePrivateIP;
        return (T) this;
    }

    public T withNetworkSecurityGroupName(String nsgName) {
        this.nsgName = nsgName;
        return (T) this;
    }

    public T withJvmOptions(String jvmOptions) {
        this.jvmOptions = jvmOptions;
        return (T) this;
    }

    public T withNumberOfExecutors(String noOfParallelJobs) {
        this.noOfParallelJobs = noOfParallelJobs;
        return (T) this;
    }
    public T withDisableTemplate(boolean templateDisabled) {
        this.templateDisabled = templateDisabled;
        return (T) this;
    }
    //CHECKSTYLE:ON

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
