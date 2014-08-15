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
package com.microsoftopentechnologies.azure.retry;

import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.util.AzureUtil;

/**
 * @author Suresh Nallamilli (snallami@gmail.com)
 */
public class DefaultRetryStrategy implements RetryStrategy {
	protected int currentRetryCount = 0;
	protected int maxRetries = 3;
	protected int waitInterval = 2; // 2 seconds
	protected int defaultTimeoutInSeconds = 4 * 60; // 4 minutes
	
	public DefaultRetryStrategy() {
	}
	
	public DefaultRetryStrategy(int maxRetries, int waitInterval, int defaultTimeoutInSeconds) {
		this.maxRetries = maxRetries;
		this.waitInterval = waitInterval;
		this.defaultTimeoutInSeconds = defaultTimeoutInSeconds;
	}

	public boolean canRetry(int currentRetryCount, Exception e) throws AzureCloudException {
		if (currentRetryCount >=  maxRetries) {
			throw new AzureCloudException("Exceeded maximum retry count "+maxRetries, e);
		} else if (AzureUtil.isHostNotFound(e.getMessage()) || AzureUtil.isConflictError(e.getLocalizedMessage())) {
			return true;
		} else {	
			return false;
		}
	}

	public void handleRetry(Exception e) throws AzureCloudException {
		currentRetryCount++;
		
		if(canRetry(currentRetryCount, e)) {
			try {
				Thread.sleep(getWaitPeriodInSeconds(currentRetryCount, e) * 1000);
			} catch (InterruptedException e1) {
			}
		}
	}

	public int getWaitPeriodInSeconds(int currentRetryCount, Exception e) {
		return waitInterval;
	}

	public int getMaxTimeoutInSeconds() {
		return defaultTimeoutInSeconds;
	}
	
	public void reset() {
		currentRetryCount = 0;
		maxRetries = 3;
		waitInterval = 2;
		defaultTimeoutInSeconds = 4 * 60;
	}

}
