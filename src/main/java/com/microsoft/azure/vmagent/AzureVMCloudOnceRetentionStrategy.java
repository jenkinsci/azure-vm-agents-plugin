package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.util.CleanUpAction;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.RetentionStrategy;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AzureVMCloudOnceRetentionStrategy extends AzureVMCloudBaseRetentionStrategy implements ExecutorListener {
    private static final Logger LOGGER = Logger.getLogger(AzureVMManagementServiceDelegate.class.getName());
    private static final long serialVersionUID = 1566788691L;
    private static final transient long IDLE_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final transient long LAPSE = TimeUnit.SECONDS.toMillis(5);

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
        LOGGER.log(Level.FINE, "Starting azureComputer name {0}",
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

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new AzureVMCloudOnceRetentionStrategy.DescriptorImpl();

    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override @NonNull
        public String getDisplayName() {
            return "Azure VM Once Retention Strategy";
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
