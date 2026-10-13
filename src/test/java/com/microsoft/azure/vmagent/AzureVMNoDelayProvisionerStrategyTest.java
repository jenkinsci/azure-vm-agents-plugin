package com.microsoft.azure.vmagent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import hudson.model.LoadStatistics;
import hudson.slaves.NodeProvisioner;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AzureVMNoDelayProvisionerStrategyTest {

    @Test
    void applyGivenCloudWithNoDelayProvisioningDisabledThenDoesNotProvision(JenkinsRule j) {
        // Given
        final TestCloud cloud = mkCloud(true);
        j.jenkins.clouds.add(cloud);
        final NodeProvisioner.StrategyState state = mkStrategyState(1, 0);

        // When
        final NodeProvisioner.StrategyDecision actual = new AzureVMNoDelayProvisionerStrategy().apply(state);

        // Then
        assertThat(cloud.provisionCalls, equalTo(0));
        assertThat(actual, equalTo(NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES));
    }

    @Test
    void applyGivenCloudWithNoDelayProvisioningEnabledThenProvisions(JenkinsRule j) {
        // Given
        final TestCloud cloud = mkCloud(false);
        j.jenkins.clouds.add(cloud);
        final NodeProvisioner.StrategyState state = mkStrategyState(1, 0);

        // When
        final NodeProvisioner.StrategyDecision actual = new AzureVMNoDelayProvisionerStrategy().apply(state);

        // Then
        assertThat(cloud.provisionCalls, equalTo(1));
        assertThat(actual, equalTo(NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED));
    }

    @Test
    void applyGivenOneCloudDisabledAndOneEnabledThenProvisionsOnlyWithEnabledCloud(JenkinsRule j) {
        // Given
        final TestCloud disabledCloud = mkCloud(true);
        final TestCloud enabledCloud = mkCloud(false);
        j.jenkins.clouds.add(disabledCloud);
        j.jenkins.clouds.add(enabledCloud);
        final NodeProvisioner.StrategyState state = mkStrategyState(1, 0);

        // When
        final NodeProvisioner.StrategyDecision actual = new AzureVMNoDelayProvisionerStrategy().apply(state);

        // Then
        assertThat(disabledCloud.provisionCalls, equalTo(0));
        assertThat(enabledCloud.provisionCalls, equalTo(1));
        assertThat(actual, equalTo(NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED));
    }

    @Test
    void applyGivenEnoughAvailableCapacityThenDoesNotProvision(JenkinsRule j) {
        // Given
        final TestCloud cloud = mkCloud(false);
        j.jenkins.clouds.add(cloud);
        final NodeProvisioner.StrategyState state = mkStrategyState(1, 1);

        // When
        final NodeProvisioner.StrategyDecision actual = new AzureVMNoDelayProvisionerStrategy().apply(state);

        // Then
        assertThat(cloud.provisionCalls, equalTo(0));
        assertThat(actual, equalTo(NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED));
    }

    private static TestCloud mkCloud(boolean disableNoDelayProvisioning) {
        final TestCloud cloud = new TestCloud();
        cloud.setDisableNoDelayProvisioning(disableNoDelayProvisioning);
        return cloud;
    }

    private static NodeProvisioner.StrategyState mkStrategyState(int queueLength, int availableExecutors) {
        final LoadStatistics.LoadStatisticsSnapshot snapshot = mock(LoadStatistics.LoadStatisticsSnapshot.class);
        when(snapshot.getQueueLength()).thenReturn(queueLength);
        when(snapshot.getAvailableExecutors()).thenReturn(availableExecutors);
        when(snapshot.getConnectingExecutors()).thenReturn(0);

        final NodeProvisioner.StrategyState state = mock(NodeProvisioner.StrategyState.class);
        when(state.getSnapshot()).thenReturn(snapshot);
        when(state.getPlannedCapacitySnapshot()).thenReturn(0);
        when(state.getAdditionalPlannedCapacity()).thenReturn(0);
        return state;
    }

    private static class TestCloud extends AzureVMCloud {
        private int provisionCalls;

        TestCloud() {
            super(null, null, "0", null, null, null, null, null);
        }

        @Override
        public boolean canProvision(CloudState cloudState) {
            return true;
        }

        @Override
        public Collection<NodeProvisioner.PlannedNode> provision(CloudState cloudState, int workLoad) {
            provisionCalls++;
            return Collections.singletonList(
                    new NodeProvisioner.PlannedNode("test", new CompletableFuture<>(), workLoad));
        }
    }
}
