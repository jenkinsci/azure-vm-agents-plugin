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

import okhttp3.logging.HttpLoggingInterceptor;

public class Constants {

    public static final String CI_SYSTEM = "jenkinsagents";

    public static final String PLUGIN_NAME = "AzureJenkinsVMAgent";

    public static final int DEFAULT_SSH_PORT = 22;

    public static final int DEFAULT_RDP_PORT = 3389;

    public static final String BLOB = "blob";

    public static final String TABLE = "table";

    public static final String QUEUE = "queue";

    public static final String CONFIG_CONTAINER_NAME = "jenkinsconfig";

    public static final String HTTP_PROTOCOL_PREFIX = "http://";

    public static final String BASE_URI_SUFFIX = ".core.windows.net/";

    public static final String FWD_SLASH = "/";

    public static final int DEFAULT_MAX_VM_LIMIT = 10;
    
    public static final int DEFAULT_DEPLOYMENT_TIMEOUT_SEC = 1200;

    public static final int DEFAULT_IDLE_TIME = 60;

    public static final String DEFAULT_MANAGEMENT_URL = "https://management.core.windows.net/";

    public static final String AZURE_CLOUD_DISPLAY_NAME = "Microsoft Azure VM Agents";

    public static final String AZURE_VM_AGENT_CLOUD_DISPLAY_NAME = "Azure VM Agent";

    public static final String AZURE_CLOUD_PREFIX = "AzureVMAgents-";

    public static final String STORAGE_ACCOUNT_PREFIX = "jenkins";

    /** OS Types */
    public static final String OS_TYPE_WINDOWS = "Windows";

    public static final String OS_TYPE_LINUX = "Linux";
    
    /** Usage types for template names **/
    public static final String USAGE_TYPE_DEPLOYMENT = "Deployment";
    
    /** VM/Deployment name date formats **/
    public static final int VM_NAME_HASH_LENGTH = 6;
    public static final String DEPLOYMENT_NAME_DATE_FORMAT = "MMddHHmmssSSS";

    /** Agent launch methods */
    public static final String LAUNCH_METHOD_JNLP = "JNLP";

    public static final String LAUNCH_METHOD_SSH = "SSH";

    public static final int MAX_PROV_RETRIES = 20;

    /** Error codes */
    public static final String ERROR_CODE_RESOURCE_NF = "ResourceNotFound";

    public static final String ERROR_CODE_CONFLICT = "ConflictError";

    public static final String ERROR_CODE_BAD_REQUEST = "BadRequest";

    public static final String ERROR_CODE_FORBIDDEN = "Forbidden";

    public static final String ERROR_CODE_SERVICE_EXCEPTION = "ServiceException";

    public static final String ERROR_CODE_UNKNOWN_HOST = "UnknownHostException";

    /** End points */
    public static final String PROTOCOL_TCP = "tcp";

    public static final String EP_SSH_NAME = "ssh";

    public static final String EP_RDP_NAME = "rdp";

    /** Status messages */
    public static final String OP_SUCCESS = "Success";

    /** Provisioning failure reasons */
    public static final String JNLP_POST_PROV_LAUNCH_FAIL
            = "Provisioning Failure: JNLP agent failed to connect. Make sure that "
            + "agent node is able to reach master and necessary firewall rules are configured";

    public static final String AGENT_POST_PROV_JAVA_NOT_FOUND
            = "Post Provisioning Failure: Java runtime not found. At a minimum init script "
            + " should ensure that java runtime is installed";

    public static final String AGENT_POST_PROV_AUTH_FAIL
            = "Post Provisioning Failure: Not able to authenticate via username and "
            + " Image may not be supporting password authentication , marking template has disabled";

    public static final String AGENT_POST_PROV_CONN_FAIL
            = "Post Provisioning Failure: Not able to connect to agent machine. Ensure that ssh server is configured properly";

    public static final String REG_EX_DIGIT = "\\d+";

    /** Role Status */
    public static final String PROVISIONING_OR_DEPROVISIONING_VM_STATUS = "PROVISIONING_OR_DEPROVISIONING";

    public static final String DEFAULT_RESOURCE_GROUP_NAME = "jenkins";

    public static final String DEFAULT_VNET_ADDRESS_MASK = "10.0.0.0/16";
    
    public static final String DEFAULT_SUBNET_ADDRESS_MASK = "10.0.0.0/24";
    
    public static final String DEFAULT_VNET_NAME = "jenkinsarm-vnet";
    
    public static final String DEFAULT_SUBNET_NAME = "jenkinsarm-snet";
    
    public static final String DEFAULT_RESOURCE_GROUP_PATTERN = "^[a-zA-Z0-9][a-zA-Z\\-_0-9]{0,62}[a-zA-Z0-9]$";
    
    public static final HttpLoggingInterceptor.Level DEFAULT_AZURE_SDK_LOGGING_LEVEL = HttpLoggingInterceptor.Level.NONE;
}
