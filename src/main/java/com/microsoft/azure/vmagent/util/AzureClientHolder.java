package com.microsoft.azure.vmagent.util;

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.vmagent.AzureVMManagementServiceDelegate;
import org.apache.commons.lang.StringUtils;

import javax.annotation.Nonnull;

/**
 * The Azure client cache holder for the configuration page, where the VMCloud objects may not exist.
 */
public final class AzureClientHolder {
    private static String cachedId;
    private static Azure cachedClient;

    private AzureClientHolder() {
    }

    @Nonnull
    public static synchronized Azure get(String credentialId) {
        if (credentialId == null) {
            throw new NullPointerException("credentialId is null!");
        }
        if (!StringUtils.equals(cachedId, credentialId)) {
            cachedId = credentialId;
            cachedClient = AzureClientFactory.getClient(credentialId);
        }
        return cachedClient;
    }

    public static AzureVMManagementServiceDelegate getDelegate(String credentialId) {
        return AzureVMManagementServiceDelegate.getInstance(get(credentialId));
    }
}
