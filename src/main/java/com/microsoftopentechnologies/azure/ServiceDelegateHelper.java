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
import com.microsoft.aad.adal4j.ClientCredential;
import com.microsoft.azure.management.compute.ComputeManagementClient;
import com.microsoft.azure.management.compute.ComputeManagementService;
import com.microsoft.azure.management.network.NetworkResourceProviderClient;
import com.microsoft.azure.management.network.NetworkResourceProviderService;
import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.ResourceManagementService;
import com.microsoft.azure.management.storage.StorageManagementClient;
import com.microsoft.azure.management.storage.StorageManagementService;
import java.net.URI;
import java.net.URISyntaxException;

import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.management.ManagementClient;
import com.microsoft.windowsazure.management.ManagementService;
import com.microsoft.windowsazure.management.configuration.ManagementConfiguration;
import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.exceptions.UnrecoverableCloudException;
import com.microsoftopentechnologies.azure.util.Constants;
import hudson.slaves.Cloud;
import java.net.MalformedURLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.ServiceUnavailableException;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

/**
 * Helper class to form the required client classes to call azure rest APIs
 *
 * @author Suresh Nallamilli
 *
 */
public class ServiceDelegateHelper {

    private static final Logger LOGGER = Logger.getLogger(ServiceDelegateHelper.class.getName());

    public static Configuration getConfiguration(final AzureCloud cloud) throws AzureCloudException {
        try {
            return loadConfiguration(
                    cloud.getSubscriptionId(),
                    cloud.getClientId(),
                    cloud.getClientSecret(),
                    cloud.getOauth2TokenEndpoint(),
                    cloud.getServiceManagementURL());
        } catch (AzureCloudException e) {
            LOGGER.log(Level.SEVERE,
                    "AzureManagementServiceDelegate: getConfiguration: Failure loading configuration", e);
            throw new AzureCloudException(e);
        }
    }

    public static Configuration getConfiguration(final AzureSlave slave) throws AzureCloudException {
        try {
            return loadConfiguration(
                    slave.getSubscriptionId(),
                    slave.getClientId(),
                    slave.getClientSecret(),
                    slave.getOauth2TokenEndpoint(),
                    slave.getManagementURL());
        } catch (AzureCloudException e) {
            // let's assume no updated information into the slave instance
            LOGGER.log(Level.INFO, "Missing connection with slave {0}", slave.getNodeName());

            final Jenkins instance = Jenkins.getInstance();

            if (instance == null) {
                LOGGER.log(Level.INFO, "No jenkins instance available");
                throw e;
            }

            final Cloud cloud = instance.getCloud(slave.getCloudName());

            if (cloud == null) {
                LOGGER.log(Level.INFO, "Cloud {0} is no longer available", slave.getCloudName());
                throw new UnrecoverableCloudException(e);
            }

            LOGGER.log(Level.INFO, "Trying with {0}", cloud.name);
            return getConfiguration(AzureCloud.class.cast(cloud));
        }
    }

    public static Configuration getConfiguration(final AzureSlaveTemplate template) throws AzureCloudException {
        final AzureCloud azureCloud = template.getAzureCloud();

        return loadConfiguration(
                azureCloud.getSubscriptionId(),
                azureCloud.getClientId(),
                azureCloud.getClientSecret(),
                azureCloud.getOauth2TokenEndpoint(),
                azureCloud.getServiceManagementURL());
    }

    private static AuthenticationResult getAccessTokenByRefreshToken(
            final String refreshToken,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String serviceManagementURL)
            throws MalformedURLException, ExecutionException, InterruptedException, ServiceUnavailableException {

        final ExecutorService service = Executors.newFixedThreadPool(1);

        AuthenticationResult result = null;

        try {
            LOGGER.log(Level.INFO, "Aquiring access token: \n\t{0}\n\t{1}\n\t{2}",
                    new Object[] { oauth2TokenEndpoint, serviceManagementURL, clientId });

            final ClientCredential credential = new ClientCredential(clientId, clientSecret);

            final Future<AuthenticationResult> future = new AuthenticationContext(oauth2TokenEndpoint, false, service).
                    acquireTokenByRefreshToken(refreshToken, credential, null);

            result = future.get();
            LOGGER.log(Level.INFO, "Aquired access token {0}", result.getAccessToken());
        } finally {
            service.shutdown();
        }

        if (result == null) {
            throw new ServiceUnavailableException("authentication result was null");
        }

        return result;
    }

    private static AuthenticationResult getAccessTokenFromServicePrincipalCredentials(
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String serviceManagementURL)
            throws MalformedURLException, ExecutionException, InterruptedException, ServiceUnavailableException {

        final ExecutorService service = Executors.newFixedThreadPool(1);

        AuthenticationResult result = null;

        try {
            LOGGER.log(Level.INFO, "Aquiring access token: \n\t{0}\n\t{1}\n\t{2}",
                    new Object[] { oauth2TokenEndpoint, serviceManagementURL, clientId });

            final ClientCredential credential = new ClientCredential(clientId, clientSecret);

            final Future<AuthenticationResult> future = new AuthenticationContext(oauth2TokenEndpoint, false, service).
                    acquireToken(serviceManagementURL, credential, null);

            result = future.get();
            LOGGER.log(Level.INFO, "Aquired access token {0}", result.getAccessToken());
        } finally {
            service.shutdown();
        }

        if (result == null) {
            throw new ServiceUnavailableException("authentication result was null");
        }

        return result;
    }

    /**
     * Loads configuration object..
     *
     * @param subscriptionId
     * @param clientId
     * @param clientSecret
     * @param oauth2TokenEndpoint
     * @param serviceManagementURL
     * @return
     * @throws AzureCloudException
     */
    public static Configuration loadConfiguration(
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String serviceManagementURL)
            throws AzureCloudException {

        // Azure libraries are internally using ServiceLoader.load(...) method which uses context classloader and
        // this causes problems for jenkins plugin, hence setting the class loader explicitly and then reseting back 
        // to original one.
        ClassLoader thread = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagementServiceDelegate.class.getClassLoader());

        URI managementURI = null;

        final String url;
        if (StringUtils.isBlank(serviceManagementURL)) {
            url = Constants.DEFAULT_MANAGEMENT_URL;
        } else {
            url = serviceManagementURL;
        }

        try {
            managementURI = new URI(url);
        } catch (URISyntaxException e) {
            throw new AzureCloudException(
                    "The syntax of the Url in the publish settings file is incorrect.", e);
        }
        try {
            AuthenticationResult authres = getAccessTokenFromServicePrincipalCredentials(
                    clientId,
                    clientSecret,
                    oauth2TokenEndpoint,
                    url);

            LOGGER.log(Level.INFO,
                    "Authentication result:\n\taccess token: {0}\n\trefresh token: {1}\n\tExpires On: {2}",
                    new Object[] { authres.getAccessToken(), authres.getRefreshToken(), authres.getExpiresOn() });

            if (authres.getRefreshToken() != null && authres.getExpiresOn() < System.currentTimeMillis()) {
                LOGGER.log(Level.INFO, "Refreshing token by {0}", authres.getRefreshToken());
                authres = getAccessTokenByRefreshToken(
                        authres.getRefreshToken(),
                        clientId,
                        clientSecret,
                        oauth2TokenEndpoint,
                        serviceManagementURL);
            }

            return ManagementConfiguration.configure(
                    null,
                    managementURI,
                    subscriptionId,
                    authres.getAccessToken());

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Loading connection configuration parameters", e);
            throw new AzureCloudException("Cannot obtain OAuth 2.0 access token", e);
        } finally {
            Thread.currentThread().setContextClassLoader(thread);
        }
    }

    /**
     * Gets ResourceManagementClient.
     *
     * @param config
     * @return
     */
    public static ResourceManagementClient getResourceeManagementClient(final Configuration config) {
        ClassLoader thread = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagementServiceDelegate.class.getClassLoader());

        try {
            return ResourceManagementService.create(config);
        } finally {
            Thread.currentThread().setContextClassLoader(thread);
        }
    }

    /**
     * Gets ComputeManagementClient.
     *
     * @param config
     * @return
     */
    public static ComputeManagementClient getComputeManagementClient(final Configuration config) {
        ClassLoader thread = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagementServiceDelegate.class.getClassLoader());

        try {
            return ComputeManagementService.create(config);
        } finally {
            Thread.currentThread().setContextClassLoader(thread);
        }
    }

    // Convenience method which returns ComputeManagementClient   
    public static ComputeManagementClient getComputeManagementClient(final AzureSlave slave)
            throws AzureCloudException {
        return getComputeManagementClient(getConfiguration(slave));
    }

    // Gets StorageManagementClient
    public static StorageManagementClient getStorageManagementClient(final Configuration config) {
        ClassLoader thread = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagementServiceDelegate.class.getClassLoader());

        try {
            return StorageManagementService.create(config);
        } finally {
            Thread.currentThread().setContextClassLoader(thread);
        }
    }

    // Gets ManagementClient
    public static ManagementClient getManagementClient(final Configuration config) {
        ClassLoader thread = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagementServiceDelegate.class.getClassLoader());

        try {
            return ManagementService.create(config);
        } finally {
            Thread.currentThread().setContextClassLoader(thread);
        }
    }

    // Gets ManagementClient
    public static NetworkResourceProviderClient getNetworkManagementClient(final Configuration config) {
        ClassLoader thread = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagementServiceDelegate.class.getClassLoader());

        try {
            return NetworkResourceProviderService.create(config);
        } finally {
            Thread.currentThread().setContextClassLoader(thread);
        }
    }

    // Gets ManagementClient
    public static NetworkResourceProviderClient getNetworkManagementClient(final AzureSlave slave)
            throws AzureCloudException {
        return getNetworkManagementClient(getConfiguration(slave));
    }
}
