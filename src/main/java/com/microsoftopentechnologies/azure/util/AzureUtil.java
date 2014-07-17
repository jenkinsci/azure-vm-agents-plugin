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

import java.util.Random;

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
	
	public static final String VAL_ADMIN_USERNAME = "([a-zA-Z0-9_-]{6,15})";

	
    // Although ugly to maintain this is best way for now.
	public static String DEFAULT_INIT_SCRIPT = "Set-ExecutionPolicy Unrestricted"+ "\n" + 
						"$jenkinsserverurl = $args[0]"+ "\n" + "$vmname = $args[1]" + "\n"+
			             "$source = \"http://azure.azulsystems.com/zulu/zulu1.7.0_51-7.3.0.4-win64.zip?jenkins\""+ "\n" +
                          "mkdir c:\\azurecsdir" 	+ "\n" +	"$destination = \"c:\\azurecsdir\\zuluJDK.zip\""+ "\n" +
			             "$wc = New-Object System.Net.WebClient "+ "\n" + "$wc.DownloadFile($source, $destination)"+ "\n" +
                          "$shell_app=new-object -com shell.application" + "\n" + "$zip_file = $shell_app.namespace($destination)" + "\n" + 
			             "mkdir c:\\java" + "\n" + "$destination = $shell_app.namespace(\"c:\\java\")"+ "\n" + "$destination.Copyhere($zip_file.items())" + "\n" + 
                          "$slaveSource = $jenkinsserverurl + \"jnlpJars/slave.jar\"" + "\n" + "$destSource = \"c:\\java\\slave.jar\"" + "\n" + 
			             "$wc = New-Object System.Net.WebClient" + "\n" + "$wc.DownloadFile($slaveSource, $destSource)" + "\n" + 
                          "$java=\"c:\\java\\zulu1.7.0_51-7.3.0.4-win64\\bin\\java.exe\"" + "\n" + "$jar=\"-jar\"" + "\n" + 
			             "$jnlpUrl=\"-jnlpUrl\""+ "\n" + "$serverURL=$jenkinsserverurl+\"computer/\" + $vmname + \"/slave-agent.jnlp\""+ "\n" +
                          "& $java $jar $destSource $jnlpUrl $serverURL";    
	
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

	/** Returns next random integer */
	public static int getRandonInt(int minRange, int maxRange) {
		Random random = new Random();
		return random.nextInt((maxRange - minRange) + 1) + minRange;
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
	 * 1.Cloud service names must start with a letter or number, and can contain only letters, numbers, and the dash (-) character.
	 * 2.Every dash (-) character must be immediately preceded and followed by a letter or number; consecutive dashes are not permitted in container names.
	 * 3.Container names must be from 3 through 63 characters long.
	 * @param cloudServiceName Name of the Windows Azure cloud service
	 * @return true if cloudServiceName name is valid else returns false
	 */
	public static boolean validateCloudServiceName(final String cloudServiceName) {
		boolean isValid = false;
		
		if (cloudServiceName != null ) {
			if(cloudServiceName.matches(VAL_CLOUD_SERVICE_NAME)) {
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
	 *     a) a lowercase character
	 *     b) an uppercase character
	 *     c) a number
	 *     d) a special character
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
	
	// Checks if given input value is null or empty
	public static boolean isNotNull(String value) {
		if (value == null || value.trim().length() == 0) {
			return false;
		}
		return true;
	}
	
	// Checks if given input value is null or empty
	public static boolean isNull(String value) {
		if (value == null || value.trim().length() == 0) {
			return true;
		}
		return false;
	}

	public static boolean isValidJvmOption(String value) {
		if (isNotNull(value)) {
			return value.trim().startsWith("-");
		}
		return false;
	}
}













