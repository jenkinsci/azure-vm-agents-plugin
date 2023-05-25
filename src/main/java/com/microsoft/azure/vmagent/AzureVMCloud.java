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

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.OperatingSystemTypes;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.resources.models.Deployment;
import com.azure.resourcemanager.resources.models.DeploymentOperation;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.util.AzureImdsCredentials;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.remote.AzureVMAgentSSHLauncher;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.FailureStage;
import com.microsoft.azure.vmagent.util.PoolLock;
import com.microsoft.jenkins.credentials.AzureResourceManagerCache;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.init.Initializer;
import hudson.logging.LogRecorder;
import hudson.logging.LogRecorderManager;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.microsoft.azure.vmagent.util.Constants.MILLIS_IN_SECOND;
import static hudson.init.InitMilestone.PLUGINS_STARTED;

public class AzureVMCloud extends Cloud {

    public static final Logger LOGGER = Logger.getLogger(AzureVMCloud.class.getName());
    private static final int DEFAULT_SSH_CONNECT_RETRY_COUNT = 3;
    private static final int SHH_CONNECT_RETRY_INTERNAL_SECONDS = 20;

    private final String cloudName;

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
    @CheckForNull
    private transient Map<String, Integer> approximateVirtualMachineCountsByTemplate;

    private transient AzureResourceManager azureClient;

    private List<AzureTagPair> cloudTags;

    //The map should not be accessed without acquiring a lock of the map
    private transient Map<AzureVMAgent, AtomicInteger> agentLocks = new HashMap<>();

    @DataBoundConstructor
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
                ? Collections.emptyList()
                : vmTemplates);

        this.cloudTags = new ArrayList<>();
    }

    @SuppressWarnings("unused") // read resolve is called by xstream
    private Object readResolve() {
        if (StringUtils.isBlank(newResourceGroupName) && StringUtils.isBlank(existingResourceGroupName)
                && StringUtils.isNotBlank(resourceGroupName)) {
            newResourceGroupName = resourceGroupName;
            resourceGroupReferenceType = "new";
        }
        //resourceGroupName is transient so we need to restore it for future using
        resourceGroupName = getResourceGroupName(
                resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
        configurationStatus = Constants.UNVERIFIED;

        if (cloudTags ==  null) {
            cloudTags = new ArrayList<>();
        }

        synchronized (this) {
            // Ensure that renamed field is set
            if (instTemplates != null && vmTemplates == null) {
                vmTemplates = instTemplates;
                instTemplates = null;
            }

            if (agentLocks == null) {
                agentLocks = new HashMap<>();
            }

            // Walk the list of templates and assign the parent cloud (which is transient).
            ensureVmTemplateList();
            for (AzureVMAgentTemplate template : vmTemplates) {
                template.addAzureCloudReference(this);
            }
        }

        return this;
    }

    @Override
    public boolean canProvision(CloudState cloudState) {
        final AzureVMAgentTemplate template = AzureVMCloud.this.getAzureAgentTemplate(cloudState.getLabel());
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
        }

        return template.getTemplateProvisionStrategy().isEnabled();

    }

    public static synchronized ExecutorService getThreadPool() {
        if (AzureVMCloud.threadPool == null) {
            AzureVMCloud.threadPool = Executors.newCachedThreadPool();
        }
        return AzureVMCloud.threadPool;
    }

    @SuppressWarnings("unused") // called by jelly
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
     */
    private void ensureVmTemplateList() {
        if (vmTemplates == null) {
            vmTemplates = new CopyOnWriteArrayList<>();
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
        for (AzureVMAgentTemplate newTemplate : newTemplates) {
            newTemplate.addAzureCloudReference(this);
        }
        this.vmTemplates = new CopyOnWriteArrayList<>(newTemplates);
    }

    public List<AzureTagPair> getCloudTags() {
        return cloudTags;
    }

    @DataBoundSetter
    public void setCloudTags(List<AzureTagPair> cloudTags) {
        this.cloudTags = cloudTags;
    }

    /**
     * Current set of templates.
     *
     * @return List of available template
     */
    public List<AzureVMAgentTemplate> getVmTemplates() {
        ensureVmTemplateList();
        return Collections.unmodifiableList(vmTemplates);
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
     * Retrieves the current approximate virtual machine count,
     * counting only VMs we've made.
     *
     * @return The approximate count
     */
    public int getApproximateVirtualMachineCount() {
        synchronized (this) {
            int count = 0;
            if (approximateVirtualMachineCountsByTemplate != null) {
                for (final Integer templateCount : approximateVirtualMachineCountsByTemplate.values()) {
                    count += templateCount;
                }
            }
            return count;
        }
    }

    /**
     * Retrieves the current count of the number of virtual machines for a specific
     * template.
     *
     * @param template the template VMs are being counted for.
     * @return The number of VMs of that template.
     */
    @Restricted(NoExternalUse.class)
    int getApproximateVirtualMachineCountForTemplate(AzureVMAgentTemplate template) {
        final String templateName = template.getTemplateName();
        synchronized (this) {
            if (approximateVirtualMachineCountsByTemplate != null) {
                final Integer templateCount = approximateVirtualMachineCountsByTemplate.get(templateName);
                if (templateCount != null) {
                    return templateCount;
                }
            }
            return 0;
        }
    }

    /**
     * Adjusts our estimate of the current number of VMs we have for a specific
     * template.
     *
     * @param delta    The change to make. Positive means we think we've got more
     *                 VMs, negative means we think we've got fewer.
     * @param template the template we're talking about.
     */
    @Restricted(NoExternalUse.class)
    void adjustApproximateVirtualMachineCount(int delta, AzureVMAgentTemplate template) {
        final String templateName = template.getTemplateName();
        synchronized (this) {
            final int currentCount = getApproximateVirtualMachineCountForTemplate(template);
            final int newCount = currentCount + delta;
            if (approximateVirtualMachineCountsByTemplate == null) {
                if (newCount != 0) {
                    setCurrentVirtualMachineCount(Collections.singletonMap(templateName, newCount));
                }
            } else {
                if (newCount == 0) {
                    approximateVirtualMachineCountsByTemplate.remove(templateName);
                    if (approximateVirtualMachineCountsByTemplate.isEmpty()) {
                        approximateVirtualMachineCountsByTemplate = null;
                    }
                } else {
                    approximateVirtualMachineCountsByTemplate.put(templateName, newCount);
                }
            }
        }
    }

    /**
     * Sets the new approximate virtual machine counts.
     * This is run by the verification task to update the VM count periodically.
     *
     * @param countsIndexedByTemplateName New approximate counts
     */
    @Restricted(NoExternalUse.class)
    void setCurrentVirtualMachineCount(Map<String, Integer> countsIndexedByTemplateName) {
        synchronized (this) {
            if (countsIndexedByTemplateName == null || countsIndexedByTemplateName.isEmpty()) {
                approximateVirtualMachineCountsByTemplate = null;
            } else {
                if (approximateVirtualMachineCountsByTemplate == null) {
                    approximateVirtualMachineCountsByTemplate = new TreeMap<>(countsIndexedByTemplateName);
                } else {
                    approximateVirtualMachineCountsByTemplate.clear();
                    approximateVirtualMachineCountsByTemplate.putAll(countsIndexedByTemplateName);
                }
            }
        }
    }

    /**
     * Returns agent template associated with the label.
     *
     * @param label Label to use for search
     * @return Agent template that has the label assigned
     */
    public AzureVMAgentTemplate getAzureAgentTemplate(Label label) {
        LOGGER.log(Level.FINE,
                "AzureVMCloud: getAzureAgentTemplate: Retrieving agent template with label {0}",
                label);
        for (AzureVMAgentTemplate agentTemplate : vmTemplates) {
            LOGGER.log(Level.FINE,
                    "AzureVMCloud: getAzureAgentTemplate: Found agent template {0}",
                    agentTemplate.getTemplateName());
            if (agentTemplate.getUsageMode() == Node.Mode.NORMAL) {
                if (label == null || label.matches(agentTemplate.getLabelDataSet())) {
                    LOGGER.log(Level.FINE,
                            "AzureVMCloud: getAzureAgentTemplate: {0} matches!",
                            agentTemplate.getTemplateName());
                    return agentTemplate;
                }
            } else if (agentTemplate.getUsageMode() == Node.Mode.EXCLUSIVE) {
                if (label != null && label.matches(agentTemplate.getLabelDataSet())) {
                    LOGGER.log(Level.FINE,
                            "AzureVMCloud: getAzureAgentTemplate: {0} matches!",
                            agentTemplate.getTemplateName());
                    return agentTemplate;
                }
            }
        }
        return null;
    }

    /**
     * Returns agent template associated with the name.
     *
     * @param name Name to use for search
     * @return Agent template that has the name assigned
     */
    public AzureVMAgentTemplate getAzureAgentTemplate(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }

        for (AzureVMAgentTemplate agentTemplate : vmTemplates) {
            if (name.equals(agentTemplate.getTemplateName())) {
                return agentTemplate;
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
     * @return New agent
     * @throws AzureCloudException If the agent cannot be created
     */
    public AzureVMAgent createProvisionedAgent(
            ProvisioningActivity.Id provisioningId,
            AzureVMAgentTemplate template,
            String vmName,
            String deploymentName) throws AzureCloudException {

        LOGGER.log(Level.INFO, "Waiting for deployment {0} with VM {1} to be completed",
                new Object[]{deploymentName, vmName});

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
                final AzureResourceManager newAzureClient = template.retrieveAzureCloudReference().getAzureClient();

                final Deployment dep = newAzureClient.deployments()
                        .getByResourceGroup(template.getResourceGroupName(), deploymentName);
                // Might find no deployment.
                if (dep == null) {
                    throw AzureCloudException.create(
                            String.format("Could not find deployment %s", deploymentName));
                }

                PagedIterable<DeploymentOperation> ops = dep.deploymentOperations().list();
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
                                    finalStatusMessage += " - " + statusMessage;
                                }
                                throw AzureCloudException.create(
                                        String.format("Deployment %s: %s:%s - %s",
                                                state, type, resource, finalStatusMessage));
                            } else if (state.equalsIgnoreCase("succeeded")) {
                                LOGGER.log(Level.FINE, "VM available: {0}", resource);

                                final VirtualMachine vm = newAzureClient.virtualMachines()
                                        .getByResourceGroup(resourceGroupName, resource);
                                final OperatingSystemTypes osType = vm.storageProfile().osDisk().osType();

                                AzureVMAgent newAgent = getServiceDelegate().parseResponse(
                                        provisioningId, vmName, deploymentName, template, osType);
                                getServiceDelegate().setVirtualMachineDetails(newAgent, template);
                                return newAgent;
                            } else {
                                LOGGER.log(Level.INFO,
                                        "Deployment {0} not yet finished ({1}): {2}:{3} - waited {4} seconds",
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
                "Deployment %s failed, max timeout reached (%d seconds)",
                deploymentName, timeoutInSeconds));
    }

    @Override
    public Collection<PlannedNode> provision(CloudState cloudState, int workLoad) {
        LOGGER.log(Level.FINE, "Starting for label {0} workLoad {1}",
                new Object[]{cloudState.getLabel(), workLoad}
        );
        final AzureVMAgentTemplate template = AzureVMCloud.this.getAzureAgentTemplate(cloudState.getLabel());

        // round up the number of required machine
        int numberOfAgents = (workLoad + template.getNoOfParallelJobs() - 1) / template.getNoOfParallelJobs();
        final List<PlannedNode> plannedNodes = new ArrayList<>(numberOfAgents);

        if (!template.getTemplateProvisionStrategy().isVerifiedPass()) {
            AzureVMCloudVerificationTask.verify(cloudName, template.getTemplateName());
        }
        if (template.getTemplateProvisionStrategy().isVerifiedFailed()) {
            LOGGER.log(Level.INFO, "Template {0} has just verified failed", template.getTemplateName());
            if (StringUtils.isNotBlank(template.getTemplateStatusDetails())) {
                LOGGER.log(Level.INFO, template.getTemplateStatusDetails());
            }
            return new ArrayList<>();
        }

        // reuse existing nodes if available
        LOGGER.log(Level.FINE, "Checking for node reuse options");
        for (Computer agentComputer : Jenkins.get().getComputers()) {
            if (numberOfAgents == 0) {
                break;
            }
            if (agentComputer instanceof AzureVMComputer && agentComputer.isOffline()) {
                final AzureVMComputer azureComputer = (AzureVMComputer) agentComputer;
                final AzureVMAgent agentNode = azureComputer.getNode();

                if (agentNode != null && isNodeEligibleForReuse(agentNode, template)) {
                    LOGGER.log(Level.FINE, "Agent computer eligible for reuse {0}", agentComputer.getName());

                    try {
                        if (AzureVMManagementServiceDelegate.virtualMachineExists(agentNode)) {
                            numberOfAgents--;

                            plannedNodes.add(new PlannedNode(agentNode.getNodeName(),
                                    Computer.threadPoolForRemoting.submit(() -> {
                                        final Object agentLock = getLockForAgent(agentNode);
                                        try {
                                            synchronized (agentLock) {
                                                SlaveComputer computer = agentNode.getComputer();

                                                if (computer != null && computer.isOnline()) {
                                                    return agentNode;
                                                }
                                                LOGGER.log(Level.INFO, "Found existing node, starting VM {0}",
                                                        agentNode.getNodeName());

                                                try {
                                                    getServiceDelegate().startVirtualMachine(agentNode);
                                                    // set virtual machine details again
                                                    getServiceDelegate().setVirtualMachineDetails(
                                                            agentNode, template);
                                                    Jenkins.get().addNode(agentNode);
                                                    if (agentNode.getAgentLaunchMethod().equalsIgnoreCase("SSH")) {
                                                        retrySshConnect(azureComputer);
                                                    } else { // Wait until node is online
                                                        waitUntilJNLPNodeIsOnline(agentNode);
                                                    }
                                                    LOGGER.info(String.format("Remove suspended status for node: %s",
                                                            agentNode.getNodeName()));
                                                    azureComputer.setAcceptingTasks(true);
                                                    agentNode.clearCleanUpAction();
                                                    agentNode.setEligibleForReuse(false);
                                                } catch (Exception e) {
                                                    throw AzureCloudException.create(e);
                                                }
                                                template.getTemplateProvisionStrategy().success();
                                                return agentNode;
                                            }
                                        } finally {
                                            releaseLockForAgent(agentNode);
                                        }
                                    }), template.getNoOfParallelJobs()));
                        }
                    } catch (Exception e) {
                        // Couldn't bring the node back online.  Mark it as needing deletion
                        LOGGER.log(Level.WARNING, String.format("Failed to reuse agent computer %s",
                                agentComputer.getName()), e);
                        azureComputer.setAcceptingTasks(false);
                        agentNode.setCleanUpAction(CleanUpAction.DEFAULT,
                                Messages._Shutdown_Agent_Failed_To_Revive());
                    }
                }
            }
        }

        // provision new nodes if required
        if (numberOfAgents > 0) {
            if (template.getMaximumDeploymentSize() > 0 && numberOfAgents > template.getMaximumDeploymentSize()) {
                LOGGER.log(Level.FINE,
                        "Reduced template {0} deployment from {1} to {2} nodes, due to its maximumDeploymentSize",
                        new Object[] {template.getTemplateName(), numberOfAgents,
                                template.getMaximumDeploymentSize() });
                numberOfAgents = template.getMaximumDeploymentSize();
            }

            try {
                // Determine how many agents we can actually provision from here and
                // adjust our count (before deployment to avoid races)
                final int adjustedNumberOfAgents;
                synchronized (this) {
                    adjustedNumberOfAgents = calculateNumberOfAgentsToRequest(template, numberOfAgents);
                    adjustApproximateVirtualMachineCount(adjustedNumberOfAgents, template);
                }
                if (adjustedNumberOfAgents == 0) {
                    return plannedNodes;
                }
                doProvision(adjustedNumberOfAgents, plannedNodes, template);
                // wait for deployment completion and then check for created nodes
            } catch (Exception e) {
                LOGGER.log(
                        Level.SEVERE,
                        String.format("Failure provisioning agents about '%s'", template.getLabels()),
                        e);
            }
        }

        LOGGER.log(Level.INFO, "{0} planned node(s)", plannedNodes.size());
        return plannedNodes;
    }

    /**
     * Works out how many VMs our limits allow us to request.
     *
     * @param template              The template in question
     * @param desiredNumberOfAgents The number of VMs we'd like to have if there
     *                              were no limits.
     * @return The number we can request before exceeding our cloud and template
     *         limits.
     */
    @Restricted(NoExternalUse.class) // Package access for tests only
    int calculateNumberOfAgentsToRequest(final AzureVMAgentTemplate template, int desiredNumberOfAgents) {
        final int currentVMsForTemplate = Math.max(0, getApproximateVirtualMachineCountForTemplate(template));
        final int maxVMsForTemplate = template.getMaxVirtualMachinesLimit() > 0
                ? template.getMaxVirtualMachinesLimit()
                : Integer.MAX_VALUE;
        final int currentVMsForCloud = Math.max(0, getApproximateVirtualMachineCount());
        final int maxVMsForCloud = getMaxVirtualMachinesLimit() > 0 ? getMaxVirtualMachinesLimit()
                : Integer.MAX_VALUE;
        final int maxBeforeTemplateLimit = Math.max(0, maxVMsForTemplate - currentVMsForTemplate);
        final int maxBeforeCloudLimit = Math.max(0, maxVMsForCloud - currentVMsForCloud);
        final int adjustedNumberOfAgents = Math.min(Math.min(maxBeforeTemplateLimit, maxBeforeCloudLimit),
                desiredNumberOfAgents);
        final String templateMsg = desiredNumberOfAgents > maxBeforeTemplateLimit
                ? ", have template limit of {3} but have {4} VMs already so we can have {5} more"
                : ", currently have {4} VMs of this template";
        final String cloudMsg = desiredNumberOfAgents > maxBeforeCloudLimit
                ? ", have cloud limit of {6} but have {7} VMs already so we can only have {8} more"
                : ", currently have {7} VMs in cloud";
        final Object[] logParams = new Object[] {template.getTemplateName(), desiredNumberOfAgents,
                adjustedNumberOfAgents, maxVMsForTemplate, currentVMsForTemplate, maxBeforeTemplateLimit,
                maxVMsForCloud, currentVMsForCloud, maxBeforeCloudLimit };
        if (adjustedNumberOfAgents == 0) {
            LOGGER.log(Level.INFO, "Wanted to create {1} nodes from template {0} but cannot create any"
                    + templateMsg + cloudMsg, logParams);
        } else if (adjustedNumberOfAgents < desiredNumberOfAgents) {
            LOGGER.log(Level.INFO, "Wanted to create {1} nodes from template {0} but can only create {2}"
                    + templateMsg + cloudMsg, logParams);
        } else {
            LOGGER.log(Level.INFO, "Creating {1} nodes from template {0}" + templateMsg + cloudMsg, logParams);
        }
        return adjustedNumberOfAgents;
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
                                AzureVMDeploymentInfo info;
                                try {
                                    info = deploymentFuture.get();
                                } catch (InterruptedException | ExecutionException e) {
                                    handleFailure(template, null, e, FailureStage.DEPLOYMENT);
                                    throw AzureCloudException.create(e);
                                }

                                final String deploymentName = info.getDeploymentName();
                                final String vmBaseName = info.getVmBaseName();
                                final String vmName = String.format("%s%d", vmBaseName, index);

                                AzureVMAgent agent;
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
                                    LOGGER.log(Level.INFO, "Adding agent {0} to Jenkins nodes",
                                            agent.getNodeName());
                                    // Place the node in blocked state while it starts.
                                    try {
                                        agent.blockCleanUpAction();
                                        Jenkins.get().addNode(agent);
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
                                        Jenkins.get().removeNode(agent);
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
                                template.getTemplateProvisionStrategy().success();
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
                            // Attempt to terminate whatever was created if any
                            if (vmName != null) {
                                try {
                                    getServiceDelegate().terminateVirtualMachine(
                                            vmName,
                                            template.getResourceGroupName(),
                                            template.getUsePrivateIP());
                                } catch (AzureCloudException terminateEx) {
                                    LOGGER.log(
                                            Level.SEVERE,
                                            String.format("Failure terminating previous failed agent '%s'", vmName),
                                            terminateEx);
                                    // Do not throw to avoid it being recorded
                                }
                            }
                            template.retrieveAzureCloudReference().adjustApproximateVirtualMachineCount(-1,
                                    template);
                            // Update the template status given this new issue.
                            template.handleTemplateProvisioningFailure(e.getMessage(), stage);
                        }
                    })));
        }
    }

    private void retrySshConnect(final AzureVMComputer azureComputer) throws ExecutionException, InterruptedException {
        int count = 0;
        while (true) {
            try {
                azureComputer.connect(false).get();
                return;
            } catch (InterruptedException | ExecutionException e) {
                if (count >= DEFAULT_SSH_CONNECT_RETRY_COUNT) {
                    throw e;
                }
                LOGGER.warning(String.format("Fail to connect %s with SSH for %s", azureComputer.getName(),
                        e.getMessage()));
                count++;
                TimeUnit.SECONDS.sleep(SHH_CONNECT_RETRY_INTERNAL_SECONDS);
            }
        }
    }

    /**
     * Wait till a node that connects through JNLP comes online and connects to
     * Jenkins.
     *
     * @param agent Node to wait for
     * @throws AzureCloudException If the wait time expires or other exception happens
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
            LOGGER.log(Level.INFO, "Azure Cloud: waitUntilOnline: node {0} is alive, result {1}",
            new Object[]{agent.getDisplayName(), result});
        } catch (Exception ex) {
            throw AzureCloudException.create(String.format("Azure Cloud: waitUntilOnline: "
                + "Failure waiting {0} till online", agent.getDisplayName()), ex);
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

    private Object getLockForAgent(AzureVMAgent agent) {
        synchronized (agentLocks) {
            AtomicInteger lockCount = agentLocks.computeIfAbsent(agent, (a) -> new AtomicInteger(0));
            lockCount.incrementAndGet();
            return lockCount;
        }
    }

    private void releaseLockForAgent(AzureVMAgent agent) {
        synchronized (agentLocks) {
            AtomicInteger lockCount = agentLocks.get(agent);
            if (lockCount != null && lockCount.decrementAndGet() == 0) {
                agentLocks.remove(agent);
            }
        }
    }

    public AzureResourceManager getAzureClient() {
        if (azureClient == null && credentialsId != null) {
            this.azureClient = AzureResourceManagerCache.get(credentialsId);
        }

        return azureClient;
    }

    public AzureVMManagementServiceDelegate getServiceDelegate() {
        return AzureVMManagementServiceDelegate.getInstance(getAzureClient(), credentialsId);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        private static final String LOG_RECORDER_NAME = "Azure VM Agent (Auto)";

        public String getLogRecorderName() {
            return LOG_RECORDER_NAME;
        }

        @Initializer(before = PLUGINS_STARTED)
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
            if (!h.hasPermission(Jenkins.ADMINISTER)) {
                return;
            }
            LogRecorderManager manager = h.getLog();
            LogRecorder recorder = new LogRecorder(LOG_RECORDER_NAME);
            List<LogRecorder> logRecorders = manager.getRecorders();
            if (!logRecorders.contains(recorder)) {
                String packageName = AzureVMAgent.class.getPackage().getName();
                recorder.getLoggers().add(new LogRecorder.Target(packageName, Level.WARNING));
                logRecorders.add(recorder);
                recorder.save();
            }
        }

        @Override @NonNull
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

        @RequirePOST
        public FormValidation doVerifyConfiguration(
                @QueryParameter String azureCredentialsId,
                @QueryParameter String deploymentTimeout,
                @QueryParameter String resourceGroupReferenceType,
                @QueryParameter String newResourceGroupName,
                @QueryParameter String existingResourceGroupName) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            try {
                String resourceGroupName = getResourceGroupName(
                        resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
                if (StringUtils.isBlank(resourceGroupName)) {
                    resourceGroupName = Constants.DEFAULT_RESOURCE_GROUP_NAME;
                }
                AzureResourceManager azureClient = AzureResourceManagerCache.get(azureCredentialsId);
                final String validationResult = AzureVMManagementServiceDelegate
                        .getInstance(azureClient, azureCredentialsId)
                        .verifyConfiguration(resourceGroupName, deploymentTimeout);
                if (!validationResult.equalsIgnoreCase(Constants.OP_SUCCESS)) {
                    return FormValidation.error(validationResult);
                }
                return FormValidation.ok(Messages.Azure_Config_Success());
            } catch (Exception e) {
                return FormValidation.error(e, "Failed to verify configuration");
            }
        }

        @POST
        public ListBoxModel doFillAzureCredentialsIdItems(@QueryParameter String azureCredentialsId) {
            StandardListBoxModel model = new StandardListBoxModel();
            Jenkins context = Jenkins.get();
            if (!context.hasPermission(Jenkins.ADMINISTER)) {
                return model.includeCurrentValue(azureCredentialsId);
            }
            return model
                    .includeAs(ACL.SYSTEM, context, AzureCredentials.class)
                    .includeAs(ACL.SYSTEM, context, AzureImdsCredentials.class);
        }

        @POST
        public ListBoxModel doFillExistingResourceGroupNameItems(@QueryParameter String azureCredentialsId) {
            Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);
            ListBoxModel model = new ListBoxModel();
            model.add("--- Select Resource Group ---", "");
            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }

            try {
                final AzureResourceManager azureClient = AzureResourceManagerCache.get(azureCredentialsId);
                Set<String> resourceGroupNames = new HashSet<>();
                for (ResourceGroup resourceGroup : azureClient.resourceGroups().list()) {
                   resourceGroupNames.add(resourceGroup.name());
                }
                resourceGroupNames.stream().sorted(String::compareToIgnoreCase).forEach(model::add);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Cannot list resource group name: ", e);
            }
            return model;
        }
    }
}
