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
public interface RetryStrategy {

    void handleRetry(Exception e) throws AzureCloudException;

    boolean canRetry(int currentRetryCount, Exception e) throws AzureCloudException;

    int getWaitPeriodInSeconds(int currentRetryCount, Exception e);

    int getMaxTimeoutInSeconds();

    void reset();

}
