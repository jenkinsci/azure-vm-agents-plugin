package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.util.DynamicBufferCalculator;
import com.microsoft.azure.vmagent.util.PoolLock;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class AzureVMMaintainPoolTask extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(AzureVMMaintainPoolTask.class.getName());

    private static final int RECURRENCE_PERIOD_IN_MILLIS = 5 * 60 * 1000;

    public AzureVMMaintainPoolTask() {
        super("Azure VM Maintainer Pool Size");
    }

    public void maintain(AzureVMCloud cloud, AzureVMAgentTemplate template) {
        LOGGER.log(getNormalLoggingLevel(), "Starting to maintain template: {0}", template.getTemplateName());

        if (PoolLock.checkProvisionLock(template)) {
            LOGGER.log(getNormalLoggingLevel(), "Agents of template {0} is creating, check later", template);
            return;
        }

        AzureVMCloudPoolRetentionStrategy retentionStrategy =
                (AzureVMCloudPoolRetentionStrategy) template.getRetentionStrategy();

        // Calculate current metrics using the utility class
        DynamicBufferCalculator.BufferMetrics metrics =
                DynamicBufferCalculator.calculateBufferMetrics(template);

        // Calculate the effective pool size (static or dynamic based on configuration)
        final int effectivePoolSize = retentionStrategy.calculateEffectivePoolSize(
                metrics.getBusyMachines(),
                metrics.getQueuedItems());

        LOGGER.log(getNormalLoggingLevel(),
                "Template {0}: busy={1}, idle={2}, total={3}, queued={4}, effectivePoolSize={5}",
                new Object[]{
                        template.getTemplateName(),
                        metrics.getBusyMachines(),
                        metrics.getIdleMachines(),
                        metrics.getTotalMachines(),
                        metrics.getQueuedItems(),
                        effectivePoolSize
                });

        int currentSize = metrics.getTotalMachines();

        if (currentSize < effectivePoolSize) {
            // Determine how many nodes to provision
            int deploymentSize = effectivePoolSize - currentSize;
            if (template.getMaximumDeploymentSize() > 0 && deploymentSize > template.getMaximumDeploymentSize()) {
                deploymentSize = template.getMaximumDeploymentSize();
            }
            LOGGER.log(getNormalLoggingLevel(), "Prepare for provisioning {0} agents for template {1}",
                    new Object[]{deploymentSize, template.getTemplateName()});
            provisionNodes(cloud, template, deploymentSize);
        }
    }

    public void provisionNodes(AzureVMCloud cloud, AzureVMAgentTemplate template, int newAgents) {
        if (!template.retrieveTemplateProvisionStrategy().isVerifiedPass()) {
            AzureVMCloudVerificationTask.verify(cloud.getCloudName(), template.getTemplateName());
        }
        if (template.retrieveTemplateProvisionStrategy().isVerifiedPass()) {
            cloud.doProvision(newAgents,
                    new ArrayList<>(),
                    template,
                    true
                    );
        } else {
            LOGGER.log(Level.WARNING, "Template {0} failed to verify, cannot be provisioned",
                    template.getTemplateName());
        }
    }

    @Override
    public void execute(TaskListener arg0) {
        for (Cloud cloud : Jenkins.get().clouds) {
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
