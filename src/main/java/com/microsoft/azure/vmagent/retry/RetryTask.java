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

import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Suresh Nallamilli (snallami@gmail.com)
 */
public class RetryTask<T> implements Callable<T> {

    private static final Logger LOGGER = Logger.getLogger(RetryTask.class.getName());

    private final Callable<T> task;

    private final RetryStrategy retryStrategy;

    public RetryTask(Callable<T> task) {
        this.task = task;
        this.retryStrategy = new DefaultRetryStrategy();
    }

    public RetryTask(Callable<T> task, RetryStrategy retryStrategy) {
        this.task = task;
        this.retryStrategy = retryStrategy;
    }

    @Override
    public T call() throws AzureCloudException {
        while (true) {
            try {
                return task.call();
            } catch (Exception e) {
                LOGGER.log(Level.INFO, "Handle retry due to: ", e);
                retryStrategy.handleRetry(e);
            }
        }
    }
}
