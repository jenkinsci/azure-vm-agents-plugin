package com.microsoft.azure.vmagent;

import hudson.model.Computer;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

public abstract class AzureVMCloudBaseRetentionStrategy extends RetentionStrategy<AzureVMComputer>
        implements Serializable {

    private static final transient long LAPSE_START_JENKINS = TimeUnit.MINUTES.toMillis(3);

    public void resetShutdownVMStatus(final AzureVMAgent agent) {
        Computer computer = Jenkins.get().toComputer();
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
}
