package com.microsoft.azure.vmagent.util;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.models.Provider;
import com.azure.resourcemanager.resources.models.ProviderResourceType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class LocationCache {
    private static Map<String, Set<String>> regions = new HashMap<>();
    private static final long EXPIRE_TIME_IN_MILLIS = TimeUnit.HOURS.toMillis(24); //re-get locations every 24 hours
    private static Map<String, Long> achieveTimeInMillis = new HashMap<>();

    private static final Logger LOGGER = Logger.getLogger(LocationCache.class.getName());

    public static Set<String> getLocation(AzureResourceManager azureClient, String key) throws Exception {
        if (regions.containsKey(key)
                && !regions.get(key).isEmpty()
                && System.currentTimeMillis() < achieveTimeInMillis.get(key) + EXPIRE_TIME_IN_MILLIS) {
            return regions.get(key);
        } else {
            synchronized (LocationCache.class) {
                if (regions.containsKey(key)
                        && !regions.get(key).isEmpty()
                        && System.currentTimeMillis()
                            < achieveTimeInMillis.get(key) + EXPIRE_TIME_IN_MILLIS) {
                    return regions.get(key);
                } else {
                    Provider byName = azureClient.providers().getByName("Microsoft.Compute");
                    ProviderResourceType resourceType = byName.resourceTypes()
                            .stream()
                            .filter(type -> type.resourceType().equalsIgnoreCase("virtualMachines"))
                            .findFirst()
                            .orElse(null);

                    if (resourceType == null) {
                        throw new RuntimeException("Virtual machines provider not registered");
                    }

                    Set<String> locations = new HashSet<>(resourceType.locations());

                    achieveTimeInMillis.put(key, System.currentTimeMillis());
                    regions.put(key, locations);
                    return locations;
                }
            }
        }
    }

    private LocationCache() {

    }
}
