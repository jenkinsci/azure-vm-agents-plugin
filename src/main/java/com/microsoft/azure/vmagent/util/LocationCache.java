package com.microsoft.azure.vmagent.util;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.VirtualMachineSize;
import com.azure.resourcemanager.resources.models.Provider;
import com.azure.resourcemanager.resources.models.ProviderResourceType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
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
                    Set<String> locations = new HashSet<>();

                    Provider byName = azureClient.providers().getByName("Microsoft.Compute");
                    ProviderResourceType resourceType = byName.resourceTypes()
                            .stream()
                            .filter(type -> type.resourceType().equalsIgnoreCase("virtualMachines"))
                            .findFirst()
                            .orElse(null);

                    if (resourceType == null) {
                        throw new RuntimeException("Virtual machines provider not registered");
                    }

                    for (String location : resourceType.locations()) {
                        if (!locations.contains(location)) {
                            try {
                                PagedIterable<VirtualMachineSize> virtualMachineSizes = azureClient
                                        .virtualMachines().sizes().listByRegion(location);
                                if (virtualMachineSizes.stream().findAny().isPresent()) {
                                    locations.add(location);
                                }
                            } catch (Exception e) {
                                LOGGER.log(Level.SEVERE, e.getMessage(), e);
                                // some of the provider regions might not be valid for other API calls.
                                // The SDK call will throw an exception instead of returning an empty list
                            }
                        }
                    }

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
