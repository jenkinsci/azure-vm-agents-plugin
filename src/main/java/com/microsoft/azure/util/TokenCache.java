/*
 Copyright 2016 Microsoft, Inc.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 
 http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.microsoft.azure.util;

import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.util.AzureCredentials;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

public class TokenCache {

    private static final Logger LOGGER = Logger.getLogger(TokenCache.class.getName());

    private static final Object tsafe = new Object();

    private static TokenCache cache = null;
    
    protected final AzureCredentials.ServicePrincipal credentials;

    public static TokenCache getInstance(final AzureCredentials.ServicePrincipal servicePrincipal) {
        synchronized (tsafe) {
            if (cache == null) {
                cache = new TokenCache(servicePrincipal);
            } else if (cache.credentials == null
                    || !StringUtils.isEmpty(cache.credentials.getSubscriptionId()) || !cache.credentials.getSubscriptionId().equals(servicePrincipal.getSubscriptionId())
                    || !StringUtils.isEmpty(cache.credentials.getClientId()) || !cache.credentials.getClientId().equals(servicePrincipal.getClientId())
                    || !StringUtils.isEmpty(cache.credentials.getClientSecret()) || !cache.credentials.getClientSecret().equals(servicePrincipal.getClientSecret())
                    || !StringUtils.isEmpty(cache.credentials.getTenant())|| !cache.credentials.getTenant().equals(servicePrincipal.getTenant())
                    || !StringUtils.isEmpty(cache.credentials.getServiceManagementURL()) || !cache.credentials.getServiceManagementURL().equals(servicePrincipal.getServiceManagementURL())) {
                cache = new TokenCache(servicePrincipal);
            }
        }

        return cache;
    }

    protected TokenCache(final AzureCredentials.ServicePrincipal servicePrincipal) {
        LOGGER.log(Level.FINEST, "TokenCache: TokenCache: Instantiate new cache manager");
        this.credentials = servicePrincipal;
    }

    public static String getUserAgent() {
        String version = null;
        try {
            version = TokenCache.class.getPackage().getImplementationVersion();
        } catch (Exception e) {
        }

        if(version == null) {
            version = "local";
        }
        return Constants.PLUGIN_NAME + "/" + version;
    }

    public static ApplicationTokenCredentials get(AzureCredentials.ServicePrincipal servicePrincipal) {
        return new ApplicationTokenCredentials(
                servicePrincipal.getClientId(),
                servicePrincipal.getTenant(),
                servicePrincipal.getClientSecret(),
                new AzureEnvironment(
                        servicePrincipal.getAuthenticationEndpoint(),
                        servicePrincipal.getServiceManagementURL(),
                        servicePrincipal.getResourceManagerEndpoint(),
                        servicePrincipal.getGraphEndpoint()
                )
        );
    }

    public Azure getAzureClient() {
        return Azure
            .configure()
            .withLogLevel(Constants.DEFAULT_AZURE_SDK_LOGGING_LEVEL)
            .withUserAgent(getUserAgent())
            .authenticate(get(credentials))
            .withSubscription(credentials.getSubscriptionId());
    }
}
