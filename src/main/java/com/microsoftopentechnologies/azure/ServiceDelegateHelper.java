/*
 Copyright 2014 Microsoft Open Technologies, Inc.
 
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
package com.microsoftopentechnologies.azure;

import com.microsoft.aad.adal4j.AuthenticationContext;
import com.microsoft.aad.adal4j.AuthenticationResult;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.management.ManagementClient;
import com.microsoft.windowsazure.management.ManagementService;
import com.microsoft.windowsazure.management.compute.ComputeManagementClient;
import com.microsoft.windowsazure.management.compute.ComputeManagementService;
import com.microsoft.windowsazure.management.configuration.ManagementConfiguration;
import com.microsoft.windowsazure.management.network.NetworkManagementClient;
import com.microsoft.windowsazure.management.network.NetworkManagementService;
import com.microsoft.windowsazure.management.storage.StorageManagementClient;
import com.microsoft.windowsazure.management.storage.StorageManagementService;
import com.microsoftopentechnologies.azure.util.Constants;
import java.net.MalformedURLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.naming.ServiceUnavailableException;
import org.apache.commons.lang.StringUtils;

/**
 * Helper class to form the required client classes to call azure rest APIs
 *
 * @author Suresh Nallamilli
 *
 */
public class ServiceDelegateHelper {

    private static AuthenticationResult getAccessTokenFromServicePrincipalCredentials(
            String nativeClientId,
            String oauth2TokenEndpoint,
            String azureUsername,
            String azurePassword,
            String serviceManagementURL)
            throws MalformedURLException, ExecutionException, InterruptedException, ServiceUnavailableException {

        final ExecutorService service = Executors.newFixedThreadPool(1);

        AuthenticationResult result = null;

        try {
            final Future<AuthenticationResult> future = new AuthenticationContext(oauth2TokenEndpoint, false, service).
                    acquireToken(
                            serviceManagementURL,
                            nativeClientId,
                            azureUsername,
                            azurePassword,
                            null);

            result = future.get();
        } finally {
            service.shutdown();
        }

        if (result == null) {
            throw new ServiceUnavailableException("authentication result was null");
        }

        return result;
    }

    /**
     * Loads configuration object.
     */
    public static Configuration loadConfiguration(String subscriptionId, String nativeClientId,
            String oauth2TokenEndpoint, String azureUsername, String azurePassword, String serviceManagementURL)
            throws IOException {

        // Azure libraries are internally using ServiceLoader.load(...) method which uses context classloader and
        // this causes problems for jenkins plugin, hence setting the class loader explicitly and then reseting back to original 
        // one.
        ClassLoader thread = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagementServiceDelegate.class.getClassLoader());

        try {
            URI managementURI = null;

            if (StringUtils.isBlank(serviceManagementURL)) {
                serviceManagementURL = Constants.DEFAULT_MANAGEMENT_URL;
            }

            try {
                managementURI = new URI(serviceManagementURL);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(
                        "The syntax of the Url in the publish settings file is incorrect.", e);
            }

            return ManagementConfiguration.configure(
                    null,
                    managementURI,
                    subscriptionId,
                    getAccessTokenFromServicePrincipalCredentials(
                            nativeClientId,
                            oauth2TokenEndpoint,
                            azureUsername,
                            azurePassword,
                            serviceManagementURL).
                    getAccessToken());
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot obtain OAuth 2.0 access token", e);
        } finally {
            Thread.currentThread().setContextClassLoader(thread);
        }
    }

    // Gets ComputeManagementClient
    public static ComputeManagementClient getComputeManagementClient(Configuration config) {
        ClassLoader thread = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagementServiceDelegate.class.getClassLoader());

        try {
            return ComputeManagementService.create(config);
        } finally {
            Thread.currentThread().setContextClassLoader(thread);
        }
    }

    // Convenience method which returns ComputeManagementClient   
    public static ComputeManagementClient getComputeManagementClient(AzureSlave slave) throws Exception {
        Configuration config = loadConfiguration(
                slave.getSubscriptionId(),
                slave.getNativeClientId(),
                slave.getOauth2TokenEndpoint(),
                slave.getAzureUsername(),
                slave.getAzurePassword(),
                slave.getManagementURL());
        return getComputeManagementClient(config);
    }

    // Gets StorageManagementClient
    public static StorageManagementClient getStorageManagementClient(Configuration config) {
        ClassLoader thread = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagementServiceDelegate.class.getClassLoader());

        try {
            return StorageManagementService.create(config);
        } finally {
            Thread.currentThread().setContextClassLoader(thread);
        }
    }

    // Gets ManagementClient
    public static ManagementClient getManagementClient(Configuration config) {
        ClassLoader thread = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagementServiceDelegate.class.getClassLoader());

        try {
            return ManagementService.create(config);
        } finally {
            Thread.currentThread().setContextClassLoader(thread);
        }
    }

    // Gets ManagementClient
    public static NetworkManagementClient getNetworkManagementClient(Configuration config) {
        ClassLoader thread = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagementServiceDelegate.class.getClassLoader());

        try {
            return NetworkManagementService.create(config);
        } finally {
            Thread.currentThread().setContextClassLoader(thread);
        }
    }
}
