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

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import com.microsoft.azure.AzureVMManagementServiceDelegate;
import com.microsoft.azure.Messages;
import com.microsoft.azure.exceptions.AzureCredentialsValidationException;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 *
 * @author clguiman
 */
public class AzureCredentials extends BaseStandardCredentials {

    public static class ServicePrincipal {

        public final Secret subscriptionId;
        public final Secret clientId;
        public final Secret clientSecret;
        public final Secret oauth2TokenEndpoint;
        public final String serviceManagementURL;

        public ServicePrincipal(
                String subscriptionId,
                String clientId,
                String clientSecret,
                String oauth2TokenEndpoint,
                String serviceManagementURL) {
            this.subscriptionId = Secret.fromString(subscriptionId);
            this.clientId = Secret.fromString(clientId);
            this.clientSecret = Secret.fromString(clientSecret);
            this.oauth2TokenEndpoint = Secret.fromString(oauth2TokenEndpoint);
            this.serviceManagementURL = StringUtils.isBlank(serviceManagementURL)
                    ? Constants.DEFAULT_MANAGEMENT_URL
                    : serviceManagementURL;
        }

        public ServicePrincipal() {
            this.subscriptionId = Secret.fromString("");
            this.clientId = Secret.fromString("");
            this.clientSecret = Secret.fromString("");
            this.oauth2TokenEndpoint = Secret.fromString("");
            this.serviceManagementURL = Constants.DEFAULT_MANAGEMENT_URL;
        }

        public boolean isBlank() {
            return StringUtils.isBlank(subscriptionId.getPlainText())
                    || StringUtils.isBlank(clientId.getPlainText())
                    || StringUtils.isBlank(oauth2TokenEndpoint.getPlainText())
                    || StringUtils.isBlank(clientSecret.getPlainText());
        }

        public void Validate(String resourceGroupName, String maxVMLimit, String deploymentTimeout) throws AzureCredentialsValidationException {
            if (StringUtils.isBlank(subscriptionId.getPlainText())) {
                throw new AzureCredentialsValidationException("Error: Subscription ID is missing");
            }
            if (StringUtils.isBlank(clientId.getPlainText())) {
                throw new AzureCredentialsValidationException("Error: Native Client ID is missing");
            }
            if (StringUtils.isBlank(clientSecret.getPlainText())) {
                throw new AzureCredentialsValidationException("Error: Azure Password is missing");
            }
            if (StringUtils.isBlank(oauth2TokenEndpoint.getPlainText())) {
                throw new AzureCredentialsValidationException("Error: OAuth 2.0 Token Endpoint is missing");
            }
            if (StringUtils.isBlank(maxVMLimit) || !maxVMLimit.matches(Constants.REG_EX_DIGIT)) {
                throw new AzureCredentialsValidationException("Error: Maximum Virtual Machine Limit should be a positive integer e.g. "+Constants.DEFAULT_MAX_VM_LIMIT);
            }

            if (StringUtils.isBlank(deploymentTimeout) || !deploymentTimeout.matches(Constants.REG_EX_DIGIT)) {
                throw new AzureCredentialsValidationException("Error: Deployment Timeout should be a positive number");
            }
            
            if (deploymentTimeout.matches(Constants.REG_EX_DIGIT) && Integer.parseInt(deploymentTimeout) < Constants.DEFAULT_DEPLOYMENT_TIMEOUT_SEC) {
                throw new AzureCredentialsValidationException("Error: Deployment Timeout should be at least minimum "+Constants.DEFAULT_DEPLOYMENT_TIMEOUT_SEC);
            }
            

            String response = AzureVMManagementServiceDelegate.verifyConfiguration(this, resourceGroupName);
            if (!Constants.OP_SUCCESS.equalsIgnoreCase(response)) {
                throw new AzureCredentialsValidationException(response);
            }
        }

    }

    public final ServicePrincipal data;

    @DataBoundConstructor
    public AzureCredentials(
            CredentialsScope scope,
            String id,
            String description,
            String subscriptionId,
            String clientId,
            String clientSecret,
            String oauth2TokenEndpoint,
            String serviceManagementURL) {
        super(scope, id, description);
        data = new ServicePrincipal(subscriptionId, clientId, clientSecret, oauth2TokenEndpoint, serviceManagementURL);
    }

    public static AzureCredentials.ServicePrincipal getServicePrincipal(final String credentialsId) {
        AzureCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(AzureCredentials.class, Jenkins.getInstance(), ACL.SYSTEM, Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(credentialsId));
        if (creds == null) {
            return new AzureCredentials.ServicePrincipal();
        }
        return creds.data;
    }

    public String getSubscriptionId() {
        return data.subscriptionId.getEncryptedValue();
    }

    public String getClientId() {
        return data.clientId.getEncryptedValue();
    }

    public String getClientSecret() {
        return data.clientSecret.getEncryptedValue();
    }

    public String getOauth2TokenEndpoint() {
        return data.oauth2TokenEndpoint.getEncryptedValue();
    }

    public String getServiceManagementURL() {
        return data.serviceManagementURL;
    }

    @Extension
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {

        @Override
        public String getDisplayName() {
            return "Microsoft Azure VM Agents";
        }

        public String getDefaultserviceManagementURL() {
            return Constants.DEFAULT_MANAGEMENT_URL;
        }

        public FormValidation doVerifyConfiguration(
                @QueryParameter String subscriptionId,
                @QueryParameter String clientId,
                @QueryParameter String clientSecret,
                @QueryParameter String oauth2TokenEndpoint,
                @QueryParameter String serviceManagementURL) {

            AzureCredentials.ServicePrincipal servicePrincipal = new AzureCredentials.ServicePrincipal(subscriptionId, clientId, clientSecret, oauth2TokenEndpoint, 
                                                                serviceManagementURL);
            try {
                servicePrincipal.Validate(Constants.DEFAULT_RESOURCE_GROUP_NAME, Integer.toString(Constants.DEFAULT_MAX_VM_LIMIT), 
                        Integer.toString(Constants.DEFAULT_DEPLOYMENT_TIMEOUT_SEC));
            } catch (AzureCredentialsValidationException e) {
                return FormValidation.error(e.getMessage());
            }

            return FormValidation.ok(Messages.Azure_Config_Success());
        }

    }
}
