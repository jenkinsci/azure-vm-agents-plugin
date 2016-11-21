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
package com.microsoft.azure.util;

import com.microsoft.windowsazure.core.pipeline.filter.ServiceRequestContext;
import com.microsoft.windowsazure.core.pipeline.filter.ServiceRequestFilter;

public class AzureUserAgentFilter implements ServiceRequestFilter {
    private static String PLUGIN_NAME = "AzureJenkinsVMAgent";

    public void filter(ServiceRequestContext request) {
        String version = null;
        try {
            version = getClass().getPackage().getImplementationVersion();
        } catch (Exception e) {
            
        }
        if(version == null) {
            version = "local";
        }
        
        String userAgent;
        if (request.getHeader("User-Agent") != null) {
            String currentUserAgent = request.getHeader("User-Agent");
            userAgent = PLUGIN_NAME + "/" + version + " " + currentUserAgent;
            request.removeHeader("User-Agent");
        } else {
            userAgent = PLUGIN_NAME + "/" + version;
        }
        request.setHeader("User-Agent", userAgent);
    }
}
