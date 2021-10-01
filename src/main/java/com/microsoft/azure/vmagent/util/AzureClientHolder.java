package com.microsoft.azure.vmagent.util;

import com.azure.resourcemanager.AzureResourceManager;
import com.microsoft.azure.vmagent.AzureVMManagementServiceDelegate;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.commons.lang.StringUtils;

/**
 * The Azure client cache holder for the configuration page, where the VMCloud objects may not exist.
 */
public final class AzureClientHolder {
    private static String cachedId;
    private static AzureResourceManager cachedClient;

    private AzureClientHolder() {
    }

    @NonNull
    public static synchronized AzureResourceManager get(String credentialId) {
        if (credentialId == null) {
            throw new NullPointerException("credentialId is null!");
        }
        if (!StringUtils.equals(cachedId, credentialId)) {
            cachedId = credentialId;
            cachedClient = AzureClientUtil.getClient(credentialId);
        }
        return cachedClient;
    }

    public static AzureVMManagementServiceDelegate getDelegate(String credentialId) {
        return AzureVMManagementServiceDelegate.getInstance(get(credentialId), credentialId);
    }
}
