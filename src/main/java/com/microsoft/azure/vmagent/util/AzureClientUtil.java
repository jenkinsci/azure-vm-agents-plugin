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

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.AzureResourceManager;
import com.microsoft.azure.util.AzureBaseCredentials;
import com.microsoft.azure.util.AzureCredentialUtil;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import io.jenkins.plugins.azuresdk.HttpClientRetriever;

public final class AzureClientUtil {

    public static AzureResourceManager getClient(String credentialId) {
        AzureBaseCredentials credential = AzureCredentialUtil.getCredential(null, credentialId);

        return getAzureResourceManager(credential, credential.getSubscriptionId());
    }

    /**
     * Allows using a different subscription to the one configured on the credential.
     */
    public static AzureResourceManager getClient(String credentialId, String subscriptionId)
            throws AzureCloudException {
        boolean validSubscriptionId = AzureUtil.isValidSubscriptionId(credentialId, subscriptionId);
        if (!validSubscriptionId) {
            throw AzureCloudException.create("The subscription id for gallery image is not valid");
        }
        AzureBaseCredentials credential = AzureCredentialUtil.getCredential(null, credentialId);
        return getAzureResourceManager(credential, subscriptionId);
    }

    private static AzureResourceManager getAzureResourceManager(
            AzureBaseCredentials azureCredentials, String subscriptionId) {
        AzureProfile profile = new AzureProfile(azureCredentials.getAzureEnvironment());
        TokenCredential tokenCredential = AzureCredentials.getTokenCredential(azureCredentials);

        return AzureResourceManager
                .configure()
                .withHttpClient(HttpClientRetriever.get())
                .authenticate(tokenCredential, profile)
                .withSubscription(subscriptionId);
    }

    private AzureClientUtil() {
    }
}
