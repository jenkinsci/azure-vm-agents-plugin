package com.microsoft.azure.vmagent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public enum ImageReferenceType {
    UNKNOWN("unknown"),
    CUSTOM("custom"),
    CUSTOM_IMAGE("customImage"),
    GALLERY("gallery"),
    REFERENCE("reference");

    private String name;

    ImageReferenceType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static ImageReferenceType get(String name) {
        return Arrays.stream(values())
                .filter(value -> value.name.equalsIgnoreCase(name))
                .findFirst()
                .orElse(ImageReferenceType.UNKNOWN);
    }
}
