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
import com.microsoft.azure.vmagent.util.AzureUtil;

import static com.microsoft.azure.vmagent.util.Constants.MILLIS_IN_SECOND;

/**
 * @author Suresh Nallamilli (snallami@gmail.com)
 */
public class DefaultRetryStrategy implements RetryStrategy {
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_WAIT_INTERVAL_IN_SECONDS = 2;
    private static final int DEFAULT_TIMEOUT_IN_SECONDS = 4 * 60; // 4 minutes

    private int currentRetryCount = 0;

    private int maxRetries = DEFAULT_MAX_RETRIES;

    private int waitInterval = DEFAULT_WAIT_INTERVAL_IN_SECONDS;

    private int defaultTimeoutInSeconds = DEFAULT_TIMEOUT_IN_SECONDS;

    public DefaultRetryStrategy() {
    }

    public DefaultRetryStrategy(int maxRetries, int waitInterval, int defaultTimeoutInSeconds) {
        this.maxRetries = maxRetries;
        this.waitInterval = waitInterval;
        this.defaultTimeoutInSeconds = defaultTimeoutInSeconds;
    }

    @Override
    public boolean canRetry(int retryCount, Exception e) throws AzureCloudException {
        if (retryCount >= maxRetries) {
            throw AzureCloudException.create("Exceeded maximum retry count " + maxRetries, e);
        } else {
            return AzureUtil.isHostNotFound(e.getMessage()) || AzureUtil.isConflictError(e.getLocalizedMessage());
        }
    }

    @Override
    public void handleRetry(Exception e) throws AzureCloudException {
        currentRetryCount++;

        if (canRetry(currentRetryCount, e)) {
            try {
                Thread.sleep(getWaitPeriodInSeconds(currentRetryCount, e) * MILLIS_IN_SECOND);
            } catch (InterruptedException e1) {
            }
        }
    }

    @Override
    public int getWaitPeriodInSeconds(int retryCount, Exception e) {
        return waitInterval;
    }

    @Override
    public int getMaxTimeoutInSeconds() {
        return defaultTimeoutInSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    @Override
    public void reset() {
        currentRetryCount = 0;
        maxRetries = DEFAULT_MAX_RETRIES;
        waitInterval = DEFAULT_WAIT_INTERVAL_IN_SECONDS;
        defaultTimeoutInSeconds = DEFAULT_TIMEOUT_IN_SECONDS;
    }

}
