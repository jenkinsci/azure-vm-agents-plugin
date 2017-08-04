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
package com.microsoft.azure.vmagent.exceptions;

import com.microsoft.azure.CloudException;

public final class AzureCloudException extends Exception {

    private static final long serialVersionUID = -8157417759485046943L;

    private AzureCloudException(String msg) {
        super(msg);
    }

    private AzureCloudException(String msg, Exception ex) {
        super(msg, ex);
    }

    public static AzureCloudException create(Exception ex) {
        return create(null, ex);
    }

    public static AzureCloudException create(String msg) {
        return new AzureCloudException(msg);
    }

    public static AzureCloudException create(String msg, Exception ex) {
        if (ex instanceof CloudException) {
            // Drop stacktrace of CloudException and throw its message only
            //
            // Fields in CloudException contain details of HTTP requests and responses. Their types are in okhttp
            // package. Once serialized and persisted, these fields may not be recognized and unmarshalled properly
            // when Cloud Statistics Plugin loads next time, as okhttp related classes haven't loaded yet at that
            // point. This can cause crash of the plugin and makes Jenkins unusable.
            if (msg != null) {
                return new AzureCloudException(String.format("%s: %s", msg, ex.getMessage()));
            } else {
                return new AzureCloudException(ex.getMessage());
            }
        } else {
            return new AzureCloudException(msg, ex);
        }
    }
}
