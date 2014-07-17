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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.lang.reflect.Field;
import java.security.cert.CertificateException;

import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.core.utils.Base64;
import com.microsoft.windowsazure.core.utils.KeyStoreType;
import com.microsoft.windowsazure.management.ManagementClient;
import com.microsoft.windowsazure.management.ManagementService;
import com.microsoft.windowsazure.management.compute.ComputeManagementClient;
import com.microsoft.windowsazure.management.compute.ComputeManagementService;
import com.microsoft.windowsazure.management.configuration.ManagementConfiguration;
import com.microsoft.windowsazure.management.storage.StorageManagementClient;
import com.microsoft.windowsazure.management.storage.StorageManagementService;
import com.microsoftopentechnologies.azure.util.AzureUtil;
import com.microsoftopentechnologies.azure.util.Constants;

/**
 * Helper class to form the required client classes to call azure rest APIs
 * @author Suresh Nallamilli
 *
 */
public class ServiceDelegateHelper {
	
	/**
	 * Loads configuration object
	 * @param subscriptionId Azure subscription ID
	 * @param serviceManagementCert Contents of base64 encoded pfx file
	 * @param passPhrase passPhrase for pfx file.
	 * @param serviceManagementURL Azure service management URL
	 * @return configuration objects
	 * @throws IOException
	 */
	public static Configuration loadConfiguration(String subscriptionId, String serviceManagementCert, 
			String passPhrase, String serviceManagementURL) throws IOException {
        // Azure libraries are internally using ServiceLoader.load(...) method which uses context classloader and
		// this causes problems for jenkins plugin, hence setting the class loader explicitly and then reseting back to original 
		// one.
		ClassLoader thread = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(AzureManagementServiceDelegate.class.getClassLoader());
	
		try {
		 if (passPhrase == null || passPhrase.trim().length() == 0) {
			passPhrase = "";
		}

		URI managementURI = null;
		
		if (AzureUtil.isNull(serviceManagementURL)) {
			serviceManagementURL = Constants.DEFAULT_MANAGEMENT_URL;
		}

		try {
			managementURI = new URI(serviceManagementURL);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(
					"The syntax of the Url in the publish settings file is incorrect.",	e);
		}

		// Form outFile
		String outputKeyStore = System.getProperty("user.home")	+ File.separator + ".azure" + File.separator 
				+ subscriptionId + ".out";
		createKeyStoreFromCertifcate(serviceManagementCert, outputKeyStore,
				passPhrase);
		return ManagementConfiguration.configure(managementURI, subscriptionId,
				outputKeyStore, passPhrase, KeyStoreType.pkcs12);
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
		Configuration config = loadConfiguration(slave.getSubscriptionID(), slave.getManagementCert(), 
				   slave.getPassPhrase(), slave.getManagementURL());
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

	/**
	 * Loads certificate into keystore and also creates keystore file in user home
	 * @param certificate contents of base 64 encoded pfx file
	 * @param keyStoreFilePath path to keystore file
	 * @param passPhrase password for pfx file
	 * @return keystore
	 * @throws IOException
	 */
	public static KeyStore createKeyStoreFromCertifcate(String certificate, String keyStoreFilePath, 
			String passPhrase) throws IOException {
		KeyStore keyStore = null;
		try {
			if (Float.valueOf(System.getProperty("java.specification.version")) < 1.7) {
                // Use Bouncy Castle Provider for java versions less than 1.7
                keyStore = getBCProviderKeyStore();
            } else {
                keyStore = KeyStore.getInstance("PKCS12");
            }
			keyStore.load(null, "".toCharArray());
			InputStream sslInputStream = new ByteArrayInputStream(
					Base64.decode(certificate));
			keyStore.load(sslInputStream, "".toCharArray());
			// create directories if does not exists
			File outStoreFile = new File(keyStoreFilePath);
			if (!outStoreFile.getParentFile().exists()) {
				outStoreFile.getParentFile().mkdirs();
			}

			OutputStream outputStream;
			outputStream = new FileOutputStream(keyStoreFilePath);
			keyStore.store(outputStream, passPhrase.toCharArray());
			outputStream.close();
		} catch (KeyStoreException e) {
			throw new IllegalArgumentException(
					"Cannot create keystore from the publish settings file", e);
		} catch (CertificateException e) {
			throw new IllegalArgumentException(
					"Cannot create keystore from the publish settings file", e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalArgumentException(
					"Cannot create keystore from the publish settings file", e);
		}
		return keyStore;
	}
	
	 /**
     * Sun JCE provider cannot open password less pfx files , refer to
     * discussion @ https://community.oracle.com/thread/2334304
     * 
     * To read password less pfx files in java versions less than 1.7 need to
     * use BouncyCastle's JCE provider
     */
    private static KeyStore getBCProviderKeyStore() {
        KeyStore keyStore = null;
        try {
            // Loading Bouncy castle classes dynamically so that BC dependency
            // is only for java 1.6 clients
            Class<?> providerClass = Class
                    .forName("org.bouncycastle.jce.provider.BouncyCastleProvider");
            Security.addProvider((Provider) providerClass.newInstance());

            Field field = providerClass.getField("PROVIDER_NAME");
            keyStore = KeyStore.getInstance("PKCS12", field.get(null)
                    .toString());
        } catch (Exception e) {
            // Using catch all exception class to avoid repeated code in
            // different catch blocks
            throw new RuntimeException(
                "Could not create keystore from publishsettings file."
                + "Make sure java versions less than 1.7 has bouncycastle jar in classpath",
                e);
        }
        return keyStore;
    }
}
