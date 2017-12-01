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

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.OperatingSystemTypes;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.resources.Deployment;
import com.microsoft.azure.management.resources.DeploymentOperation;
import com.microsoft.azure.management.resources.ResourceGroup;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.util.AzureMsiCredentials;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.remote.AzureVMAgentSSHLauncher;
import com.microsoft.azure.vmagent.util.AzureClientHolder;
import com.microsoft.azure.vmagent.util.AzureClientFactory;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.FailureStage;
import com.microsoft.azure.vmagent.util.PoolLock;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsConstants;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.logging.LogRecorder;
import hudson.logging.LogRecorderManager;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.microsoft.azure.vmagent.util.Constants.MILLIS_IN_SECOND;
import static hudson.init.InitMilestone.PLUGINS_STARTED;

public class AzureVMCloud extends Cloud {

    public static final Logger LOGGER = Logger.getLogger(AzureVMCloud.class.getName());

    private String cloudName;

    private final String credentialsId;

    private final int maxVirtualMachinesLimit;

    private String resourceGroupReferenceType;

    private String newResourceGroupName;

    private final String existingResourceGroupName;

    private transient String resourceGroupName;

    // Current set of VM templates.
    // This list should not be accessed without copying it
    // or obtaining synchronization on vmTemplatesListLock
    private List<AzureVMAgentTemplate> vmTemplates;

    @Deprecated
    private transient List<AzureVMAgentTemplate> instTemplates;

    private final int deploymentTimeout;

    private static ExecutorService threadPool;

    // True if the subscription has been verified.
    // False otherwise.
    private transient String configurationStatus;

    // Approximate virtual machine count.  Updated periodically.
    private int approximateVirtualMachineCount;

    private transient Supplier<Azure> azureClient = createAzureClientSupplier();

    private Supplier<Azure> createAzureClientSupplier() {
        return Suppliers.memoize(new Supplier<Azure>() {
            @Override
            public Azure get() {
                return AzureClientFactory.getClient(credentialsId);
            }
        });
    }

    @DataBoundConstructor
    public AzureVMCloud(
            String cloudName,
            String id,
            String azureCredentialsId,
            String maxVirtualMachinesLimit,
            String deploymentTimeout,
            String resourceGroupReferenceType,
            String newResourceGroupName,
            String existingResourceGroupName,
            List<AzureVMAgentTemplate> vmTemplates) {
        this(
                cloudName,
                azureCredentialsId,
                maxVirtualMachinesLimit,
                deploymentTimeout,
                resourceGroupReferenceType,
                newResourceGroupName,
                existingResourceGroupName,
                vmTemplates);
    }

    public AzureVMCloud(
            String cloudName,
            String azureCredentialsId,
            String maxVirtualMachinesLimit,
            String deploymentTimeout,
            String resourceGroupReferenceType,
            String newResourceGroupName,
            String existingResourceGroupName,
            List<AzureVMAgentTemplate> vmTemplates) {
        super(
                getOrGenerateCloudName(
                        cloudName,
                        azureCredentialsId,
                        getResourceGroupName(
                                resourceGroupReferenceType,
                                newResourceGroupName,
                                existingResourceGroupName)));
        this.credentialsId = azureCredentialsId;
        this.resourceGroupReferenceType = resourceGroupReferenceType;
        this.newResourceGroupName = newResourceGroupName;
        this.existingResourceGroupName = existingResourceGroupName;
        this.resourceGroupName = getResourceGroupName(
                resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
        this.cloudName = getOrGenerateCloudName(cloudName, azureCredentialsId, this.resourceGroupName);

        if (StringUtils.isBlank(maxVirtualMachinesLimit) || !maxVirtualMachinesLimit.matches(Constants.REG_EX_DIGIT)) {
            this.maxVirtualMachinesLimit = Constants.DEFAULT_MAX_VM_LIMIT;
        } else {
            this.maxVirtualMachinesLimit = Integer.parseInt(maxVirtualMachinesLimit);
        }

        if (StringUtils.isBlank(deploymentTimeout) || !deploymentTimeout.matches(Constants.REG_EX_DIGIT)) {
            this.deploymentTimeout = Constants.DEFAULT_DEPLOYMENT_TIMEOUT_SEC;
        } else {
            this.deploymentTimeout = Integer.parseInt(deploymentTimeout);
        }

        this.configurationStatus = Constants.UNVERIFIED;

        // Set the templates
        setVmTemplates(vmTemplates == null
                ? Collections.<AzureVMAgentTemplate>emptyList()
                : vmTemplates);
    }

    private Object readResolve() {
        if (StringUtils.isBlank(newResourceGroupName) && StringUtils.isBlank(existingResourceGroupName)
                && StringUtils.isNotBlank(resourceGroupName)) {
            newResourceGroupName = resourceGroupName;
            resourceGroupReferenceType = "new";
        }
        //resourceGroupName is transient so we need to restore it for future using
        resourceGroupName = getResourceGroupName(
                resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
        azureClient = createAzureClientSupplier();
        configurationStatus = Constants.UNVERIFIED;
        synchronized (this) {
            // Ensure that renamed field is set
            if (instTemplates != null && vmTemplates == null) {
                vmTemplates = instTemplates;
                instTemplates = null;
            }

            // Walk the list of templates and assign the parent cloud (which is transient).
            ensureVmTemplateList();
            for (AzureVMAgentTemplate template : vmTemplates) {
                template.setAzureCloud(this);
            }
        }

        return this;
    }

    @Override
    public boolean canProvision(Label label) {
        // Only when configurationStatus is certain invalid, reject at once.
        if (this.getConfigurationStatus().equals(Constants.VERIFIED_FAILED)) {
            LOGGER.log(Level.INFO,
                    "AzureVMCloud: canProvision: Subscription is invalid, cannot provision");
        }

        final AzureVMAgentTemplate template = AzureVMCloud.this.getAzureAgentTemplate(label);
        // return false if there is no template for this label.
        if (template == null) {
            // Avoid logging this, it happens a lot and is just noisy in logs.
            return false;
        } else if (template.isTemplateDisabled()) {
            // Log this.  It's not terribly noisy and can be useful
            LOGGER.log(Level.INFO,
                    "AzureVMCloud: canProvision: template {0} is marked has disabled, cannot provision agents",
                    template.getTemplateName());
            return false;
        } else if (template.getTemplateConfigurationStatus().equals(Constants.VERIFIED_FAILED)) {
            // The template is available, but not verified. It may be queued for
            // verification, but ensure that it's added.
            LOGGER.log(Level.INFO,
                    "AzureVMCloud: canProvision: template {0} has failed verification",
                    template.getTemplateName());
            return false;
        } else {
            return true;
        }
    }

    public static synchronized ExecutorService getThreadPool() {
        if (AzureVMCloud.threadPool == null) {
            AzureVMCloud.threadPool = Executors.newCachedThreadPool();
        }
        return AzureVMCloud.threadPool;
    }

    public Boolean isResourceGroupReferenceTypeEquals(String type) {
        if (this.resourceGroupReferenceType == null && type.equalsIgnoreCase("new")) {
            return true;
        }
        return type != null && type.equalsIgnoreCase(this.resourceGroupReferenceType);
    }

    public int getMaxVirtualMachinesLimit() {
        return maxVirtualMachinesLimit;
    }

    public static String getResourceGroupName(String type, String newName, String existingName) {
        //type maybe null in this version, so we can guess according to whether newName is blank or not
        if (StringUtils.isBlank(type) && StringUtils.isNotBlank(newName)
                || StringUtils.isNotBlank(type) && type.equalsIgnoreCase("new")) {
            return newName;
        }
        return existingName;
    }

    public String getCloudName() {
        return cloudName;
    }

    public static String getOrGenerateCloudName(String cloudName, String credentialId, String resourceGroupName) {
        return StringUtils.isBlank(cloudName)
                ? AzureUtil.getCloudName(credentialId, resourceGroupName)
                : cloudName;
    }

    public String getNewResourceGroupName() {
        return newResourceGroupName;
    }

    public String getExistingResourceGroupName() {
        return existingResourceGroupName;
    }

    public String getResourceGroupReferenceType() {
        return resourceGroupReferenceType;
    }

    public String getResourceGroupName() {
        return resourceGroupName;
    }

    public int getDeploymentTimeout() {
        return deploymentTimeout;
    }

    public String getAzureCredentialsId() {
        return credentialsId;
    }

    /**
     * Ensures there is a valid template list available.
     * Must be called under the vmTemplatesListLock
     */
    private void ensureVmTemplateList() {
        if (vmTemplates == null) {
            vmTemplates = new ArrayList<AzureVMAgentTemplate>();
        }
    }

    /**
     * Remove all templates in the template set.
     */
    public final void clearVmTemplates() {
        // Obtain lock while we copy the list.
        synchronized (this) {
            ensureVmTemplateList();
            vmTemplates.clear();
        }
    }

    /**
     * Adds a new VM template to the store.
     */
    public final void addVmTemplate(AzureVMAgentTemplate newTemplate) {
        // Obtain lock while we copy the list.
        synchronized (this) {
            ensureVmTemplateList();
            vmTemplates.add(newTemplate);
            newTemplate.setAzureCloud(this);
        }
    }

    /**
     * Sets the template list to a new template list.  The list is cleared and
     * the elements are iteratively added (to avoid leakage of the
     * internal template list)
     *
     * @param newTemplates Template set to use
     */
    public final void setVmTemplates(List<AzureVMAgentTemplate> newTemplates) {
        synchronized (this) {
            ensureVmTemplateList();
            vmTemplates.clear();
            for (AzureVMAgentTemplate newTemplate : newTemplates) {
                vmTemplates.add(newTemplate);
                newTemplate.setAzureCloud(this);
            }
        }
    }

    /**
     * Removes a template from the template store by name.
     * Removes all templates with the template name.
     *
     * @param templateName Template name to remove
     */
    public final void removeVmTemplate(String templateName) {
        // Obtain lock while we copy the list.
        synchronized (this) {
            ensureVmTemplateList();
            // in 1.8 we can use lambdas here, but in order to maintain
            // 1.7 compatibility we don't.
            ArrayList<AzureVMAgentTemplate> removeTemplates = new ArrayList<AzureVMAgentTemplate>();
            for (AzureVMAgentTemplate template : vmTemplates) {
                if (template.getTemplateName().equals(templateName)) {
                    removeTemplates.add(template);
                }
            }
            vmTemplates.removeAll(removeTemplates);
        }
    }

    /**
     * Returns the current set of templates. Required for config.jelly
     *
     * @return List of available template
     */
    public List<AzureVMAgentTemplate> getVmTemplates() {
        // Obtain lock while we copy the list.
        synchronized (this) {
            ensureVmTemplateList();
            // Copy the list.  Objects underneath can be left as-is.
            List<AzureVMAgentTemplate> listCopy = new ArrayList<AzureVMAgentTemplate>(vmTemplates);
            return Collections.unmodifiableList(listCopy);
        }
    }

    /**
     * Is the configuration set up and verified?
     *
     * @return True if the configuration set up and verified, false otherwise.
     */
    public String getConfigurationStatus() {
        return configurationStatus;
    }

    /**
     * Set the configuration verification status.
     *
     * @param status True for verified + valid, false otherwise.
     */
    public void setConfigurationStatus(String status) {
        configurationStatus = status;
    }

    /**
     * Retrieves the current approximate virtual machine count.
     *
     * @return
     */
    public int getApproximateVirtualMachineCount() {
        synchronized (this) {
            return approximateVirtualMachineCount;
        }
    }

    /**
     * Given the number of VMs that are desired, returns the number of VMs that
     * can be allocated and adjusts the number of VMs we believe exist.
     * If the number desired is less than 0, this subtracts from the total number
     * of virtual machines we currently have available.
     *
     * @param delta Number that are desired, or if less than 0, the number we are 'returning' to the pool
     * @return Number that can be allocated, up to the number desired.  0 if the number desired was < 0
     */
    public int adjustVirtualMachineCount(int delta) {
        synchronized (this) {
            if (delta < 0) {
                LOGGER.log(Level.FINE, "Current estimated VM count: {0}, reducing by {1}",
                        new Object[]{approximateVirtualMachineCount, delta});
                approximateVirtualMachineCount = Math.max(0, approximateVirtualMachineCount + delta);
                return 0;
            } else {
                LOGGER.log(Level.FINE, "Current estimated VM count: {0}, quantity desired {1}",
                        new Object[]{approximateVirtualMachineCount, delta});
                if (approximateVirtualMachineCount + delta <= getMaxVirtualMachinesLimit()) {
                    // Enough available, return the desired quantity, and update the number we think we
                    // have laying around.
                    approximateVirtualMachineCount += delta;
                    return delta;
                } else {
                    // Not enough available, return what we have. Remember we could
                    // go negative (if for instance another Jenkins instance had
                    // a higher limit.
                    int quantityAvailable = Math.max(0, getMaxVirtualMachinesLimit() - approximateVirtualMachineCount);
                    approximateVirtualMachineCount += quantityAvailable;
                    return quantityAvailable;
                }
            }
        }
    }

    /**
     * Sets the new approximate virtual machine count. This is run by the
     * verification task to update the VM count periodically.
     *
     * @param newCount
     */
    public void setVirtualMachineCount(int newCount) {
        synchronized (this) {
            approximateVirtualMachineCount = newCount;
        }
    }

    /**
     * Returns agent template associated with the label.
     *
     * @param label
     * @return
     */
    public AzureVMAgentTemplate getAzureAgentTemplate(Label label) {
        LOGGER.log(Level.FINE,
                "AzureVMCloud: getAzureAgentTemplate: Retrieving agent template with label {0}",
                label);
        // Lock the templates list rather than using getVMTemplates to avoid unnecessary copies.
        synchronized (this) {
            for (AzureVMAgentTemplate agentTemplate : vmTemplates) {
                LOGGER.log(Level.FINE,
                        "AzureVMCloud: getAzureAgentTemplate: Found agent template {0}",
                        agentTemplate.getTemplateName());
                if (agentTemplate.getUseAgentAlwaysIfAvail() == Node.Mode.NORMAL) {
                    if (label == null || label.matches(agentTemplate.getLabelDataSet())) {
                        LOGGER.log(Level.FINE,
                                "AzureVMCloud: getAzureAgentTemplate: {0} matches!",
                                agentTemplate.getTemplateName());
                        return agentTemplate;
                    }
                } else if (agentTemplate.getUseAgentAlwaysIfAvail() == Node.Mode.EXCLUSIVE) {
                    if (label != null && label.matches(agentTemplate.getLabelDataSet())) {
                        LOGGER.log(Level.FINE,
                                "AzureVMCloud: getAzureAgentTemplate: {0} matches!",
                                agentTemplate.getTemplateName());
                        return agentTemplate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns agent template associated with the name.
     *
     * @param name
     * @return
     */
    public AzureVMAgentTemplate getAzureAgentTemplate(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }

        synchronized (this) {
            for (AzureVMAgentTemplate agentTemplate : vmTemplates) {
                if (name.equals(agentTemplate.getTemplateName())) {
                    return agentTemplate;
                }
            }
        }
        return null;
    }

    /**
     * Once a new deployment is created, construct a new AzureVMAgent object
     * given information about the template.
     *
     * @param template       Template used to create the new agent
     * @param vmName         Name of the created VM
     * @param deploymentName Name of the deployment containing the VM
     * @return New agent. Throws otherwise.
     * @throws AzureCloudException
     */
    public AzureVMAgent createProvisionedAgent(
            ProvisioningActivity.Id provisioningId,
            AzureVMAgentTemplate template,
            String vmName,
            String deploymentName) throws AzureCloudException {

        LOGGER.log(Level.INFO,
                "AzureVMCloud: createProvisionedAgent: Waiting for deployment {0} to be completed",
                deploymentName);

        final int sleepTimeInSeconds = 30;
        final int timeoutInSeconds = getDeploymentTimeout();
        final int maxTries = timeoutInSeconds / sleepTimeInSeconds;
        int triesLeft = maxTries;
        do {
            triesLeft--;
            try {
                Thread.sleep(sleepTimeInSeconds * MILLIS_IN_SECOND);
            } catch (InterruptedException ex) {
                // ignore
            }

            try {
                // Create a new RM client each time because the config may expire while
                // in this long running operation
                final Azure newAzureClient = AzureClientFactory.getClient(credentialsId);
                final Deployment dep = newAzureClient.deployments().getByName(deploymentName);
                // Might find no deployment.
                if (dep == null) {
                    throw AzureCloudException.create(
                            String.format("AzureVMCloud: createProvisionedAgent: Could not find deployment %s",
                                    deploymentName));
                }

                PagedList<DeploymentOperation> ops = dep.deploymentOperations().list();
                for (DeploymentOperation op : ops) {
                    if (op.targetResource() == null) {
                        continue;
                    }
                    final String resource = op.targetResource().resourceName();
                    final String type = op.targetResource().resourceType();
                    final String state = op.provisioningState();
                    if (op.targetResource().resourceType().contains("virtualMachine")) {
                        if (resource.equalsIgnoreCase(vmName)) {

                            if (!state.equalsIgnoreCase("creating")
                                    && !state.equalsIgnoreCase("succeeded")
                                    && !state.equalsIgnoreCase("running")) {
                                final String statusCode = op.statusCode();
                                final Object statusMessage = op.statusMessage();
                                String finalStatusMessage = statusCode;
                                if (statusMessage != null) {
                                    finalStatusMessage += " - " + statusMessage.toString();
                                }
                                throw AzureCloudException.create(
                                        String.format("AzureVMCloud: createProvisionedAgent: Deployment %s: %s:%s - %s",
                                                state, type, resource, finalStatusMessage));
                            } else if (state.equalsIgnoreCase("succeeded")) {
                                LOGGER.log(Level.INFO,
                                        "AzureVMCloud: createProvisionedAgent: VM available: {0}",
                                        resource);

                                final VirtualMachine vm = newAzureClient.virtualMachines()
                                        .getByResourceGroup(resourceGroupName, resource);
                                final OperatingSystemTypes osType = vm.storageProfile().osDisk().osType();

                                AzureVMAgent newAgent = getServiceDelegate().parseResponse(
                                        provisioningId, vmName, deploymentName, template, osType);
                                getServiceDelegate().setVirtualMachineDetails(newAgent, template);
                                return newAgent;
                            } else {
                                LOGGER.log(Level.INFO,
                                        "AzureVMCloud: createProvisionedAgent: "
                                                + "Deployment {0} not yet finished ({1}): {2}:{3} - waited {4} seconds",
                                        new Object[]{deploymentName, state, type, resource,
                                                (maxTries - triesLeft) * sleepTimeInSeconds});
                            }
                        }
                    }
                }
            } catch (AzureCloudException e) {
                throw e;
            } catch (Exception e) {
                throw AzureCloudException.create(e);
            }
        } while (triesLeft > 0);

        throw AzureCloudException.create(String.format(
                "AzureVMCloud: createProvisionedAgent: Deployment %s failed, max timeout reached (%d seconds)",
                deploymentName, timeoutInSeconds));
    }

    @Override
    public Collection<PlannedNode> provision(Label label, int workLoad) {
        LOGGER.log(Level.INFO,
                "AzureVMCloud: provision: start for label {0} workLoad {1}", new Object[]{label, workLoad});

        final AzureVMAgentTemplate template = AzureVMCloud.this.getAzureAgentTemplate(label);

        // round up the number of required machine
        int numberOfAgents = (workLoad + template.getNoOfParallelJobs() - 1) / template.getNoOfParallelJobs();
        final List<PlannedNode> plannedNodes = new ArrayList<PlannedNode>(numberOfAgents);

        // verify cloud and template if it's status is unverified
        if (this.getConfigurationStatus().equals(Constants.UNVERIFIED)
                || template.getTemplateConfigurationStatus().equals(Constants.UNVERIFIED)) {
            AzureVMCloudVerificationTask.verify(cloudName, template.getTemplateName());
        }
        if (!this.getConfigurationStatus().equals(Constants.VERIFIED_PASS)
                || !template.getTemplateConfigurationStatus().equals(Constants.VERIFIED_PASS)) {
            return plannedNodes;
        }

        // reuse existing nodes if available
        LOGGER.log(Level.INFO, "AzureVMCloud: provision: checking for node reuse options");
        for (Computer agentComputer : Jenkins.getInstance().getComputers()) {
            if (numberOfAgents == 0) {
                break;
            }
            if (agentComputer instanceof AzureVMComputer && agentComputer.isOffline()) {
                final AzureVMComputer azureComputer = AzureVMComputer.class.cast(agentComputer);
                final AzureVMAgent agentNode = azureComputer.getNode();

                if (agentNode != null && isNodeEligibleForReuse(agentNode, template)) {
                    LOGGER.log(Level.INFO,
                            "AzureVMCloud: provision: agent computer eligible for reuse {0}",
                            agentComputer.getName());

                    final ProvisioningActivity.Id provisioningId =
                            new ProvisioningActivity.Id(this.name, template.getTemplateName());

                    try {
                        if (AzureVMManagementServiceDelegate.virtualMachineExists(agentNode)) {
                            numberOfAgents--;

                            plannedNodes.add(new TrackedPlannedNode(provisioningId,
                                    template.getNoOfParallelJobs(),
                                    Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                                        @Override
                                        public Node call() throws AzureCloudException {
                                            LOGGER.log(Level.INFO, "Found existing node, starting VM {0}",
                                                    agentNode.getNodeName());

                                            try {
                                                getServiceDelegate().startVirtualMachine(agentNode);
                                                final int waitTimeInMillis = 30 * 1000; // wait for 30 seconds
                                                // set virtual machine details again
                                                Thread.sleep(waitTimeInMillis);
                                                getServiceDelegate().setVirtualMachineDetails(
                                                        agentNode, template);
                                                Jenkins.getInstance().addNode(agentNode);
                                                if (agentNode.getAgentLaunchMethod().equalsIgnoreCase("SSH")) {
                                                    azureComputer.connect(false).get();
                                                } else { // Wait until node is online
                                                    waitUntilJNLPNodeIsOnline(agentNode);
                                                }
                                                azureComputer.setAcceptingTasks(true);
                                                agentNode.clearCleanUpAction();
                                                agentNode.setEligibleForReuse(false);
                                            } catch (Exception e) {
                                                throw AzureCloudException.create(e);
                                            }

                                            return agentNode;
                                        }
                                    })));
                        }
                    } catch (Exception e) {
                        // Couldn't bring the node back online.  Mark it
                        // as needing deletion
                        azureComputer.setAcceptingTasks(false);
                        agentNode.setCleanUpAction(CleanUpAction.DEFAULT, Messages._Shutdown_Agent_Failed_To_Revive());
                    }
                }
            }
        }

        // AI events to send
        final Map<String, String> properties = new HashMap<>();
        properties.put("NumberOfAgents", String.valueOf(plannedNodes.size()));
        properties.put(AppInsightsConstants.AZURE_SUBSCRIPTION_ID, getAzureClient().subscriptionId());
        properties.put(AppInsightsConstants.AZURE_LOCATION, template.getLocation());
        properties.put("AgentOS", template.getOsType());
        properties.put("RetentionStrategy", template.getRetentionStrategy().getClass().getName());

        // provision new nodes if required
        if (numberOfAgents > 0) {
            try {
                // Determine how many agents we can actually provision from here and
                // adjust our count (before deployment to avoid races)
                int adjustedNumberOfAgents = adjustVirtualMachineCount(numberOfAgents);
                if (adjustedNumberOfAgents == 0) {
                    LOGGER.log(Level.INFO,
                            "Not able to create any new nodes, at or above maximum VM count of {0}",
                            getMaxVirtualMachinesLimit());
                    return plannedNodes;
                } else if (adjustedNumberOfAgents < numberOfAgents) {
                    LOGGER.log(Level.INFO,
                            "Able to create new nodes, but can only create {0} (desired {1})",
                            new Object[]{adjustedNumberOfAgents, numberOfAgents});
                }
                doProvision(adjustedNumberOfAgents, plannedNodes, template);
                // wait for deployment completion ant than check for created nodes
            } catch (Exception e) {
                LOGGER.log(
                        Level.SEVERE,
                        String.format("Failure provisioning agents about '%s'", template.getLabels()),
                        e);
                properties.put("Message", e.getMessage());
                AzureVMAgentPlugin.sendEvent(Constants.AI_VM_AGENT, "ProvisionFailed", properties);
            }
        }

        LOGGER.log(Level.INFO,
                "AzureVMCloud: provision: asynchronous provision finished, returning {0} planned node(s)",
                plannedNodes.size());
        AzureVMAgentPlugin.sendEvent(Constants.AI_VM_AGENT, "Provision", properties);
        return plannedNodes;
    }

    public void doProvision(final int numberOfNewAgents,
                            List<PlannedNode> plannedNodes,
                            final AzureVMAgentTemplate template) {
        doProvision(numberOfNewAgents, plannedNodes, template, false);
    }

    public void doProvision(
            final int numberOfNewAgents,
            List<PlannedNode> plannedNodes,
            final AzureVMAgentTemplate template,
            final boolean isProvisionOutside) {
        Callable<AzureVMDeploymentInfo> callableTask = new Callable<AzureVMDeploymentInfo>() {
            @Override
            public AzureVMDeploymentInfo call() throws AzureCloudException {
                try {
                    return template.provisionAgents(
                            new StreamTaskListener(System.out, Charset.defaultCharset()), numberOfNewAgents);
                } catch (AzureCloudException e) {
                    throw e;
                } catch (Exception e) {
                    throw AzureCloudException.create(e);
                }
            }
        };

        final Future<AzureVMDeploymentInfo> deploymentFuture = getThreadPool().submit(callableTask);

        for (int i = 0; i < numberOfNewAgents; i++) {
            final int index = i;
            final ProvisioningActivity.Id provisioningId =
                    new ProvisioningActivity.Id(this.name, template.getTemplateName());

            plannedNodes.add(new TrackedPlannedNode(
                    provisioningId,
                    template.getNoOfParallelJobs(),
                    Computer.threadPoolForRemoting.submit(new Callable<Node>() {

                        @Override
                        public Node call() throws AzureCloudException {
                            // Wait for the future to complete
                            try {
                                PoolLock.provisionLock(template); //Only lock for pool maintaining.
                                if (isProvisionOutside) {
                                    CloudStatistics.ProvisioningListener.get().onStarted(provisioningId);
                                }
                                AzureVMDeploymentInfo info = null;
                                try {
                                    info = deploymentFuture.get();
                                } catch (InterruptedException | ExecutionException e) {
                                    throw AzureCloudException.create(e);
                                }

                                final String deploymentName = info.getDeploymentName();
                                final String vmBaseName = info.getVmBaseName();
                                final String vmName = String.format("%s%d", vmBaseName, index);

                                AzureVMAgent agent = null;
                                try {
                                    agent = createProvisionedAgent(
                                            provisioningId,
                                            template,
                                            vmName,
                                            deploymentName);
                                } catch (AzureCloudException e) {
                                    LOGGER.log(
                                            Level.SEVERE,
                                            String.format("Failure creating provisioned agent '%s'", vmName),
                                            e);

                                    handleFailure(template, vmName, e, FailureStage.PROVISIONING);

                                    throw e;
                                }

                                try {
                                    LOGGER.log(Level.INFO,
                                            "Azure Cloud: provision: Adding agent {0} to Jenkins nodes",
                                            agent.getNodeName());
                                    // Place the node in blocked state while it starts.
                                    try {
                                        agent.blockCleanUpAction();
                                        Jenkins.getInstance().addNode(agent);
                                        Computer computer = agent.toComputer();
                                        if (agent.getAgentLaunchMethod().equalsIgnoreCase("SSH")
                                                && computer != null) {
                                            computer.connect(false).get();
                                        } else if (agent.getAgentLaunchMethod()
                                                .equalsIgnoreCase("JNLP")) {
                                            // Wait until node is online
                                            waitUntilJNLPNodeIsOnline(agent);
                                        }
                                    } finally {
                                        // Place node in default state, now can be
                                        // dealt with by the cleanup task.
                                        agent.clearCleanUpAction();
                                    }
                                } catch (Exception e) {
                                    LOGGER.log(
                                            Level.SEVERE,
                                            String.format("Failure to in post-provisioning for '%s'", vmName),
                                            e);

                                    handleFailure(template, vmName, e, FailureStage.POSTPROVISIONING);

                                    // Remove the node from jenkins
                                    try {
                                        Jenkins.getInstance().removeNode(agent);
                                    } catch (IOException nodeRemoveEx) {
                                        LOGGER.log(
                                                Level.SEVERE,
                                                String.format("Failure removing Jenkins node for '%s'", vmName),
                                                nodeRemoveEx);
                                        // Do not throw to avoid it being recorded
                                    }

                                    throw AzureCloudException.create(e);
                                }

                                if (isProvisionOutside) {
                                    CloudStatistics.ProvisioningListener.get().onComplete(provisioningId, agent);
                                }
                                return agent;
                            } catch (AzureCloudException e) {
                                if (isProvisionOutside) {
                                    CloudStatistics.ProvisioningListener.get().onFailure(provisioningId, e);
                                }
                                throw e;
                            } finally {
                                PoolLock.provisionUnlock(template);
                            }
                        }

                        private void handleFailure(
                                AzureVMAgentTemplate template,
                                String vmName,
                                Exception e,
                                FailureStage stage) {
                            // Attempt to terminate whatever was created
                            try {
                                getServiceDelegate().terminateVirtualMachine(
                                        vmName,
                                        template.getResourceGroupName());
                            } catch (AzureCloudException terminateEx) {
                                LOGGER.log(
                                        Level.SEVERE,
                                        String.format("Failure terminating previous failed agent '%s'", vmName),
                                        terminateEx);
                                // Do not throw to avoid it being recorded
                            }

                            template.getAzureCloud().adjustVirtualMachineCount(-1);

                            // Update the template status given this new issue.
                            template.handleTemplateProvisioningFailure(e.getMessage(), stage);
                        }
                    })));
        }
    }

    /**
     * Wait till a node that connects through JNLP comes online and connects to
     * Jenkins.
     *
     * @param agent Node to wait for
     * @throws Exception Throws if the wait time expires or other exception happens.
     */
    private void waitUntilJNLPNodeIsOnline(final AzureVMAgent agent) throws AzureCloudException {
        LOGGER.log(Level.INFO, "Azure Cloud: waitUntilOnline: for agent {0}", agent.getDisplayName());
        Callable<String> callableTask = new Callable<String>() {

            @Override
            public String call() {
                try {
                    Computer computer = agent.toComputer();
                    if (computer != null) {
                        computer.waitUntilOnline();
                    }
                } catch (InterruptedException e) {
                    // just ignore
                }
                return "success";
            }
        };
        Future<String> future = getThreadPool().submit(callableTask);

        try {
            // 30 minutes is decent time for the node to be alive
            final int timeoutInMinutes = 30;
            String result = future.get(timeoutInMinutes, TimeUnit.MINUTES);
            LOGGER.log(Level.INFO, "Azure Cloud: waitUntilOnline: node is alive , result {0}", result);
        } catch (Exception ex) {
            throw AzureCloudException.create("Azure Cloud: waitUntilOnline: Failure waiting till online", ex);
        } finally {
            future.cancel(true);
        }
    }

    /**
     * Checks if node configuration matches with template definition.
     */
    private static boolean isNodeEligibleForReuse(AzureVMAgent agentNode, AzureVMAgentTemplate agentTemplate) {
        if (!agentNode.isEligibleForReuse()) {
            return false;
        }

        // Check for null label and mode.
        if (StringUtils.isBlank(agentNode.getLabelString()) && (agentNode.getMode() == Node.Mode.NORMAL)) {
            return true;
        }

        if (StringUtils.isNotBlank(agentNode.getLabelString()) && agentNode.getLabelString().equalsIgnoreCase(
                agentTemplate.getLabels())) {
            return true;
        }

        return false;
    }

    public Azure getAzureClient() {
        return azureClient.get();
    }

    public AzureVMManagementServiceDelegate getServiceDelegate() {
        return AzureVMManagementServiceDelegate.getInstance(getAzureClient());
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        private static final String LOG_RECORDER_NAME = "Azure VM Agent (Auto)";

        public String getLogRecorderName() {
            return LOG_RECORDER_NAME;
        }

        @Initializer(before = InitMilestone.PLUGINS_STARTED)
        public static void addAliases() {
            Jenkins.XSTREAM2.addCompatibilityAlias(
                    "com.microsoft.azure.AzureVMCloud", AzureVMCloud.class);
            Jenkins.XSTREAM2.addCompatibilityAlias(
                    "com.microsoft.azure.AzureVMAgent", AzureVMAgent.class);
            Jenkins.XSTREAM2.addCompatibilityAlias(
                    "com.microsoft.azure.remote.AzureVMAgentSSHLauncher", AzureVMAgentSSHLauncher.class);
            Jenkins.XSTREAM2.addCompatibilityAlias(
                    "com.microsoft.azure.AzureVMAgentTemplate", AzureVMAgentTemplate.class);
            Jenkins.XSTREAM2.addCompatibilityAlias(
                    "com.microsoft.azure.AzureVMCloudRetensionStrategy", AzureVMCloudRetensionStrategy.class);
            Jenkins.XSTREAM2.addCompatibilityAlias(
                    "com.microsoft.azure.AzureVMAgentPostBuildAction", AzureVMAgentPostBuildAction.class);
            Jenkins.XSTREAM2.addCompatibilityAlias(
                    "com.microsoft.azure.Messages", Messages.class);
        }

        @Initializer(before = PLUGINS_STARTED)
        public static void addLogRecorder(Jenkins h) throws IOException {
            // avoid the failure in dynamic loading.
            if (!h.hasPermission(h.ADMINISTER)) {
                return;
            }
            LogRecorderManager manager = h.getLog();
            Map<String, LogRecorder> logRecorders = manager.logRecorders;
            if (!logRecorders.containsKey(LOG_RECORDER_NAME)) {
                LogRecorder recorder = new LogRecorder(LOG_RECORDER_NAME);
                String packageName = AzureVMAgent.class.getPackage().getName();
                recorder.targets.add(new LogRecorder.Target(packageName, Level.WARNING));
                logRecorders.put(LOG_RECORDER_NAME, recorder);
                recorder.save();
            }
        }

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

        public int getDefaultDeploymentTimeout() {
            return Constants.DEFAULT_DEPLOYMENT_TIMEOUT_SEC;
        }

        public String getDefaultResourceGroupName() {
            return Constants.DEFAULT_RESOURCE_GROUP_NAME;
        }

        public FormValidation doVerifyConfiguration(
                @QueryParameter String azureCredentialsId,
                @QueryParameter String maxVirtualMachinesLimit,
                @QueryParameter String deploymentTimeout,
                @QueryParameter String resourceGroupReferenceType,
                @QueryParameter String newResourceGroupName,
                @QueryParameter String existingResourceGroupName) {

            String resourceGroupName = getResourceGroupName(
                    resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
            if (StringUtils.isBlank(resourceGroupName)) {
                resourceGroupName = Constants.DEFAULT_RESOURCE_GROUP_NAME;
            }
            Azure azureClient = AzureClientHolder.get(azureCredentialsId);
            final String validationResult = AzureVMManagementServiceDelegate.getInstance(azureClient)
                    .verifyConfiguration(resourceGroupName, maxVirtualMachinesLimit, deploymentTimeout);
            if (!validationResult.equalsIgnoreCase(Constants.OP_SUCCESS)) {
                return FormValidation.error(validationResult);
            }
            return FormValidation.ok(Messages.Azure_Config_Success());
        }

        public ListBoxModel doFillAzureCredentialsIdItems(@AncestorInPath Item owner) {
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .withAll(CredentialsProvider.lookupCredentials(AzureCredentials.class,
                            owner,
                            ACL.SYSTEM,
                            Collections.<DomainRequirement>emptyList()))
                    .withAll(CredentialsProvider.lookupCredentials(AzureMsiCredentials.class,
                            owner,
                            ACL.SYSTEM,
                            Collections.<DomainRequirement>emptyList()));
        }

        public ListBoxModel doFillExistingResourceGroupNameItems(@QueryParameter String azureCredentialsId)
                throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            model.add("--- Select Resource Group ---", "");
            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }

            try {
                final Azure azureClient = AzureClientHolder.get(azureCredentialsId);
                for (ResourceGroup resourceGroup : azureClient.resourceGroups().list()) {
                    model.add(resourceGroup.name());
                }
            } catch (Exception e) {
                LOGGER.log(Level.INFO, "Cannot list resource group name: {0}", e);
            } finally {
                return model;
            }
        }
    }
}
