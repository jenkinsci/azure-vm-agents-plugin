package com.microsoft.azure.vmagent;

import hudson.model.Computer;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;

import java.io.Serializable;

public abstract class AzureVMCloudBaseRetentionStrategy extends RetentionStrategy<AzureVMComputer>
        implements Serializable {

    private static final transient long LAPSE_START_JENKINS = TimeUnit2.MINUTES.toMillis(3);

    public void resetShutdownVMStatus(final AzureVMAgent agent) {
        if (System.currentTimeMillis() - Jenkins.getInstance().toComputer().getConnectTime() < LAPSE_START_JENKINS) {
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
