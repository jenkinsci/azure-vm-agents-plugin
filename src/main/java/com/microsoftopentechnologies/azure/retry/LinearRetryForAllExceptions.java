package com.microsoftopentechnologies.azure.retry;

import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;

public class LinearRetryForAllExceptions extends DefaultRetryStrategy {
	
	public LinearRetryForAllExceptions(int maxRetries, int waitInterval, int defaultTimeoutInSeconds) {
		super(maxRetries, waitInterval, defaultTimeoutInSeconds);
	}
	
	public boolean canRetry(int currentRetryCount, Exception e) throws AzureCloudException {
		if (currentRetryCount >=  maxRetries) {
			throw new AzureCloudException("Exceeded maximum retry count "+maxRetries, e);
		} else {
			return true;
		}
	}

}
