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
public class ExponentialRetryStrategy implements RetryStrategy {
    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final int DEFAULT_MAX_WAIT_INTERVAL_IN_SECONDS = 10;

    private int currentRetryCount = 0;

    private int maxRetries = DEFAULT_MAX_RETRIES;

    private int maxWaitIntervalInSec = DEFAULT_MAX_WAIT_INTERVAL_IN_SECONDS;

    public ExponentialRetryStrategy() {
    }

    public ExponentialRetryStrategy(int maxRetries, int maxWaitIntervalInSec) {
        this.maxRetries = maxRetries;
        this.maxWaitIntervalInSec = maxWaitIntervalInSec;
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
    public boolean canRetry(int retryCount, Exception e)
            throws AzureCloudException {
        if (retryCount >= maxRetries) {
            throw AzureCloudException.create("Exceeded maximum retry count " + maxRetries, e);
        } else {
            return AzureUtil.isHostNotFound(e.getMessage()) || AzureUtil.isConflictError(e.getLocalizedMessage());
        }
    }

    @Override
    public int getWaitPeriodInSeconds(int retryCount, Exception e) {
        return calculateWaitInterval(retryCount);
    }

    @Override
    public int getMaxTimeoutInSeconds() {
        return 0; // No time out.
    }

    @Override
    public void reset() {
        currentRetryCount = 0;
        maxRetries = DEFAULT_MAX_RETRIES;
        maxWaitIntervalInSec = DEFAULT_MAX_WAIT_INTERVAL_IN_SECONDS;
    }

    public int calculateWaitInterval(int retryCount) {
        int incrementDelta = (int) (Math.pow(2, retryCount) - 1);

        return Math.min(incrementDelta, maxWaitIntervalInSec);
    }

}
