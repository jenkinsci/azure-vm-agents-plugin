/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azure.vmagent.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.AzureTokenCredentials;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MsiTokenCredentials extends AzureTokenCredentials {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Map<String, Token> tokens;

    private volatile String tenantId;

    private int msiPort;

    /**
     * Initializes a new instance of the AzureTokenCredentials.
     *
     * @param environment the Azure environment to use
     */
    public MsiTokenCredentials(int msiPort, AzureEnvironment environment) {
        super(environment, null);
        tokens = new HashMap<>();
        this.msiPort = msiPort;
    }

    @Override
    public synchronized String getToken(String resource) throws IOException {
        Token authenticationResult = tokens.get(resource);
        if (authenticationResult == null || authenticationResult.isExpired()) {
            authenticationResult = acquireAccessToken(resource);
            tokens.put(resource, authenticationResult);
        }
        return authenticationResult.getAccessToken();
    }

    private Token acquireAccessToken(String resource) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("resource", resource)
                .build();
        Request request = new Request.Builder()
                .addHeader("Metadata", "true")
                .url("http://localhost:" + msiPort + "/oauth2/token")
                .post(body)
                .build();

        Response response = new OkHttpClient().newCall(request).execute();
        if (!response.isSuccessful()) {
            throw new RuntimeException("http response: " + response.code() + " " + response.message());
        } else {
            String responseBody = response.body().string();
            return parseToken(responseBody);
        }
    }

    private Token parseToken(String responseBody) throws IOException {
        Token token = mapper.readValue(responseBody, Token.class);
        if (token == null) {
            throw new RuntimeException("Failed to parse the response.");
        } else if (StringUtils.isEmpty(token.getAccessToken())) {
            throw new RuntimeException("The access token isn't included in the response.");
        } else {
            if (StringUtils.isEmpty(tenantId)) {
                String rawJwt = token.getAccessToken();
                DecodedJWT jwt = JWT.decode(rawJwt);
                this.tenantId = jwt.getClaim("tid").asString();
            }
            return token;
        }
    }

    @Override
    public String domain() {
        return tenantId;
    }

    public static final class Token {
        private String resource;

        @JsonProperty("expires_in")
        private long expiresIn;

        @JsonProperty("expires_on")
        private long expiresOn;

        @JsonProperty("access_token")
        private String accessToken;

        @JsonProperty("refresh_token")
        private String refreshToken;

        @JsonProperty("token_type")
        private String tokenType;

        public Token() {
        }

        public Token(String resource, long expiresIn, long expiresOn,
                      String accessToken, String refreshToken, String tokenType) {
            this.resource = resource;
            this.expiresIn = expiresIn;
            this.expiresOn = expiresOn;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.tokenType = tokenType;
        }

        public String getResource() {
            return resource;
        }

        public long getExpiresIn() {
            return expiresIn;
        }

        public long getExpiresOn() {
            return expiresOn;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public String getRefreshToken() {
            return refreshToken;
        }

        public String getTokenType() {
            return tokenType;
        }

        public void setResource(String resource) {
            this.resource = resource;
        }

        public void setExpiresIn(long expiresIn) {
            this.expiresIn = expiresIn;
        }

        public void setExpiresOn(long expiresOn) {
            this.expiresOn = expiresOn;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public void setRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
        }

        public void setTokenType(String tokenType) {
            this.tokenType = tokenType;
        }

        boolean isExpired() {
            long now = TimeUnit.SECONDS.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            return expiresOn < now;
        }
    }
}
