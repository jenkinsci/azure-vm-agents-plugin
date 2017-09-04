package com.microsoft.azure.vmagent.builders;


public class AdvancedImage extends AdvancedImageParam {
    public AdvancedImage(String imageReferenceType,
                         String image,
                         String osType,
                         String imagePublisher,
                         String imageOffer,
                         String imageSku,
                         String imageVersion,
                         String agentLaunchMethod,
                         boolean preInstallSsh,
                         String initScript,
                         boolean executeInitScriptAsRoot,
                         boolean doNotUseMachineIfInitFails,
                         String virtualNetworkName,
                         String virtualNetworkResourceGroupName,
                         String subnetName,
                         boolean usePrivateIP,
                         String nsgName,
                         String jvmOptions,
                         int noOfParallelJobs,
                         boolean templateDisabled) {
        this.imageReferenceType = imageReferenceType;
        this.image = image;
        this.osType = osType;
        this.imagePublisher = imagePublisher;
        this.imageOffer = imageOffer;
        this.imageSku = imageSku;
        this.imageVersion = imageVersion;
        this.agentLaunchMethod = agentLaunchMethod;
        this.preInstallSsh = preInstallSsh;
        this.initScript = initScript;
        this.executeInitScriptAsRoot = executeInitScriptAsRoot;
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
        this.virtualNetworkName = virtualNetworkName;
        this.virtualNetworkResourceGroupName = virtualNetworkResourceGroupName;
        this.subnetName = subnetName;
        this.usePrivateIP = usePrivateIP;
        this.nsgName = nsgName;
        this.jvmOptions = jvmOptions;
        this.noOfParallelJobs = noOfParallelJobs;
        this.templateDisabled = templateDisabled;
    }
}
