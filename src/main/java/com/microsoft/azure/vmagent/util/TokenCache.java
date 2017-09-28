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
package com.microsoft.azure.vmagent.util;

import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.vmagent.AzureVMAgentPlugin;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TokenCache {

    private static final Logger LOGGER = Logger.getLogger(TokenCache.class.getName());

    private static final Object TSAFE = new Object();

    private static TokenCache cache = null;

    private final AzureCredentials.ServicePrincipal credentials;

    public static TokenCache getInstance(AzureCredentials.ServicePrincipal servicePrincipal) {
        synchronized (TSAFE) {
            if (cache == null) {
                cache = new TokenCache(servicePrincipal);
            } else if (cache.credentials == null
                    || !StringUtils.isEmpty(cache.credentials.getSubscriptionId())
                    || !cache.credentials.getSubscriptionId().equals(servicePrincipal.getSubscriptionId())
                    || !StringUtils.isEmpty(cache.credentials.getClientId())
                    || !cache.credentials.getClientId().equals(servicePrincipal.getClientId())
                    || !StringUtils.isEmpty(cache.credentials.getClientSecret())
                    || !cache.credentials.getClientSecret().equals(servicePrincipal.getClientSecret())
                    || !StringUtils.isEmpty(cache.credentials.getTenant())
                    || !cache.credentials.getTenant().equals(servicePrincipal.getTenant())
                    || !StringUtils.isEmpty(cache.credentials.getServiceManagementURL())
                    || !cache.credentials.getServiceManagementURL().equals(
                    servicePrincipal.getServiceManagementURL())) {
                cache = new TokenCache(servicePrincipal);
            }
        }

        return cache;
    }

    protected TokenCache(AzureCredentials.ServicePrincipal servicePrincipal) {
        LOGGER.log(Level.FINEST, "TokenCache: TokenCache: Instantiate new cache manager");
        this.credentials = servicePrincipal;
    }

    public static String getUserAgent() {
        String version = null;
        String instanceId = null;
        try {
            version = TokenCache.class.getPackage().getImplementationVersion();
            instanceId = Jenkins.getInstance().getLegacyInstanceId();
        } catch (Exception e) {
        }

        if (version == null) {
            version = "local";
        }
        if (instanceId == null) {
            instanceId = "local";
        }

        return Constants.PLUGIN_NAME + "/" + version + "/" + instanceId;
    }

    public static ApplicationTokenCredentials get(final AzureCredentials.ServicePrincipal servicePrincipal) {
        return new ApplicationTokenCredentials(
                servicePrincipal.getClientId(),
                servicePrincipal.getTenant(),
                servicePrincipal.getClientSecret(),
                new AzureEnvironment(new HashMap<String, String>() {
                    {
                        this.put("activeDirectoryEndpointUrl", servicePrincipal.getAuthenticationEndpoint());
                        this.put("activeDirectoryGraphResourceId", servicePrincipal.getGraphEndpoint());
                        this.put("managementEndpointUrl", servicePrincipal.getServiceManagementURL());
                        this.put("resourceManagerEndpointUrl", servicePrincipal.getResourceManagerEndpoint());
                        this.put("activeDirectoryResourceId", "https://management.core.windows.net/");
                    }
                })
        );
    }

    public Azure getAzureClient() throws AzureCloudException {
        try {
            return Azure
                    .configure()
                    .withInterceptor(new AzureVMAgentPlugin.AzureTelemetryInterceptor())
                    .withLogLevel(Constants.DEFAULT_AZURE_SDK_LOGGING_LEVEL)
                    .withUserAgent(getUserAgent())
                    .authenticate(get(credentials))
                    .withSubscription(credentials.getSubscriptionId());
        } catch (Exception e) {
            throw AzureCloudException.create(e);
        }
    }
}
