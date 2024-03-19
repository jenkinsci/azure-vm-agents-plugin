package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.TemplateUtil;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Computer;
import hudson.model.Queue;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.slaves.Cloud;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AzureVMCloudPoolRetentionStrategy extends AzureVMCloudBaseRetentionStrategy implements ExecutorListener {
    private static final long serialVersionUID = 1577788691L;

    private final long retentionMillis;

    private final int poolSize;

    private boolean singleUseAgents;

    private static final long IDLE_LIMIT_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private static final Logger LOGGER = Logger.getLogger(AzureVMManagementServiceDelegate.class.getName());

    @DataBoundConstructor
    public AzureVMCloudPoolRetentionStrategy(int retentionInHours, int poolSize, boolean singleUseAgents) {
        retentionInHours = Math.max(retentionInHours, 0);
        this.retentionMillis = TimeUnit.HOURS.toMillis(retentionInHours);
        this.poolSize = Math.max(poolSize, 0);
        this.singleUseAgents = singleUseAgents;
    }

    @Deprecated
    public AzureVMCloudPoolRetentionStrategy(int retentionInHours, int poolSize) {
        retentionInHours = Math.max(retentionInHours, 0);
        this.retentionMillis = TimeUnit.HOURS.toMillis(retentionInHours);
        this.poolSize = Math.max(poolSize, 0);
        this.singleUseAgents = false;
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

        final int currentPoolSize
                = ((AzureVMCloudPoolRetentionStrategy) currentTemplate.getRetentionStrategy()).getPoolSize();

        Computer.threadPoolForRemoting.submit(new Runnable() {
            @Override
            public void run() {
                checkPoolSizeAndDelete(agentComputer, currentPoolSize);
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
                            String.format("Exception occurred while calling timeout on node %s",
                                    agentComputer.getName()), e);
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
        int count = 0;
        List<Computer> computers = Arrays.asList(Jenkins.get().getComputers());
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

        if (count > poolSize) {
            LOGGER.log(Level.INFO, "Delete VM {0} for pool size exceed limit: {1}",
                    new Object[]{agentComputer, count});
            tryDeleteWhenIdle(agentComputer);
            return;
        }
    }

    public long getRetentionInHours() {
        return TimeUnit.MILLISECONDS.toHours(retentionMillis);
    }

    public int getPoolSize() {
        return poolSize;
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {

    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        done(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        done(executor);
    }

    public void done(Executor executor) {
        final AbstractCloudComputer<?> computer = (AbstractCloudComputer) executor.getOwner();
        if (!(computer instanceof AzureVMComputer)) {
            return;
        }
        done((AzureVMComputer) computer);
    }
    public void done(AzureVMComputer computer) {
        if (!isSingleUseAgents()) {
            return;
        }

        final AzureVMAgent agent = computer.getNode();
        if (agent == null) {
            return;
        }

        computer.setAcceptingTasks(false);
        if (agent.isShutdownOnIdle()) {
            LOGGER.log(Level.FINE, "Tagging VM to shutdown when idle: {0}",
                    computer.getName());
            agent.setCleanUpAction(CleanUpAction.SHUTDOWN, Messages._Build_Action_Shutdown_Agent());
        } else {
            LOGGER.log(Level.FINE, "Tagging VM to delete when idle: {0}", computer.getName());
            agent.setCleanUpAction(CleanUpAction.DELETE, Messages._Build_Action_Delete_Agent());
        }
    }

    public boolean isSingleUseAgents() {
        return this.singleUseAgents;
    }

    @DataBoundSetter
    public void setSingleUseAgents(boolean singleUseAgents) {
        this.singleUseAgents = singleUseAgents;
    }

    @Override
    public void start(AzureVMComputer azureComputer) {
        //TODO: check when this method is getting called and add code accordingly
        LOGGER.log(Level.INFO, "Starting azureComputer {0}", azureComputer.getDisplayName());
        azureComputer.connect(false);
        resetShutdownVMStatus(azureComputer.getNode());
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override @NonNull
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
