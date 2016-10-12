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
package com.microsoftopentechnologies.azure.util;

import com.microsoft.aad.adal4j.AuthenticationResult;
import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.management.configuration.ManagementConfiguration;
import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;

public class AccessToken implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String subscriptionId;

    private final String serviceManagementUrl;

    private final String token;

    private final long expiration;

    AccessToken(
            final String subscriptionId, final String serviceManagementUrl, final AuthenticationResult authres) {
        this.subscriptionId = subscriptionId;
        this.serviceManagementUrl = serviceManagementUrl;
        this.token = authres.getAccessToken();
        // In the 0.9.4 version of the SDK, expiresOn is the number of seconds till expire
        this.expiration = System.currentTimeMillis() + authres.getExpiresOn() * 1000;
    }

    public Configuration getConfiguration() throws AzureCloudException {
        try {
            return ManagementConfiguration.configure(
                    null,
                    new URI(serviceManagementUrl),
                    subscriptionId,
                    token);
        } catch (URISyntaxException e) {
            throw new AzureCloudException("The syntax of the Url in the publish settings file is incorrect.", e);
        } catch (IOException e) {
            throw new AzureCloudException("Error updating authentication configuration", e);
        }
    }

    public Date getExpirationDate() {
        return new Date(expiration);
    }

    public boolean isExpiring() {
        return expiration < System.currentTimeMillis();
    }

    public String getToken() {
        return token;
    }

    @Override
    public String toString() {
        return token;
    }
}
