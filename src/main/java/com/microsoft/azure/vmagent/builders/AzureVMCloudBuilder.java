package com.microsoft.azure.vmagent.builders;

import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import com.microsoft.azure.vmagent.AzureVMCloud;
import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
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

    public AzureVMCloudBuilder(AzureVMCloud cloud) {
        cloudName = cloud.getCloudName();
        azureCredentialsId = cloud.getAzureCredentialsId();
        maxVirtualMachinesLimit = String.valueOf(cloud.getMaxVirtualMachinesLimit());
        deploymentTimeout = String.valueOf(cloud.getDeploymentTimeout());
        resourceGroupReferenceType = cloud.getResourceGroupReferenceType();
        newResourceGroupName = cloud.getNewResourceGroupName();
        existingResourceGroupName = cloud.getExistingResourceGroupName();
        // getVmTemplates returns unmodifiableList
        vmTemplates = new ArrayList<>();
        vmTemplates.addAll(cloud.getVmTemplates());
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
        Collections.addAll(this.vmTemplates, templates);
        return this;
    }

    public AzureVMTemplateNested addNewTemplate() {
        return new AzureVMTemplateNested();
    }

    public AzureVMTemplateNested addNewTemplateLike(AzureVMAgentTemplate template) {
        return new AzureVMTemplateNested(template);
    }
    //CHECKSTYLE:ON

    public AzureVMCloud build() {
        return new AzureVMCloud(StringUtils.defaultString(cloudName),
                StringUtils.defaultString(azureCredentialsId),
                maxVirtualMachinesLimit,
                deploymentTimeout,
                resourceGroupReferenceType,
                StringUtils.defaultString(newResourceGroupName),
                StringUtils.defaultString(existingResourceGroupName),
                vmTemplates);
    }

    public class AzureVMTemplateNested extends AzureVMTemplateFluent<AzureVMTemplateNested> {

        private final AzureVMTemplateBuilder builder;

        AzureVMTemplateNested() {
            this.builder = new AzureVMTemplateBuilder(this);
        }

        AzureVMTemplateNested(AzureVMAgentTemplate template) {
            this.builder = new AzureVMTemplateBuilder(this, template);
        }

        public AzureVMCloudBuilder endTemplate() {
            return AzureVMCloudBuilder.this.addToTemplates(builder.build());
        }
    }
}
