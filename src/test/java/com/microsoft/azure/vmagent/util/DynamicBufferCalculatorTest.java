package com.microsoft.azure.vmagent.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import org.junit.jupiter.api.Test;

class DynamicBufferCalculatorTest {

    @Test
    void bufferMetricsConstructorSetsAllFields() {
        // Given
        int busy = 5;
        int idle = 3;
        int total = 8;
        int queued = 10;

        // When
        DynamicBufferCalculator.BufferMetrics metrics =
                new DynamicBufferCalculator.BufferMetrics(busy, idle, total, queued);

        // Then
        assertThat(metrics.getBusyMachines(), equalTo(5));
        assertThat(metrics.getIdleMachines(), equalTo(3));
        assertThat(metrics.getTotalMachines(), equalTo(8));
        assertThat(metrics.getQueuedItems(), equalTo(10));
    }

    @Test
    void bufferMetricsToStringContainsAllValues() {
        // Given
        DynamicBufferCalculator.BufferMetrics metrics =
                new DynamicBufferCalculator.BufferMetrics(5, 3, 8, 10);

        // When
        String result = metrics.toString();

        // Then
        assertThat(result.contains("busy=5"), equalTo(true));
        assertThat(result.contains("idle=3"), equalTo(true));
        assertThat(result.contains("total=8"), equalTo(true));
        assertThat(result.contains("queued=10"), equalTo(true));
    }

    @Test
    void calculateMachinesToProvisionGivenDeficitReturnsPositiveNumber() {
        // Given
        int effectivePoolSize = 10;
        int currentTotal = 6;

        // When
        int result = DynamicBufferCalculator.calculateMachinesToProvision(
                null, effectivePoolSize, currentTotal);

        // Then
        assertThat(result, equalTo(4));
    }

    @Test
    void calculateMachinesToProvisionGivenSurplusReturnsZero() {
        // Given
        int effectivePoolSize = 10;
        int currentTotal = 15;

        // When
        int result = DynamicBufferCalculator.calculateMachinesToProvision(
                null, effectivePoolSize, currentTotal);

        // Then
        assertThat(result, equalTo(0));
    }

    @Test
    void calculateMachinesToProvisionGivenExactPoolSizeReturnsZero() {
        // Given
        int effectivePoolSize = 10;
        int currentTotal = 10;

        // When
        int result = DynamicBufferCalculator.calculateMachinesToProvision(
                null, effectivePoolSize, currentTotal);

        // Then
        assertThat(result, equalTo(0));
    }

    @Test
    void calculateMachinesToProvisionGivenZeroPoolSizeReturnsZero() {
        // Given
        int effectivePoolSize = 0;
        int currentTotal = 5;

        // When
        int result = DynamicBufferCalculator.calculateMachinesToProvision(
                null, effectivePoolSize, currentTotal);

        // Then
        assertThat(result, equalTo(0));
    }

    @Test
    void calculateMachinesToProvisionGivenNegativeCurrentReturnsPoolSize() {
        // Given - edge case where current is somehow negative
        int effectivePoolSize = 10;
        int currentTotal = -5;

        // When
        int result = DynamicBufferCalculator.calculateMachinesToProvision(
                null, effectivePoolSize, currentTotal);

        // Then - should still calculate correctly (10 - (-5) = 15)
        assertThat(result, greaterThanOrEqualTo(10));
    }
}
