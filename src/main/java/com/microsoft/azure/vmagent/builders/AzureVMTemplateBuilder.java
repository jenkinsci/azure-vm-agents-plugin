package com.microsoft.azure.vmagent.builders;

import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import com.microsoft.azure.vmagent.AzureVMCloudBaseRetentionStrategy;

public class AzureVMTemplateBuilder extends AzureVMTemplateFluent<AzureVMTemplateBuilder> {

    private AzureVMTemplateFluent<?> fluent;

    public AzureVMTemplateBuilder() {
        this.fluent = this;
    }

    public AzureVMTemplateBuilder(AzureVMAgentTemplate template) {
        this.fluent = this;
        fluent.withName(template.getTemplateName());
        fluent.withDescription(template.getTemplateDesc());
        fluent.withLabels(template.getLabels());
        fluent.withLocation(template.getLocation());
        fluent.withAvailability(template.getAvailabilityInside());
        fluent.withVirtualMachineSize(template.getVirtualMachineSize());
        if (template.getStorageAccountNameReferenceType().equalsIgnoreCase("new")) {
            fluent.withNewStorageAccount(template.getNewStorageAccountName());
        } else {
            fluent.withExistingStorageAccount(template.getExistingStorageAccountName());
        }
        fluent.withStorageAccountType(template.getStorageAccountType());
        fluent.withDiskType(template.getDiskType());
        fluent.withEphemeralOSDisk(template.isEphemeralOSDisk());
        fluent.withOsDiskSize(template.getOsDiskSize());
        fluent.withRetentionStrategy((AzureVMCloudBaseRetentionStrategy) template.getRetentionStrategy());
        fluent.withUsageMode(template.getUsageMode());
        fluent.withAdminCredential(template.getCredentialsId());
        fluent.withWorkspace(template.getAgentWorkspace());
        fluent.withShutdownOnIdle(template.isShutdownOnIdle());

        if (template.getImageTopLevelType().equalsIgnoreCase("basic")) {
            fluent.withBuiltInImage(template.getBuiltInImageInside());
        } else {
            fluent.withAdvancedImage(template.getAdvancedImageInside());
        }
    }

    public AzureVMTemplateBuilder(AzureVMTemplateFluent<?> fluent) {
        this.fluent = fluent;
    }

    public AzureVMTemplateBuilder(AzureVMTemplateFluent<?> fluent, AzureVMAgentTemplate template) {
        this.fluent = fluent;
        fluent.withName(template.getTemplateName());
        fluent.withDescription(template.getTemplateDesc());
        fluent.withLabels(template.getLabels());
        fluent.withLocation(template.getLocation());
        fluent.withAvailability(template.getAvailabilityInside());
        fluent.withVirtualMachineSize(template.getVirtualMachineSize());
        if (template.getStorageAccountNameReferenceType().equalsIgnoreCase("new")) {
            fluent.withNewStorageAccount(template.getNewStorageAccountName());
        } else {
            fluent.withExistingStorageAccount(template.getExistingStorageAccountName());
        }
        fluent.withStorageAccountType(template.getStorageAccountType());
        fluent.withDiskType(template.getDiskType());
        fluent.withOsDiskSize(template.getOsDiskSize());
        fluent.withRetentionStrategy((AzureVMCloudBaseRetentionStrategy) template.getRetentionStrategy());
        fluent.withUsageMode(template.getUsageMode());
        fluent.withAdminCredential(template.getCredentialsId());
        fluent.withWorkspace(template.getAgentWorkspace());
        fluent.withShutdownOnIdle(template.isShutdownOnIdle());

        if (template.getImageTopLevelType().equalsIgnoreCase("basic")) {
            fluent.withBuiltInImage(template.getBuiltInImageInside());
        } else {
            fluent.withAdvancedImage(template.getAdvancedImageInside());
        }
    }

    public AzureVMAgentTemplate build() {
        return new AzureVMAgentTemplate(
                fluent.getName(),
                fluent.getDescription(),
                fluent.getLabels(),
                fluent.getLocation(),
                new AzureVMAgentTemplate.AvailabilityTypeClass(fluent.getAvailability().getAvailabilitySet()),
                fluent.getVirtualMachineSize(),
                fluent.getStorageAccountNameReferenceType(),
                fluent.getStorageAccountType(),
                fluent.getNewStorageAccountName(),
                fluent.getExistingStorageAccountName(),
                fluent.getDiskType(),
                fluent.isEphemeralOSDisk(),
                fluent.getOsDiskSize(),
                fluent.getAdvancedImage().getNoOfParallelJobs(),
                fluent.getUsageMode(),
                fluent.getBuiltInImage().getBuiltInImage(),
                fluent.getBuiltInImage().isInstallGit(),
                fluent.getBuiltInImage().isInstallMaven(),
                fluent.getBuiltInImage().isInstallDocker(),
                fluent.getAdvancedImage().getOsType(),
                fluent.getImageTopLevelType(),
                new AzureVMAgentTemplate.ImageReferenceTypeClass(fluent.getAdvancedImage().getImage(),
                        fluent.getAdvancedImage().getImageId(),
                        fluent.getAdvancedImage().getImagePublisher(),
                        fluent.getAdvancedImage().getImageOffer(),
                        fluent.getAdvancedImage().getImageSku(),
                        fluent.getAdvancedImage().getImageVersion(),
                        fluent.getAdvancedImage().getGalleryName(),
                        fluent.getAdvancedImage().getGalleryImageDefinition(),
                        fluent.getAdvancedImage().getGalleryImageVersion(),
                        fluent.getAdvancedImage().getGallerySubscriptionId(),
                        fluent.getAdvancedImage().getGalleryResourceGroup()),
                fluent.getAdvancedImage().getAgentLaunchMethod(),
                fluent.getAdvancedImage().isPreInstallSsh(),
                fluent.getAdvancedImage().getInitScript(),
                fluent.getAdvancedImage().getTerminateScript(),
                fluent.getCredentialsId(),
                fluent.getAdvancedImage().getVirtualNetworkName(),
                fluent.getAdvancedImage().getVirtualNetworkResourceGroupName(),
                fluent.getAdvancedImage().getSubnetName(),
                fluent.getAdvancedImage().isUsePrivateIP(),
                fluent.getAdvancedImage().getNsgName(),
                fluent.getWorkspace(),
                fluent.getAdvancedImage().getJvmOptions(),
                fluent.getRetentionStrategy(),
                fluent.isShutdownOnIdle(),
                fluent.getAdvancedImage().isTemplateDisabled(),
                fluent.getAdvancedImage().isExecuteInitScriptAsRoot(),
                fluent.getAdvancedImage().isDoNotUseMachineIfInitFails(),
                fluent.getAdvancedImage().isEnableMSI(),
                fluent.getAdvancedImage().isEnableUAMI(),
                fluent.getAdvancedImage().getUamiID());
    }
}
