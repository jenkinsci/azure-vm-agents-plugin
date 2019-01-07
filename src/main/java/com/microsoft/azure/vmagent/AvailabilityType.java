package com.microsoft.azure.vmagent;

import java.util.HashMap;
import java.util.Map;

public enum  AvailabilityType {
    UNKNOWN("unknown"),
    AVAILABILITY_SET("availabilitySet");

    private String name;

    AvailabilityType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    private static final Map<String, AvailabilityType> LOOKUP = new HashMap<>();

    static {
        for (AvailabilityType ref : AvailabilityType.values()) {
            LOOKUP.put(ref.getName(), ref);
        }
    }

    public static AvailabilityType get(String name) {
        AvailabilityType result;
        try {
            result = LOOKUP.get(name);
        } catch (Exception e) {
            return AvailabilityType.UNKNOWN;
        }
        return result;
    }
}
