package com.microsoft.azure.vmagent.builders;

import com.microsoft.azure.vmagent.AvailabilityType;

public class AvailabilityFluent<T extends AvailabilityFluent<T>> {
    private String availabilityType;
    private String availabilitySet;

    public AvailabilityFluent() {
        this.availabilityType = AvailabilityType.UNKNOWN.getName();
    }

    //CHECKSTYLE:OFF
    public T withAvailabilitySet(String availabilitySet) {
        this.availabilityType = AvailabilityType.AVAILABILITY_SET.getName();
        this.availabilitySet = availabilitySet;
        return (T) this;
    }
    //CHECKSTYLE:ON

    public String getAvailabilitySet() {
        return availabilitySet;
    }

    public String getAvailabilityType() {
        return availabilityType;
    }
}
