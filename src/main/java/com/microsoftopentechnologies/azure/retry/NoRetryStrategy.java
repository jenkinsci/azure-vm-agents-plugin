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

/**
 * @author Suresh Nallamilli (snallami@gmail.com)
 */
public class NoRetryStrategy implements RetryStrategy {
	private int defaultTimeoutInSeconds = 4 * 60; // 4 minutes
	
	public NoRetryStrategy() {
	}
	
	public NoRetryStrategy(int defaultTimeoutInSeconds) {
		this.defaultTimeoutInSeconds = defaultTimeoutInSeconds;
	}

	public boolean canRetry(int currentRetryCount, Exception e) throws AzureCloudException {
		return false;
	}

	public int getWaitPeriodInSeconds(int currentRetryCount, Exception e) {
		return 0;
	}

	public void handleRetry(Exception e) throws AzureCloudException {
		throw new AzureCloudException(e.getMessage(), e);
	}

	public int getMaxTimeoutInSeconds() {
		return defaultTimeoutInSeconds;
	}

	public void reset() {
		// Resetting back to default values
		defaultTimeoutInSeconds = 4 * 60; // 4 minutes
	}

}
