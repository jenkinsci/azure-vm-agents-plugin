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

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.common.collect.ImmutableMap;
import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.util.AzureMsiCredentials;
import com.microsoft.azure.vmagent.AzureVMAgentPlugin;
import hudson.security.ACL;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collections;
import java.util.logging.Logger;

public final class AzureClientFactory {

    private static final Logger LOGGER = Logger.getLogger(AzureClientFactory.class.getName());

    public static String getUserAgent() {
        String version = null;
        String instanceId = null;
        try {
            version = AzureClientFactory.class.getPackage().getImplementationVersion();
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

    @Nonnull
    public static Azure getClient(String credentialId) {
        AzureMsiCredentials credential = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        AzureMsiCredentials.class,
                        Jenkins.getInstance(),
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(credentialId));
        if (credential != null) {
            try {
                return getClient(credential.getMsiPort());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(credentialId);
            return getClient(servicePrincipal);
        }
    }

    @Nonnull
    public static Azure getClient(final AzureCredentials.ServicePrincipal credentials) {
        if (credentials == null) {
            throw new NullPointerException();
        }
        ApplicationTokenCredentials token = new ApplicationTokenCredentials(
                credentials.getClientId(),
                credentials.getTenant(),
                credentials.getClientSecret(),
                new AzureEnvironment(ImmutableMap.of(
                        "activeDirectoryEndpointUrl", credentials.getAuthenticationEndpoint(),
                        "activeDirectoryGraphResourceId", credentials.getGraphEndpoint(),
                        "managementEndpointUrl", credentials.getServiceManagementURL(),
                        "resourceManagerEndpointUrl", credentials.getResourceManagerEndpoint(),
                        "activeDirectoryResourceId", "https://management.core.windows.net/")));
        return Azure.configure()
                .withInterceptor(new AzureVMAgentPlugin.AzureTelemetryInterceptor())
                .withLogLevel(Constants.DEFAULT_AZURE_SDK_LOGGING_LEVEL)
                .withUserAgent(getUserAgent())
                .authenticate(token)
                .withSubscription(credentials.getSubscriptionId());
    }

    @Nonnull
    public static Azure getClient(int msiPort) throws IOException {
        MsiTokenCredentials msiToken = new MsiTokenCredentials(msiPort, AzureEnvironment.AZURE);
        return Azure.configure()
                .withInterceptor(new AzureVMAgentPlugin.AzureTelemetryInterceptor())
                .withLogLevel(Constants.DEFAULT_AZURE_SDK_LOGGING_LEVEL)
                .withUserAgent(getUserAgent())
                .authenticate(msiToken)
                .withDefaultSubscription();
    }

    public static String getManagementEndpoint(String credentialId) {
        AzureMsiCredentials credential = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        AzureMsiCredentials.class,
                        Jenkins.getInstance(),
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(credentialId));
        if (credential != null) {
            return AzureEnvironment.AZURE.managementEndpoint();
        } else {
            return AzureCredentials.getServicePrincipal(credentialId).getManagementEndpoint();
        }
    }

    private AzureClientFactory() {
    }
}
