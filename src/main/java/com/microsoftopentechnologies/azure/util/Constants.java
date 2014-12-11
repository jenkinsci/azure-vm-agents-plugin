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

public class Constants {
	public static final String CI_SYSTEM = "jenkinsslaves";
	public static final int DEFAULT_SSH_PORT = 22;
	public static final int DEFAULT_RDP_PORT = 3389;
	public static final String BLOB = "blob";
	public static final String TABLE = "table";
	public static final String QUEUE = "queue";
	public static final String CONFIG_CONTAINER_NAME = "jenkinsconfig";
	public static final String HTTP_PROTOCOL_PREFIX = "http://";
	public static final String BASE_URI_SUFFIX = ".core.windows.net/";
	public static final String FWD_SLASH = "/";
	public static final String VM_NAME_PREFIX="Azure";
	public static final int DEFAULT_MAX_VM_LIMIT = 10;
	public static final int DEFAULT_IDLE_TIME = 60;
	public static final String DEFAULT_MANAGEMENT_URL = "https://management.core.windows.net";
	public static final String AZURE_CLOUD_DISPLAY_NAME = "Microsoft Azure";
	public static final String AZURE_SLAVE_DISPLAY_NAME = "Azure Slave";
	public static final String AZURE_CLOUD_PREFIX = "Azure-";
	public static final String STORAGE_ACCOUNT_PREFIX = "jenkins";
	
	/** OS Types */
	public static final String OS_TYPE_WINDOWS = "Windows";
	public static final String OS_TYPE_LINUX = "Linux";
	
	/** Slaves launch method */
	public static final String LAUNCH_METHOD_JNLP = "JNLP";
	public static final String LAUNCH_METHOD_SSH = "SSH";
	
	/** Template Status */
	public static final String TEMPLATE_STATUS_ACTIVE = "Active until first failure";
	public static final String TEMPLATE_STATUS_ACTIVE_ALWAYS = "Active always";
	public static final String TEMPLATE_STATUS_DISBALED = "Disabled";
	public static final int MAX_PROV_RETRIES = 20;	
	
	/** Error codes */
	public static final String ERROR_CODE_RESOURCE_NF="ResourceNotFound";
	public static final String ERROR_CODE_CONFLICT="ConflictError";	
	public static final String ERROR_CODE_BAD_REQUEST="BadRequest";
	public static final String ERROR_CODE_FORBIDDEN="Forbidden";
	public static final String ERROR_CODE_SERVICE_EXCEPTION="ServiceException";
	public static final String ERROR_CODE_UNKNOWN_HOST="UnknownHostException";
	
	/** End points */
	public static final String PROTOCOL_TCP = "tcp";
	public static final String EP_SSH_NAME= "ssh";
	public static final String EP_RDP_NAME= "rdp";
	
	
	/** Status messages */
	public static final String OP_SUCCESS="Success";
	
	/** Provisioning failure reasons */
	public static final String JNLP_POST_PROV_LAUNCH_FAIL = "Provisioning Failure: JNLP slave failed to connect. Make sure that " + "slave node is able to reach master and necessary firewall rules are configured";
	
	public static final String SLAVE_POST_PROV_JAVA_NOT_FOUND = "Post Provisioning Failure: Java runtime not found. At a minimum init script "
																	+ " should ensure that java runtime is installed";
	
	public static final String SLAVE_POST_PROV_AUTH_FAIL = "Post Provisioning Failure: Not able to authenticate via username and "	+
															" Image may not be supporting password authentication , marking template has disabled";
	
	public static final String SLAVE_POST_PROV_CONN_FAIL = "Post Provisioning Failure: Not able to connect to slave machine. Ensure that ssh server is configured properly";
	
	public static final String REG_EX_DIGIT = "\\d+";
	
	/** Role Status */
	public static final String READY_ROLE_STATUS = "ReadyRole";
	public static final String DELETING_VM_STATUS = "DeletingVM";
	public static final String STOPPED_VM_STATUS = "StoppedVM";
	public static final String STOPPING_VM_STATUS = "StoppingVM";
	public static final String STOPPING_ROLE_STATUS = "StoppingRole";
	public static final String STOPPED_DEALLOCATED_VM_STATUS = "StoppedDeallocated";
	
}
