package com.microsoft.azure.vmagent.builders;

import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import com.microsoft.azure.vmagent.AzureVMCloud;

import java.util.ArrayList;
import java.util.List;

public class AzureVMCloudBuilder {

    private String cloudName;

    private String azureCredentialsId;

    private String maxVirtualMachinesLimit;

    private String deploymentTimeout;

    private String resourceGroupReferenceType;

    private String newResourceGroupName;

    private String existingResourceGroupName;

    private List<AzureVMAgentTemplate> vmTemplates;

    public AzureVMCloudBuilder() {
        maxVirtualMachinesLimit = "10";
        deploymentTimeout = "1200";
        resourceGroupReferenceType = "new";
        vmTemplates = new ArrayList<>();
    }

    //CHECKSTYLE:OFF
    public AzureVMCloudBuilder withCloudName(String cloudName) {
        this.cloudName = cloudName;
        return this;
    }

    public AzureVMCloudBuilder withAzureCredentialsId(String azureCredentialsId) {
        this.azureCredentialsId = azureCredentialsId;
        return this;
    }

    public AzureVMCloudBuilder withMaxVirtualMachinesLimit(String maxVirtualMachinesLimit) {
        this.maxVirtualMachinesLimit = maxVirtualMachinesLimit;
        return this;
    }

    public AzureVMCloudBuilder withDeploymentTimeout(String deploymentTimeout) {
        this.deploymentTimeout = deploymentTimeout;
        return this;
    }

    public AzureVMCloudBuilder withNewResourceGroupName(String resourceGroupName) {
        this.resourceGroupReferenceType = "new";
        this.newResourceGroupName = resourceGroupName;
        return this;
    }

    public AzureVMCloudBuilder withExistingResourceGroupName(String resourceGroupName) {
        this.resourceGroupReferenceType = "existing";
        this.existingResourceGroupName = resourceGroupName;
        return this;
    }

    public AzureVMCloudBuilder withTemplates(List<AzureVMAgentTemplate> templates) {
        this.vmTemplates.clear();
        this.vmTemplates.addAll(templates);
        return this;
    }

    public AzureVMCloudBuilder addToTemplates(List<AzureVMAgentTemplate> templates) {
        this.vmTemplates.addAll(templates);
        return this;
    }

    public AzureVMCloudBuilder addToTemplates(AzureVMAgentTemplate... templates) {
        for (AzureVMAgentTemplate template : templates) {
            this.vmTemplates.add(template);
        }
        return this;
    }

    public AzureVMTemplateNested addNewTemplate() {
        return new AzureVMTemplateNested();
    }
    //CHECKSTYLE:ON

    public AzureVMCloud build() {
        return new AzureVMCloud(cloudName,
                "",
                azureCredentialsId,
                maxVirtualMachinesLimit,
                deploymentTimeout,
                resourceGroupReferenceType,
                newResourceGroupName,
                existingResourceGroupName,
                vmTemplates);
    }

    public class AzureVMTemplateNested extends AzureVMTemplateFluent<AzureVMTemplateNested> {

        private final AzureVMTemplateBuilder builder;

        AzureVMTemplateNested() {
            this.builder = new AzureVMTemplateBuilder(this);
        }

        public AzureVMCloudBuilder endTemplate() {
            return AzureVMCloudBuilder.this.addToTemplates(builder.build());
        }
    }







}
