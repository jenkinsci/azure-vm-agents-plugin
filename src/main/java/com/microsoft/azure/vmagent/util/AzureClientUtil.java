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

import com.microsoft.azure.management.Azure;
import com.microsoft.azure.util.AzureBaseCredentials;
import com.microsoft.azure.util.AzureCredentialUtil;
import com.microsoft.azure.vmagent.AzureVMAgentPlugin;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.jenkins.azurecommons.core.AzureClientFactory;
import com.microsoft.jenkins.azurecommons.core.credentials.TokenCredentialData;
import org.apache.commons.lang.StringUtils;

import java.util.logging.Logger;

public final class AzureClientUtil {

    private static final Logger LOGGER = Logger.getLogger(AzureClientUtil.class.getName());

    public static TokenCredentialData getToken(String credentialId) {
        AzureBaseCredentials credential = AzureCredentialUtil.getCredential2(credentialId);
        if (credential == null) {
            throw new NullPointerException("Can't find credential with id: " + credentialId);
        }
        return TokenCredentialData.deserialize(credential.serializeToTokenData());
    }

    public static Azure getClient(String credentialId) {
        TokenCredentialData token = getToken(credentialId);
        return getClient(token);
    }

    public static Azure getClient(String credentialId, String subscriptionId) throws AzureCloudException {
        boolean validSubscriptionId = AzureUtil.isValidSubscriptionId(credentialId, subscriptionId);
        if (!validSubscriptionId) {
            throw AzureCloudException.create("The subscription id for gallery image is not valid");
        }
        TokenCredentialData token = getToken(credentialId);
        if (StringUtils.isNotEmpty(subscriptionId)) {
            token.setSubscriptionId(subscriptionId);
        }
        return getClient(token);
    }

    public static Azure getClient(TokenCredentialData token) {
        return AzureClientFactory.getClient(token, new AzureClientFactory.Configurer() {
            @Override
            public Azure.Configurable configure(Azure.Configurable configurable) {
                return configurable
                        .withInterceptor(new AzureVMAgentPlugin.AzureTelemetryInterceptor())
                        .withUserAgent(AzureClientFactory.getUserAgent(Constants.PLUGIN_NAME,
                                AzureClientUtil.class.getPackage().getImplementationVersion()));
            }
        });
    }

    private AzureClientUtil() {
    }
}
