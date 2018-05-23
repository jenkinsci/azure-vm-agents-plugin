package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.util.CleanUpAction;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.logging.Level;
import java.util.logging.Logger;

public class AzureVMCloudOnceRetentionStrategy extends AzureVMCloudBaseRetentionStrategy implements ExecutorListener {
    private static final Logger LOGGER = Logger.getLogger(AzureVMManagementServiceDelegate.class.getName());
    private static final long serialVersionUID = 1566788691L;
    private static final transient long IDLE_MILLIS = TimeUnit2.MINUTES.toMillis(1);
    private static final transient long LAPSE = TimeUnit2.SECONDS.toMillis(5);

    @DataBoundConstructor
    public AzureVMCloudOnceRetentionStrategy() {

    }

    @Override
    public long check(final AzureVMComputer agentComputer) {
        if (agentComputer.isIdle()) {
            final boolean neverConnected =
                    agentComputer.getIdleStartMilliseconds() - agentComputer.getConnectTime() < LAPSE;
            final long idleMilliseconds = System.currentTimeMillis() - agentComputer.getIdleStartMilliseconds();
            if (!neverConnected && idleMilliseconds > IDLE_MILLIS) {
                done(agentComputer);
            }
        }
        return 1;
    }

    @Override
    public void start(AzureVMComputer azureComputer) {
        LOGGER.log(Level.INFO, "AzureVMCloudOnceRetentionStrategy: start: azureComputer name {0}",
                azureComputer.getDisplayName());
        azureComputer.connect(false);
        resetShutdownVMStatus(azureComputer.getNode());
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
        final AzureVMAgent agent = computer.getNode();
        if (agent == null) {
            return;
        }

        AzureVMComputer azureComputer = (AzureVMComputer) computer;

        azureComputer.setAcceptingTasks(false);
        if (agent.isShutdownOnIdle()) {
            LOGGER.log(Level.INFO, "AzureVMCloudOnceRetentionStrategy: Tagging VM to shutdown when idle: {0}",
                    azureComputer.getName());
            agent.setCleanUpAction(CleanUpAction.SHUTDOWN, Messages._Build_Action_Shutdown_Agent());
        } else {
            LOGGER.log(Level.INFO, "AzureVMCloudOnceRetentionStrategy: Tagging VM to delete when idle: {0}",
                    azureComputer.getName());
            agent.setCleanUpAction(CleanUpAction.DELETE, Messages._Build_Action_Delete_Agent());
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new AzureVMCloudOnceRetentionStrategy.DescriptorImpl();

    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "Azure VM Once Retention Strategy";
        }
    }
}
