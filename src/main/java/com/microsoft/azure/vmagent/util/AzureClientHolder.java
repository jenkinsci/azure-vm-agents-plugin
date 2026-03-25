package com.microsoft.azure.vmagent.util;

import com.azure.resourcemanager.AzureResourceManager;
import com.microsoft.azure.vmagent.AzureVMManagementServiceDelegate;
import com.microsoft.jenkins.credentials.AzureResourceManagerCache;

public final class AzureClientHolder {
    private AzureClientHolder() {
    }

    public static AzureVMManagementServiceDelegate getDelegate(String credentialId) {
        AzureResourceManager azureClient = AzureResourceManagerCache.get(credentialId);
        if (azureClient == null) {
            return null;
        }
        return AzureVMManagementServiceDelegate.getInstance(azureClient, credentialId);
    }
}
