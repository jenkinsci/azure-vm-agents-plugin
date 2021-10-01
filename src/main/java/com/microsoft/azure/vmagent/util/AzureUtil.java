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

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.models.Subscription;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;

public final class AzureUtil {

    private static final String STORAGE_ACCOUNT_NAME_PATTERN = "^[a-z0-9]+$";

    private static final String NOT_A_NUMBER_FORMAT = ".*[^0-9].*";

    /* Regular expression for valid cloud name */
    public static final String VAL_CLOUD_SERVICE_NAME = "^(([a-z\\d]((-(?=[a-z\\d]))|([a-z\\d])){2,62}))$";

    /* Regular expression for password */
    public static final String VAL_DIGIT_REGEX = "(?=.*[0-9]).{1,}";

    public static final String VAL_LOWER_CASE_REGEX = "(?=.*[a-z]).{1,}";

    public static final String VAL_UPPER_CASE_REGEX = "(?=.*[A-Z]).{1,}";


    public static final String VAL_SPECIAL_CHAR_REGEX =
            "(?=.*[@#\\$%\\^&\\*-_!+=\\[\\]{}|\\\\:`,\\.\\?/~\"\\(\\);\']).{1,}";

    public static final String VAL_PASSWORD_REGEX =
            "([0-9a-zA-Z@#\\$%\\^&\\*-_!+=\\[\\]{}|\\\\:`,\\.\\?/~\"\\(\\);\']{8,123})";

    public static final String VAL_ADMIN_USERNAME = "([a-zA-Z0-9_-]{3,15})";

    public static final String VAL_TEMPLATE = "^[a-z][a-z0-9-]*[a-z0-9]$";

    public static final int STORAGE_ACCOUNT_MIN_LENGTH = 3;
    public static final int STORAGE_ACCOUNT_MAX_LENGTH = 24;

    public static final int PASSWORD_MIN_COMBINATION = 3;
    public static final int PASSWORD_MIN_LENGTH = 12;
    public static final int PASSWORD_MAX_LENGTH = 123;

    public static final int TEMPLATE_NAME_MAX_LENGTH_LINUX = 63;
    public static final int TEMPLATE_NAME_MAX_LENGTH_WIN = 15;
    public static final int TEMPLATE_NAME_MAX_LENGTH_DEPLOYMENT = 64;

    /**
     * Converts bytes to hex representation.
     */
    public static String hexify(byte[] bytes) {
        final int byteHigherHalfMask = 0xF0;
        final int byteLowerHalfMask = 0x0F;
        final int byteHalfLength = 4;
        char[] hexDigits = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                'a', 'b', 'c', 'd', 'e', 'f'};
        StringBuffer buf = new StringBuffer(bytes.length * 2);
        for (int i = 0; i < bytes.length; ++i) {
            buf.append(hexDigits[(bytes[i] & byteHigherHalfMask) >> byteHalfLength]);
            buf.append(hexDigits[bytes[i] & byteLowerHalfMask]);
        }
        return buf.toString();
    }

    /**
     * Validates storage account name.
     */
    public static boolean validateStorageAccountName(String storageAccountName) {
        if (storageAccountName.length() < STORAGE_ACCOUNT_MIN_LENGTH
                || storageAccountName.length() > STORAGE_ACCOUNT_MAX_LENGTH) {
            return false;
        }
        if (!storageAccountName.matches(STORAGE_ACCOUNT_NAME_PATTERN)) {
            return false;
        }
        return true;
    }

    //** Validates in given input is number or not */
    public static boolean validateNumberFormat(String value) {
        return !value.matches(NOT_A_NUMBER_FORMAT);
    }

    /**
     * Checks for validity of cloud service name.
     * Rules for cloud service name
     * 1.Cloud service names must start with a letter or number, and can contain only letters, numbers, and the dash (-)
     * character.
     * 2.Every dash (-) character must be immediately preceded and followed by a letter or number; consecutive dashes
     * are not permitted in container names.
     * 3.Container names must be from 3 through 63 characters long.
     *
     * @param cloudServiceName Name of the Windows Azure cloud service
     * @return true if cloudServiceName name is valid else returns false
     */
    public static boolean validateCloudServiceName(String cloudServiceName) {
        boolean isValid = false;

        if (cloudServiceName != null) {
            if (cloudServiceName.matches(VAL_CLOUD_SERVICE_NAME)) {
                isValid = true;
            }
        }
        return isValid;
    }

    /**
     * Checks for validity of password
     * Rules for password:
     * 1. The password must contain at least 12 characters
     * 2. The password cannot be longer than 123 characters
     * 3. The password must contain 3 of the following
     * a) a lowercase character
     * b) an uppercase character
     * c) a number
     * d) a special character
     *
     * @return true if password is valid or false otherwise
     */
    public static boolean isValidPassword(String value) {
        int matchCount = 0;

        // check if at least one digit is present
        if (value.matches(VAL_DIGIT_REGEX)) {
            matchCount++;
        }

        //check if lowercase is present'
        if (value.matches(VAL_LOWER_CASE_REGEX)) {
            matchCount++;
        }

        //check if uppercase is present'
        if (value.matches(VAL_UPPER_CASE_REGEX)) {
            matchCount++;
        }

        if (value.matches(VAL_SPECIAL_CHAR_REGEX)) {
            matchCount++;
        }

        if (matchCount < PASSWORD_MIN_COMBINATION) {
            return false;
        }

        return value.length() >= PASSWORD_MIN_LENGTH
                && value.matches(VAL_PASSWORD_REGEX)
                && value.length() < PASSWORD_MAX_LENGTH;
    }

    public static boolean isValidUserName(String value) {
        if (value == null || value.trim().length() == 0) {
            return false;
        }

        return value.matches(VAL_ADMIN_USERNAME);
    }

    public static int isPositiveInteger(String value) throws IllegalArgumentException {
        if (value == null || value.trim().length() == 0) {
            throw new IllegalArgumentException("value is null or empty");
        }

        try {
            int number = Integer.parseInt(value);

            if (number > 0) {
                return number;
            } else {
                throw new IllegalArgumentException("Not a positive number");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a valid number");
        }

    }

    // consider zero has positive integer
    public static int isNonNegativeInteger(String value) throws IllegalArgumentException {
        if (value == null || value.trim().length() == 0) {
            throw new IllegalArgumentException("value is null or empty");
        }

        try {
            int number = Integer.parseInt(value);

            if (number >= 0) {
                return number;
            } else {
                throw new IllegalArgumentException("Not a Non-Negative number");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a valid number");
        }

    }

    public static boolean isValidJvmOption(String value) {
        if (StringUtils.isNotBlank(value)) {
            return value.trim().startsWith("-");
        }
        return false;
    }

    public static boolean isConflictError(String errorMessage) {
        if (StringUtils.isBlank(errorMessage)) {
            return false;
        }

        return errorMessage.contains(Constants.ERROR_CODE_SERVICE_EXCEPTION)
                && errorMessage.contains(Constants.ERROR_CODE_CONFLICT);
    }

    public static boolean isHostNotFound(String errorMessage) {
        if (StringUtils.isBlank(errorMessage)) {
            return false;
        }

        return errorMessage.contains(Constants.ERROR_CODE_UNKNOWN_HOST);
    }

    public static boolean isBadRequestOrForbidden(String errorMessage) {
        if (StringUtils.isBlank(errorMessage)) {
            return false;
        }

        return errorMessage.contains(Constants.ERROR_CODE_SERVICE_EXCEPTION) && (errorMessage.contains(
                Constants.ERROR_CODE_BAD_REQUEST) || errorMessage.contains(Constants.ERROR_CODE_FORBIDDEN));
    }

    public static boolean isDeploymentNotFound(String errorMessage, String deploymentName) {
        if (StringUtils.isBlank(errorMessage) || StringUtils.isBlank(deploymentName)) {
            return false;
        }

        return errorMessage.contains(Constants.ERROR_CODE_SERVICE_EXCEPTION) && errorMessage.contains(
                Constants.ERROR_CODE_RESOURCE_NF) && errorMessage.contains("The deployment name '" + deploymentName
                + "' does not exist");
    }

    public static boolean isDeploymentAlreadyOccupied(String errorMessage) {
        if (StringUtils.isBlank(errorMessage)) {
            return false;
        }

        return errorMessage.contains("The specified deployment slot Production is occupied");
    }

    /**
     * Retrieves the name of the cloud for registering with Jenkins.
     *
     * @param credentialId    credential id
     * @param resourceGroupName Resource group name
     * @return Name of the cloud
     */
    public static String getCloudName(String credentialId, String resourceGroupName) {
        return Constants.AZURE_CLOUD_PREFIX + credentialId + "-" + resourceGroupName;
    }

    /**
     * Returns a template name that can be used for the base of a VM name.
     *
     * @return A shortened template name if required, the full name otherwise
     */
    private static String getShortenedTemplateName(
            String templateName, String usageType, int dateDigits, int extraSuffixDigits) {
        // We'll be adding on 10 characters for the deployment ID (which is a formatted date)
        // Plus an index of the
        // The template name should already be valid at least, so check that first
        if (!isValidTemplateName(templateName)) {
            throw new IllegalArgumentException("Template name is not valid");
        }
        // If the template name ends in a number, we add a dash
        // to split up the name

        int maxLength;
        switch (usageType) {
            case Constants.OS_TYPE_LINUX:
                // Linux, length <= 63 characters, 10 characters for the date
                maxLength = TEMPLATE_NAME_MAX_LENGTH_LINUX;
                break;
            case Constants.OS_TYPE_WINDOWS:
                // Windows, length is 15 characters.  10 characters for the date
                maxLength = TEMPLATE_NAME_MAX_LENGTH_WIN;
                break;
            case Constants.USAGE_TYPE_DEPLOYMENT:
                // Maximum is 64 characters
                maxLength = TEMPLATE_NAME_MAX_LENGTH_DEPLOYMENT;
                break;
            default:
                throw new IllegalArgumentException("Unknown OS/Usage type");
        }

        // Chop of what we need for date digits
        maxLength -= dateDigits;
        // Chop off extra if needed for suffix digits
        maxLength -= extraSuffixDigits;

        // Shorten the name
        String shortenedName = templateName.substring(0, Math.min(templateName.length(), maxLength));
        // If the name ends in a digit, either append or replace the last char with a - so it's
        // not confusing
        if (StringUtils.isNumeric(shortenedName.substring(shortenedName.length() - 1))) {
            shortenedName = shortenedName.substring(0, Math.min(templateName.length(), maxLength - 1));
            shortenedName += '-';
        }

        return shortenedName;
    }

    /**
     * Returns true if the template name is valid, false otherwise.
     *
     * @param templateName Template name to validate
     * @return True if the template is valid, false otherwise
     */
    public static boolean isValidTemplateName(String templateName) {
        return templateName.matches(VAL_TEMPLATE);
    }

    /**
     * Creates a deployment given a template name and timestamp.
     *
     * @param templateName Template name
     * @param timestamp    Timestamp
     * @return Valid deployment name to use for a new deployment
     */
    public static String getDeploymentName(String templateName, Date timestamp) {
        if (!isValidTemplateName(templateName)) {
            throw new IllegalArgumentException("Invalid template name");
        }

        Format formatter = new SimpleDateFormat(Constants.DEPLOYMENT_NAME_DATE_FORMAT);
        return String.format("%s%s", getShortenedTemplateName(templateName, Constants.USAGE_TYPE_DEPLOYMENT,
                Constants.DEPLOYMENT_NAME_DATE_FORMAT.length(), 0),
                formatter.format(timestamp));
    }

    /**
     * Creates a new VM base name given the input parameters.
     *
     * @param templateName        Template name
     * @param osType              Type of OS
     * @param numberOfVMs         Number of VMs that will be created
     *                            (which is added to the suffix of the VM name by azure)
     * @return Valid VM base name to use for new VMs
     */
    public static String getVMBaseName(String templateName, String deploymentName, String osType, int numberOfVMs) {
        if (!isValidTemplateName(templateName)) {
            throw new IllegalArgumentException("Invalid template name");
        }

        // For VM names, we use a simpler form.  VM names are pretty short
        int numberOfDigits = (int) Math.floor(Math.log10((double) numberOfVMs)) + 1;
        // Get the hash of the deployment name
        Integer deploymentHashCode = deploymentName.hashCode();
        // Convert the int into a hex string and do a substring
        // If the deployment names are similar, the prefix of hashCode will be same
        // So suffix of the hashCode.
        String deploymentHashString = Integer.toHexString(deploymentHashCode);
        String shortenedDeploymentHash = null;
        if (deploymentHashString.length() <= Constants.VM_NAME_HASH_LENGTH - 1) {
            shortenedDeploymentHash = deploymentHashString;
        } else {
            shortenedDeploymentHash = deploymentHashString
                        .substring(deploymentHashString.length() - (Constants.VM_NAME_HASH_LENGTH - 1));
        }
        return String.format("%s%s", getShortenedTemplateName(templateName, osType,
                Constants.VM_NAME_HASH_LENGTH, numberOfDigits),
                shortenedDeploymentHash);
    }

    public static StandardUsernamePasswordCredentials getCredentials(String credentialsId) throws AzureCloudException {
        // Grab the pass
        StandardUsernamePasswordCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class,
                        Jenkins.getInstance(),
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(credentialsId));

        if (creds == null) {
            throw AzureCloudException.create("Could not find credentials with id: " + credentialsId);
        }

        return creds;
    }

    /**
     * Checks if the ResourceGroup Name is valid with Azure Standards.
     *
     * @param resourceGroupName Resource Group Name
     * @return true if the name is valid else return false
     */
    public static boolean isValidResourceGroupName(String resourceGroupName) {
        if (resourceGroupName.matches(Constants.DEFAULT_RESOURCE_GROUP_PATTERN)) {
            return true;
        }
        return false;
    }

    /**
     * Checks if the maximum virtual machines limit is valid.
     *
     * @param maxVMLimit Maximum Virtual Limit
     * @return true if it is valid else return false
     */
    public static boolean isValidMAxVMLimit(String maxVMLimit) {
        if (StringUtils.isBlank(maxVMLimit) || !maxVMLimit.matches(Constants.REG_EX_DIGIT)) {
            return false;
        }
        return true;
    }

    /**
     * Checks if the deployment Timeout is valid.
     *
     * @param deploymentTimeout Deployment Timeout
     * @return true if it is valid else return false
     */
    public static boolean isValidTimeOut(String deploymentTimeout) {
        if ((StringUtils.isBlank(deploymentTimeout) || !deploymentTimeout.matches(Constants.REG_EX_DIGIT)
                || Integer.parseInt(deploymentTimeout) < Constants.DEFAULT_DEPLOYMENT_TIMEOUT_SEC)) {
            return false;
        }
        return true;
    }

    /**
     * Checks if the subscription id is valid in the current Azure client.
     *
     * @param credentialId credentials used to create an Azure client
     * @param subscriptionId target subscription id
     * @return true, if the subscription id is valid
     */
    public static boolean isValidSubscriptionId(String credentialId, String subscriptionId) {
        if (StringUtils.isEmpty(subscriptionId)) {
            return true;
        }
        AzureResourceManager defaultClient = AzureClientUtil.getClient(credentialId);
        PagedIterable<Subscription> subscriptions = defaultClient.subscriptions().list();
        boolean isSubscriptionIdValid = false;
        for (Subscription subscription : subscriptions) {
            if (subscription.subscriptionId().equals(subscriptionId)) {
                isSubscriptionIdValid = true;
            }
        }
        return isSubscriptionIdValid;
    }

    public static class DeploymentTag {

        public DeploymentTag() {
            this(System.currentTimeMillis() / Constants.MILLIS_IN_SECOND);
        }

        /*  Expects a string in this format: "<id>/<timestamp>".
            If id is omitted it will be replaced with an empty string
            If timestamp is omitted or it's a negative number than it will be replaced with 0 */
        public DeploymentTag(String tag) {
            String id = "";
            long ts = 0;

            if (tag != null && !tag.isEmpty()) {
                String[] parts = tag.split("/");
                if (parts.length >= 1) {
                    id = parts[0];
                }
                if (parts.length >= 2) {
                    try {
                        ts = Long.parseLong(parts[1]);
                        ts = (ts < 0) ? 0 : ts;
                    } catch (Exception e) {
                        ts = 0;
                    }
                }
            }
            this.instanceId = id;
            this.timestamp = ts;
        }

        public String get() {
            return instanceId + "/" + Long.toString(timestamp);
        }

        // two tags match if they have the same instance id and the timestamp diff is greater than
        // Constants.AZURE_DEPLOYMENT_TIMEOUT
        public boolean matches(DeploymentTag rhs) {
            return matches(rhs, Constants.AZURE_DEPLOYMENT_TIMEOUT);
        }

        public boolean matches(DeploymentTag rhs, long timeout) {
            if (!instanceId.equals(rhs.instanceId)) {
                return false;
            }
            return Math.abs(timestamp - rhs.timestamp) > timeout;
        }

        public boolean isFromSameInstance(DeploymentTag rhs) {
            return instanceId.equals(rhs.instanceId);
        }

        protected DeploymentTag(long timestamp) {
            String id = "";
            try {
                id = Jenkins.get().getLegacyInstanceId();
            } catch (Exception e) {
                id = "AzureJenkins000";
            }
            this.instanceId = id;
            this.timestamp = timestamp;
        }

        private final String instanceId;
        private final long timestamp;
    }

    public static String getLocationNameByLabel(String label) {
        return label.toLowerCase().replace(" ", "");
    }

    private AzureUtil() {
        // hide constructor
    }
}
