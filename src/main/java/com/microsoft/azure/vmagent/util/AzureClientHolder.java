package com.microsoft.azure.vmagent.util;

import com.microsoft.azure.vmagent.AzureVMManagementServiceDelegate;
import com.microsoft.jenkins.credentials.AzureResourceManagerCache;

public final class AzureClientHolder {
    private AzureClientHolder() {
    }

    public static AzureVMManagementServiceDelegate getDelegate(String credentialId) {
        return AzureVMManagementServiceDelegate.getInstance(AzureResourceManagerCache.get(credentialId), credentialId);
    }
}
