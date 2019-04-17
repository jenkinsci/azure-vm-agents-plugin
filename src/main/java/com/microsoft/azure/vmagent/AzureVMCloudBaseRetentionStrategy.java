package com.microsoft.azure.vmagent;

import hudson.model.Computer;
import hudson.node_monitors.DiskSpaceMonitor;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

public abstract class AzureVMCloudBaseRetentionStrategy extends RetentionStrategy<AzureVMComputer>
        implements Serializable {

    private static final transient long LAPSE_START_JENKINS = TimeUnit.MINUTES.toMillis(3);
    private static final int FREE_SPACE_THRESHOLD_MB = 100;
    private static final int BYTES_IN_MB = 1024 * 1024;

    public void resetShutdownVMStatus(final AzureVMAgent agent) {
        Computer computer = Jenkins.getInstance().toComputer();
        if (computer != null
                && System.currentTimeMillis() - computer.getConnectTime() < LAPSE_START_JENKINS) {
            Computer.threadPoolForRemoting.submit(new Runnable() {
                @Override
                public void run() {
                    if (agent.getTemplate().isShutdownOnIdle()) {
                        agent.setEligibleForReuse(false);
                        agent.shutdown(agent.getCleanUpReason());
                        agent.blockCleanUpAction();
                    }
                }
            });
        }
    }

    protected void checkDiskSpace(AzureVMComputer agent) {
        DiskSpaceMonitor monitor = new DiskSpaceMonitor();
        DiskSpaceMonitorDescriptor.DiskSpace freeSpace = monitor.getFreeSpace(agent);
        long freeSpaceInMb = freeSpace.size / BYTES_IN_MB;
        if (freeSpaceInMb < FREE_SPACE_THRESHOLD_MB) {
            agent.setTemporarilyOffline(true, OfflineCause.create(Messages._Limit_Disk_Space()));
        } else {
            if (agent.isOffline() && agent.getOfflineCauseReason().equals(Messages.Limit_Disk_Space())) {
                agent.setTemporarilyOffline(false, null);
            }

        }
    }
}
