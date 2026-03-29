package com.microsoft.azure.vmagent.builders;

import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import com.microsoft.azure.vmagent.AzureVMCloud;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

public class AzureVMCloudBuilder {

    private static final Logger LOGGER = Logger.getLogger(AzureVMCloudBuilder.class.getName());
    private static final int DEFAULT_MAX_VIRTUAL_MACHINES_LIMIT = 10;

    private String cloudName;

    private String azureCredentialsId;

    private int maxVirtualMachinesLimit;

    private String deploymentTimeout;

    private String resourceGroupReferenceType;

    private String newResourceGroupName;

    private String existingResourceGroupName;

    private List<AzureVMAgentTemplate> vmTemplates;

    public AzureVMCloudBuilder() {
        maxVirtualMachinesLimit = DEFAULT_MAX_VIRTUAL_MACHINES_LIMIT;
        deploymentTimeout = "1200";
        resourceGroupReferenceType = "new";
        vmTemplates = new ArrayList<>();
    }

    public AzureVMCloudBuilder(AzureVMCloud cloud) {
        cloudName = cloud.getCloudName();
        azureCredentialsId = cloud.getAzureCredentialsId();
        maxVirtualMachinesLimit = cloud.getMaxVirtualMachinesLimit();
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

    public AzureVMCloudBuilder withMaxVirtualMachinesLimit(int maxVirtualMachinesLimit) {
        this.maxVirtualMachinesLimit = maxVirtualMachinesLimit;
        return this;
    }

    @Deprecated
    public AzureVMCloudBuilder withMaxVirtualMachinesLimit(String maxVirtualMachinesLimit) {
        if (StringUtils.isBlank(maxVirtualMachinesLimit) || !maxVirtualMachinesLimit.matches("\\d+")) {
            LOGGER.warning("Couldn't parse maxVirtualMachinesLimit, defaulting to "
                    + DEFAULT_MAX_VIRTUAL_MACHINES_LIMIT);
            this.maxVirtualMachinesLimit = DEFAULT_MAX_VIRTUAL_MACHINES_LIMIT;
        } else {
            LOGGER.warning("deprecated: use the version of this method that is an int");
            this.maxVirtualMachinesLimit = Integer.parseInt(maxVirtualMachinesLimit);
        }
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
                String.valueOf(maxVirtualMachinesLimit),
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
