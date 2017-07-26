/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azure.vmagent;

import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsClientFactory;
import com.microsoft.jenkins.azurecommons.telemetry.TelemetryInterceptorRecorder;
import hudson.Plugin;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Map;

public class AzureVMAgentPlugin extends Plugin {
    public static void sendEvent(final String item, final String action, final Map<String, String> properties) {
        AppInsightsClientFactory.getInstance(AzureVMAgentPlugin.class)
                .sendEvent(item, action, properties, false);
    }

    public static class TelemetryInterceptor implements Interceptor {
        @Override
        public Response intercept(final Chain chain) throws IOException {
            final Request request = chain.request();
            final Response response = chain.proceed(request);
            new TelemetryInterceptorRecorder(AppInsightsClientFactory.getInstance(AzureVMAgentPlugin.class))
                    .record(request, response);
            return response;
        }
    }
}
