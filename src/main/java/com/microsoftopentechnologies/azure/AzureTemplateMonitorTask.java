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
package com.microsoftopentechnologies.azure;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.Hudson;
import com.microsoftopentechnologies.azure.util.Constants;

@Extension
public final class AzureTemplateMonitorTask extends AsyncPeriodicWork {
	private static final Logger LOGGER = Logger.getLogger(AzureTemplateMonitorTask.class.getName());
	
	private static Map<String, String> templates;

	public AzureTemplateMonitorTask() {
		super("AzureTemplateMonitorTask");
	}

	public void execute(TaskListener arg0) throws IOException, InterruptedException {
		if (templates != null && templates.size() > 0) {
			LOGGER.info("AzureTemplateMonitorTask: execute: start , template size "+templates.size());
			for (Map.Entry<String, String> entry : templates.entrySet()) {
				AzureCloud azureCloud = getCloud(entry.getValue());
				AzureSlaveTemplate slaveTemplate = azureCloud.getAzureSlaveTemplate(entry.getKey());
				
				if (slaveTemplate.getTemplateStatus().equals(Constants.TEMPLATE_STATUS_DISBALED)) {
					try {
						List<String> errors = slaveTemplate.verifyTemplate();
						
						if (errors.size() == 0) {
							// Template errors are now gone, set template to Active
							slaveTemplate.setTemplateStatus(Constants.TEMPLATE_STATUS_ACTIVE);
							slaveTemplate.setTemplateStatusDetails("");
							// remove from the list
							templates.remove(slaveTemplate.getTemplateName());
						}
					} catch (Exception e) {
						// just ignore
					}
				}
			}
			LOGGER.info("AzureTemplateMonitorTask: execute: end");
		}
	}
	
	public synchronized static void registerTemplate(AzureSlaveTemplate template) {
		if (templates == null) 
			templates = new HashMap<String, String>();
		templates.put(template.getTemplateName(), Constants.AZURE_CLOUD_PREFIX+template.getAzureCloud().getSubscriptionId());
	}

	public long getRecurrencePeriod() {
		// Every 10 minutes
		return 15 * 60 * 1000;
	}
	
	public AzureCloud getCloud(String cloudName) {
    	return (AzureCloud) Hudson.getInstance().getCloud(cloudName);
    }
}
