package com.microsoft.azure.vmagent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

class AzureVMAgentTemplateTest {

    @Test
    void getEffectiveTemplateMaxVirtualMachinesLimitGivenLimitSetThenReturnsLimit() {
        // Given
        AzureVMAgentTemplate template = mkTemplate();
        template.setMaxVirtualMachinesLimit(300);

        // When
        int actual = template.getEffectiveTemplateMaxVirtualMachinesLimit();

        // Then
        assertThat(actual, equalTo(300));
    }

    @Test
    void getEffectiveTemplateMaxVirtualMachinesLimitGivenLimitZeroThenReturnsMaxValue() {
        // Given
        AzureVMAgentTemplate template = mkTemplate();
        template.setMaxVirtualMachinesLimit(0);

        // When
        int actual = template.getEffectiveTemplateMaxVirtualMachinesLimit();

        // Then
        assertThat(actual, equalTo(Integer.MAX_VALUE));
    }

    @Test
    void getEffectiveMaxVirtualMachinesLimitGivenTemplateMaxOnlyThenReturnsTemplateMax() {
        // Given
        AzureVMAgentTemplate template = mkTemplate();
        template.setMaxVirtualMachinesLimit(300);
        int cloudMaxVMs = 0; // No cloud limit

        // When
        int actual = template.getEffectiveMaxVirtualMachinesLimit(cloudMaxVMs);

        // Then
        assertThat(actual, equalTo(300));
    }

    @Test
    void getEffectiveMaxVirtualMachinesLimitGivenCloudMaxOnlyThenReturnsCloudMax() {
        // Given
        AzureVMAgentTemplate template = mkTemplate();
        template.setMaxVirtualMachinesLimit(0); // No template limit
        int cloudMaxVMs = 500;

        // When
        int actual = template.getEffectiveMaxVirtualMachinesLimit(cloudMaxVMs);

        // Then
        assertThat(actual, equalTo(500));
    }

    @Test
    void getEffectiveMaxVirtualMachinesLimitGivenCloudLowerThanTemplateThenReturnsCloudMax() {
        // Given
        AzureVMAgentTemplate template = mkTemplate();
        template.setMaxVirtualMachinesLimit(500);
        int cloudMaxVMs = 300; // Cloud limit is lower

        // When
        int actual = template.getEffectiveMaxVirtualMachinesLimit(cloudMaxVMs);

        // Then
        assertThat(actual, equalTo(300));
    }

    @Test
    void getEffectiveMaxVirtualMachinesLimitGivenTemplateLowerThanCloudThenReturnsTemplateMax() {
        // Given
        AzureVMAgentTemplate template = mkTemplate();
        template.setMaxVirtualMachinesLimit(300);
        int cloudMaxVMs = 500; // Cloud limit is higher

        // When
        int actual = template.getEffectiveMaxVirtualMachinesLimit(cloudMaxVMs);

        // Then
        assertThat(actual, equalTo(300));
    }

    @Test
    void getEffectiveMaxVirtualMachinesLimitGivenBothEqualThenReturnsThatValue() {
        // Given
        AzureVMAgentTemplate template = mkTemplate();
        template.setMaxVirtualMachinesLimit(300);
        int cloudMaxVMs = 300;

        // When
        int actual = template.getEffectiveMaxVirtualMachinesLimit(cloudMaxVMs);

        // Then
        assertThat(actual, equalTo(300));
    }

    @Test
    void getEffectiveMaxVirtualMachinesLimitGivenBothZeroThenReturnsMaxValue() {
        // Given
        AzureVMAgentTemplate template = mkTemplate();
        template.setMaxVirtualMachinesLimit(0);
        int cloudMaxVMs = 0;

        // When
        int actual = template.getEffectiveMaxVirtualMachinesLimit(cloudMaxVMs);

        // Then
        assertThat(actual, equalTo(Integer.MAX_VALUE));
    }

    private static AzureVMAgentTemplate mkTemplate() {
        return new AzureVMAgentTemplate(
                "testTemplate", null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, false, false);
    }
}
