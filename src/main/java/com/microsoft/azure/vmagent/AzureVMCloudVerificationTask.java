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
package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.util.Constants;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


public final class AzureVMCloudVerificationTask {

    private static final Logger LOGGER = Logger.getLogger(AzureVMCloudVerificationTask.class.getName());

    private AzureVMCloudVerificationTask() {
    }

    public static synchronized void verify(String cloudName, String templateName) {
        if (StringUtils.isBlank(cloudName) || StringUtils.isBlank(templateName)) {
            return;
        }

        verifyCloud(cloudName);

        final AzureVMCloud cloud = getCloud(cloudName);
        if (cloud == null) {
            LOGGER.log(Level.INFO,
                    "AzureVMCloudVerificationTask: verify: parent cloud not found for {0} in {1}",
                    new Object[]{templateName, cloudName});
            return;
        }
        final AzureVMAgentTemplate agentTemplate = cloud.getAzureAgentTemplate(templateName);
        if (agentTemplate == null) {
            LOGGER.log(Level.INFO,
                    "AzureVMCloudVerificationTask: verify: "
                            + "could not retrieve agent template named {0} in {1}",
                    new Object[]{templateName, cloudName});
            return;
        }

        if (!cloud.getConfigurationStatus().equals(Constants.VERIFIED_PASS)) {
            agentTemplate.setTemplateConfigurationStatus(Constants.UNVERIFIED);
            return;
        }

        if (!agentTemplate.getTemplateConfigurationStatus().equals(Constants.UNVERIFIED)) {
            return;
        }

        // The template is not yet verified.  Do so now
        try {
            List<String> errors = agentTemplate.verifyTemplate();
            if (errors.isEmpty()) {
                LOGGER.log(Level.FINE,
                        "AzureVMCloudVerificationTask: verify: {0} verified successfully",
                        templateName);
                // Verified, set the template to verified.
                agentTemplate.setTemplateConfigurationStatus(Constants.VERIFIED_PASS);
                // Reset the status details
                agentTemplate.setTemplateStatusDetails("");
            } else {
                String details = StringUtils.join(errors, "\n");
                LOGGER.log(Level.INFO,
                        "AzureVMCloudVerificationTask: verify: {0} could not be verified:\n{1}",
                        new Object[]{templateName, details});
                agentTemplate.setTemplateConfigurationStatus(Constants.VERIFIED_FAILED);
                // Set the status details to the set of messages
                agentTemplate.setTemplateStatusDetails(details);
            }
        } catch (Exception e) {
            // Log, but ignore overall
            LOGGER.log(Level.INFO,
                    "AzureVMCloudVerificationTask: verify: got exception while verifying {0}:\n{1}",
                    new Object[]{templateName, e.toString()});
            agentTemplate.setTemplateConfigurationStatus(Constants.UNVERIFIED);
        }
    }

    private static void verifyCloud(String cloudName) {
        if (StringUtils.isBlank(cloudName)) {
            return;
        }
        LOGGER.log(Level.FINE,
                "AzureVMCloudVerificationTask: verify: verifying cloud {0}",
                cloudName);

        AzureVMCloud cloud = getCloud(cloudName);

        // Unknown cloud.  Maybe the name changed
        if (cloud == null) {
            LOGGER.log(Level.INFO,
                    "AzureVMCloudVerificationTask: verify: subscription {0} not found, skipping",
                    cloudName);
            return;
        }

        // If already verified, skip
        if (!cloud.getConfigurationStatus().equals(Constants.UNVERIFIED)) {
            LOGGER.log(Level.FINE,
                    "AzureVMCloudVerificationTask: verify: subscription {0} already verified",
                    cloudName);
            // Update the count.
            cloud.setVirtualMachineCount(getVirtualMachineCount(cloud));
            return;
        }

        // Verify.  Update the VM count before setting to valid
        if (verifyConfiguration(cloud)) {
            LOGGER.log(Level.FINE, "AzureVMCloudVerificationTask: validate: {0} verified", cloudName);
            // Update the count
            cloud.setVirtualMachineCount(getVirtualMachineCount(cloud));
            // We grab the current VM count and
            cloud.setConfigurationStatus(Constants.VERIFIED_PASS);
            return;
        }

        // Not valid!
        cloud.setConfigurationStatus(Constants.VERIFIED_FAILED);
        LOGGER.log(Level.INFO,
                "AzureVMCloudVerificationTask: verify: {0} not verified, has errors",
                cloudName);
    }

    /**
     * Checks the subscription for validity if needed.
     *
     * @param cloud
     * @return True if the subscription is valid, false otherwise. Updates the
     * cloud state if it is. If subscription is not valid, then we can just
     * return
     */
    public static boolean verifyConfiguration(AzureVMCloud cloud) {
        LOGGER.info("AzureVMCloudVerificationTask: verifyConfiguration: start");

        // Check the sub and off we go
        String result = cloud.getServiceDelegate().verifyConfiguration(
                cloud.getResourceGroupName(),
                Integer.toString(cloud.getMaxVirtualMachinesLimit()),
                Integer.toString(cloud.getDeploymentTimeout()));
        if (result != Constants.OP_SUCCESS) {
            LOGGER.log(Level.INFO, "AzureVMCloudVerificationTask: verifyConfiguration: {0}", result);
            cloud.setConfigurationStatus(Constants.VERIFIED_FAILED);
            return false;
        }

        return true;
    }

    /**
     * Retrieve the current VM count.
     *
     * @param cloud
     * @return
     */
    public static int getVirtualMachineCount(AzureVMCloud cloud) {
        LOGGER.info("AzureVMCloudVerificationTask: getVirtualMachineCount: start");
        try {
            int vmCount = cloud.getServiceDelegate().getVirtualMachineCount(cloud.getResourceGroupName());
            LOGGER.log(Level.INFO,
                    "AzureVMCloudVerificationTask: getVirtualMachineCount: end, currently {0} vms",
                    vmCount);
            return vmCount;
        } catch (Exception e) {
            LOGGER.log(Level.INFO,
                    "AzureVMCloudVerificationTask: getVirtualMachineCount: failed to retrieve vm count:\n{0}",
                    e.toString());
            // We could have failed for any number of reasons.  Just return the current
            // number of virtual machines.
            return cloud.getApproximateVirtualMachineCount();
        }
    }


    public static AzureVMCloud getCloud(String cloudName) {
        return Jenkins.getInstance() == null ? null : (AzureVMCloud) Jenkins.getInstance().getCloud(cloudName);
    }

}
