package com.microsoft.azure.vmagent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

class AzureVMCloudPoolRetentionStrategyTest {

    @Test
    void constructorGivenNegativeRetentionThenSetsToZero() {
        // Given
        int retentionInHours = -5;
        int poolSize = 100;

        // When
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(retentionInHours, poolSize);

        // Then
        assertThat(strategy.getRetentionInHours(), equalTo(0L));
    }

    @Test
    void constructorGivenNegativePoolSizeThenSetsToZero() {
        // Given
        int retentionInHours = 24;
        int poolSize = -10;

        // When
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(retentionInHours, poolSize);

        // Then
        assertThat(strategy.getPoolSize(), equalTo(0));
    }

    // Dynamic Buffer Validation Tests

    @Test
    void bufferPercentageGivenNegativeValueThenSetsToZero() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 10);

        // When
        strategy.setBufferPercentage(-10);

        // Then
        assertThat(strategy.getBufferPercentage(), equalTo(0));
    }

    @Test
    void bufferPercentageGivenValueOver100ThenSetsTo100() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 10);

        // When
        strategy.setBufferPercentage(150);

        // Then
        assertThat(strategy.getBufferPercentage(), equalTo(100));
    }

    @Test
    void minimumBufferGivenNegativeValueThenSetsToZero() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 10);

        // When
        strategy.setMinimumBuffer(-5);

        // Then
        assertThat(strategy.getMinimumBuffer(), equalTo(0));
    }

    @Test
    void maximumBufferGivenZeroValueThenSetsToMaxInt() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 10);

        // When
        strategy.setMaximumBuffer(0);

        // Then
        assertThat(strategy.getMaximumBuffer(), equalTo(Integer.MAX_VALUE));
    }

    // Dynamic Buffer Calculation Tests

    @Test
    void calculateEffectivePoolSizeGivenDynamicDisabledThenReturnsStaticPoolSize() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 10);
        strategy.setDynamicBufferEnabled(false);

        // When
        int effectiveSize = strategy.calculateEffectivePoolSize(50, 20);

        // Then - should return static pool size regardless of busy/queued
        assertThat(effectiveSize, equalTo(10));
    }

    @Test
    void calculateEffectivePoolSizeGivenDynamicEnabledWithBusyMachinesThenAddsBuffer() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 10);
        strategy.setDynamicBufferEnabled(true);
        strategy.setBufferPercentage(20); // 20%

        // When - 50 busy machines * 20% = 10 buffer
        int effectiveSize = strategy.calculateEffectivePoolSize(50, 0);

        // Then - poolSize (10) + buffer (10) = 20
        assertThat(effectiveSize, equalTo(20));
    }

    @Test
    void calculateEffectivePoolSizeGivenDynamicEnabledWithQueueThenAddsBuffer() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 10);
        strategy.setDynamicBufferEnabled(true);
        strategy.setBufferPercentage(10); // 10%

        // When - 100 queued * 10% = 10 buffer
        int effectiveSize = strategy.calculateEffectivePoolSize(0, 100);

        // Then - poolSize (10) + buffer (10) = 20
        assertThat(effectiveSize, equalTo(20));
    }

    @Test
    void calculateEffectivePoolSizeGivenBothBusyAndQueuedThenUsesLargerBuffer() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 10);
        strategy.setDynamicBufferEnabled(true);
        strategy.setBufferPercentage(10); // 10%

        // When - busy: 50 * 10% = 5, queued: 100 * 10% = 10
        int effectiveSize = strategy.calculateEffectivePoolSize(50, 100);

        // Then - poolSize (10) + larger buffer (10) = 20
        assertThat(effectiveSize, equalTo(20));
    }

    @Test
    void calculateEffectivePoolSizeGivenMinimumBufferThenEnforcesMinimum() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 10);
        strategy.setDynamicBufferEnabled(true);
        strategy.setBufferPercentage(10); // 10%
        strategy.setMinimumBuffer(15); // minimum 15

        // When - calculated buffer: 10 * 10% = 1, but minimum is 15
        int effectiveSize = strategy.calculateEffectivePoolSize(10, 0);

        // Then - poolSize (10) + minimum buffer (15) = 25
        assertThat(effectiveSize, equalTo(25));
    }

    @Test
    void calculateEffectivePoolSizeGivenMaximumBufferThenEnforcesMaximum() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 10);
        strategy.setDynamicBufferEnabled(true);
        strategy.setBufferPercentage(50); // 50%
        strategy.setMaximumBuffer(10); // max 10

        // When - calculated buffer: 100 * 50% = 50, but max is 10
        int effectiveSize = strategy.calculateEffectivePoolSize(100, 0);

        // Then - poolSize (10) + capped buffer (10) = 20
        assertThat(effectiveSize, equalTo(20));
    }

    @Test
    void calculateEffectivePoolSizeGivenMinAndMaxBufferThenBothEnforced() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 5);
        strategy.setDynamicBufferEnabled(true);
        strategy.setBufferPercentage(10);
        strategy.setMinimumBuffer(3);
        strategy.setMaximumBuffer(20);

        // When - calculated buffer: 10 * 10% = 1, minimum is 3
        int effectiveSize = strategy.calculateEffectivePoolSize(10, 0);

        // Then - poolSize (5) + buffer (min enforced: 3) = 8
        assertThat(effectiveSize, equalTo(8));
    }

    @Test
    void calculateEffectivePoolSizeGivenZeroBusyAndZeroQueuedThenReturnsPoolSizePlusMinBuffer() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 10);
        strategy.setDynamicBufferEnabled(true);
        strategy.setBufferPercentage(20);
        strategy.setMinimumBuffer(5);

        // When - no busy, no queued, but minimum buffer is 5
        int effectiveSize = strategy.calculateEffectivePoolSize(0, 0);

        // Then - poolSize (10) + minimum buffer (5) = 15
        assertThat(effectiveSize, equalTo(15));
    }

    @Test
    void calculateEffectivePoolSizeGivenZeroMinimumBufferAndNoWorkloadThenReturnsPoolSize() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(24, 10);
        strategy.setDynamicBufferEnabled(true);
        strategy.setBufferPercentage(20);
        strategy.setMinimumBuffer(0);

        // When - no busy, no queued, no minimum buffer
        int effectiveSize = strategy.calculateEffectivePoolSize(0, 0);

        // Then - poolSize (10) + buffer (0) = 10
        assertThat(effectiveSize, equalTo(10));
    }
}
