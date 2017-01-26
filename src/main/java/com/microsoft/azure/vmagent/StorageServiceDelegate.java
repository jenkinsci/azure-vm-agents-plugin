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
package com.microsoft.azure.vmagent;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.BlobContainerPermissions;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.SharedAccessBlobPermissions;
import com.microsoft.azure.storage.blob.SharedAccessBlobPolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.logging.Logger;


import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.util.Constants;

/**
 * Business delegate class which handles storage service calls
 *
 * @author Suresh Nallamilli
 *
 */
public class StorageServiceDelegate {

    private static final Logger LOGGER = Logger.getLogger(StorageServiceDelegate.class.getName());

    private final static String DOT_CHAR = ".";

    /**
     * Returns list of storage account URIs
     *
     * @param config Azure cloud configuration object
     * @param storageAccountName storage account name
     * @return list of storage account URIs
     * @throws Exception
     */
 /*   private static List<URI> getStorageAccountURIs(final Configuration config, final String storageAccountName, 
            final String resourceGroupName) throws
            AzureCloudException {
        StorageManagementClient client = StorageManagementService.create(config)
            .withRequestFilterFirst(new AzureUserAgentFilter());
        StorageAccountGetPropertiesResponse response;

        try {
            response = client.getStorageAccountsOperations().
                    getProperties(resourceGroupName, storageAccountName);
        } catch (Exception e) {
            throw new AzureCloudException("StorageServiceDelegate: getStorageAccountURIs: storage account with name "
                    + storageAccountName + " does not exist");
        }

        final List<URI> res = new ArrayList<URI>();
        if (response.getStorageAccount().getPrimaryEndpoints() != null) {
            if (response.getStorageAccount().getPrimaryEndpoints().getBlob() != null) {
                res.add(response.getStorageAccount().getPrimaryEndpoints().getBlob());
            }
            if (response.getStorageAccount().getPrimaryEndpoints().getTable() != null) {
                res.add(response.getStorageAccount().getPrimaryEndpoints().getTable());
            }
            if (response.getStorageAccount().getPrimaryEndpoints().getQueue() != null) {
                res.add(response.getStorageAccount().getPrimaryEndpoints().getQueue());
            }
        }

        if (response.getStorageAccount().getSecondaryEndpoints() != null) {
            if (response.getStorageAccount().getSecondaryEndpoints().getBlob() != null) {
                res.add(response.getStorageAccount().getSecondaryEndpoints().getBlob());
            }
            if (response.getStorageAccount().getSecondaryEndpoints().getTable() != null) {
                res.add(response.getStorageAccount().getSecondaryEndpoints().getTable());
            }
            if (response.getStorageAccount().getSecondaryEndpoints().getQueue() != null) {
                res.add(response.getStorageAccount().getSecondaryEndpoints().getQueue());
            }
        }

        return res;
    }*/

    /**
     * Returns Storage Account Properties
     *
     * @param config
     * @param storageAccountName
     * @return
     * @throws AzureCloudException
     */
    /*public static StorageAccountGetPropertiesResponse getStorageAccountProps(
            final Configuration config, final String storageAccountName, final String resourceGroupName)
            throws AzureCloudException {
        StorageManagementClient client = StorageManagementService.create(config)
            .withRequestFilterFirst(new AzureUserAgentFilter());
        StorageAccountGetPropertiesResponse response = null;
        try {
            response = client.getStorageAccountsOperations().getProperties(
                    resourceGroupName, storageAccountName);
        } catch (Exception e) {
            throw new AzureCloudException("StorageServiceDelegate: getStorageAccountURIs: storage account with name "
                    + storageAccountName + " does not exist");
        }

        return response;
    }*/

    /**
     * This methods checks if service type URI present in the list and returns
     * type specific URI back
     *
     * @param config
     * @param storageAccountName
     * @param type
     * @return
     * @throws AzureCloudException
     */
    /*public static String getStorageAccountURI(Configuration config, String storageAccountName, String type, 
            String resourceGroupName) throws
            AzureCloudException {
        String serviceURI = null;
        String defaultURL = Constants.HTTP_PROTOCOL_PREFIX + storageAccountName + DOT_CHAR + type
                + Constants.BASE_URI_SUFFIX;

        // Get service URLS
        List<URI> storageAccountURLs = getStorageAccountURIs(config, storageAccountName, resourceGroupName);

        if (storageAccountURLs == null || storageAccountURLs.isEmpty()) {
            LOGGER.info("StorageServiceDelegate: getStorageAccountURI: storageAccountURLs is null, returning default");
            //return default url
            return defaultURL;
        }

        for (URI uri : storageAccountURLs) {
            if (uri.toString().contains(storageAccountName + DOT_CHAR + type + DOT_CHAR)) {
                serviceURI = uri.toString();
                break;
            }
        }

        // This case may never happen - just cautious 
        if (serviceURI == null || serviceURI.length() == 0) {
            LOGGER.info("StorageServiceDelegate: getStorageAccountURI: serviceURI is null, returning default");
            return defaultURL;
        }

        LOGGER.info("StorageServiceDelegate: getStorageAccountURI: " + type + " URI is " + serviceURI);
        // Most weird thing
        serviceURI = serviceURI.replace("http://", "https://");
        return serviceURI;
    }*/

    /**
     * Uploads init script to storage account and returns blob url
     *
     * @param storageAccountName Azure storage account name
     * @param key Azure storage account key
     * @param baseURI Azure storage account blob url.
     * @param blobName blob file name
     * @param fileContents contents of file to be uploaded
     * @return blob url
     * @throws AzureCloudException
     * @throws URISyntaxException
     * @throws StorageException
     * @throws IOException
     */
    /*public static String uploadFileToStorage(Configuration config, String storageAccountName, String key,
            String baseURI, String resourceGroupName, String containerName,
            String blobName, byte[] fileContents) throws AzureCloudException, URISyntaxException, StorageException,
            IOException {
        CloudBlockBlob blob = null;
        String storageURI = getStorageAccountURI(config, storageAccountName, Constants.BLOB, resourceGroupName);
        CloudBlobContainer container = getBlobContainerReference(storageAccountName, key, storageURI,
                containerName);
        blob = container.getBlockBlobReference(blobName);
        InputStream is = new ByteArrayInputStream(fileContents);

        try {
            blob.upload(is, fileContents.length);
        } finally {
            is.close();
        }

        return storageURI + Constants.CONFIG_CONTAINER_NAME + "/" + blobName;
    }*/

    /**
     * Gets Blob containers reference
     *
     * @param storageAccountName
     * @param key
     * @param blobURL
     * @param containerName
     * @return containers reference
     * @throws URISyntaxException
     * @throws StorageException
     */
    public static CloudBlobContainer getBlobContainerReference(String storageAccountName, String key, String blobURL,
            String containerName) throws URISyntaxException, StorageException {
        CloudStorageAccount cloudStorageAccount;
        CloudBlobClient serviceClient;
        CloudBlobContainer container;
        StorageCredentialsAccountAndKey credentials = new StorageCredentialsAccountAndKey(storageAccountName, key);
        cloudStorageAccount = new CloudStorageAccount(credentials, new URI(blobURL),
                new URI(getCustomURI(storageAccountName, Constants.QUEUE, blobURL)),
                new URI(getCustomURI(storageAccountName, Constants.TABLE, blobURL)));
        serviceClient = cloudStorageAccount.createCloudBlobClient();
        container = serviceClient.getContainerReference(containerName);
        container.createIfNotExists();
        return container;
    }

    /** Returns custom URL for queue and table. */
    private static String getCustomURI(String storageAccountName, String type, String blobURL) {

        if (Constants.QUEUE.equalsIgnoreCase(type)) {
            return blobURL.replace(storageAccountName + "." + Constants.BLOB,
                    storageAccountName + "." + type);
        } else if (Constants.TABLE.equalsIgnoreCase(type)) {
            return blobURL.replace(storageAccountName + "." + Constants.BLOB,
                    storageAccountName + "." + type);
        } else {
            return null;
        }
    }

    /**
     * Generates SAS URL for blob in Azure storage account
     *
     * @param storageAccountName
     * @param storageAccountKey
     * @param containerName
     * @param strBlobURL
     * @return SAS URL
     * @throws Exception
     */
    public static String generateSASURL(String storageAccountName, String storageAccountKey,
            String containerName, String strBlobURL) throws Exception {
        LOGGER.info("StorageServiceDelegate: generateSASURL: Generating SAS URL for blob " + strBlobURL);
        StorageCredentialsAccountAndKey credentials = new StorageCredentialsAccountAndKey(storageAccountName,
                storageAccountKey);
        URL blobURL = new URL(strBlobURL);
        String saBlobURI = new StringBuilder().append(blobURL.getProtocol()).append("://").append(blobURL.getHost()).
                append("/").toString();
        CloudStorageAccount cloudStorageAccount = new CloudStorageAccount(credentials, new URI(saBlobURI),
                new URI(getCustomURI(storageAccountName, Constants.QUEUE, saBlobURI)),
                new URI(getCustomURI(storageAccountName, Constants.TABLE, saBlobURI)));
        // Create the blob client.
        CloudBlobClient blobClient = cloudStorageAccount.createCloudBlobClient();
        CloudBlobContainer container = blobClient.getContainerReference(containerName);

        // At this point need to throw an error back since container itself did not exist.
        if (!container.exists()) {
            throw new AzureCloudException("StorageServiceDelegate: generateSASURL: Container " + containerName
                    + " does not exist in storage account " + storageAccountName);
        }

        SharedAccessBlobPolicy policy = new SharedAccessBlobPolicy();
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.setTime(new Date());
        policy.setSharedAccessStartTime(calendar.getTime());
        calendar.add(Calendar.HOUR, 1);
        policy.setSharedAccessExpiryTime(calendar.getTime());
        policy.setPermissions(EnumSet.of(SharedAccessBlobPermissions.READ,
                SharedAccessBlobPermissions.WRITE));

        // TODO: Test if old sas is valid after permissions are updated
        BlobContainerPermissions containerPermissions = new BlobContainerPermissions();
        containerPermissions.getSharedAccessPolicies().put("jenkinssharedAccess", policy);
        container.uploadPermissions(containerPermissions);

        // Create a shared access signature for the container.
        String sas = container.generateSharedAccessSignature(policy, null);
        strBlobURL = strBlobURL.replace("http://", "https://");

        LOGGER.info("StorageServiceDelegate: generateSASURL: Successfully generated SAS url " + strBlobURL + "?" + sas);
        return strBlobURL + "?" + sas;
    }
}
