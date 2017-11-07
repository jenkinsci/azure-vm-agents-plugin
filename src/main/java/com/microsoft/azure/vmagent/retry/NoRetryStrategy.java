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
package com.microsoft.azure.vmagent.retry;

import com.microsoft.azure.vmagent.exceptions.AzureCloudException;

/**
 * @author Suresh Nallamilli (snallami@gmail.com)
 */
public class NoRetryStrategy implements RetryStrategy {
    private static final int DEFAULT_TIMEOUT_IN_SECONDS = 4 * 60; // 4 minutes

    private int defaultTimeoutInSeconds = DEFAULT_TIMEOUT_IN_SECONDS;

    public NoRetryStrategy() {
    }

    public NoRetryStrategy(int defaultTimeoutInSeconds) {
        this.defaultTimeoutInSeconds = defaultTimeoutInSeconds;
    }

    @Override
    public boolean canRetry(int currentRetryCount, Exception e) throws AzureCloudException {
        return false;
    }

    @Override
    public int getWaitPeriodInSeconds(int currentRetryCount, Exception e) {
        return 0;
    }

    @Override
    public void handleRetry(Exception e) throws AzureCloudException {
        throw AzureCloudException.create(e.getMessage(), e);
    }

    @Override
    public int getMaxTimeoutInSeconds() {
        return defaultTimeoutInSeconds;
    }

    @Override
    public void reset() {
        // Resetting back to default values
        defaultTimeoutInSeconds = DEFAULT_TIMEOUT_IN_SECONDS;
    }

}
