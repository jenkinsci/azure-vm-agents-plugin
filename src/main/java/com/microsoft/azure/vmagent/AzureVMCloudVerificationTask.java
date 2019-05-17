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
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public final class AzureVMCloudVerificationTask extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(AzureVMCloudVerificationTask.class.getName());

    private static final int RECURRENCE_PERIOD_IN_MILLIS = 60 * 60 * 1000;

    public AzureVMCloudVerificationTask() {
        super("Azure VM Verification Task");
    }

    public static void verify(String cloudName, String templateName) {
        if (StringUtils.isBlank(cloudName) || StringUtils.isBlank(templateName)) {
            return;
        }

        verifyCloud(cloudName);

        final AzureVMCloud cloud = getCloud(cloudName);
        if (cloud == null) {
            LOGGER.log(getStaticNormalLoggingLevel(),
                    "AzureVMCloudVerificationTask: verify: parent cloud not found for {0} in {1}",
                    new Object[]{templateName, cloudName});
            return;
        }
        final AzureVMAgentTemplate agentTemplate = cloud.getAzureAgentTemplate(templateName);
        if (agentTemplate == null) {
            LOGGER.log(getStaticNormalLoggingLevel(),
                    "AzureVMCloudVerificationTask: verify: "
                            + "could not retrieve agent template named {0} in {1}",
                    new Object[]{templateName, cloudName});
            return;
        }

        synchronized (agentTemplate) {
            // If cloud verified failed, all the template in the cloud should set as failed.
            if (!cloud.getConfigurationStatus().equals(Constants.VERIFIED_PASS)) {
                agentTemplate.getTemplateProvisionStrategy().failure();
                return;
            }

            if (agentTemplate.getTemplateProvisionStrategy().isVerifiedPass()) {
                return;
            }

            // This means the template just verified failed soon before.
            if (!agentTemplate.getTemplateProvisionStrategy().isEnabled()) {
                return;
            }

            // The template is failed or not verified.  Do so now
            try {
                List<String> errors = agentTemplate.verifyTemplate();
                if (errors.isEmpty()) {
                    LOGGER.log(getStaticNormalLoggingLevel(),
                            "AzureVMCloudVerificationTask: verify: {0} verified successfully",
                            templateName);
                    // Verified, set the template to verified.
                    agentTemplate.getTemplateProvisionStrategy().verifiedPass();
                    // Reset the status details
                    agentTemplate.setTemplateStatusDetails("");
                } else {
                    String details = StringUtils.join(errors, "\n");
                    LOGGER.log(Level.WARNING,
                            "AzureVMCloudVerificationTask: verify: {0} could not be verified:\n{1}",
                            new Object[]{templateName, details});
                    agentTemplate.getTemplateProvisionStrategy().failure();
                    // Set the status details to the set of messages
                    agentTemplate.setTemplateStatusDetails(details);
                }
            } catch (Exception e) {
                // Log, but ignore overall
                LOGGER.log(Level.WARNING,
                        "AzureVMCloudVerificationTask: verify: got exception while verifying {0}:\n{1}",
                        new Object[]{templateName, e.toString()});
                agentTemplate.getTemplateProvisionStrategy().failure();
            }
        }
    }

    private static void verifyCloud(String cloudName) {
        if (StringUtils.isBlank(cloudName)) {
            return;
        }
        LOGGER.log(getStaticNormalLoggingLevel(),
                "AzureVMCloudVerificationTask: verify: verifying cloud {0}",
                cloudName);

        AzureVMCloud cloud = getCloud(cloudName);

        // Unknown cloud.  Maybe the name changed
        if (cloud == null) {
            LOGGER.log(getStaticNormalLoggingLevel(),
                    "AzureVMCloudVerificationTask: verify: subscription {0} not found, skipping",
                    cloudName);
            return;
        }

        synchronized (cloud) {
            // Only if verified pass, return at once
            if (cloud.getConfigurationStatus().equals(Constants.VERIFIED_PASS)) {
                LOGGER.log(getStaticNormalLoggingLevel(),
                        "AzureVMCloudVerificationTask: verify: cloud {0} already verified pass",
                        cloudName);
                // Update the count.
                cloud.setVirtualMachineCount(getVirtualMachineCount(cloud));
                return;
            }

            // Verify.  Update the VM count before setting to valid
            if (verifyConfiguration(cloud)) {
                LOGGER.log(getStaticNormalLoggingLevel(), "AzureVMCloudVerificationTask: validate: {0} "
                        + "verified pass", cloudName);
                // Update the count
                cloud.setVirtualMachineCount(getVirtualMachineCount(cloud));
                // We grab the current VM count and
                cloud.setConfigurationStatus(Constants.VERIFIED_PASS);
                return;
            }

            // Not valid!
            cloud.setConfigurationStatus(Constants.VERIFIED_FAILED);
            LOGGER.log(Level.WARNING,
                    "AzureVMCloudVerificationTask: verify: {0} not verified, has errors",
                    cloudName);
        }
    }

    /**
     * Checks the subscription for validity if needed.
     *
     * @param cloud The Azure VM Cloud
     * @return True if the subscription is valid, false otherwise. Updates the
     * cloud state if it is. If subscription is not valid, then we can just
     * return
     */
    public static boolean verifyConfiguration(AzureVMCloud cloud) {
        LOGGER.log(getStaticNormalLoggingLevel(), "AzureVMCloudVerificationTask: verifyConfiguration: start");

        // Check the sub and off we go
        String result = cloud.getServiceDelegate().verifyConfiguration(
                cloud.getResourceGroupName(),
                Integer.toString(cloud.getMaxVirtualMachinesLimit()),
                Integer.toString(cloud.getDeploymentTimeout()));
        if (!Constants.OP_SUCCESS.equals(result)) {
            LOGGER.log(getStaticNormalLoggingLevel(), "AzureVMCloudVerificationTask: verifyConfiguration: {0}", result);
            cloud.setConfigurationStatus(Constants.VERIFIED_FAILED);
            return false;
        }

        return true;
    }

    /**
     * Retrieve the current VM count.
     *
     * @param cloud The Azure VM Cloud
     * @return The current VM count
     */
    public static int getVirtualMachineCount(AzureVMCloud cloud) {
        LOGGER.log(getStaticNormalLoggingLevel(), "AzureVMCloudVerificationTask: getVirtualMachineCount: start");
        try {
            int vmCount = cloud.getServiceDelegate().getVirtualMachineCount(cloud.getCloudName(),
                    cloud.getResourceGroupName());
            LOGGER.log(getStaticNormalLoggingLevel(),
                    "AzureVMCloudVerificationTask: getVirtualMachineCount: end, cloud {0} has currently {1} vms",
                    new Object[]{cloud.getCloudName(), vmCount});
            return vmCount;
        } catch (Exception e) {
            LOGGER.log(getStaticNormalLoggingLevel(),
                    "AzureVMCloudVerificationTask: getVirtualMachineCount: failed to retrieve vm count:\n{0}",
                    e.toString());
            // We could have failed for any number of reasons.  Just return the current
            // number of virtual machines.
            return cloud.getApproximateVirtualMachineCount();
        }
    }


    public static AzureVMCloud getCloud(String cloudName) {
        return Jenkins.getInstanceOrNull() == null ? null : (AzureVMCloud) Jenkins.getInstance().getCloud(cloudName);
    }

    @Override
    public void execute(TaskListener arg0) {
        for (Cloud cloud : Jenkins.getInstance().clouds) {
            if (cloud instanceof AzureVMCloud) {
                AzureVMCloud azureVMCloud = (AzureVMCloud) cloud;
                synchronized (azureVMCloud) {
                    azureVMCloud.setVirtualMachineCount(getVirtualMachineCount(azureVMCloud));
                }
            }
        }
    }

    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD_IN_MILLIS;
    }

    @Override
    protected Level getNormalLoggingLevel() {
        return Level.FINE;
    }

    private static Level getStaticNormalLoggingLevel() {
        return Level.FINE;
    }
}
