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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.retry.NoRetryStrategy;
import com.microsoftopentechnologies.azure.retry.RetryStrategy;
import com.microsoftopentechnologies.azure.retry.RetryTask;

public class ExecutionEngine {

    public static <T> T executeWithNoRetry(final Callable<T> task) throws AzureCloudException {
        return executeWithRetry(task, new NoRetryStrategy());
    }

    public static <T> T executeWithRetry(final Callable<T> task, final RetryStrategy retryStrategy)
            throws AzureCloudException {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<T> result = executorService.submit(new RetryTask<T>(task, retryStrategy));

        try {
            if (retryStrategy.getMaxTimeoutInSeconds() == 0) {
                return result.get();
            } else {
                return result.get(retryStrategy.getMaxTimeoutInSeconds(), TimeUnit.SECONDS);
            }
        } catch (TimeoutException timeoutException) {
            throw new AzureCloudException("Operation timed out: ", timeoutException);
        } catch (Exception ex) {
            throw new AzureCloudException(ex);
        } finally {
            executorService.shutdown();
        }
    }

    public static <T> Future<T> executeAsync(final Callable<T> task, final RetryStrategy retryStrategy)
            throws AzureCloudException {
        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final Future<T> res = executorService.submit(new RetryTask<T>(task, retryStrategy));
        executorService.shutdown();
        return res;
    }
}
