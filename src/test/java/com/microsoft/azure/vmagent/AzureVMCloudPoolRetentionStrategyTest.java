package com.microsoft.azure.vmagent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

class AzureVMCloudPoolRetentionStrategyTest {

    @Test
    void calculateEffectiveMaxGivenMaxLimitGreaterThanPoolSizeThenReturnsMaxLimit() {
        // Given
        int poolSize = 100;
        int maxVirtualMachinesLimit = 500;
        int expected = 500;

        // When
        int actual = AzureVMCloudPoolRetentionStrategy.calculateEffectiveMax(poolSize, maxVirtualMachinesLimit);

        // Then
        assertThat(actual, equalTo(expected));
    }

    @Test
    void calculateEffectiveMaxGivenMaxLimitEqualToPoolSizeThenReturnsPoolSize() {
        // Given
        int poolSize = 100;
        int maxVirtualMachinesLimit = 100;
        int expected = 100;

        // When
        int actual = AzureVMCloudPoolRetentionStrategy.calculateEffectiveMax(poolSize, maxVirtualMachinesLimit);

        // Then
        assertThat(actual, equalTo(expected));
    }

    @Test
    void calculateEffectiveMaxGivenMaxLimitLessThanPoolSizeThenReturnsPoolSize() {
        // Given
        int poolSize = 100;
        int maxVirtualMachinesLimit = 50;
        int expected = 100;

        // When
        int actual = AzureVMCloudPoolRetentionStrategy.calculateEffectiveMax(poolSize, maxVirtualMachinesLimit);

        // Then
        assertThat(actual, equalTo(expected));
    }

    @Test
    void calculateEffectiveMaxGivenMaxLimitZeroThenReturnsPoolSize() {
        // Given
        int poolSize = 100;
        int maxVirtualMachinesLimit = 0;
        int expected = 100;

        // When
        int actual = AzureVMCloudPoolRetentionStrategy.calculateEffectiveMax(poolSize, maxVirtualMachinesLimit);

        // Then
        assertThat(actual, equalTo(expected));
    }

    @Test
    void calculateEffectiveMaxGivenMaxLimitNegativeThenReturnsPoolSize() {
        // Given
        int poolSize = 100;
        int maxVirtualMachinesLimit = -1;
        int expected = 100;

        // When
        int actual = AzureVMCloudPoolRetentionStrategy.calculateEffectiveMax(poolSize, maxVirtualMachinesLimit);

        // Then
        assertThat(actual, equalTo(expected));
    }

    @Test
    void calculateEffectiveMaxGivenPoolSizeZeroAndMaxLimitSetThenReturnsMaxLimit() {
        // Given
        int poolSize = 0;
        int maxVirtualMachinesLimit = 500;
        int expected = 500;

        // When
        int actual = AzureVMCloudPoolRetentionStrategy.calculateEffectiveMax(poolSize, maxVirtualMachinesLimit);

        // Then
        assertThat(actual, equalTo(expected));
    }

    @Test
    void calculateEffectiveMaxGivenBothZeroThenReturnsZero() {
        // Given
        int poolSize = 0;
        int maxVirtualMachinesLimit = 0;
        int expected = 0;

        // When
        int actual = AzureVMCloudPoolRetentionStrategy.calculateEffectiveMax(poolSize, maxVirtualMachinesLimit);

        // Then
        assertThat(actual, equalTo(expected));
    }

    @Test
    void constructorGivenValidValuesThenSetsFields() {
        // Given
        int retentionInHours = 24;
        int poolSize = 100;

        // When
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(retentionInHours, poolSize);

        // Then
        assertThat(strategy.getRetentionInHours(), equalTo(24L));
        assertThat(strategy.getPoolSize(), equalTo(100));
    }

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

    @Test
    void singleUseAgentsGivenNotSetThenReturnsFalse() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(0, 100);

        // When
        boolean actual = strategy.isSingleUseAgents();

        // Then
        assertThat(actual, equalTo(false));
    }

    @Test
    void singleUseAgentsGivenSetTrueThenReturnsTrue() {
        // Given
        AzureVMCloudPoolRetentionStrategy strategy = new AzureVMCloudPoolRetentionStrategy(0, 100);
        strategy.setSingleUseAgents(true);

        // When
        boolean actual = strategy.isSingleUseAgents();

        // Then
        assertThat(actual, equalTo(true));
    }
}

