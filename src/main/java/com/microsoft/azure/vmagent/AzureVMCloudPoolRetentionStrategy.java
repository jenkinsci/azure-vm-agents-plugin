package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.TemplateUtil;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.slaves.Cloud;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AzureVMCloudPoolRetentionStrategy extends AzureVMCloudBaseRetentionStrategy {
    private static final long serialVersionUID = 1577788691L;

    private final long retentionMillis;

    private final int poolSize;

    private static final long IDLE_LIMIT_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private static final Logger LOGGER = Logger.getLogger(AzureVMManagementServiceDelegate.class.getName());

    @DataBoundConstructor
    public AzureVMCloudPoolRetentionStrategy(int retentionInHours, int poolSize) {
        retentionInHours = retentionInHours >= 0 ? retentionInHours : 0;
        this.retentionMillis = TimeUnit.HOURS.toMillis(retentionInHours);
        this.poolSize = poolSize >= 0 ? poolSize : 0;
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

    private static synchronized void checkPoolSizeAndDelete(AzureVMComputer agentComputer, int poolSize) {
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
    public void start(AzureVMComputer azureComputer) {
        //TODO: check when this method is getting called and add code accordingly
        LOGGER.log(Level.INFO, "AzureVMCloudRetensionStrategy: start: azureComputer name {0}",
                azureComputer.getDisplayName());
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
