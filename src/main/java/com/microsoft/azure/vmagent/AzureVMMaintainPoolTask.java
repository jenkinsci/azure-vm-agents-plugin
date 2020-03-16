package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.util.PoolLock;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class AzureVMMaintainPoolTask extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(AzureVMMaintainPoolTask.class.getName());

    // 30 Seconds
    private static final int RECURRENCE_PERIOD_IN_MILLIS = 30 * 1000;

    public AzureVMMaintainPoolTask() {
        super("Azure VM Maintainer Pool Size");
    }

    public void maintain(AzureVMCloud cloud, AzureVMAgentTemplate template) {
        LOGGER.log(getNormalLoggingLevel(), "Starting to maintain template: {0}", template.getTemplateName());
        final int poolSize = ((AzureVMCloudPoolRetentionStrategy) template.getRetentionStrategy()).getPoolSize();

        if (PoolLock.checkProvisionLock(template)) {
            LOGGER.log(getNormalLoggingLevel(), "Agents of template {0} is creating, check later", template);
            return;
        }

        // safe cast because of check in execute function
        final AzureVMCloudPoolRetentionStrategy retentionStrategy =
            (AzureVMCloudPoolRetentionStrategy) template.getRetentionStrategy();
        final boolean sparePool = retentionStrategy.getSparePool();
        int agentsToProvision = 0;
        if (!sparePool) {
            // Ensure poolSize agents are connected
            LOGGER.log(Level.FINE, "Maintaining absolute pool");
            final int currentNumberOfAgentsForTemplate =
                AzureVMCloudPoolRetentionStrategy.countCurrentNumberOfAgents(template);
            if (currentNumberOfAgentsForTemplate < poolSize) {
                agentsToProvision = poolSize - currentNumberOfAgentsForTemplate;
            }
        } else {
            // Ensure poolsize spare agents are available
            LOGGER.log(Level.FINE, "Maintaining spare pool");
            final int buildsWaitingForTemplate =
                AzureVMCloudPoolRetentionStrategy.countQueueItemsForAgentTemplate(template);
            final int currentNumberOfSpareAgentsForTemplate =
                AzureVMCloudPoolRetentionStrategy.countCurrentNumberOfSpareAgents(template);
            final int numberOfProvisioningAgentsForTemplate =
                AzureVMCloudPoolRetentionStrategy.countCurrentNumberOfProvisioningAgents(template);
            agentsToProvision = (poolSize + buildsWaitingForTemplate)
                              - (currentNumberOfSpareAgentsForTemplate + numberOfProvisioningAgentsForTemplate);

            LOGGER.log(Level.FINE, "Q: {0}, Spare: {1}, Provisioning: {2}, Needed: {3}",
                new Object[]{
                    buildsWaitingForTemplate,
                    currentNumberOfSpareAgentsForTemplate,
                    numberOfProvisioningAgentsForTemplate,
                    agentsToProvision
                }
            );
        }

        if (agentsToProvision > 0) {
            LOGGER.log(getNormalLoggingLevel(), "Prepare for provisioning {0} agents for template {1}",
                    new Object[]{agentsToProvision, template.getTemplateName()});
            provisionNodes(cloud, template, agentsToProvision);
        }
    }

    public void provisionNodes(AzureVMCloud cloud, AzureVMAgentTemplate template, int newAgents) {
        if (!template.getTemplateProvisionStrategy().isVerifiedPass()) {
            AzureVMCloudVerificationTask.verify(cloud.getCloudName(), template.getTemplateName());
        }
        if (template.getTemplateProvisionStrategy().isVerifiedPass()) {
            cloud.doProvision(newAgents,
                    new ArrayList<NodeProvisioner.PlannedNode>(),
                    template,
                    true,
                    new HashMap<String, String>());
        } else {
            LOGGER.log(Level.WARNING, "Template {0} failed to verify, cannot be provisioned",
                    template.getTemplateName());
        }
    }

    @Override
    public void execute(TaskListener arg0) {
        for (Cloud cloud : Jenkins.getInstance().clouds) {
            if (cloud instanceof AzureVMCloud) {
                AzureVMCloud azureVMCloud = (AzureVMCloud) cloud;
                for (AzureVMAgentTemplate template : azureVMCloud.getVmTemplates()) {
                    if (template.getRetentionStrategy() instanceof AzureVMCloudPoolRetentionStrategy) {
                        maintain(azureVMCloud, template);
                    }
                }
            }
        }
    }

    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD_IN_MILLIS;
    }

    @Override
    protected Level getNormalLoggingLevel() {
        return Level.FINE;
    }
}
