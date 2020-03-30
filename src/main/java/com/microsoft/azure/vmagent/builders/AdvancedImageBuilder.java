package com.microsoft.azure.vmagent.builders;


import com.microsoft.azure.vmagent.ImageReferenceType;

public class AdvancedImageBuilder extends AdvancedImageFluent<AdvancedImageBuilder> {

    private AdvancedImageFluent<?> fluent;

    public AdvancedImageBuilder(AdvancedImageFluent<?> fluent) {
        this.fluent = fluent;
    }

    public AdvancedImageBuilder(AdvancedImageFluent<?> fluent, AdvancedImage image) {
        this.fluent = fluent;
        if (ImageReferenceType.CUSTOM.getName().equals(image.getImageReferenceType())) {
            fluent.withCustomImage(image.getImage());
        } else if (ImageReferenceType.CUSTOM_IMAGE.getName().equals(image.getImageReferenceType())) {
            fluent.withCustomManagedImage(image.getImageId());
        } else {
            fluent.withReferenceImage(image.getImagePublisher(),
                    image.getImageOffer(),
                    image.getImageSku(),
                    image.getImageVersion());
        }
        fluent.withNumberOfExecutors(String.valueOf(image.getNoOfParallelJobs()));
        fluent.withOsType(image.getOsType());
        fluent.withLaunchMethod(image.getAgentLaunchMethod());
        fluent.withPreInstallSsh(image.isPreInstallSsh());
        fluent.withInitScript(image.getInitScript());
        fluent.withTerminateScript(image.getTerminateScript());
        fluent.withVirtualNetworkName(image.getVirtualNetworkName());
        fluent.withVirtualNetworkResourceGroupName(image.getVirtualNetworkResourceGroupName());
        fluent.withSubnetName(image.getSubnetName());
        fluent.withUsePrivateIP(image.isUsePrivateIP());
        fluent.withNetworkSecurityGroupName(image.getNsgName());
        fluent.withLoadBalancerName(image.getLoadBalancerName());
        fluent.withLoadBalancerResourceGroupName(image.getLoadBalancerResourceGroupName());
        fluent.withBackendPoolName(image.getBackendPoolName());
        fluent.withJvmOptions(image.getJvmOptions());
        fluent.withDisableTemplate(image.isTemplateDisabled());
        fluent.withRunScriptAsRoot(image.isExecuteInitScriptAsRoot());
        fluent.withDoNotUseMachineIfInitFails(image.isDoNotUseMachineIfInitFails());

    }

    public AdvancedImageBuilder() {
        this.fluent = this;
    }

    public AdvancedImageBuilder(AdvancedImage image) {
        this.fluent = this;
        if (ImageReferenceType.CUSTOM.getName().equals(image.getImageReferenceType())) {
            fluent.withCustomImage(image.getImage());
        } else if (ImageReferenceType.CUSTOM_IMAGE.getName().equals(image.getImageReferenceType())) {
            fluent.withCustomManagedImage(image.getImageId());
        } else {
            fluent.withReferenceImage(image.getImagePublisher(),
                    image.getImageOffer(),
                    image.getImageSku(),
                    image.getImageVersion());
        }
        fluent.withNumberOfExecutors(String.valueOf(image.getNoOfParallelJobs()));
        fluent.withOsType(image.getOsType());
        fluent.withLaunchMethod(image.getAgentLaunchMethod());
        fluent.withPreInstallSsh(image.isPreInstallSsh());
        fluent.withInitScript(image.getInitScript());
        fluent.withTerminateScript(image.getTerminateScript());
        fluent.withVirtualNetworkName(image.getVirtualNetworkName());
        fluent.withVirtualNetworkResourceGroupName(image.getVirtualNetworkResourceGroupName());
        fluent.withSubnetName(image.getSubnetName());
        fluent.withUsePrivateIP(image.isUsePrivateIP());
        fluent.withNetworkSecurityGroupName(image.getNsgName());
        fluent.withLoadBalancerName(image.getLoadBalancerName());
        fluent.withLoadBalancerResourceGroupName(image.getLoadBalancerResourceGroupName());
        fluent.withBackendPoolName(image.getBackendPoolName());
        fluent.withJvmOptions(image.getJvmOptions());
        fluent.withDisableTemplate(image.isTemplateDisabled());
        fluent.withRunScriptAsRoot(image.isExecuteInitScriptAsRoot());
        fluent.withDoNotUseMachineIfInitFails(image.isDoNotUseMachineIfInitFails());
    }

    public AdvancedImage build() {
        return new AdvancedImage(fluent.getImageReferenceType(),
                fluent.getImage(),
                fluent.getOsType(),
                fluent.getImageId(),
                fluent.getImagePublisher(),
                fluent.getImageOffer(),
                fluent.getImageSku(),
                fluent.getImageVersion(),
                fluent.getGalleryName(),
                fluent.getGalleryImageDefinition(),
                fluent.getGalleryImageVersion(),
                fluent.getGallerySubscriptionId(),
                fluent.getGalleryResourceGroup(),
                fluent.getAgentLaunchMethod(),
                fluent.isPreInstallSsh(),
                fluent.getInitScript(),
                fluent.getTerminateScript(),
                fluent.isExecuteInitScriptAsRoot(),
                fluent.isDoNotUseMachineIfInitFails(),
                fluent.isEnableMSI(),
                fluent.isEnableUAMI(),
                fluent.getUamiID(),
                fluent.getVirtualNetworkName(),
                fluent.getVirtualNetworkResourceGroupName(),
                fluent.getSubnetName(),
                fluent.isUsePrivateIP(),
                fluent.getNsgName(),
                fluent.getLoadBalancerName(),
                fluent.getLoadBalancerResourceGroupName(),
                fluent.getBackendPoolName(),
                fluent.getJvmOptions(),
                fluent.getNoOfParallelJobs(),
                fluent.isTemplateDisabled());
    }
}
