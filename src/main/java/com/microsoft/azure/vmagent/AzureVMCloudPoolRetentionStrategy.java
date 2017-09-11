package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.retry.LinearRetryForAllExceptions;
import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.ExecutionEngine;
import com.microsoft.azure.vmagent.util.PoolLock;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AzureVMCloudPoolRetentionStrategy extends RetentionStrategy<AzureVMComputer> {
    private final long retentionMillis;

    private final int poolSize;

    private static final long IDLE_LIMIT_MILLIS = TimeUnit2.MINUTES.toMillis(1);

    private static final Logger LOGGER = Logger.getLogger(AzureVMManagementServiceDelegate.class.getName());

    @DataBoundConstructor
    public AzureVMCloudPoolRetentionStrategy(int idleTerminationHours, int poolSize) {
        this.retentionMillis = TimeUnit2.HOURS.toMillis(idleTerminationHours);
        this.poolSize = poolSize > 1 ? poolSize : 1;
    }

    public int getPoolSize() {
        return poolSize;
    }

    @Override
    public long check(AzureVMComputer agentComputer) {
        Cloud cloud = agentComputer.getNode().getCloud();
        if (cloud == null || !(cloud instanceof AzureVMCloud)) {
            //cloud has changed
            tryDeleteWhenIdle(agentComputer);
            return 1;
        }

        AzureVMCloud currentCloud = (AzureVMCloud) cloud;
        if (!currentCloud.getVmTemplates().contains(agentComputer.getNode().getTemplate())) {
            //template has changed
            tryDeleteWhenIdle(agentComputer);
            return 1;
        }

        if (System.currentTimeMillis() - agentComputer.getNode().getCreationTime() > retentionMillis) {
            //exceed retention limit
            tryDeleteWhenIdle(agentComputer);
            return 1;
        }

        checkPoolSizeAndDelete(agentComputer, poolSize);
        return 1;
    }

    private static void tryDeleteWhenIdle(final AzureVMComputer agentComputer) {
        final AzureVMAgent agentNode = agentComputer.getNode();
        if (agentComputer.isIdle() && agentNode != null) {
            if (System.currentTimeMillis() - agentComputer.getIdleStartMilliseconds() > IDLE_LIMIT_MILLIS) {
                final AzureVMAgentTemplate template = agentNode.getTemplate();

                final Callable<Void> task = new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        try {
                            PoolLock.deprovisionLock(template);
                            agentNode.blockCleanUpAction();
                            agentNode.deprovision(Messages._Idle_Timeout_Delete());
                            return null;
                        } finally {
                            PoolLock.deprovisionUnlock(template);
                        }
                    }
                };

                try {
                    final int maxRetries = 30;
                    final int waitInterval = 30;
                    final int defaultTimeoutInSeconds = 30 * 60;
                    new ExecutionEngine().executeAsync(task,
                            new LinearRetryForAllExceptions(
                                    maxRetries,
                                    waitInterval,
                                    defaultTimeoutInSeconds
                            ));
                } catch (Exception e) {
                    LOGGER.log(Level.INFO,
                            "AzureVMCloudRetensionStrategy: check: "
                                    + "Exception occured while calling timeout on node {0}: {1}",
                            new Object[]{agentComputer.getName(), e});
                    // If we have an exception, set the agent for deletion.
                    // It's unlikely we'll be able to shut it down properly ever.
                    AzureVMAgent node = agentComputer.getNode();
                    if (node != null) {
                        node.setCleanUpAction(CleanUpAction.DELETE, Messages._Failed_Initial_Shutdown_Or_Delete());
                    }
                }

            }
        }
    }

    private static synchronized void checkPoolSizeAndDelete(AzureVMComputer agentComputer, int poolSize) {
        if (PoolLock.checkDeprovisionLock(agentComputer.getNode().getTemplate())) {
            return;
        }
        int count = 0;
        List<Computer> computers = Arrays.asList(Jenkins.getInstance().getComputers());
        for (Computer computer : computers) {
            if (computer instanceof AzureVMComputer
                    && ((AzureVMComputer) computer).getNode() != null
                    && ((AzureVMComputer) computer).getNode().getTemplate()
                    .equals(agentComputer.getNode().getTemplate())) {
                count++;
                if (count > poolSize) {
                    tryDeleteWhenIdle(agentComputer);
                    return;
                }
            }
        }
    }

    @Override
    public void start(AzureVMComputer azureComputer) {
        //TODO: check when this method is getting called and add code accordingly
        LOGGER.log(Level.INFO, "AzureVMCloudRetensionStrategy: start: azureComputer name {0}",
                azureComputer.getDisplayName());
        azureComputer.connect(false);
    }

    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {

        @Override
        public String getDisplayName() {
            return Constants.AZURE_CLOUD_DISPLAY_NAME;
        }
    }
}
