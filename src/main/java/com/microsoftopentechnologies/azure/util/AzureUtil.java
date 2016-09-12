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

import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.apache.commons.lang.StringUtils;

public class AzureUtil {

    private final static String STORAGE_ACCOUNT_NAME_PATTERN = "^[a-z0-9]+$";

    private final static String NOT_A_NUMBER_FORMAT = ".*[^0-9].*";

    /* Regular expression for valid cloud name */
    public static final String VAL_CLOUD_SERVICE_NAME = "^(([a-z\\d]((-(?=[a-z\\d]))|([a-z\\d])){2,62}))$";

    /* Regular expression for password */
    public static final String VAL_DIGIT_REGEX = "(?=.*[0-9]).{1,}";

    public static final String VAL_LOWER_CASE_REGEX = "(?=.*[a-z]).{1,}";

    public static final String VAL_UPPER_CASE_REGEX = "(?=.*[A-Z]).{1,}";

    public static final String VAL_SPECIAL_CHAR_REGEX = "(?=.*[!@#$%^&*.]).{1,}";

    public static final String VAL_PASSWORD_REGEX = "([0-9a-zA-Z!@#\\$%\\^&\\*\\.]*{8,123})";

    public static final String VAL_ADMIN_USERNAME = "([a-zA-Z0-9_-]{3,15})";
    
    public static final String VAL_TEMPLATE = "^[a-z][a-z0-9-]*[a-z0-9]$";

    // Although ugly to maintain this is best way for now.
    public static String DEFAULT_INIT_SCRIPT = "Set-ExecutionPolicy Unrestricted" + "\n"
            + "$jenkinsServerUrl = $args[0]" + "\n" + "$vmName = $args[1]" + "\n" + "$secret = $args[2]" + "\n"
            + "$jenkinsSlaveJarUrl = $jenkinsServerUrl + \"jnlpJars/slave.jar\"" + "\n"
            + "$jnlpUrl=$jenkinsServerUrl + 'computer/' + $vmName + '/slave-agent.jnlp'" + "\n"
            + "$baseDir = 'c:\\azurecsdir'" + "\n"
            + "$JDKUrl = 'http://azure.azulsystems.com/zulu/zulu1.7.0_51-7.3.0.4-win64.zip?jenkins'" + "\n"
            + "$destinationJDKZipPath = $baseDir + '\\zuluJDK.zip'" + "\n"
            + "$destinationSlaveJarPath = $baseDir + '\\slave.jar'" + "\n"
            + "$javaExe = $baseDir + '\\zulu1.7.0_51-7.3.0.4-win64\\bin\\java.exe'" + "\n" + "function Get-ScriptPath"
            + "\n" + "{" + "\n" + "return $MyInvocation.ScriptName;" + "\n" + "}" + "\n"
            + "If(-not((Test-Path $destinationJDKZipPath)))" + "\n" + "{" + "\n" + "md -Path $baseDir -Force" + "\n"
            + "$wc = New-Object System.Net.WebClient" + "\n" + "$wc.DownloadFile($JDKUrl, $destinationJDKZipPath)"
            + "\n" + "$shell_app = new-object -com shell.application" + "\n"
            + "$zip_file = $shell_app.namespace($destinationJDKZipPath)" + "\n"
            + "$javaInstallDir = $shell_app.namespace($baseDir)" + "\n" + "$javaInstallDir.Copyhere($zip_file.items())"
            + "\n" + "$wc = New-Object System.Net.WebClient" + "\n"
            + "$wc.DownloadFile($jenkinsSlaveJarUrl, $destinationSlaveJarPath)" + "\n" + "$scriptPath = Get-ScriptPath"
            + "\n"
            + "$content = 'powershell.exe -ExecutionPolicy Unrestricted -file' + ' '+ $scriptPath + ' '+ $jenkinsServerUrl + ' ' + $vmName + ' ' + $secret"
            + "\n" + "$commandFile = $baseDir + '\\slaveagenttask.cmd'" + "\n"
            + "$content | Out-File $commandFile -Encoding ASCII -Append" + "\n"
            + "schtasks /create /tn \"Jenkins slave agent\" /ru \"SYSTEM\" /sc onstart /rl HIGHEST /delay 0000:30 /tr $commandFile /f"
            + "\n" + "$scriptPath = Get-ScriptPath" + "\n" + "}" + "\n"
            + "$process = New-Object System.Diagnostics.Process;" + "\n" + "$process.StartInfo.FileName = $javaExe;"
            + "\n" + "If($secret)" + "\n" + "{" + "\n"
            + "$process.StartInfo.Arguments = \"-jar $destinationSlaveJarPath -secret $secret -jnlpUrl $jnlpUrl\""
            + "\n" + "}" + "\n" + "else" + "\n" + "{" + "\n"
            + "$process.StartInfo.Arguments = \"-jar $destinationSlaveJarPath -jnlpUrl $jnlpUrl\"" + "\n" + "}" + "\n"
            + "$process.StartInfo.RedirectStandardError = $true;" + "\n"
            + "$process.StartInfo.RedirectStandardOutput = $true;" + "\n"
            + "$process.StartInfo.UseShellExecute = $false;" + "\n" + "$process.StartInfo.CreateNoWindow = $true;"
            + "\n" + "$process.StartInfo;" + "\n" + "$process.Start();" + "\n";

    /** Converts bytes to hex representation */
    public static String hexify(byte bytes[]) {
        char[] hexDigits = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'a', 'b', 'c', 'd', 'e', 'f' };
        StringBuffer buf = new StringBuffer(bytes.length * 2);
        for (int i = 0; i < bytes.length; ++i) {
            buf.append(hexDigits[(bytes[i] & 0xf0) >> 4]);
            buf.append(hexDigits[bytes[i] & 0x0f]);
        }
        return buf.toString();
    }

    /** Validates storage account name */
    public static boolean validateStorageAccountName(String storageAccountName) {
        if (storageAccountName.length() < 3 || storageAccountName.length() > 24) {
            return false;
        }
        if (!storageAccountName.matches(STORAGE_ACCOUNT_NAME_PATTERN)) {
            return false;
        }
        return true;
    }

    //** Validates in given inut is number or not */
    public static boolean validateNumberFormat(String value) {
        return !value.matches(NOT_A_NUMBER_FORMAT);
    }

    /** Checks for validity of cloud service name.
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
    public static boolean validateCloudServiceName(final String cloudServiceName) {
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
     * 1. The password must contain at least 8 characters
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

        // check if atleast one digit is present
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

        if (matchCount < 3) {
            return false;
        }

        return value.length() >= 8 && value.matches(VAL_PASSWORD_REGEX) && value.length() < 123;
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
     * Retrieves the name of the cloud for registering with Jenkins
     * @param subscriptionId Subscription id
     * @return Name of the cloud
     */
    public static String getCloudName(String subscriptionId) {
        return Constants.AZURE_CLOUD_PREFIX + subscriptionId;
    }
    
    /**
     * Returns a template name that can be used for the base of a VM name
     * @return A shortened template name if required, the full name otherwise
     */
    private static String getShortenedTemplateName(String templateName, String usageType, int dateDigits, int extraSuffixDigits) {
        // We'll be adding on 10 characters for the deployment ID (which is a formatted date)
        // Plus an index of the 
        // The template name should already be valid at least, so check that first
        if (!isValidTemplateName(templateName)) {
            throw new IllegalArgumentException("Template name is not valid");
        }
        // If the template name ends in a number, we add a dash
        // to split up the name
        
        int maxLength;
        if (usageType.equals(Constants.OS_TYPE_LINUX)) {
            // Linux, length <= 63 characters, 10 characters for the date
            maxLength = 63;
        }
        else if (usageType.equals(Constants.OS_TYPE_WINDOWS)) {
            // Windows, length is 15 characters.  10 characters for the date
            maxLength = 15;
        }
        else if (usageType.equals(Constants.USAGE_TYPE_DEPLOYMENT)) {
            // Maximum is 64 characters
            maxLength = 64;
        }
        else {
            throw new IllegalArgumentException("Unknown OS/Usage type");
        }
        
        // Chop of what we need for date digits
        maxLength -= dateDigits;
        // Chop off extra if needed for suffix digits
        maxLength -= extraSuffixDigits;
        
        // Shorten the name
        String shortenedName = templateName.substring(0,Math.min(templateName.length(), maxLength));
        
        // If the name ends in a digit, either append or replace the last char with a - so it's
        // not confusing
        if (StringUtils.isNumeric(shortenedName.substring(shortenedName.length()-1))) {
            shortenedName = shortenedName.substring(0, Math.min(templateName.length(), maxLength-1));
            shortenedName += '-';
        }
        
        return shortenedName;
    }
    
    /**
     * Returns true if the template name is valid, false otherwise
     * @param templateName Template name to validate
     * @return True if the template is valid, false otherwise
     */
    public static boolean isValidTemplateName(String templateName) {
        return templateName.matches(VAL_TEMPLATE);
    }
    
    /**
     * Creates a deployment given a template name and OS type
     * @param templateName Valid template name
     * @param osType Valid os type
     * @return Valid deployment name to use for a new deployment
     */
    public static String getDeploymentName(String templateName) {
        if (!isValidTemplateName(templateName)) {
            throw new IllegalArgumentException("Invalid template name");
        }
        
        Format formatter = new SimpleDateFormat(Constants.DEPLOYMENT_NAME_DATE_FORMAT);
        return String.format("%s%s", getShortenedTemplateName(templateName, Constants.USAGE_TYPE_DEPLOYMENT, 
            Constants.DEPLOYMENT_NAME_DATE_FORMAT.length(), 0), 
                formatter.format(new Date(System.currentTimeMillis())));
    }
    
    /**
     * Creates a new VM base name given the input parameters.
     * @param templateName Template name
     * @param osType Type of OS
     * @param numberOfVmsToCreate Number of VMs that will be created
     *       (which is added to the suffix of the VM name by azure)
     * @return 
     */
    public static String getVMBaseName(String templateName, String osType, int numberOfVMs) {
        if (!isValidTemplateName(templateName)) {
            throw new IllegalArgumentException("Invalid template name");
        }
        
        // For VM names, we use a simpler form.  VM names are pretty short 
        Format formatter = new SimpleDateFormat(Constants.VM_NAME_DATE_FORMAT);
        int numberOfDigits = (int)Math.floor(Math.log10((double)numberOfVMs))+1;
        return String.format("%s%s", getShortenedTemplateName(templateName, osType, 
            Constants.VM_NAME_DATE_FORMAT.length(), numberOfDigits), 
                formatter.format(new Date(System.currentTimeMillis())));
    }
}
