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
public class ExponentialRetryStrategy implements RetryStrategy {

    int currentRetryCount = 0;

    private int maxRetries = 5;

    private int MaxWaitIntervalInSec = 10; // 10 seconds

    public ExponentialRetryStrategy() {
    }

    public ExponentialRetryStrategy(int maxRetries, int MaxWaitIntervalInSec) {
        this.maxRetries = maxRetries;
        this.MaxWaitIntervalInSec = MaxWaitIntervalInSec;
    }

    @Override
    public void handleRetry(final Exception e) throws AzureCloudException {
        currentRetryCount++;

        if (canRetry(currentRetryCount, e)) {
            try {
                Thread.sleep(getWaitPeriodInSeconds(currentRetryCount, e) * 1000);
            } catch (InterruptedException e1) {
            }
        }
    }

    @Override
    public boolean canRetry(final int currentRetryCount, final Exception e)
            throws AzureCloudException {
        if (currentRetryCount >= maxRetries) {
            throw new AzureCloudException("Exceeded maximum retry count " + maxRetries, e);
        } else if (AzureUtil.isHostNotFound(e.getMessage()) || AzureUtil.isConflictError(e.getLocalizedMessage())) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public int getWaitPeriodInSeconds(int currentRetryCount, Exception e) {
        return calculateWaitInterval(currentRetryCount);
    }

    @Override
    public int getMaxTimeoutInSeconds() {
        return 0; // No time out.
    }

    @Override
    public void reset() {
        currentRetryCount = 0;
        maxRetries = 5;
        MaxWaitIntervalInSec = 10; // 1 hour
    }

    public int calculateWaitInterval(int currentRetryCount) {
        int incrementDelta = (int) (Math.pow(2, currentRetryCount) - 1);

        return Math.min(incrementDelta, MaxWaitIntervalInSec);
    }

}
