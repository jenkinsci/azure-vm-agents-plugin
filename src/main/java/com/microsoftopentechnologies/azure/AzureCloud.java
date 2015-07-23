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

import com.microsoft.azure.management.compute.models.VirtualMachineGetResponse;
import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.ResourceManagementService;
import com.microsoft.azure.management.resources.models.DeploymentOperation;
import com.microsoft.azure.management.resources.models.ProvisioningState;
import com.microsoft.windowsazure.Configuration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.OfflineCause;
import hudson.util.FormValidation;
import hudson.util.StreamTaskListener;

import com.microsoftopentechnologies.azure.util.Constants;
import com.microsoftopentechnologies.azure.util.FailureStage;
import java.nio.charset.Charset;
import java.util.logging.Level;
import org.apache.commons.lang.StringUtils;

public class AzureCloud extends Cloud {

    public static final Logger LOGGER = Logger.getLogger(AzureCloud.class.getName());

    private final String subscriptionId;

    private final String clientId;

    private final String clientSecret;

    private final String oauth2TokenEndpoint;

    private final String serviceManagementURL;

    private final int maxVirtualMachinesLimit;

    private final List<AzureSlaveTemplate> instTemplates;

    @DataBoundConstructor
    public AzureCloud(
            final String id,
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String serviceManagementURL,
            final String maxVirtualMachinesLimit,
            final List<AzureSlaveTemplate> instTemplates,
            final String fileName,
            final String fileData) {

        super(Constants.AZURE_CLOUD_PREFIX + subscriptionId);

        this.subscriptionId = subscriptionId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.oauth2TokenEndpoint = oauth2TokenEndpoint;

        this.serviceManagementURL = StringUtils.isBlank(serviceManagementURL)
                ? Constants.DEFAULT_MANAGEMENT_URL
                : serviceManagementURL;

        if (StringUtils.isBlank(maxVirtualMachinesLimit) || !maxVirtualMachinesLimit.matches(Constants.REG_EX_DIGIT)) {
            this.maxVirtualMachinesLimit = Constants.DEFAULT_MAX_VM_LIMIT;
        } else {
            this.maxVirtualMachinesLimit = Integer.parseInt(maxVirtualMachinesLimit);
        }

        this.instTemplates = instTemplates == null
                ? Collections.<AzureSlaveTemplate>emptyList()
                : instTemplates;
        readResolve();
    }

    private Object readResolve() {
        for (AzureSlaveTemplate template : instTemplates) {
            template.azureCloud = this;
        }
        return this;
    }

    @Override
    public boolean canProvision(final Label label) {
        AzureSlaveTemplate template = getAzureSlaveTemplate(label);

        // return false if there is no template
        if (template == null) {
            if (label != null) {
                LOGGER.log(Level.INFO,
                        "Azurecloud: canProvision: template not found for label {0}", label.getDisplayName());
            } else {
                LOGGER.info("Azurecloud: canProvision: template not found for empty label. "
                        + "All templates exclusive to jobs that require that template.");
            }
            return false;
        } else if (template.getTemplateStatus().equalsIgnoreCase(Constants.TEMPLATE_STATUS_DISBALED)) {
            LOGGER.log(Level.INFO,
                    "Azurecloud: canProvision: template {0} is marked has disabled, cannot provision slaves",
                    template.getTemplateName());
            return false;
        } else {
            return true;
        }
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getOauth2TokenEndpoint() {
        return oauth2TokenEndpoint;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getServiceManagementURL() {
        return serviceManagementURL;
    }

    public int getMaxVirtualMachinesLimit() {
        return maxVirtualMachinesLimit;
    }

    /**
     * Returns slave template associated with the label.
     *
     * @param label
     * @return
     */
    public AzureSlaveTemplate getAzureSlaveTemplate(final Label label) {
        for (AzureSlaveTemplate slaveTemplate : instTemplates) {
            if (slaveTemplate.getUseSlaveAlwaysIfAvail() == Node.Mode.NORMAL) {
                if (label == null || label.matches(slaveTemplate.getLabelDataSet())) {
                    return slaveTemplate;
                }
            } else if (slaveTemplate.getUseSlaveAlwaysIfAvail() == Node.Mode.EXCLUSIVE) {
                if (label != null && label.matches(slaveTemplate.getLabelDataSet())) {
                    return slaveTemplate;
                }
            }
        }
        return null;
    }

    /**
     * Returns slave template associated with the name.
     *
     * @param name
     * @return
     */
    public AzureSlaveTemplate getAzureSlaveTemplate(final String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }

        for (AzureSlaveTemplate slaveTemplate : instTemplates) {
            if (name.equalsIgnoreCase(slaveTemplate.getTemplateName())) {
                return slaveTemplate;
            }
        }
        return null;
    }

    public List<AzureSlaveTemplate> getInstTemplates() {
        return Collections.unmodifiableList(instTemplates);
    }

    private boolean verifyTemplate(final AzureSlaveTemplate template) {
        boolean isVerified;
        try {
            LOGGER.log(Level.INFO, "Azure Cloud: provision: Verifying template {0}", template.getTemplateName());

            final List<String> errors = template.verifyTemplate();

            isVerified = errors.isEmpty();

            if (isVerified) {
                LOGGER.log(Level.INFO,
                        "Azure Cloud: provision: template {0} has no validation errors", template.getTemplateName());
            } else {
                LOGGER.log(Level.INFO, "Azure Cloud: provision: template {0}"
                        + " has validation errors , cannot provision slaves with this configuration {1}",
                        new Object[] { template.getTemplateName(), errors });
                template.handleTemplateStatus("Validation Error: Validation errors in template \n"
                        + " Root cause: " + errors, FailureStage.VALIDATION, null);

                // Register template for periodic check so that jenkins can make template active if 
                // validation errors are corrected
                if (!Constants.TEMPLATE_STATUS_ACTIVE_ALWAYS.equals(template.getTemplateStatus())) {
                    AzureTemplateMonitorTask.registerTemplate(template);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Azure Cloud: provision: Exception occured while validating template", e);
            template.handleTemplateStatus("Validation Error: Exception occured while validating template "
                    + e.getMessage(), FailureStage.VALIDATION, null);

            // Register template for periodic check so that jenkins can make template active if validation errors 
            // are corrected
            if (!Constants.TEMPLATE_STATUS_ACTIVE_ALWAYS.equals(template.getTemplateStatus())) {
                AzureTemplateMonitorTask.registerTemplate(template);
            }
            isVerified = false;
        }

        return isVerified;
    }

    private AzureSlave provisionedSlave(
            final AzureSlaveTemplate template,
            final String prefix,
            final int index,
            final int expectedVMs,
            final Configuration config) throws Exception {
        final ResourceManagementClient rmc = ResourceManagementService.create(config);

        final String vmName = String.format("%s%s%d", template.getTemplateName(), prefix, index);

        int completed = 0;

        AzureSlave slave = null;

        do {
            try {
                Thread.sleep(30 * 1000);
            } catch (InterruptedException ex) {
                // ignore
            }

            final List<DeploymentOperation> ops = rmc.getDeploymentOperationsOperations().
                    list(Constants.RESOURCE_GROUP_NAME, prefix, null).getOperations();

            completed = 0;
            for (DeploymentOperation op : ops) {
                final String resource = op.getProperties().getTargetResource().getResourceName();
                final String type = op.getProperties().getTargetResource().getResourceType();
                final String state = op.getProperties().getProvisioningState();

                if (ProvisioningState.CANCELED.equals(state)
                        || ProvisioningState.FAILED.equals(state)
                        || ProvisioningState.NOTSPECIFIED.equals(state)) {
                    LOGGER.log(Level.INFO, "Failed({0}): {1}:{2}", new Object[] { state, type, resource });

                    slave = AzureManagementServiceDelegate.parseResponse(
                            vmName, prefix, template, template.getOsType());
                } else if (ProvisioningState.SUCCEEDED.equals(state)) {
                    if (op.getProperties().getTargetResource().getResourceType().contains("virtualMachine")) {
                        if (resource.equalsIgnoreCase(vmName)) {
                            LOGGER.log(Level.INFO, "VM available: {0}", resource);

                            final VirtualMachineGetResponse vm
                                    = ServiceDelegateHelper.getComputeManagementClient(config).
                                    getVirtualMachinesOperations().
                                    getWithInstanceView(Constants.RESOURCE_GROUP_NAME, resource);

                            final String osType = vm.getVirtualMachine().getStorageProfile().getOSDisk().
                                    getOperatingSystemType();

                            slave = AzureManagementServiceDelegate.parseResponse(vmName, prefix, template, osType);
                        }

                        completed++;
                    }
                } else {
                    LOGGER.log(Level.INFO, "To Be Completed({0}): {1}:{2}", new Object[] { state, type, resource });
                }
            }
        } while (slave == null && completed < expectedVMs);

        if (slave == null) {
            throw new IllegalStateException(String.format("Slave machine '%s' not found into '%s'", vmName, prefix));
        }

        return slave;
    }

    @Override
    public Collection<PlannedNode> provision(final Label label, int workLoad) {
        LOGGER.log(Level.INFO,
                "Azure Cloud: provision: start for label {0} workLoad {1}", new Object[] { label, workLoad });

        final AzureSlaveTemplate template = getAzureSlaveTemplate(label);

        // verify template
        if (!verifyTemplate(template)) {
            return Collections.<PlannedNode>emptyList();
        }

        // round up the number of required machine
        int numberOfSlaves = (workLoad + template.getNoOfParallelJobs() - 1) / template.getNoOfParallelJobs();
        final List<PlannedNode> plannedNodes = new ArrayList<PlannedNode>(numberOfSlaves);

        // reuse existing nodes if available
        for (Computer slaveComputer : Jenkins.getInstance().getComputers()) {
            LOGGER.log(Level.INFO, "Azure Cloud: provision: got slave computer {0}", slaveComputer.getName());
            if (slaveComputer instanceof AzureComputer && slaveComputer.isOffline()) {
                final AzureComputer azureComputer = AzureComputer.class.cast(slaveComputer);
                final AzureSlave slaveNode = azureComputer.getNode();

                if (isNodeEligibleForReuse(slaveNode, template)) {

                    LOGGER.log(Level.INFO,
                            "Azure Cloud: provision: \n - slave node {0}\n - slave template {1}",
                            new Object[] { slaveNode.getLabelString(), template.getLabels() });

                    try {
                        if (AzureManagementServiceDelegate.virtualMachineExists(slaveNode)) {
                            numberOfSlaves--;
                            plannedNodes.add(new PlannedNode(
                                    template.getTemplateName(),
                                    Computer.threadPoolForRemoting.submit(new Callable<Node>() {

                                        @Override
                                        public Node call() throws Exception {
                                            LOGGER.log(Level.INFO, "Found existing node, starting VM {0}", slaveNode.
                                                    getNodeName());
                                            AzureManagementServiceDelegate.startVirtualMachine(slaveNode);
                                            // set virtual machine details again
                                            Thread.sleep(30 * 1000); // wait for 30 seconds
                                            AzureManagementServiceDelegate.setVirtualMachineDetails(
                                                    slaveNode, template);
                                            Jenkins.getInstance().addNode(slaveNode);
                                            if (slaveNode.getSlaveLaunchMethod().equalsIgnoreCase("SSH")) {
                                                slaveNode.toComputer().connect(false).get();
                                            } else // Wait until node is online
                                            {
                                                waitUntilOnline(slaveNode);
                                            }
                                            azureComputer.setAcceptingTasks(true);
                                            return slaveNode;
                                        }
                                    }), template.getNoOfParallelJobs()));
                        } else {
                            slaveNode.setDeleteSlave(true);
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }

        // provision new nodes if required
        if (numberOfSlaves > 0) {
            try {
                final String deployment = template.provisionSlaves(
                        new StreamTaskListener(System.out, Charset.defaultCharset()), numberOfSlaves);

                final int count = numberOfSlaves;

                for (int i = 0; i < numberOfSlaves; i++) {
                    final int index = i;
                    plannedNodes.add(new PlannedNode(
                            template.getTemplateName(),
                            Computer.threadPoolForRemoting.submit(new Callable<Node>() {

                                @Override
                                public Node call() throws Exception {
                                    final AzureSlave slave = provisionedSlave(
                                            template,
                                            deployment,
                                            index,
                                            count,
                                            ServiceDelegateHelper.getConfiguration(template));

                                    // Get virtual machine properties
                                    LOGGER.log(Level.INFO,
                                            "Azure Cloud: provision: Getting slave {0} ({1}) properties",
                                            new Object[] { slave.getNodeName(), slave.getOsType() });

                                    try {
                                        template.setVirtualMachineDetails(slave);
                                        if (slave.getSlaveLaunchMethod().equalsIgnoreCase("SSH")) {
                                            LOGGER.info("Azure Cloud: provision: Adding slave to azure nodes ");
                                            Jenkins.getInstance().addNode(slave);
                                            slave.toComputer().connect(false).get();
                                        } else if (slave.getSlaveLaunchMethod().equalsIgnoreCase("JNLP")) {
                                            LOGGER.info("Azure Cloud: provision: Checking for slave status");
                                            // slaveTemplate.waitForReadyRole(slave);
                                            LOGGER.info("Azure Cloud: provision: Adding slave to azure nodes ");
                                            Jenkins.getInstance().addNode(slave);
                                            // Wait until node is online
                                            waitUntilOnline(slave);
                                        }
                                    } catch (Exception e) {
                                        template.handleTemplateStatus(
                                                e.getMessage(), FailureStage.POSTPROVISIONING, slave);
                                        throw e;
                                    }
                                    return slave;
                                }
                            }), template.getNoOfParallelJobs()));
                }
                // wait for deployment completion ant than check for created nodes
            } catch (Exception e) {
                LOGGER.log(
                        Level.SEVERE,
                        String.format("Failure provisioning slaves about '%s'", template.getLabels()),
                        e);
            }
        }

        return plannedNodes;
    }

    /** this methods wait for node to be available */
    private void waitUntilOnline(final AzureSlave slave) {
        LOGGER.log(Level.INFO, "Azure Cloud: waitUntilOnline: for slave {0}", slave.getDisplayName());
        ExecutorService executorService = Executors.newCachedThreadPool();
        Callable<String> callableTask = new Callable<String>() {

            @Override
            public String call() {
                try {
                    slave.toComputer().waitUntilOnline();
                } catch (InterruptedException e) {
                    // just ignore
                }
                return "success";
            }
        };
        Future<String> future = executorService.submit(callableTask);

        try {
            // 30 minutes is decent time for the node to be alive
            String result = future.get(30, TimeUnit.MINUTES);
            LOGGER.log(Level.INFO, "Azure Cloud: waitUntilOnline: node is alive , result {0}", result);
        } catch (Exception ex) {
            LOGGER.log(Level.INFO, "Azure Cloud: waitUntilOnline: Failure waiting till online", ex);
            markSlaveForDeletion(slave, Constants.JNLP_POST_PROV_LAUNCH_FAIL);
        } finally {
            future.cancel(true);
            executorService.shutdown();
        }
    }

    /**
     * Checks if node configuration matches with template definition.
     */
    private static boolean isNodeEligibleForReuse(AzureSlave slaveNode, AzureSlaveTemplate slaveTemplate) {
        // Do not reuse slave if it is marked for deletion.  
        if (slaveNode.isDeleteSlave()) {
            return false;
        }

        // Check for null label and mode.
        if (StringUtils.isBlank(slaveNode.getLabelString()) && (slaveNode.getMode() == Node.Mode.NORMAL)) {
            return true;
        }

        if (StringUtils.isNotBlank(slaveNode.getLabelString()) && slaveNode.getLabelString().equalsIgnoreCase(
                slaveTemplate.getLabels())) {
            return true;
        }

        return false;
    }

    private static void markSlaveForDeletion(AzureSlave slave, String message) {
        slave.setTemplateStatus(Constants.TEMPLATE_STATUS_DISBALED, message);
        if (slave.toComputer() != null) {
            slave.toComputer().setTemporarilyOffline(true, OfflineCause.create(Messages._Slave_Failed_To_Connect()));
        }
        slave.setDeleteSlave(true);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        public String getDisplayName() {
            return Constants.AZURE_CLOUD_DISPLAY_NAME;
        }

        public String getDefaultserviceManagementURL() {
            return Constants.DEFAULT_MANAGEMENT_URL;
        }

        public int getDefaultMaxVMLimit() {
            return Constants.DEFAULT_MAX_VM_LIMIT;
        }

        public FormValidation doVerifyConfiguration(
                @QueryParameter String subscriptionId,
                @QueryParameter String clientId,
                @QueryParameter String clientSecret,
                @QueryParameter String oauth2TokenEndpoint,
                @QueryParameter String serviceManagementURL) {

            if (StringUtils.isBlank(subscriptionId)) {
                return FormValidation.error("Error: Subscription ID is missing");
            }
            if (StringUtils.isBlank(clientId)) {
                return FormValidation.error("Error: Native Client ID is missing");
            }
            if (StringUtils.isBlank(clientSecret)) {
                return FormValidation.error("Error: Azure Password is missing");
            }
            if (StringUtils.isBlank(oauth2TokenEndpoint)) {
                return FormValidation.error("Error: OAuth 2.0 Token Endpoint is missing");
            }
            if (StringUtils.isBlank(serviceManagementURL)) {
                serviceManagementURL = Constants.DEFAULT_MANAGEMENT_URL;
            }

            String response = AzureManagementServiceDelegate.verifyConfiguration(
                    subscriptionId,
                    clientId,
                    clientSecret,
                    oauth2TokenEndpoint,
                    serviceManagementURL);

            if (Constants.OP_SUCCESS.equalsIgnoreCase(response)) {
                return FormValidation.ok(Messages.Azure_Config_Success());
            } else {
                return FormValidation.error(response);
            }
        }
    }
}
