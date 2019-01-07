package com.microsoft.azure.vmagent.builders;

import com.microsoft.azure.vmagent.AvailabilityType;

public class AvailabilityBuilder extends AvailabilityFluent<AvailabilityBuilder> {
    private AvailabilityFluent<?> fluent;

    public AvailabilityBuilder(AvailabilityFluent<?> fluent) {
        this.fluent = fluent;
    }

    public AvailabilityBuilder(AvailabilityFluent<?> fluent, Availability availability) {
        this.fluent = fluent;
        if (AvailabilityType.AVAILABILITY_SET.getName().equals(availability.getAvailabilityType())) {
            fluent.withAvailabilitySet(availability.getAvailabilitySet());
        }
    }

    public AvailabilityBuilder() {
        this.fluent = this;
    }

    public AvailabilityBuilder(Availability availability) {
        this.fluent = this;
        if (AvailabilityType.AVAILABILITY_SET.getName().equals(availability.getAvailabilityType())) {
            fluent.withAvailabilitySet(availability.getAvailabilitySet());
        }
    }

    public Availability build() {
        return new Availability(fluent.getAvailabilityType(),
                fluent.getAvailabilitySet());
    }
}
