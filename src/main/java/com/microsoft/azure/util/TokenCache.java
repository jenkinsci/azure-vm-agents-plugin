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

import com.microsoft.aad.adal4j.AuthenticationContext;
import com.microsoft.aad.adal4j.AuthenticationResult;
import com.microsoft.aad.adal4j.ClientCredential;
import com.microsoft.azure.exceptions.AzureCloudException;
import java.io.File;
import java.nio.file.Path;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.MalformedURLException;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

public class TokenCache {

    private static final Logger LOGGER = Logger.getLogger(TokenCache.class.getName());

    private static final Object tsafe = new Object();

    private static TokenCache cache = null;
    
    protected final AzureCredentials.ServicePrincipal credentials;

    private final String path;

    public static TokenCache getInstance(final AzureCredentials.ServicePrincipal servicePrincipal) {
        synchronized (tsafe) {
            if (cache == null) {
                cache = new TokenCache(servicePrincipal);
            } else if (cache.credentials == null
                    || cache.credentials.subscriptionId == null || !cache.credentials.subscriptionId.equals(servicePrincipal.subscriptionId)
                    || cache.credentials.clientId == null || !cache.credentials.clientId.equals(servicePrincipal.clientId)
                    || cache.credentials.clientSecret == null || !cache.credentials.clientSecret.equals(servicePrincipal.clientSecret)
                    || cache.credentials.oauth2TokenEndpoint == null || !cache.credentials.oauth2TokenEndpoint.equals(servicePrincipal.oauth2TokenEndpoint)
                    || cache.credentials.serviceManagementURL == null || !cache.credentials.serviceManagementURL.equals(servicePrincipal.serviceManagementURL)) {
                cache.clear();
                cache = new TokenCache(servicePrincipal);
            }
        }

        return cache;
    }

    private TokenCache(final AzureCredentials.ServicePrincipal servicePrincipal) {
        LOGGER.log(Level.FINEST, "TokenCache: TokenCache: Instantiate new cache manager");
        this.credentials = servicePrincipal;
        
        // Compute the cloud name
        String cloudName = "<unknown";
        if(credentials != null)
            cloudName =AzureUtil.getCloudName(credentials.subscriptionId.getPlainText());

        final String home = Jenkins.getInstance().root.getPath();

        LOGGER.log(Level.FINEST, "TokenCache: TokenCache: Cache home \"{0}\"", home);

        // Present the cloud name to the token file (rather than just simply the
        // subscription id because the same subscription id could be used between
        // classic and v2 plugins.
        Path homePath = Paths.get(home, String.format("%s-%s", cloudName, "azuretoken.txt"));
        this.path = homePath.toString();

        LOGGER.log(Level.FINEST, "TokenCache: TokenCache: Cache file path \"{0}\"", path);
    }

    public AccessToken get() throws AzureCloudException {
        LOGGER.log(Level.FINEST, "TokenCache: get: Get token from cache");
        synchronized (tsafe) {
            AccessToken token = readTokenFile();
            if (token == null || token.isExpiring()) {
                LOGGER.log(Level.FINEST, "TokenCache: get: Token is no longer valid ({0})",
                        token == null ? null : token.getExpirationDate());
                clear();
                token = getNewToken();
            }
            return token;
        }
    }

    public final void clear() {
        LOGGER.log(Level.FINEST, "TokenCache: clear: Remove cache file {0}", path);
        FileUtils.deleteQuietly(new File(path));
    }

    private AccessToken readTokenFile() {
        LOGGER.log(Level.FINEST, "TokenCache: readTokenFile: Read token from file {0}", path);
        FileInputStream is = null;
        ObjectInputStream objectIS = null;

        try {
            final File fileCache = new File(path);
            if (fileCache.exists()) {
                is = new FileInputStream(fileCache);
                objectIS = new ObjectInputStream(is);
                return AccessToken.class.cast(objectIS.readObject());
            } else {
                LOGGER.log(Level.FINEST, "TokenCache: readTokenFile: File {0} does not exist", path);
            }
        } catch (FileNotFoundException e) {
            LOGGER.log(Level.SEVERE, "TokenCache: readTokenFile: Cache file not found", e);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "TokenCache: readTokenFile: Error reading serialized object", e);
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "TokenCache: readTokenFile: Error deserializing object", e);
        } finally {
            IOUtils.closeQuietly(is);
            IOUtils.closeQuietly(objectIS);
        }

        return null;
    }

    private boolean writeTokenFile(final AccessToken token) {
        LOGGER.log(Level.FINEST, "TokenCache: writeTokenFile: Write token into file {0}", path);

        FileOutputStream fout = null;
        ObjectOutputStream oos = null;

        boolean res = false;

        try {
            fout = new FileOutputStream(path, false);
            oos = new ObjectOutputStream(fout);
            oos.writeObject(token);
            res = true;
        } catch (FileNotFoundException e) {
            LOGGER.log(Level.SEVERE, "TokenCache: writeTokenFile: Cache file not found", e);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "TokenCache: writeTokenFile: Error serializing object", e);
        } finally {
            IOUtils.closeQuietly(fout);
            IOUtils.closeQuietly(oos);
        }

        return res;
    }

    private AccessToken getNewToken() throws AzureCloudException {
        LOGGER.log(Level.FINEST, "TokenCache: getNewToken: Retrieve new access token");

        final ExecutorService service = Executors.newFixedThreadPool(1);

        AuthenticationResult authres = null;

        try {
            LOGGER.log(Level.FINEST, "TokenCache: getNewToken: Aquiring access token:");

            final ClientCredential credential = new ClientCredential(credentials.clientId.getPlainText(), credentials.clientSecret.getPlainText());

            final Future<AuthenticationResult> future = new AuthenticationContext(credentials.oauth2TokenEndpoint.getPlainText(), false, service).
                    acquireToken(credentials.serviceManagementURL, credential, null);

            authres = future.get();
        } catch (MalformedURLException e) {
            throw new AzureCloudException("Authentication error", e);
        } catch (InterruptedException e) {
            throw new AzureCloudException("Authentication interrupted", e);
        } catch (ExecutionException e) {
            throw new AzureCloudException("Authentication execution failed", e);
        } finally {
            service.shutdown();
        }

        if (authres == null) {
            throw new AzureCloudException("Authentication result was null");
        }

        final AccessToken token = new AccessToken(credentials.subscriptionId.getPlainText(), credentials.serviceManagementURL, authres);

        writeTokenFile(token);
        return token;
    }
}
