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
import com.microsoft.azure.management.resources.models.DeploymentGetResult;
import com.microsoft.azure.management.resources.models.DeploymentOperation;
import com.microsoft.azure.management.resources.models.ProvisioningState;
import com.microsoft.windowsazure.Configuration;
import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.util.AzureUtil;
import com.microsoftopentechnologies.azure.util.CleanUpAction;
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
    
    private final String resourceGroupName;

    private final List<AzureSlaveTemplate> instTemplates;
    
    // True if the subscription has been verified.
    // False otherwise.
    private boolean configurationValid;
    
    // True if initial verification was queued for this cloud.
    // Set on either: construction or initial canProvision if
    // not already set.
    private transient boolean initialVerificationQueued;
    
    // Approximate virtual machine count.  Updated periodically.
    private int approximateVirtualMachineCount;

    @DataBoundConstructor
    public AzureCloud(
            final String id,
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String serviceManagementURL,
            final String maxVirtualMachinesLimit,
            final String resourceGroupName,
            final List<AzureSlaveTemplate> instTemplates) {

        super(AzureUtil.getCloudName(subscriptionId));

        this.subscriptionId = subscriptionId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.oauth2TokenEndpoint = oauth2TokenEndpoint;
        this.resourceGroupName = resourceGroupName;

        this.serviceManagementURL = StringUtils.isBlank(serviceManagementURL)
                ? Constants.DEFAULT_MANAGEMENT_URL
                : serviceManagementURL;

        if (StringUtils.isBlank(maxVirtualMachinesLimit) || !maxVirtualMachinesLimit.matches(Constants.REG_EX_DIGIT)) {
            this.maxVirtualMachinesLimit = Constants.DEFAULT_MAX_VM_LIMIT;
        } else {
            this.maxVirtualMachinesLimit = Integer.parseInt(maxVirtualMachinesLimit);
        }
        
        this.configurationValid = false;
        
        this.instTemplates = instTemplates == null
                ? Collections.<AzureSlaveTemplate>emptyList()
                : instTemplates;
        
        readResolve();
        
        registerInitialVerificationIfNeeded();
    }
    
    /**
     * Register the initial verification if required
     */
    private void registerInitialVerificationIfNeeded() {
        if (this.initialVerificationQueued) {
            return;
        }
        // Register the cloud and the templates for verification
        AzureVerificationTask.registerCloud(this.name);
        // Register all templates.  We don't know what happened with them
        // when save was hit.
        AzureVerificationTask.registerTemplates(this.getInstTemplates());
        // Force the verification task to run if it's not already running.
        // Note that early in startup this could return null
        if (AzureVerificationTask.get() != null) {
            AzureVerificationTask.get().doRun();
            // Set the initial verification as being queued and ready to go.
            this.initialVerificationQueued = true;
        }
    }

    private Object readResolve() {
        for (AzureSlaveTemplate template : instTemplates) {
            template.azureCloud = this;
        }
        return this;
    }

    @Override
    public boolean canProvision(final Label label) {
        if (!configurationValid) {
            // The subscription is not verified or is not valid,
            // so we can't provision any nodes.
            LOGGER.log(Level.INFO, "Azurecloud: canProvision: Subscription not verified, or is invalid, cannot provision");
            registerInitialVerificationIfNeeded();
            return false;
        }
        
        final AzureSlaveTemplate template = getAzureSlaveTemplate(label);
        // return false if there is no template for this label.
        if (template == null) {
            // Avoid logging this, it happens a lot and is just noisy in logs.
            return false;
        } else if (template.isTemplateDisabled()) {
            // Log this.  It's not terribly noisy and can be useful
            LOGGER.log(Level.INFO,
                    "Azurecloud: canProvision: template {0} is marked has disabled, cannot provision slaves",
                    template.getTemplateName());
            return false;
        } else if (!template.isTemplateVerified()) {
            // The template is available, but not verified. It may be queued for
            // verification, but ensure that it's added.
            LOGGER.log(Level.INFO,
                "Azurecloud: canProvision: template {0} is awaiting verification or has failed verification",
                template.getTemplateName());
            AzureVerificationTask.registerTemplate(template);
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
    
    public String getResourceGroupName() {
        return resourceGroupName;
    }
    
    /**
     * Returns the current set of templates.
     * Required for config.jelly
     * @return 
     */
    public List<AzureSlaveTemplate> getInstTemplates() {
        return Collections.unmodifiableList(instTemplates);
    }
    
    /**
     * Is the configuration set up and verified?
     * @return True if the configuration set up and verified, false otherwise.
     */
    public boolean isConfigurationValid() {
        return configurationValid;
    }
    
    /**
     * Set the configuration verification status
     * @param isValid True for verified + valid, false otherwise.
     */
    public void setConfigurationValid(boolean isValid) {
        configurationValid = isValid;
    }
    
    /**
     * Retrieves the current approximate virtual machine count
     * @return 
     */
    public int getApproximateVirtualMachineCount() {
        synchronized (this) {
            return approximateVirtualMachineCount;
        }
    }
    
    /**
     * Given the number of VMs that are desired, returns the number
     * of VMs that can be allocated. 
     * @param quantityDesired Number that are desired
     * @return Number that can be allocated
     */
    public int getAvailableVirtualMachineCount(int quantityDesired) {
        synchronized (this) {
            if (approximateVirtualMachineCount + quantityDesired <= getMaxVirtualMachinesLimit()) {
                // Enough available, return the desired quantity
                return quantityDesired;
            }
            else {
                // Not enough available, return what we have. Remember we could
                // go negative (if for instance another Jenkins instance had
                // a higher limit.
                return Math.max(0, getMaxVirtualMachinesLimit() - approximateVirtualMachineCount);
            }
        }
    }
    
    /**
     * Adjust the number of currently allocated VMs
     * @param delta Number to adjust by.
     */
    public void adjustVirtualMachineCount(int delta) {
        synchronized (this) {
            approximateVirtualMachineCount = Math.max(0, approximateVirtualMachineCount + delta);
        }
    }
    
    /**
     * Sets the new approximate virtual machine count.  This is run by
     * the verification task to update the VM count periodically.
     * @param newCount 
     */
    public void setVirtualMachineCount(int newCount) {
        synchronized (this) {
            approximateVirtualMachineCount = newCount;
        }
    }
    
    /**
     * Returns slave template associated with the label.
     *
     * @param label
     * @return
     */
    public AzureSlaveTemplate getAzureSlaveTemplate(final Label label) {
        LOGGER.log(Level.FINE, "AzureCloud: getAzureSlaveTemplate: Retrieving slave template with label {0}", label);
        for (AzureSlaveTemplate slaveTemplate : instTemplates) {
            LOGGER.log(Level.FINE, "AzureCloud: getAzureSlaveTemplate: Found slave template {0}", slaveTemplate.getTemplateName());
            if (slaveTemplate.getUseSlaveAlwaysIfAvail() == Node.Mode.NORMAL) {
                if (label == null || label.matches(slaveTemplate.getLabelDataSet())) {
                    LOGGER.log(Level.FINE, "AzureCloud: getAzureSlaveTemplate: {0} matches!", slaveTemplate.getTemplateName());
                    return slaveTemplate;
                }
            } else if (slaveTemplate.getUseSlaveAlwaysIfAvail() == Node.Mode.EXCLUSIVE) {
                if (label != null && label.matches(slaveTemplate.getLabelDataSet())) {
                    LOGGER.log(Level.FINE, "AzureCloud: getAzureSlaveTemplate: {0} matches!", slaveTemplate.getTemplateName());
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

    /**
     * Once a new deployment is created, construct a new AzureSlave object
     * given information about the template
     * @param template Template used to create the new slave
     * @param vmName Name of the created VM
     * @param deploymentName Name of the deployment containing the VM
     * @param config Azure configuration.
     * @return New slave.  Throws otherwise.
     * @throws Exception 
     */
    private AzureSlave createProvisionedSlave(
            final AzureSlaveTemplate template,
            final String vmName,
            final String deploymentName,
            final Configuration config) throws Exception {
        final ResourceManagementClient rmc = ResourceManagementService.create(config);

        LOGGER.log(Level.INFO, "AzureCloud: createProvisionedSlave: Waiting for deployment to be completed");
        
        int triesLeft = 20;
        do {
            triesLeft--;
            try {
                Thread.sleep(30 * 1000);
            } catch (InterruptedException ex) {
                // ignore
            }
        
            final List<DeploymentOperation> ops = rmc.getDeploymentOperationsOperations().
                    list(resourceGroupName, deploymentName, null).getOperations();
                    
            for (DeploymentOperation op : ops) {
                final String resource = op.getProperties().getTargetResource().getResourceName();
                final String type = op.getProperties().getTargetResource().getResourceType();
                final String state = op.getProperties().getProvisioningState();

                if (op.getProperties().getTargetResource().getResourceType().contains("virtualMachine")) {
                    if (resource.equalsIgnoreCase(vmName)) {
                        if (ProvisioningState.CANCELED.equals(state)
                                || ProvisioningState.FAILED.equals(state)
                                || ProvisioningState.NOTSPECIFIED.equals(state)) {
                            final String statusCode = op.getProperties().getStatusCode();
                            final String statusMessage = op.getProperties().getStatusMessage();
                            String finalStatusMessage = statusCode;
                            if (statusMessage != null) {
                                finalStatusMessage += " - " + statusMessage;
                            }

                            throw new AzureCloudException(String.format("AzureCloud: createProvisionedSlave: Deployment %s: %s:%s - %s", new Object[] { state, type, resource, finalStatusMessage }));
                        } else if (ProvisioningState.SUCCEEDED.equals(state)) {
                            LOGGER.log(Level.INFO, "AzureCloud: createProvisionedSlave: VM available: {0}", resource);

                            final VirtualMachineGetResponse vm
                                    = ServiceDelegateHelper.getComputeManagementClient(config).
                                    getVirtualMachinesOperations().
                                    getWithInstanceView(resourceGroupName, resource);

                            final String osType = vm.getVirtualMachine().getStorageProfile().getOSDisk().
                                    getOperatingSystemType();

                            AzureSlave newSlave = AzureManagementServiceDelegate.parseResponse(vmName, deploymentName, template, osType);
                            // Set the virtual machine details
                            AzureManagementServiceDelegate.setVirtualMachineDetails(newSlave, template);
                            return newSlave;
                        }
                        else {
                            LOGGER.log(Level.INFO, "AzureCloud: createProvisionedSlave: Deployment not yet finished ({0}): {1}:{2}", new Object[] { state, type, resource });
                        }
                    }
                }
            }
        } while (triesLeft > 0);

        throw new AzureCloudException(String.format("AzureCloud: createProvisionedSlave: Deployment failed, max tries reached for %s", deploymentName));
    }

    @Override
    public Collection<PlannedNode> provision(final Label label, int workLoad) {
        LOGGER.log(Level.INFO,
                "AzureCloud: provision: start for label {0} workLoad {1}", new Object[] { label, workLoad });

        final AzureSlaveTemplate template = getAzureSlaveTemplate(label);

        // round up the number of required machine
        int numberOfSlaves = (workLoad + template.getNoOfParallelJobs() - 1) / template.getNoOfParallelJobs();
        final List<PlannedNode> plannedNodes = new ArrayList<PlannedNode>(numberOfSlaves);

        // reuse existing nodes if available
        LOGGER.log(Level.INFO, "AzureCloud: provision: checking for node reuse options");
        for (Computer slaveComputer : Jenkins.getInstance().getComputers()) {
            if (numberOfSlaves == 0) { 
                break;
            }
            if (slaveComputer instanceof AzureComputer && slaveComputer.isOffline()) {
                final AzureComputer azureComputer = AzureComputer.class.cast(slaveComputer);
                final AzureSlave slaveNode = azureComputer.getNode();

                if (isNodeEligibleForReuse(slaveNode, template)) {
                    LOGGER.log(Level.INFO, "AzureCloud: provision: slave computer eligible for reuse {0}", slaveComputer.getName());
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
                                            } else { // Wait until node is online
                                                waitUntilJNLPNodeIsOnline(slaveNode);
                                            }
                                            azureComputer.setAcceptingTasks(true);
                                            slaveNode.clearCleanUpAction();
                                            slaveNode.setEligibleForReuse(false);
                                            return slaveNode;
                                        }
                                    }), template.getNoOfParallelJobs()));
                        }
                    } catch (Exception e) {
                        // Couldn't bring the node back online.  Mark it
                        // as needing deletion
                        azureComputer.setAcceptingTasks(false);
                        slaveNode.setCleanUpAction(CleanUpAction.DEFAULT, Messages._Shutdown_Slave_Failed_To_Revive());
                    }
                }
            }
        }

        // provision new nodes if required
        if (numberOfSlaves > 0) {
            try {
                // Determine how many slaves we can actually provision from here and
                // adjust our count (before deployment to avoid races)
                int adjustedNumberOfSlaves = getAvailableVirtualMachineCount(numberOfSlaves);
                if (adjustedNumberOfSlaves == 0) {
                    LOGGER.log(Level.INFO, "Not able to create any new nodes, at or above maximum VM count of {0}",
                        getMaxVirtualMachinesLimit());
                }
                else if (adjustedNumberOfSlaves < numberOfSlaves) {
                    LOGGER.log(Level.INFO, "Able to create new nodes, but can only create {0} (desired {1})",
                        new Object[] { adjustedNumberOfSlaves, numberOfSlaves } );
                }
                final int numberOfNewSlaves = adjustedNumberOfSlaves;
                // Adjust number of nodes available by the number of created nodes.
                // Negative to reduce number available.
                this.adjustVirtualMachineCount(-adjustedNumberOfSlaves);
                
                ExecutorService executorService = Executors.newCachedThreadPool();
                Callable<AzureDeploymentInfo> callableTask = new Callable<AzureDeploymentInfo>() {
                    @Override
                    public AzureDeploymentInfo call() throws Exception {
                        return template.provisionSlaves(new StreamTaskListener(System.out, Charset.defaultCharset()), numberOfNewSlaves);
                    }
                };
                final Future<AzureDeploymentInfo> deploymentFuture = executorService.submit(callableTask);

                for (int i = 0; i < numberOfSlaves; i++) {
                    final int index = i;
                    plannedNodes.add(new PlannedNode(
                            template.getTemplateName(),
                            Computer.threadPoolForRemoting.submit(new Callable<Node>() {

                                @Override
                                public Node call() throws Exception {
                                    
                                    // Wait for the future to complete 
                                    AzureDeploymentInfo info = deploymentFuture.get();
                                    
                                    final String deploymentName = info.getDeploymentName();
                                    final String vmBaseName = info.getVmBaseName();
                                    final String vmName = String.format("%s%d", vmBaseName, index);
                                    
                                    AzureSlave slave = null;
                                    try {
                                        slave = createProvisionedSlave(
                                            template,
                                            vmName,
                                            deploymentName,
                                            ServiceDelegateHelper.getConfiguration(template));
                                    }
                                    catch (Exception e) {
                                        LOGGER.log(
                                            Level.SEVERE,
                                            String.format("Failure creating provisioned slave '%s'", vmName),
                                            e);
                                        
                                        // Attempt to terminate whatever was created
                                        AzureManagementServiceDelegate.terminateVirtualMachine(
                                            ServiceDelegateHelper.getConfiguration(template), vmName, 
                                            template.getResourceGroupName());
                                        template.getAzureCloud().adjustVirtualMachineCount(1);
                                        // Update the template status given this new issue.
                                        template.handleTemplateProvisioningFailure(e.getMessage(), FailureStage.PROVISIONING);
                                        throw e;
                                    }

                                    try {
                                        LOGGER.log(Level.INFO, "Azure Cloud: provision: Adding slave {0} to Jenkins nodes", slave.getNodeName());
                                        // Place the node in blocked state while it starts.
                                        slave.blockCleanUpAction();
                                        Jenkins.getInstance().addNode(slave);
                                        if (slave.getSlaveLaunchMethod().equalsIgnoreCase("SSH")) {
                                            slave.toComputer().connect(false).get();
                                        } else if (slave.getSlaveLaunchMethod().equalsIgnoreCase("JNLP")) {
                                            // Wait until node is online
                                            waitUntilJNLPNodeIsOnline(slave);
                                        }
                                        // Place node in default state, now can be
                                        // dealt with by the cleanup task.
                                        slave.clearCleanUpAction();
                                    } catch (Exception e) {
                                        LOGGER.log(
                                            Level.SEVERE,
                                            String.format("Failure to in post-provisioning for '%s'", vmName),
                                            e);
                                        
                                        // Attempt to terminate whatever was created
                                        AzureManagementServiceDelegate.terminateVirtualMachine(
                                            ServiceDelegateHelper.getConfiguration(template), vmName, 
                                            template.getResourceGroupName());
                                        template.getAzureCloud().adjustVirtualMachineCount(1);
                                        
                                        // Update the template status
                                        template.handleTemplateProvisioningFailure(vmName, FailureStage.POSTPROVISIONING);
                                        
                                        // Remove the node from jenkins
                                        Jenkins.getInstance().removeNode(slave);
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

        LOGGER.log(Level.INFO,
                "AzureCloud: provision: asynchronous provision finished, returning {0} planned node(s)", plannedNodes.size());
        return plannedNodes;
    }

    /**
     * Wait till a node that connects through JNLP comes online and connects to Jenkins.
     * @param slave Node to wait for
     * @throws Exception Throws if the wait time expires or other exception happens.
     */
    private void waitUntilJNLPNodeIsOnline(final AzureSlave slave) throws Exception {
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
            throw new AzureCloudException("Azure Cloud: waitUntilOnline: Failure waiting till online", ex);
        } finally {
            future.cancel(true);
            executorService.shutdown();
        }
    }

    /**
     * Checks if node configuration matches with template definition.
     */
    private static boolean isNodeEligibleForReuse(AzureSlave slaveNode, AzureSlaveTemplate slaveTemplate) {
        if (!slaveNode.isEligibleForReuse()) {
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
        
        public String getDefaultResourceGroupName() {
            return Constants.DEFAULT_RESOURCE_GROUP_NAME;
        }

        public FormValidation doVerifyConfiguration(
                @QueryParameter String subscriptionId,
                @QueryParameter String clientId,
                @QueryParameter String clientSecret,
                @QueryParameter String oauth2TokenEndpoint,
                @QueryParameter String serviceManagementURL,
                @QueryParameter String resourceGroupName) {

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
            if (StringUtils.isBlank(resourceGroupName)) {
                resourceGroupName = Constants.DEFAULT_RESOURCE_GROUP_NAME;
            }

            String response = AzureManagementServiceDelegate.verifyConfiguration(
                    subscriptionId,
                    clientId,
                    clientSecret,
                    oauth2TokenEndpoint,
                    serviceManagementURL,
                    resourceGroupName);

            if (Constants.OP_SUCCESS.equalsIgnoreCase(response)) {
                return FormValidation.ok(Messages.Azure_Config_Success());
            } else {
                return FormValidation.error(response);
            }
        }
    }
}
