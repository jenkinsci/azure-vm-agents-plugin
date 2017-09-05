package com.microsoft.azure.vmagent.builders;


public class AdvancedImageBuilder extends AdvancedImageFluent<AdvancedImageBuilder> {

    private AdvancedImageFluent<?> fluent;

    public AdvancedImageBuilder(AdvancedImageFluent<?> fluent) {
        this.fluent = fluent;
    }

    public AdvancedImageBuilder() {
        this.fluent = this;
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
