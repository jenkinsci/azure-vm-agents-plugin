package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.TemplateUtil;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.slaves.Cloud;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

public class AzureVMCloudPoolRetentionStrategy extends AzureVMCloudBaseRetentionStrategy implements ExecutorListener {
    private static final long serialVersionUID = 1577788691L;

    private final long retentionMillis;

    private final int poolSize;

    private final boolean sparePool;

    private final boolean singleUse;

    private static final long IDLE_LIMIT_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private static final Logger LOGGER = Logger.getLogger(AzureVMManagementServiceDelegate.class.getName());

    @DataBoundConstructor
    public AzureVMCloudPoolRetentionStrategy(int retentionInHours, int poolSize, boolean sparePool, boolean singleUse) {
        retentionInHours = retentionInHours >= 0 ? retentionInHours : 0;
        this.retentionMillis = TimeUnit.HOURS.toMillis(retentionInHours);
        this.poolSize = poolSize >= 0 ? poolSize : 0;
        this.sparePool = sparePool;
        this.singleUse = singleUse;
    }

    @Deprecated
    public AzureVMCloudPoolRetentionStrategy(int retentionInHours, int poolSize) {
        this(retentionInHours, poolSize, false, false);
    }

    @Override
    public long check(final AzureVMComputer agentComputer) {
        final AzureVMAgent agentNode = agentComputer.getNode();
        if (agentNode == null) {
            return 1;
        }

        final Cloud cloud = agentNode.getCloud();
        if (cloud == null) {
            //cloud has changed
            LOGGER.log(Level.INFO, "Delete VM {0} for cloud not found", agentComputer);
            Computer.threadPoolForRemoting.submit(new Runnable() {
                @Override
                public void run() {
                    tryDeleteWhenIdle(agentComputer);
                }
            });
            return 1;
        }

        AzureVMCloud currentCloud = (AzureVMCloud) cloud;
        AzureVMAgentTemplate currentTemplate = null;
        boolean isContainsTemplate = false;
        for (AzureVMAgentTemplate template : currentCloud.getVmTemplates()) {
            if (template.getRetentionStrategy() instanceof AzureVMCloudPoolRetentionStrategy
                    && TemplateUtil.checkSame(template, agentNode.getTemplate())) {
                isContainsTemplate = true;
                currentTemplate = template;
                break;
            }
        }

        if (!isContainsTemplate) {
            //template has changed
            LOGGER.log(Level.INFO, "Delete VM {0} for template {1} not found",
                    new Object[] {agentComputer, agentNode.getTemplate().getTemplateName()});
            Computer.threadPoolForRemoting.submit(new Runnable() {
                @Override
                public void run() {
                    tryDeleteWhenIdle(agentComputer);
                }
            });
            return 1;
        }

        if (retentionMillis != 0
                && System.currentTimeMillis() - agentNode.getCreationTime() > retentionMillis) {
            //exceed retention limit
            LOGGER.log(Level.INFO, "Delete VM {0} for timeout", agentComputer);
            Computer.threadPoolForRemoting.submit(new Runnable() {
                @Override
                public void run() {
                    tryDeleteWhenIdle(agentComputer);
                }
            });
            return 1;
        }

        // in single use mode, agents are removed as soon as they have been used,
        // pool size is checked when provisioning
        final int currentPoolSize
                = ((AzureVMCloudPoolRetentionStrategy) currentTemplate.getRetentionStrategy()).getPoolSize();
        Computer.threadPoolForRemoting.submit(new Runnable() {
            @Override
            public void run() {
                checkPoolSizeAndDelete(agentComputer, currentPoolSize, sparePool);
            }
        });

        return 1;
    }

    private static void tryDeleteWhenIdle(final AzureVMComputer agentComputer) {
        final AzureVMAgent agentNode = agentComputer.getNode();
        if (agentComputer.isIdle() && agentNode != null) {
            if (System.currentTimeMillis() - agentComputer.getIdleStartMilliseconds() > IDLE_LIMIT_MILLIS) {
                try {
                    agentNode.blockCleanUpAction();
                    agentNode.deprovision(Messages._Idle_Timeout_Delete());
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING,
                            String.format("AzureVMCloudRetensionStrategy: check: "
                                    + "Exception occurred while calling timeout on node %s", agentComputer.getName()),
                            e);
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

    private static Stream<Computer> agentsForTemplate(@Nonnull AzureVMAgentTemplate agentTemplate) {
        return (Stream<Computer>) Arrays.stream(Jenkins.getInstance().getComputers())
                .filter(computer -> computer instanceof AzureVMComputer)
                .filter(computer -> {
                    AzureVMAgentTemplate computerTemplate = ((AzureVMComputer) computer).getTemplate();
                    return computerTemplate != null
                            && TemplateUtil.checkSame(computerTemplate, agentTemplate);
                });
    }

    public static int countCurrentNumberOfAgents(@Nonnull AzureVMAgentTemplate agentTemplate) {
        return (int) agentsForTemplate(agentTemplate).count();
    }

    public static int countCurrentNumberOfSpareAgents(@Nonnull AzureVMAgentTemplate agentTemplate) {
        return (int) agentsForTemplate(agentTemplate)
            .filter(computer -> computer.countBusy() == 0)
            .filter(computer -> computer.isOnline())
            .count();
    }

    public static int countCurrentNumberOfProvisioningAgents(@Nonnull AzureVMAgentTemplate agentTemplate) {
        return (int) agentsForTemplate(agentTemplate)
            .filter(computer -> computer.countBusy() == 0)
            .filter(computer -> computer.isOffline())
            .filter(computer -> computer.isConnecting())
            .count();
    }

        /*
        Get the number of queued builds that match an AMI (agentTemplate)
    */
    public static int countQueueItemsForAgentTemplate(@Nonnull AzureVMAgentTemplate agentTemplate) {
        return (int)
            Queue
            .getInstance()
            .getBuildableItems()
            .stream()
            .map((Queue.Item item) -> item.getAssignedLabel())
            .filter(Objects::nonNull)
            .filter((Label label) -> label.matches(agentTemplate.getLabelDataSet()))
            .count();
    }

    private static synchronized void checkPoolSizeAndDelete(AzureVMComputer agentComputer,
                                                            int poolSize, boolean sparePool) {
        int count = 0;
        List<Computer> computers = Arrays.asList(Jenkins.getInstance().getComputers());
        for (Computer computer : computers) {
            if (computer instanceof AzureVMComputer) {
                AzureVMAgent patternAgentNode = ((AzureVMComputer) computer).getNode();
                AzureVMAgent templateAgentNode = agentComputer.getNode();
                if (patternAgentNode != null && templateAgentNode != null
                        && TemplateUtil.checkSame(patternAgentNode.getTemplate(), templateAgentNode.getTemplate())) {
                    count++;
                }
            }
        }

        AzureVMAgentTemplate template = agentComputer.getTemplate();
        int currentNumberOfAgentsForTemplate = countCurrentNumberOfAgents(template);
        if (!sparePool) {
            // poolSize is the desired number of nodes regardless of utilisation
            if (currentNumberOfAgentsForTemplate > poolSize) {
                LOGGER.log(Level.INFO, "Delete VM {0} for pool size exceed limit: {1}",
                        new Object[]{agentComputer, count});
                tryDeleteWhenIdle(agentComputer);
                return;
            }
        } else {
            // poolSize is the desired number of spare nodes
            int currentNumberOfSpareAgents = countCurrentNumberOfSpareAgents(template);
            if (currentNumberOfSpareAgents > poolSize) {
                LOGGER.log(Level.INFO, "Delete VM {0} for spare node count exceeds limit: {1}",
                        new Object[]{agentComputer, count});
                tryDeleteWhenIdle(agentComputer);
                return;
            }
        }
    }

    public long getRetentionInHours() {
        return TimeUnit.MILLISECONDS.toHours(retentionMillis);
    }

    public int getPoolSize() {
        return poolSize;
    }

    public boolean getSparePool() {
        return sparePool;
    }

    public boolean getSingleUse() {
        return singleUse;
    }

    @Override
    public void start(AzureVMComputer azureComputer) {
        //TODO: check when this method is getting called and add code accordingly
        LOGGER.log(Level.INFO, "AzureVMCloudPoolRetentionStrategy: start: azureComputer name {0}",
                azureComputer.getDisplayName());
        azureComputer.connect(false);
        resetShutdownVMStatus(azureComputer.getNode());
    }

    public void cleanSingleUseAgent(Executor executor) {
        Computer computer = executor.getOwner();
        if (!(computer instanceof AzureVMComputer)) {
            return;
        }
        AzureVMComputer azureComputer = (AzureVMComputer) computer;
        AzureVMAgent agent = azureComputer.getNode();
        if (agent == null) {
            return;
        }
        azureComputer.setAcceptingTasks(false);
        if (agent.isShutdownOnIdle()) {
            LOGGER.log(Level.INFO, "AzureVMCloudPoolRetentionStrategy: Tagging VM to shutdown when idle: {0}",
                    azureComputer.getName());
            agent.setCleanUpAction(CleanUpAction.SHUTDOWN, Messages._Build_Action_Shutdown_Agent());
        } else {
            LOGGER.log(Level.INFO, "AzureVMCloudPoolRetentionStrategy: Tagging VM to delete when idle: {0}",
                    azureComputer.getName());
            agent.setCleanUpAction(CleanUpAction.DELETE, Messages._Build_Action_Delete_Agent());
        }

    }


    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        // Not needed, but required by ExecutorListener interface.
    }

    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        if (singleUse) {
           cleanSingleUseAgent(executor);
        }
    }

    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        if (singleUse) {
            cleanSingleUseAgent(executor);
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {

        @Override
        public String getDisplayName() {
            return "Azure VM Pool Retention Strategy";
        }
    }

    @Extension
    public static class DescriptorVisibilityFilterImpl extends DescriptorVisibilityFilter {
        @Override
        public boolean filter(@CheckForNull Object context, @NonNull Descriptor descriptor) {
            return !(descriptor instanceof DescriptorImpl);
        }
    }
}
