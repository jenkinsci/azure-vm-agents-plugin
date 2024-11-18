package com.microsoft.azure.vmagent;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;

/**
 * Implementation of {@link NodeProvisioner.Strategy} which will provision a new node immediately as
 * a task enter the queue.
 * In Azure, we don't really need to wait before provisioning a new node,
 * because Azure agents can be started and destroyed quickly
 *
 * @author <a href="mailto:root@junwuhui.cn">runzexia</a>
 */
@Extension(ordinal = AzureVMNoDelayProvisionerStrategy.ORDER)
public class AzureVMNoDelayProvisionerStrategy extends NodeProvisioner.Strategy {

    private static final Logger LOGGER = Logger.getLogger(AzureVMNoDelayProvisionerStrategy.class.getName());
    private static final boolean DISABLE_NO_DELAY_PROVISIONING =
            SystemProperties.getBoolean(
                    AzureVMNoDelayProvisionerStrategy.class.getName() + ".disableNoDelayProvisioning");
    private static final boolean DISABLE_CLOUD_SHUFFLE =
            SystemProperties.getBoolean(
                    AzureVMNoDelayProvisionerStrategy.class.getName() + ".disableCloudShuffle");
    public static final int ORDER = 101;

    @NonNull
    @Override
    public NodeProvisioner.StrategyDecision apply(@NonNull NodeProvisioner.StrategyState strategyState) {
        if (DISABLE_NO_DELAY_PROVISIONING) {
            LOGGER.log(Level.FINE, "Provisioning not complete, NoDelayProvisionerStrategy is disabled");
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }

        final Label label = strategyState.getLabel();

        LoadStatistics.LoadStatisticsSnapshot snapshot = strategyState.getSnapshot();
        int availableCapacity = snapshot.getAvailableExecutors() // live executors
                + snapshot.getConnectingExecutors() // executors present but not yet connected
                + strategyState
                        .getPlannedCapacitySnapshot() // capacity added by previous strategies from previous rounds
                + strategyState.getAdditionalPlannedCapacity(); // capacity added by previous strategies _this round_
        int previousCapacity = availableCapacity;
        int currentDemand = snapshot.getQueueLength();
        LOGGER.log(
                Level.FINE, "Available capacity={0}, currentDemand={1}", new Object[] {availableCapacity, currentDemand
                });
        if (availableCapacity < currentDemand) {
            List<Cloud> jenkinsClouds = new ArrayList<>(Jenkins.get().clouds);
            if (!DISABLE_CLOUD_SHUFFLE) {
                Collections.shuffle(jenkinsClouds);
            }

            Cloud.CloudState cloudState = new Cloud.CloudState(label, strategyState.getAdditionalPlannedCapacity());

            searchClouds:
            for (Cloud cloud : jenkinsClouds) {
                int workloadToProvision = currentDemand - availableCapacity;
                if (!(cloud instanceof AzureVMCloud)) {
                    continue;
                }
                if (!cloud.canProvision(cloudState)) {
                    continue;
                }
                for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                    if (cl.canProvision(cloud, cloudState, workloadToProvision) != null) {
                        continue searchClouds;
                    }
                }

                Collection<NodeProvisioner.PlannedNode> plannedNodes = cloud.provision(cloudState, workloadToProvision);
                LOGGER.log(Level.FINE, "Planned {0} new nodes", plannedNodes.size());
                fireOnStarted(cloud, strategyState.getLabel(), plannedNodes);
                strategyState.recordPendingLaunches(plannedNodes);
                availableCapacity += plannedNodes.size();
                LOGGER.log(Level.FINE, "After provisioning, available capacity={0}, currentDemand={1}", new Object[] {
                    availableCapacity, currentDemand
                });
                break;
            }
        }
        if (availableCapacity > previousCapacity && label != null) {
            LOGGER.log(Level.FINE, "Suggesting NodeProvisioner review");
            Timer.get().schedule(label.nodeProvisioner::suggestReviewNow, 1L, TimeUnit.SECONDS);
        }
        if (availableCapacity >= currentDemand) {
            LOGGER.log(Level.FINE, "Provisioning completed");
            return NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
        } else {
            LOGGER.log(Level.FINE, "Provisioning not complete, consulting remaining strategies");
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }
    }

    private static void fireOnStarted(
            final Cloud cloud, final Label label, final Collection<NodeProvisioner.PlannedNode> plannedNodes) {
        for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
            try {
                cl.onStarted(cloud, label, plannedNodes);
            } catch (Error e) {
                throw e;
            } catch (Throwable e) {
                LOGGER.log(
                        Level.SEVERE,
                        "Unexpected uncaught exception encountered while "
                                + "processing onStarted() listener call in " + cl + " for label "
                                + label.toString(),
                        e);
            }
        }
    }

    /**
     * Ping the nodeProvisioner as a new task enters the queue.
     */
    @Extension
    public static class AzureVMFastProvisioning extends QueueListener {

        @Override
        public void onEnterBuildable(Queue.BuildableItem item) {
            if (DISABLE_NO_DELAY_PROVISIONING) {
                return;
            }
            final Jenkins jenkins = Jenkins.get();
            final Label label = item.getAssignedLabel();
            for (Cloud cloud : jenkins.clouds) {
                if (cloud instanceof AzureVMCloud && cloud.canProvision(new Cloud.CloudState(label, 0))) {
                    final NodeProvisioner provisioner =
                            (label == null ? jenkins.unlabeledNodeProvisioner : label.nodeProvisioner);
                    provisioner.suggestReviewNow();
                }
            }
        }
    }
}
