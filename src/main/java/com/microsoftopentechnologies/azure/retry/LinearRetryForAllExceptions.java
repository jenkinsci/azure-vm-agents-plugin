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

public class LinearRetryForAllExceptions extends DefaultRetryStrategy {

    public LinearRetryForAllExceptions(int maxRetries, int waitInterval, int defaultTimeoutInSeconds) {
        super(maxRetries, waitInterval, defaultTimeoutInSeconds);
    }

    @Override
    public boolean canRetry(int currentRetryCount, Exception e) throws AzureCloudException {
        if (currentRetryCount >= maxRetries) {
            throw new AzureCloudException("Exceeded maximum retry count " + maxRetries, e);
        } else {
            return true;
        }
    }

}
