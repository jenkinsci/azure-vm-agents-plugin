package com.microsoft.azure.vmagent.util;

import com.microsoft.azure.vmagent.AzureVMAgentTemplate;

import java.util.HashSet;
import java.util.Set;

public final class PoolLock {

    private static Set<AzureVMAgentTemplate> templateProvisionLock = new HashSet<>();
    private static Set<AzureVMAgentTemplate> templateDeprovisionLock = new HashSet<>();

    public static synchronized void provisionLock(AzureVMAgentTemplate template) {
        templateProvisionLock.add(template);
    }

    public static synchronized void provisionUnlock(AzureVMAgentTemplate template) {
        templateProvisionLock.remove(template);
    }

    public static synchronized boolean checkProvisionLock(AzureVMAgentTemplate template) {
        return templateProvisionLock.contains(template);
    }

    public static synchronized void deprovisionLock(AzureVMAgentTemplate template) {
        templateDeprovisionLock.add(template);
    }

    public static synchronized void deprovisionUnlock(AzureVMAgentTemplate template) {
        templateDeprovisionLock.remove(template);
    }

    public static synchronized boolean checkDeprovisionLock(AzureVMAgentTemplate template) {
        return templateDeprovisionLock.contains(template);
    }
    
    private PoolLock() {

    }
}
