package com.microsoft.azure.vmagent.builders;


public class AdvancedImageBuilder extends AdvancedImageFluent<AdvancedImageBuilder> {

    private AdvancedImageFluent<?> fluent;

    public AdvancedImageBuilder(AdvancedImageFluent<?> fluent) {
        this.fluent = fluent;
    }

    public AdvancedImageBuilder(AdvancedImageFluent<?> fluent, AdvancedImage image) {
        this.fluent = fluent;
        fluent.withCustomImage(image.getImage());
        fluent.withReferenceImage(image.getImagePublisher(),
                image.getImageOffer(),
                image.getImageSku(),
                image.getImageVersion());
        fluent.withNumberOfExecutors(String.valueOf(image.getNoOfParallelJobs()));
        fluent.withOsType(image.getOsType());
        fluent.withLaunchMethod(image.getAgentLaunchMethod());
        fluent.withPreInstallSsh(image.isPreInstallSsh());
        fluent.withInitScript(image.getInitScript());
        fluent.withVirtualNetworkName(image.getVirtualNetworkName());
        fluent.withVirtualNetworkResourceGroupName(image.getVirtualNetworkResourceGroupName());
        fluent.withSubnetName(image.getSubnetName());
        fluent.withUsePrivateIP(image.isUsePrivateIP());
        fluent.withNetworkSecurityGroupName(image.getNsgName());
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
        fluent.withCustomImage(image.getImage());
        fluent.withReferenceImage(image.getImagePublisher(),
                image.getImageOffer(),
                image.getImageSku(),
                image.getImageVersion());
        fluent.withNumberOfExecutors(String.valueOf(image.getNoOfParallelJobs()));
        fluent.withOsType(image.getOsType());
        fluent.withLaunchMethod(image.getAgentLaunchMethod());
        fluent.withPreInstallSsh(image.isPreInstallSsh());
        fluent.withInitScript(image.getInitScript());
        fluent.withVirtualNetworkName(image.getVirtualNetworkName());
        fluent.withVirtualNetworkResourceGroupName(image.getVirtualNetworkResourceGroupName());
        fluent.withSubnetName(image.getSubnetName());
        fluent.withUsePrivateIP(image.isUsePrivateIP());
        fluent.withNetworkSecurityGroupName(image.getNsgName());
        fluent.withJvmOptions(image.getJvmOptions());
        fluent.withDisableTemplate(image.isTemplateDisabled());
        fluent.withRunScriptAsRoot(image.isExecuteInitScriptAsRoot());
        fluent.withDoNotUseMachineIfInitFails(image.isDoNotUseMachineIfInitFails());
    }

    public AdvancedImage build() {
        return new AdvancedImage(fluent.getImageReferenceType(),
                fluent.getImage(),
                fluent.getOsType(),
                fluent.getImagePublisher(),
                fluent.getImageOffer(),
                fluent.getImageSku(),
                fluent.getImageVersion(),
                fluent.getAgentLaunchMethod(),
                fluent.isPreInstallSsh(),
                fluent.getInitScript(),
                fluent.isExecuteInitScriptAsRoot(),
                fluent.isDoNotUseMachineIfInitFails(),
                fluent.getVirtualNetworkName(),
                fluent.getVirtualNetworkResourceGroupName(),
                fluent.getSubnetName(),
                fluent.isUsePrivateIP(),
                fluent.getNsgName(),
                fluent.getJvmOptions(),
                fluent.getNoOfParallelJobs(),
                fluent.isTemplateDisabled());
    }
}
