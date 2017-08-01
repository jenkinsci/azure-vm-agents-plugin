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
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Performs a few types of verification: 1. Overall subscription verification.
 * 2. Approximate VM count verification 3. Template verification.
 * <p>
 * When a new AzureVMCloud is constructed or a new template is added via CLI
 * interface, then we will manually trigger this workload.
 * <p>
 * This thread serves as a gate for whether we can create VMs from a certain
 * template
 *
 * @author mmitche
 */
@Extension
public final class AzureVMCloudVerificationTask extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(AzureVMCloudVerificationTask.class.getName());

    private static final int RECURRENCE_PERIOD_IN_MILLIS = 5 * 60 * 1000;  // every 5 minutes

    // Templates that need verification
    private static Set<AbstractMap.SimpleEntry<String, String>> cloudTemplates;

    // Set of clouds that need verification.
    private static Set<String> cloudNames;

    public AzureVMCloudVerificationTask() {
        super("AzureVMCloudVerificationTask");
    }

    private static final Object CLOUD_NAMES_LOCK = new Object();
    private static final Object TEMPLATES_LOCK = new Object();

    private void verify() {
        if (cloudNames != null && !cloudNames.isEmpty()) {
            // Walk the list of clouds and verify the configuration. If an element
            // is not found (perhaps a removed cloud, removes from the list)
            synchronized (CLOUD_NAMES_LOCK) {
                List<String> toRemove = new ArrayList<String>();
                for (String cloudName : cloudNames) {
                    LOGGER.log(Level.INFO,
                            "AzureVMCloudVerificationTask: verify: verifying cloud {0}",
                            cloudName);

                    AzureVMCloud cloud = getCloud(cloudName);

                    // Unknown cloud.  Maybe the name changed since the cloud name
                    // was registered.  Remove from the list
                    if (cloud == null) {
                        LOGGER.log(Level.INFO,
                                "AzureVMCloudVerificationTask: verify: subscription {0} not found, skipping",
                                cloudName);
                        // Remove
                        toRemove.add(cloudName);
                        continue;
                    }

                    // If already verified, skip
                    if (cloud.isConfigurationValid()) {
                        LOGGER.log(Level.INFO,
                                "AzureVMCloudVerificationTask: verify: subscription {0} already verified",
                                cloudName);
                        // Update the count.
                        cloud.setVirtualMachineCount(getVirtualMachineCount(cloud));
                        continue;
                    }

                    // Verify.  Update the VM count before setting to valid
                    if (verifyConfiguration(cloud)) {
                        LOGGER.log(Level.INFO, "AzureVMCloudVerificationTask: validate: {0} verified", cloudName);
                        // Update the count
                        cloud.setVirtualMachineCount(getVirtualMachineCount(cloud));
                        // We grab the current VM count and
                        cloud.setConfigurationValid(true);
                        continue;
                    }

                    // Not valid!  Remains in list.
                    LOGGER.log(Level.INFO,
                            "AzureVMCloudVerificationTask: verify: {0} not verified, has errors",
                            cloudName);
                }

                // Remove items as necessary
                for (String cloudName : toRemove) {
                    cloudNames.remove(cloudName);
                }
            }
        }

        if (cloudTemplates != null && !cloudTemplates.isEmpty()) {
            // Now walk the templates and verify.
            // Unlike the clouds, verified templates are removed from the list upon
            // verification (or left if they do not verify)
            synchronized (TEMPLATES_LOCK) {
                List<AbstractMap.SimpleEntry<String, String>> toRemove = new ArrayList<>();

                LOGGER.log(Level.INFO,
                        "AzureVMCloudVerificationTask: verify: verifying {0} template(s)",
                        cloudTemplates.size());
                for (AbstractMap.SimpleEntry<String, String> entry : cloudTemplates) {
                    final String templateName = entry.getKey();
                    final String cloudName = entry.getValue();
                    LOGGER.log(Level.INFO,
                            "AzureVMCloudVerificationTask: verify: verifying {0} in {1}",
                            new Object[]{templateName, cloudName});

                    final AzureVMCloud cloud = getCloud(cloudName);
                    // If the cloud is null, could mean that the cloud details changed
                    // between the last time we ran this task
                    if (cloud == null) {
                        LOGGER.log(Level.INFO,
                                "AzureVMCloudVerificationTask: verify: parent cloud not found for {0} in {1}",
                                new Object[]{templateName, cloudName});
                        toRemove.add(entry);
                        continue;
                    }

                    final AzureVMAgentTemplate agentTemplate = cloud.getAzureAgentTemplate(templateName);
                    // Template could have been removed since the last time we ran verification
                    if (agentTemplate == null) {
                        LOGGER.log(Level.INFO,
                                "AzureVMCloudVerificationTask: verify: "
                                        + "could not retrieve agent template named {0} in {1}",
                                new Object[]{templateName, cloudName});
                        toRemove.add(entry);
                        continue;
                    }

                    // Determine whether we need to verify the template
                    if (agentTemplate.isTemplateVerified()) {
                        LOGGER.log(Level.INFO,
                                "AzureVMCloudVerificationTask: verify: template {0} in {1} already verified",
                                new Object[]{templateName, cloudName});
                        // Good to go, nothing more to check here.  Add to removal list.
                        toRemove.add(entry);
                        continue;
                    }
                    // The template is not yet verified.  Do so now
                    try {
                        List<String> errors = agentTemplate.verifyTemplate();
                        if (errors.isEmpty()) {
                            LOGGER.log(Level.INFO,
                                    "AzureVMCloudVerificationTask: verify: {0} verified succesfully",
                                    templateName);
                            // Verified, set the template to verified.
                            agentTemplate.setTemplateVerified(true);
                            // Reset the status details
                            agentTemplate.setTemplateStatusDetails("");
                        } else {
                            String details = StringUtils.join(errors, "\n");
                            LOGGER.log(Level.INFO,
                                    "AzureVMCloudVerificationTask: verify: {0} could not be verified:\n{1}",
                                    new Object[]{templateName, details});
                            // Set the status details to the set of messages
                            agentTemplate.setTemplateStatusDetails(details);
                        }
                    } catch (Exception e) {
                        // Log, but ignore overall
                        LOGGER.log(Level.INFO,
                                "AzureVMCloudVerificationTask: verify: got exception while verifying {0}:\n{1}",
                                new Object[]{templateName, e.toString()});
                    }
                }

                // Remove items as necessary
                for (AbstractMap.SimpleEntry templateEntry : toRemove) {
                    cloudTemplates.remove(templateEntry);
                }
            }
        }
    }

    @Override
    public void execute(TaskListener arg0) throws IOException, InterruptedException {
        LOGGER.log(Level.INFO, "AzureVMCloudVerificationTask: execute: start");

        Callable<Void> callVerify = new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                verify();
                return null;
            }
        };

        Future<Void> result = AzureVMCloud.getThreadPool().submit(callVerify);

        try {
            LOGGER.info("AzureVMCloudVerificationTask: execute: Running verification with 15 minute timeout");
            // Get will block until time expires or until task completes
            final int timeout = 15;
            result.get(timeout, TimeUnit.MINUTES);
        } catch (ExecutionException executionException) {
            LOGGER.log(Level.SEVERE,
                    "AzureVMCloudVerificationTask: execute: Got execution exception while verifying",
                    executionException);
        } catch (TimeoutException timeoutException) {
            LOGGER.log(Level.SEVERE,
                    "AzureVMCloudVerificationTask: execute: Hit timeout while verifying",
                    timeoutException);
        } catch (Exception others) {
            LOGGER.log(Level.SEVERE,
                    "AzureVMCloudVerificationTask: execute: Hit other exception while verifying",
                    others);
        }

        LOGGER.info("AzureVMCloudVerificationTask: execute: end");
    }

    /**
     * Checks the subscription for validity if needed.
     *
     * @param cloud
     * @return True if the subscription is valid, false otherwise. Updates the
     * cloud state if it is. If subscription is not valid, then we can just
     * return
     */
    public boolean verifyConfiguration(AzureVMCloud cloud) {
        LOGGER.info("AzureVMCloudVerificationTask: verifyConfiguration: start");

        // Check the sub and off we go
        String result = AzureVMManagementServiceDelegate.verifyConfiguration(
                cloud.getServicePrincipal(),
                cloud.getResourceGroupName(),
                Integer.toString(cloud.getMaxVirtualMachinesLimit()),
                Integer.toString(cloud.getDeploymentTimeout()));
        if (result != Constants.OP_SUCCESS) {
            LOGGER.log(Level.INFO, "AzureVMCloudVerificationTask: verifyConfiguration: {0}", result);
            cloud.setConfigurationValid(false);
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
    public int getVirtualMachineCount(AzureVMCloud cloud) {
        LOGGER.info("AzureVMCloudVerificationTask: getVirtualMachineCount: start");
        try {
            int vmCount = AzureVMManagementServiceDelegate.getVirtualMachineCount(
                    cloud.getServicePrincipal(), cloud.getResourceGroupName());
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

    /**
     * Register more than one template at once.
     *
     * @param templatesToRegister List of templates to register
     */
    public static void registerTemplates(List<AzureVMAgentTemplate> templatesToRegister) {
        synchronized (TEMPLATES_LOCK) {
            for (AzureVMAgentTemplate template : templatesToRegister) {
                registerTemplateHelper(template);
            }
        }
    }

    /**
     * Registers a template for verification.
     *
     * @param template Template to register
     */
    public static void registerTemplate(AzureVMAgentTemplate template) {
        synchronized (TEMPLATES_LOCK) {
            registerTemplateHelper(template);
        }
    }

    /**
     * Registers a single template. The lock should be held while calling this
     * method
     *
     * @param template Template to register
     */
    private static void registerTemplateHelper(AzureVMAgentTemplate template) {
        synchronized (TEMPLATES_LOCK) {
            final String cloudName = template.getAzureCloud().name;
            LOGGER.log(Level.INFO,
                    "AzureVMCloudVerificationTask: registerTemplateHelper: "
                            + "Registering template {0} on {1} for verification",
                    new Object[]{template.getTemplateName(), cloudName});
            if (cloudTemplates == null) {
                cloudTemplates = new HashSet<>();
            }
            cloudTemplates.add(new AbstractMap.SimpleEntry<>(template.getTemplateName(), cloudName));
        }
    }

    /**
     * Register a cloud for verification.
     *
     * @param cloudName
     */
    public static void registerCloud(String cloudName) {
        LOGGER.log(Level.INFO,
                "AzureVMCloudVerificationTask: registerCloud: Registering cloud {0} for verification",
                cloudName);
        synchronized (CLOUD_NAMES_LOCK) {
            if (cloudNames == null) {
                cloudNames = new HashSet<>();
            }
            cloudNames.add(cloudName);
        }
    }

    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD_IN_MILLIS;
    }

    public AzureVMCloud getCloud(String cloudName) {
        return Jenkins.getInstance() == null ? null : (AzureVMCloud) Jenkins.getInstance().getCloud(cloudName);
    }

    /**
     * Retrieve the verification task worker. Can be used to force work
     *
     * @return The AzureVMCloudVerificationTask worker class
     */
    public static AzureVMCloudVerificationTask get() {
        return AsyncPeriodicWork.all().get(AzureVMCloudVerificationTask.class);
    }
}
