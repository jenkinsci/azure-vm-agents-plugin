package com.microsoft.azure.vmagent;

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

    private static final Map<String, ImageReferenceType> LOOKUP = new HashMap<>();

    static {
        for (ImageReferenceType ref : ImageReferenceType.values()) {
            LOOKUP.put(ref.getName(), ref);
        }
    }

    public static ImageReferenceType get(String name) {
        ImageReferenceType result;
        try {
            result = LOOKUP.get(name);
        } catch (Exception e) {
            return ImageReferenceType.UNKNOWN;
        }
        return result;
    }
}
