package com.microsoft.azure.vmagent.builders;


import com.microsoft.azure.vmagent.util.Constants;

public class AdvancedImageBuilder extends AdvancedImageParam {

    public AdvancedImageBuilder() {
        this.imageReferenceType = "reference";
        this.imageVersion = "latest";
        this.osType = Constants.OS_TYPE_LINUX;
        this.agentLaunchMethod = Constants.LAUNCH_METHOD_SSH;
        this.preInstallSsh = true;
        this.executeInitScriptAsRoot = true;
        this.doNotUseMachineIfInitFails = true;
        this.usePrivateIP = false;
        this.noOfParallelJobs = 1;
        this.templateDisabled = false;
    }

    public AdvancedImageBuilder withCustomerImage(String imageUrl) {
        this.imageReferenceType = "custom";
        this.image = imageUrl;
        return this;
    }

    public AdvancedImageBuilder withReferenceImage(String imagePublisher,
                                                   String imageOffer,
                                                   String imageSku,
                                                   String imageVersion) {
        this.imageReferenceType = "reference";
        this.imagePublisher = imagePublisher;
        this.imageOffer = imageOffer;
        this.imageSku = imageSku;
        this.imageVersion = imageVersion;
        return this;
    }

    public AdvancedImageBuilder withOsType(String osType) {
        this.osType = osType;
        return this;
    }

    public AdvancedImageBuilder withLaunchMethod(String launchMethod) {
        this.agentLaunchMethod = launchMethod;
        return this;
    }

    public AdvancedImageBuilder withPreInstallSsh(boolean preInstallSsh) {
        this.preInstallSsh = preInstallSsh;
        return this;
    }

    public AdvancedImageBuilder withInitScript(String initScript) {
        this.initScript = initScript;
        return this;
    }

    public AdvancedImageBuilder withRunScriptAsRoot(boolean executeInitScriptAsRoot) {
        this.executeInitScriptAsRoot = executeInitScriptAsRoot;
        return this;
    }

    public AdvancedImageBuilder withDoNotUseMachineIfInitFails(boolean doNotUseMachineIfInitFails) {
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
        return this;
    }

    public AdvancedImageBuilder withVirtualNetworkName(String virtualNetworkName) {
        this.virtualNetworkName = virtualNetworkName;
        return this;
    }

    public AdvancedImageBuilder withVirtualNetworkResourceGroupName(String virtualNetworkResourceGroupName) {
        this.virtualNetworkResourceGroupName = virtualNetworkResourceGroupName;
        return this;
    }

    public AdvancedImageBuilder withSubnetName(String subnetName) {
        this.subnetName = subnetName;
        return this;
    }

    public AdvancedImageBuilder withUsePrivateIP(boolean usePrivateIP) {
        this.usePrivateIP = usePrivateIP;
        return this;
    }

    public AdvancedImageBuilder withNetworkSecurityGroupName(String nsgName) {
        this.nsgName = nsgName;
        return this;
    }

    public AdvancedImageBuilder withJvmOptions(String jvmOptions) {
        this.jvmOptions = jvmOptions;
        return this;
    }

    public AdvancedImageBuilder withNumberOfExecutors(int noOfParallelJobs) {
        this.noOfParallelJobs = noOfParallelJobs;
        return this;
    }
    public AdvancedImageBuilder withDisableTemplate(boolean templateDisabled) {
        this.templateDisabled = templateDisabled;
        return this;
    }

    public AdvancedImage build() {
        return new AdvancedImage(imageReferenceType,
                image,
                osType,
                imagePublisher,
                imageOffer,
                imageSku,
                imageVersion,
                agentLaunchMethod,
                preInstallSsh,
                initScript,
                executeInitScriptAsRoot,
                doNotUseMachineIfInitFails,
                virtualNetworkName,
                virtualNetworkResourceGroupName,
                subnetName,
                usePrivateIP,
                nsgName,
                jvmOptions,
                noOfParallelJobs,
                templateDisabled);
    }

}
