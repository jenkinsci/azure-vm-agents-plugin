package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.PoolLock;
import com.microsoft.azure.vmagent.util.TemplateUtil;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class AzureVMMaintainPoolTask extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(AzureVMMaintainPoolTask.class.getName());

    private static final int RECURRENCE_PERIOD_IN_MILLIS = 1 * 60 * 1000;

    public AzureVMMaintainPoolTask() {
        super("Azure VM Maintainer Pool Size");
    }

    public void maintain(AzureVMCloud cloud, AzureVMAgentTemplate template) {
        LOGGER.log(Level.INFO, "Starting to maintain template: {0}", template.getTemplateName());
        int currentSize = 0;
        final int sizeLimit = ((AzureVMCloudPoolRetentionStrategy) template.getRetentionStrategy()).getPoolSize();

        if (PoolLock.checkProvisionLock(template)) {
            LOGGER.log(Level.INFO, "Agents of template {0} is creating, check later", template);
            return;
        }

        for (Computer computer : Jenkins.getInstance().getComputers()) {
            if (computer instanceof AzureVMComputer) {
                AzureVMComputer azureVMComputer = (AzureVMComputer) computer;
                AzureVMAgent agent = azureVMComputer.getNode();
                if (agent != null
                        && agent.getTemplate().getTemplateName().equals(template.getTemplateName())
                        && TemplateUtil.checkSame(agent.getTemplate(), template)) {
                    currentSize++;
                }
            }
        }
        if (currentSize < sizeLimit) {
            LOGGER.log(Level.INFO, "Prepare for provisioning {0} agents for template {1}",
                    new Object[]{sizeLimit - currentSize, template.getTemplateName()});
            provisionNodes(cloud, template, sizeLimit - currentSize);
        }
    }

    public void provisionNodes(AzureVMCloud cloud, AzureVMAgentTemplate template, int newAgents) {
        if (cloud.getConfigurationStatus().equals(Constants.UNVERIFIED)
                || template.getTemplateConfigurationStatus().equals(Constants.UNVERIFIED)) {
            AzureVMCloudVerificationTask.verify(cloud.getCloudName(), template.getTemplateName());
        }
        if (cloud.getConfigurationStatus().equals(Constants.VERIFIED_PASS)
                && template.getTemplateConfigurationStatus().equals(Constants.VERIFIED_PASS)) {
            cloud.doProvision(newAgents, new ArrayList<NodeProvisioner.PlannedNode>(), template, true);
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
}
