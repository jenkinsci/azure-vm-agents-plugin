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
import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.OperatingSystemTypes;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.resources.Deployment;
import com.microsoft.azure.management.resources.DeploymentOperation;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.remote.AzureVMAgentSSHLauncher;
import com.microsoft.azure.vmagent.util.*;
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

import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.*;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.StreamTaskListener;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;

import java.nio.charset.Charset;
import java.util.logging.Level;

public class AzureVMCloud extends Cloud {

    public static final Logger LOGGER = Logger.getLogger(AzureVMCloud.class.getName());

    private transient final AzureCredentials.ServicePrincipal credentials;

    private final String credentialsId;

    private final int maxVirtualMachinesLimit;

    private final String resourceGroupName;

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
    private transient boolean configurationValid;

    // Approximate virtual machine count.  Updated periodically.
    private int approximateVirtualMachineCount;

    @DataBoundConstructor
    public AzureVMCloud(
            final String id,
            final String azureCredentialsId,
            final String maxVirtualMachinesLimit,
            final String deploymentTimeout,
            final String resourceGroupName,
            final List<AzureVMAgentTemplate> vmTemplates) {
        this(AzureCredentials.getServicePrincipal(azureCredentialsId), azureCredentialsId, maxVirtualMachinesLimit, deploymentTimeout, resourceGroupName, vmTemplates);
    }

    public AzureVMCloud(
            AzureCredentials.ServicePrincipal credentials,
            final String azureCredentialsId,
            final String maxVirtualMachinesLimit,
            final String deploymentTimeout,
            final String resourceGroupName,
            final List<AzureVMAgentTemplate> vmTemplates) {
        super(AzureUtil.getCloudName(credentials.getSubscriptionId(), resourceGroupName));
        this.credentials = credentials;
        this.credentialsId = azureCredentialsId;
        this.resourceGroupName = resourceGroupName;

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

        this.configurationValid = false;

        // Set the templates
        setVmTemplates(vmTemplates == null
                ? Collections.<AzureVMAgentTemplate>emptyList()
                : vmTemplates);

        registerVerificationIfNeeded();
    }

    /**
     * Register the initial verification if required
     */
    private void registerVerificationIfNeeded() {
        if (this.isConfigurationValid()) {
            return;
        }
        // Register the cloud and the templates for verification
        AzureVMCloudVerificationTask.registerCloud(this.name);
        // Register all templates.  We don't know what happened with them
        // when save was hit.
        AzureVMCloudVerificationTask.registerTemplates(this.getVmTemplates());
        // Force the verification task to run if it's not already running.
        // Note that early in startup this could return null
        if (AzureVMCloudVerificationTask.get() != null) {
            AzureVMCloudVerificationTask.get().doRun();
        }
    }

    private Object readResolve() {
        synchronized(this) {
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
    public boolean canProvision(final Label label) {
        registerVerificationIfNeeded();

        if (!this.isConfigurationValid()) {
            LOGGER.log(Level.INFO, "AzureVMCloud: canProvision: Subscription not verified, or is invalid, cannot provision");
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
        } else if (!template.isTemplateVerified()) {
            // The template is available, but not verified. It may be queued for
            // verification, but ensure that it's added.
            LOGGER.log(Level.INFO,
                    "AzureVMCloud: canProvision: template {0} is awaiting verification or has failed verification",
                    template.getTemplateName());
            AzureVMCloudVerificationTask.registerTemplate(template);
            return false;
        } else {
            return true;
        }
    }

    public synchronized static ExecutorService getThreadPool() {
        if (AzureVMCloud.threadPool == null) {
            AzureVMCloud.threadPool = Executors.newCachedThreadPool();
        }
        return AzureVMCloud.threadPool;
    }

    public int getMaxVirtualMachinesLimit() {
        return maxVirtualMachinesLimit;
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

    public AzureCredentials.ServicePrincipal getServicePrincipal()
    {
        if(credentials == null && credentialsId != null)
            return AzureCredentials.getServicePrincipal(credentialsId);
        return credentials;
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
     * Adds a new VM template to the store
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
    public boolean isConfigurationValid() {
        return configurationValid;
    }

    /**
     * Set the configuration verification status
     *
     * @param isValid True for verified + valid, false otherwise.
     */
    public void setConfigurationValid(boolean isValid) {
        configurationValid = isValid;
    }

    /**
     * Retrieves the current approximate virtual machine count
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
     * can be allocated.
     *
     * @param quantityDesired Number that are desired
     * @return Number that can be allocated
     */
    public int getAvailableVirtualMachineCount(int quantityDesired) {
        synchronized (this) {
            if (approximateVirtualMachineCount + quantityDesired <= getMaxVirtualMachinesLimit()) {
                // Enough available, return the desired quantity
                return quantityDesired;
            } else {
                // Not enough available, return what we have. Remember we could
                // go negative (if for instance another Jenkins instance had
                // a higher limit.
                return Math.max(0, getMaxVirtualMachinesLimit() - approximateVirtualMachineCount);
            }
        }
    }

    /**
     * Adjust the number of currently allocated VMs
     *
     * @param delta Number to adjust by.
     */
    public void adjustVirtualMachineCount(int delta) {
        synchronized (this) {
            approximateVirtualMachineCount = Math.max(0, approximateVirtualMachineCount + delta);
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
    public AzureVMAgentTemplate getAzureAgentTemplate(final Label label) {
        LOGGER.log(Level.FINE, "AzureVMCloud: getAzureAgentTemplate: Retrieving agent template with label {0}", label);
        // Lock the templates list rather than using getVMTemplates to avoid unnecessary copies.
        synchronized (this) {
            for (AzureVMAgentTemplate agentTemplate : vmTemplates) {
                LOGGER.log(Level.FINE, "AzureVMCloud: getAzureAgentTemplate: Found agent template {0}", agentTemplate.getTemplateName());
                if (agentTemplate.getUseAgentAlwaysIfAvail() == Node.Mode.NORMAL) {
                    if (label == null || label.matches(agentTemplate.getLabelDataSet())) {
                        LOGGER.log(Level.FINE, "AzureVMCloud: getAzureAgentTemplate: {0} matches!", agentTemplate.getTemplateName());
                        return agentTemplate;
                    }
                } else if (agentTemplate.getUseAgentAlwaysIfAvail() == Node.Mode.EXCLUSIVE) {
                    if (label != null && label.matches(agentTemplate.getLabelDataSet())) {
                        LOGGER.log(Level.FINE, "AzureVMCloud: getAzureAgentTemplate: {0} matches!", agentTemplate.getTemplateName());
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
    public AzureVMAgentTemplate getAzureAgentTemplate(final String name) {
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
     * given information about the template
     *
     * @param template Template used to create the new agent
     * @param vmName Name of the created VM
     * @param deploymentName Name of the deployment containing the VM
     * @return New agent. Throws otherwise.
     * @throws Exception
     */
    public AzureVMAgent createProvisionedAgent(
            final ProvisioningActivity.Id provisioningId,
            final AzureVMAgentTemplate template,
            final String vmName,
            final String deploymentName) throws Exception {

        LOGGER.log(Level.INFO, "AzureVMCloud: createProvisionedAgent: Waiting for deployment {0} to be completed", deploymentName);

        final int sleepTimeInSeconds = 30;
        final int timeoutInSeconds = getDeploymentTimeout();
        final int maxTries = timeoutInSeconds / sleepTimeInSeconds;
        int triesLeft = maxTries;
        do {
            triesLeft--;
            try {
                Thread.sleep(sleepTimeInSeconds * 1000);
            } catch (InterruptedException ex) {
                // ignore
            }

            // Create a new RM client each time because the config may expire while
            // in this long running operation
            final Azure azureClient = Azure.configure()
                .withLogLevel(Constants.DEFAULT_AZURE_SDK_LOGGING_LEVEL)
                .withUserAgent(TokenCache.getUserAgent())
                .authenticate(TokenCache.get(template.getAzureCloud().getServicePrincipal()))
                .withSubscription(template.getAzureCloud().getServicePrincipal().getSubscriptionId());
            PagedList<Deployment> deployments= azureClient.deployments().listByGroup(resourceGroupName);
            for (Deployment dep : deployments) {
                PagedList<DeploymentOperation> ops = dep.deploymentOperations().list();
                for (DeploymentOperation op : ops) {
                    if (op.targetResource() == null) {
                        continue;
                    }
                    final String resource = op.targetResource().resourceName();
                    final String type = op.targetResource().resourceType();
                    final String state = op.provisioningState();
                    if (op.targetResource().resourceType().contains("virtualMachine")) {
                        if(resource.equalsIgnoreCase(vmName)) {

                            if (!state.equalsIgnoreCase("creating")
                                && !state.equalsIgnoreCase("succeeded")
                                && !state.equalsIgnoreCase("running")){
                                final String statusCode = op.statusCode();
                                final Object statusMessage = op.statusMessage();
                                String finalStatusMessage = statusCode;
                                if (statusMessage != null) {
                                    finalStatusMessage += " - " + statusMessage.toString();
                                }
                                throw new AzureCloudException(String.format("AzureVMCloud: createProvisionedAgent: Deployment %s: %s:%s - %s", new Object[]{state, type, resource, finalStatusMessage}));
                            }
                            else if (state.equalsIgnoreCase("succeeded")) {
                                LOGGER.log(Level.INFO, "AzureVMCloud: createProvisionedAgent: VM available: {0}", resource);

                                final VirtualMachine vm = azureClient.virtualMachines().getByGroup(resourceGroupName, resource);
                                final OperatingSystemTypes osType = vm.storageProfile().osDisk().osType();

                                AzureVMAgent newAgent = AzureVMManagementServiceDelegate.parseResponse(provisioningId, vmName, deploymentName, template, osType);
                                AzureVMManagementServiceDelegate.setVirtualMachineDetails(newAgent, template);
                                return newAgent;
                            } else {
                                LOGGER.log(Level.INFO, "AzureVMCloud: createProvisionedAgent: Deployment {0} not yet finished ({1}): {2}:{3} - waited {4} seconds",
                                    new Object[]{deploymentName, state, type, resource,
                                        (maxTries - triesLeft) * sleepTimeInSeconds});
                            }
                        }
                    }
                }

            }
        } while (triesLeft > 0);

        throw new AzureCloudException(String.format("AzureVMCloud: createProvisionedAgent: Deployment %s failed, max timeout reached (%d seconds)", deploymentName, sleepTimeInSeconds));
    }

    @Override
    public Collection<PlannedNode> provision(final Label label, int workLoad) {
        LOGGER.log(Level.INFO,
                "AzureVMCloud: provision: start for label {0} workLoad {1}", new Object[]{label, workLoad});

        final AzureVMAgentTemplate template = AzureVMCloud.this.getAzureAgentTemplate(label);

        // round up the number of required machine
        int numberOfAgents = (workLoad + template.getNoOfParallelJobs() - 1) / template.getNoOfParallelJobs();
        final List<PlannedNode> plannedNodes = new ArrayList<PlannedNode>(numberOfAgents);

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
                    LOGGER.log(Level.INFO, "AzureVMCloud: provision: agent computer eligible for reuse {0}", agentComputer.getName());

                    final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(this.name, template.getTemplateName());

                    try {
                        if (AzureVMManagementServiceDelegate.virtualMachineExists(agentNode)) {
                            numberOfAgents--;

                            plannedNodes.add(new TrackedPlannedNode(
                                    provisioningId,
                                    template.getNoOfParallelJobs(),
                                    Computer.threadPoolForRemoting.submit(new Callable<Node>() {

                                        @Override
                                        public Node call() throws Exception {
                                            LOGGER.log(Level.INFO, "Found existing node, starting VM {0}", agentNode.
                                                    getNodeName());

                                            AzureVMManagementServiceDelegate.startVirtualMachine(agentNode);
                                            // set virtual machine details again
                                            Thread.sleep(30 * 1000); // wait for 30 seconds
                                            AzureVMManagementServiceDelegate.setVirtualMachineDetails(
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

        // provision new nodes if required
        if (numberOfAgents > 0) {
            try {
                // Determine how many agents we can actually provision from here and
                // adjust our count (before deployment to avoid races)
                int adjustedNumberOfAgents = getAvailableVirtualMachineCount(numberOfAgents);
                if (adjustedNumberOfAgents == 0) {
                    LOGGER.log(Level.INFO, "Not able to create any new nodes, at or above maximum VM count of {0}",
                            getMaxVirtualMachinesLimit());
                    return plannedNodes;
                } else if (adjustedNumberOfAgents < numberOfAgents) {
                    LOGGER.log(Level.INFO, "Able to create new nodes, but can only create {0} (desired {1})",
                            new Object[]{adjustedNumberOfAgents, numberOfAgents});
                }
                final int numberOfNewAgents = adjustedNumberOfAgents;

                // Adjust number of nodes available by the number of created nodes.
                // Negative to reduce number available.
                this.adjustVirtualMachineCount(-adjustedNumberOfAgents);

                Callable<AzureVMDeploymentInfo> callableTask = new Callable<AzureVMDeploymentInfo>() {
                    @Override
                    public AzureVMDeploymentInfo call() throws Exception {
                        return template.provisionAgents(new StreamTaskListener(System.out, Charset.defaultCharset()), numberOfNewAgents);
                    }
                };
                final Future<AzureVMDeploymentInfo> deploymentFuture = getThreadPool().submit(callableTask);

                for (int i = 0; i < numberOfNewAgents; i++) {
                    final int index = i;
                    final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(this.name, template.getTemplateName());

                    plannedNodes.add(new TrackedPlannedNode(
                            provisioningId,
                            template.getNoOfParallelJobs(),
                            Computer.threadPoolForRemoting.submit(new Callable<Node>() {

                                @Override
                                public Node call() throws Exception {

                                    // Wait for the future to complete
                                    AzureVMDeploymentInfo info = deploymentFuture.get();

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
                                    } catch (Exception e) {
                                        LOGGER.log(
                                                Level.SEVERE,
                                                String.format("Failure creating provisioned agent '%s'", vmName),
                                                e);

                                        // Attempt to terminate whatever was created
                                        AzureVMManagementServiceDelegate.terminateVirtualMachine(
                                                template.getAzureCloud().getServicePrincipal(), vmName,
                                                template.getResourceGroupName());
                                        template.getAzureCloud().adjustVirtualMachineCount(1);
                                        // Update the template status given this new issue.
                                        template.handleTemplateProvisioningFailure(e.getMessage(), FailureStage.PROVISIONING);
                                        throw e;
                                    }

                                    try {
                                        LOGGER.log(Level.INFO, "Azure Cloud: provision: Adding agent {0} to Jenkins nodes", agent.getNodeName());
                                        // Place the node in blocked state while it starts.
                                        agent.blockCleanUpAction();
                                        Jenkins.getInstance().addNode(agent);
                                        Computer computer = agent.toComputer();
                                        if (agent.getAgentLaunchMethod().equalsIgnoreCase("SSH") && computer != null) {
                                            computer.connect(false).get();
                                        } else if (agent.getAgentLaunchMethod().equalsIgnoreCase("JNLP")) {
                                            // Wait until node is online
                                            waitUntilJNLPNodeIsOnline(agent);
                                        }
                                        // Place node in default state, now can be
                                        // dealt with by the cleanup task.
                                        agent.clearCleanUpAction();
                                    } catch (Exception e) {
                                        LOGGER.log(
                                                Level.SEVERE,
                                                String.format("Failure to in post-provisioning for '%s'", vmName),
                                                e);

                                        // Attempt to terminate whatever was created
                                        AzureVMManagementServiceDelegate.terminateVirtualMachine(
                                                template.getAzureCloud().getServicePrincipal(), vmName,
                                                template.getResourceGroupName());
                                        template.getAzureCloud().adjustVirtualMachineCount(1);

                                        // Update the template status
                                        template.handleTemplateProvisioningFailure(vmName, FailureStage.POSTPROVISIONING);

                                        // Remove the node from jenkins
                                        Jenkins.getInstance().removeNode(agent);
                                        throw e;
                                    }
                                    return agent;
                                }
                            })));
                }
                // wait for deployment completion ant than check for created nodes
            } catch (Exception e) {
                LOGGER.log(
                        Level.SEVERE,
                        String.format("Failure provisioning agents about '%s'", template.getLabels()),
                        e);
            }
        }

        LOGGER.log(Level.INFO,
                "AzureVMCloud: provision: asynchronous provision finished, returning {0} planned node(s)", plannedNodes.size());
        return plannedNodes;
    }

    /**
     * Wait till a node that connects through JNLP comes online and connects to
     * Jenkins.
     *
     * @param agent Node to wait for
     * @throws Exception Throws if the wait time expires or other exception
     * happens.
     */
    private void waitUntilJNLPNodeIsOnline(final AzureVMAgent agent) throws Exception {
        LOGGER.log(Level.INFO, "Azure Cloud: waitUntilOnline: for agent {0}", agent.getDisplayName());
        Callable<String> callableTask = new Callable<String>() {

            @Override
            public String call() {
                try {
                    Computer computer = agent.toComputer();
                    if (computer != null)
                        computer.waitUntilOnline();
                } catch (InterruptedException e) {
                    // just ignore
                }
                return "success";
            }
        };
        Future<String> future = getThreadPool().submit(callableTask);

        try {
            // 30 minutes is decent time for the node to be alive
            String result = future.get(30, TimeUnit.MINUTES);
            LOGGER.log(Level.INFO, "Azure Cloud: waitUntilOnline: node is alive , result {0}", result);
        } catch (Exception ex) {
            throw new AzureCloudException("Azure Cloud: waitUntilOnline: Failure waiting till online", ex);
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

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Initializer(before = InitMilestone.PLUGINS_STARTED)
        public static void addAliases() {
            Jenkins.XSTREAM2.addCompatibilityAlias("com.microsoft.azure.AzureVMCloud", AzureVMCloud.class);
            Jenkins.XSTREAM2.addCompatibilityAlias("com.microsoft.azure.AzureVMAgent", AzureVMAgent.class);
            Jenkins.XSTREAM2.addCompatibilityAlias("com.microsoft.azure.remote.AzureVMAgentSSHLauncher", AzureVMAgentSSHLauncher.class);
            Jenkins.XSTREAM2.addCompatibilityAlias("com.microsoft.azure.AzureVMAgentTemplate", AzureVMAgentTemplate.class);
            Jenkins.XSTREAM2.addCompatibilityAlias("com.microsoft.azure.AzureVMCloudRetensionStrategy", AzureVMCloudRetensionStrategy.class);
            Jenkins.XSTREAM2.addCompatibilityAlias("com.microsoft.azure.AzureVMAgentPostBuildAction", AzureVMAgentPostBuildAction.class);
            Jenkins.XSTREAM2.addCompatibilityAlias("com.microsoft.azure.Messages", Messages.class);
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
                @QueryParameter String resourceGroupName) {

            if (StringUtils.isBlank(resourceGroupName)) {
                resourceGroupName = Constants.DEFAULT_RESOURCE_GROUP_NAME;
            }
            AzureCredentials.ServicePrincipal credentials = AzureCredentials.getServicePrincipal(azureCredentialsId);
            try {
                credentials.validate();
                final String validationResult = AzureVMManagementServiceDelegate.verifyConfiguration(credentials, resourceGroupName, maxVirtualMachinesLimit, deploymentTimeout);
                if (!validationResult.equalsIgnoreCase(Constants.OP_SUCCESS)) {
                    return FormValidation.error(validationResult);
                }
            } catch (AzureCredentials.ValidationException e) {
                return FormValidation.error(e.getMessage());
            }
            return FormValidation.ok(Messages.Azure_Config_Success());
        }

        public ListBoxModel doFillAzureCredentialsIdItems(@AncestorInPath Item owner) {
            return new StandardListBoxModel().withAll(CredentialsProvider.lookupCredentials(AzureCredentials.class, owner, ACL.SYSTEM, Collections.<DomainRequirement>emptyList()));
        }
    }
}
