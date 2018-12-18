package com.microsoft.azure.vmagent.builders;

public class Availability {
    private String availabilityType;
    private String availabilitySet;

    public Availability(String availabilityType, String availabilitySet) {
        this.availabilityType = availabilityType;
        this.availabilitySet = availabilitySet;
    }

    public String getAvailabilitySet() {
        return availabilitySet;
    }

    public String getAvailabilityType() {
        return availabilityType;
    }
}
