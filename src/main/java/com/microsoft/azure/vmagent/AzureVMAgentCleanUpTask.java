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
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.resources.models.Deployment;
import com.azure.resourcemanager.resources.models.GenericResource;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.retry.DefaultRetryStrategy;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.ExecutionEngine;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URI;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.microsoft.azure.vmagent.util.Constants.MILLIS_IN_MINUTE;

@Extension
public class AzureVMAgentCleanUpTask extends AsyncPeriodicWork {

    private static class DeploymentInfo implements Serializable {
        private static final long serialVersionUID = 888154365;

        DeploymentInfo(String cloudName,
                       String resourceGroupName,
                       String deploymentName,
                       String scriptUri,
                       int deleteAttempts) {
            this.cloudName = cloudName;
            this.deploymentName = deploymentName;
            this.resourceGroupName = resourceGroupName;
            this.scriptUri = scriptUri;
            this.attemptsRemaining = deleteAttempts;
        }

        String getCloudName() {
            return cloudName;
        }

        String getDeploymentName() {
            return deploymentName;
        }

        String getResourceGroupName() {
            return resourceGroupName;
        }

        String getScriptUri() {
            return scriptUri;
        }

        boolean hasAttemptsRemaining() {
            return attemptsRemaining > 0;
        }

        void decrementAttemptsRemaining() {
            attemptsRemaining--;
        }

        private String cloudName;
        private String deploymentName;
        private String resourceGroupName;
        private String scriptUri;
        private int attemptsRemaining;
    }

    private static final int CLEAN_TIMEOUT_IN_MINUTES = 15;
    private static final int RECURRENCE_PERIOD_IN_MILLIS = 5 * MILLIS_IN_MINUTE;  // 5 minutes

    private static final long SUCCESSFUL_DEPLOYMENT_TIMEOUT_IN_MINUTES = 60;
    private static final long FAILING_DEPLOYMENT_TIMEOUT_IN_MINUTES = 60 * 8;
    private static final int MAX_DELETE_ATTEMPTS = 3;
    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentCleanUpTask.class.getName());

    public AzureVMAgentCleanUpTask() {
        super("Azure VM Agents Clean Task");
    }

    public static class DeploymentRegistrar {

        private static final String OUTPUT_FILE
                = Paths.get(loadProperty("JENKINS_HOME"), "deployment.out").toString();

        private static DeploymentRegistrar deploymentRegistrar = null;

        private ConcurrentLinkedQueue<DeploymentInfo> deploymentsToClean;

        protected DeploymentRegistrar() {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(OUTPUT_FILE));) {
                deploymentsToClean = (ConcurrentLinkedQueue<DeploymentInfo>) ois.readObject();
            } catch (FileNotFoundException e) {
                LOGGER.log(Level.WARNING,
                        "AzureVMAgentCleanUpTask: readResolve: Cannot open deployment output file");
                deploymentsToClean = new ConcurrentLinkedQueue<>();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "AzureVMAgentCleanUpTask: readResolve: Cannot deserialize deploymentsToClean", e);
                deploymentsToClean = new ConcurrentLinkedQueue<>();
            }
        }

        public static synchronized DeploymentRegistrar getInstance() {
            if (deploymentRegistrar == null) {
                deploymentRegistrar = new DeploymentRegistrar();
            }
            return deploymentRegistrar;
        }

        public ConcurrentLinkedQueue<DeploymentInfo> getDeploymentsToClean() {
            return deploymentsToClean;
        }

        public void registerDeployment(String cloudName,
                                       String resourceGroupName,
                                       String deploymentName,
                                       String scriptUri) {
            LOGGER.log(Level.INFO,
                    "AzureVMAgentCleanUpTask: registerDeployment: Registering deployment {0} in {1}",
                    new Object[]{deploymentName, resourceGroupName});
            DeploymentInfo newDeploymentToClean =
                    new DeploymentInfo(cloudName, resourceGroupName, deploymentName, scriptUri, MAX_DELETE_ATTEMPTS);
            deploymentsToClean.add(newDeploymentToClean);

            syncDeploymentsToClean();
        }

        public synchronized void syncDeploymentsToClean() {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(OUTPUT_FILE));) {
                oos.writeObject(deploymentsToClean);
            } catch (FileNotFoundException e) {
                LOGGER.log(Level.WARNING,
                        "AzureVMAgentCleanUpTask: registerDeployment: Cannot open deployment output file"
                                + OUTPUT_FILE);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "AzureVMAgentCleanUpTask: registerDeployment: Serialize failed", e);
            }
        }

        public AzureUtil.DeploymentTag getDeploymentTag() {
            return new AzureUtil.DeploymentTag();
        }
    }

    public void cleanDeployments() {
        cleanDeployments(SUCCESSFUL_DEPLOYMENT_TIMEOUT_IN_MINUTES, FAILING_DEPLOYMENT_TIMEOUT_IN_MINUTES);
    }

    public void cleanDeployments(long successTimeoutInMinutes, long failTimeoutInMinutes) {
        LOGGER.log(getNormalLoggingLevel(), "AzureVMAgentCleanUpTask: cleanDeployments: Cleaning deployments");
        // Walk the queue, popping and pushing until we reach an item that we've already
        // dealt with or the queue is empty.
        DeploymentInfo firstBackInQueue = null;
        ConcurrentLinkedQueue<DeploymentInfo> deploymentsToClean
                = DeploymentRegistrar.getInstance().getDeploymentsToClean();
        while (!deploymentsToClean.isEmpty() && firstBackInQueue != deploymentsToClean.peek()) {
            DeploymentInfo info = deploymentsToClean.remove();

            LOGGER.log(getNormalLoggingLevel(),
                    "AzureVMAgentCleanUpTask: cleanDeployments: Checking deployment {0}",
                    info.getDeploymentName());

            AzureVMCloud cloud = getCloud(info.getCloudName());

            if (cloud == null) {
                // Cloud could have been deleted, skip
                continue;
            }

            try {
                final AzureResourceManager azureClient = cloud.getAzureClient();
                final AzureVMManagementServiceDelegate delegate = cloud.getServiceDelegate();

                // This will throw if the deployment can't be found.  This could happen in a couple instances
                // 1) The deployment has already been deleted
                // 2) The deployment doesn't exist yet (race between creating the deployment and it
                //    being accepted by Azure.
                // To avoid this, we implement a retry.  If we hit an exception, we will decrement the number
                // of retries.  If we hit 0, we remove the deployment from our list.
                Deployment deployment;
                try {
                    deployment = azureClient.deployments().
                            getByResourceGroup(info.getResourceGroupName(), info.getDeploymentName());
                } catch (NullPointerException e) {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureVMAgentCleanUpTask: cleanDeployments: Deployment not found, skipping");
                    continue;
                }
                if (deployment == null) {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureVMAgentCleanUpTask: cleanDeployments: Deployment not found, skipping");
                    continue;
                }

                OffsetDateTime deploymentTime = deployment.timestamp();

                LOGGER.log(getNormalLoggingLevel(),
                        "AzureVMAgentCleanUpTask: cleanDeployments: Deployment created on {0}",
                        deploymentTime.toString());
                long diffTimeInMinutes = ChronoUnit.MINUTES
                        .between(deploymentTime, OffsetDateTime.now());

                String state = deployment.provisioningState();

                if (!state.equalsIgnoreCase("succeeded") && diffTimeInMinutes > failTimeoutInMinutes) {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureVMAgentCleanUpTask: cleanDeployments: "
                                    + "Failed deployment older than {0} minutes, deleting",
                            failTimeoutInMinutes);
                    // Delete the deployment
                    azureClient.deployments()
                            .deleteByResourceGroup(info.getResourceGroupName(), info.getDeploymentName());
                    if (StringUtils.isNotBlank(info.scriptUri)) {
                        delegate.removeStorageBlob(new URI(info.scriptUri), info.getResourceGroupName());
                    }
                } else if (state.equalsIgnoreCase("succeeded")
                        && diffTimeInMinutes > successTimeoutInMinutes) {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureVMAgentCleanUpTask: cleanDeployments: "
                                    + "Successful deployment older than {0} minutes, deleting",
                            successTimeoutInMinutes);
                    // Delete the deployment
                    azureClient.deployments()
                            .deleteByResourceGroup(info.getResourceGroupName(), info.getDeploymentName());
                    if (StringUtils.isNotBlank(info.scriptUri)) {
                        delegate.removeStorageBlob(new URI(info.scriptUri), info.getResourceGroupName());
                    }
                } else {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureVMAgentCleanUpTask: cleanDeployments: Deployment newer than timeout, keeping");

                    if (firstBackInQueue == null) {
                        firstBackInQueue = info;
                    }
                    // Put it back
                    deploymentsToClean.add(info);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "AzureVMAgentCleanUpTask: cleanDeployments: Failed to get/delete deployment: {0}",
                        e);
                // Check the number of attempts remaining. If greater than 0, decrement
                // and add back into the queue.
                if (info.hasAttemptsRemaining()) {
                    info.decrementAttemptsRemaining();

                    if (firstBackInQueue == null) {
                        firstBackInQueue = info;
                    }

                    // Put it back in the queue for another attempt
                    deploymentsToClean.add(info);
                }
            }
        }
        DeploymentRegistrar.getInstance().syncDeploymentsToClean();

        LOGGER.log(getNormalLoggingLevel(), "AzureVMAgentCleanUpTask: cleanDeployments: Done cleaning deployments");
    }

    /* There are some edge-cases where we might loose track of the provisioned resources:
        1. the process stops right after we start provisioning
        2. some Azure error blocks us from deleting the resource
       This method will look into the resource group and remove all resources that have our tag
       and are not accounted for.
    */
    public void cleanLeakedResources() {
        Jenkins instance = Jenkins.getInstanceOrNull();
        LOGGER.log(getNormalLoggingLevel(), "AzureVMAgentCleanUpTask: cleanLeakedResources: beginning");
        if (instance == null) {
            LOGGER.log(getNormalLoggingLevel(), "AzureVMAgentCleanUpTask: cleanLeakedResources: skipped as no"
                    + " Jenkins instance");
            return;
        }
        for (AzureVMCloud cloud : instance.clouds.getAll(AzureVMCloud.class)) {
            cleanLeakedResources(cloud, cloud.getResourceGroupName(), DeploymentRegistrar.getInstance());
        }
        LOGGER.log(getNormalLoggingLevel(), "AzureVMAgentCleanUpTask: cleanLeakedResources: completed");
    }

    public List<String> getValidVMs() {
        List<String> vms = new ArrayList<>();
        Jenkins instance = Jenkins.getInstanceOrNull();
        if (instance != null) {
            for (Computer computer : instance.getComputers()) {
                if (computer instanceof AzureVMComputer) {
                        vms.add(computer.getName());
                }
            }
        }

        return vms;
    }

    public void cleanLeakedResources(
            AzureVMCloud cloud,
            String resourceGroup,
            DeploymentRegistrar deploymentRegistrar) {
        try {
            final List<String> validVMs = getValidVMs();
            final AzureResourceManager azureClient = cloud.getAzureClient();
            final AzureVMManagementServiceDelegate serviceDelegate = cloud.getServiceDelegate();
            // can't use listByTag because for some reason that method strips all the tags from the outputted resources
            // (https://github.com/Azure/azure-sdk-for-java/issues/1436)
            final PagedIterable<GenericResource> resources = azureClient.genericResources()
                    .listByResourceGroup(resourceGroup);


            if (resources == null || !resources.iterator().hasNext()) {
                LOGGER.log(getNormalLoggingLevel(), "cleanLeakedResources: No resources found in rg: "
                    + resourceGroup);
                return;
            }

            final PriorityQueue<GenericResource> resourcesMarkedForDeletion = new PriorityQueue<>(10,
                    new Comparator<GenericResource>() {
                        @Override
                        public int compare(GenericResource o1, GenericResource o2) {
                            int o1Priority = getPriority(o1);
                            int o2Priority = getPriority(o2);
                            if (o1Priority == o2Priority) {
                                return 0;
                            }
                            return (o1Priority < o2Priority) ? -1 : 1;
                        }

                        private int getPriority(GenericResource resource) {
                            //suppress magic number check
                            //CHECKSTYLE:OFF
                            final String type = resource.type();
                            if (StringUtils.containsIgnoreCase(type, "virtualMachine")) {
                                return 1;
                            }
                            if (StringUtils.containsIgnoreCase(type, "networkInterface")) {
                                return 2;
                            }
                            if (StringUtils.containsIgnoreCase(type, "IPAddress")) {
                                return 3;
                            }
                            return 4;
                            //CHECKSTYLE:ON
                        }
                    });

            LOGGER.log(getNormalLoggingLevel(), String.format("cleanLeakedResources: beginning to look at leaked "
                + "resources in rg: %s", resourceGroup));
            for (GenericResource resource : resources) {
                final Map<String, String> tags = resource.tags();
                if (!tags.containsKey(Constants.AZURE_RESOURCES_TAG_NAME)
                        || !deploymentRegistrar.getDeploymentTag().matches(
                        new AzureUtil.DeploymentTag(tags.get(Constants.AZURE_RESOURCES_TAG_NAME)))) {
                    continue;
                }
                boolean shouldSkipDeletion = false;
                for (String validVM : validVMs) {
                    if (resource.name().contains(validVM)) {
                        shouldSkipDeletion = true;
                        break;
                    }
                }
                // we're not removing storage accounts of networks - someone else might be using them
                if (shouldSkipDeletion
                        || StringUtils.containsIgnoreCase(resource.type(), "StorageAccounts")
                        || StringUtils.containsIgnoreCase(resource.type(), "virtualNetworks")) {
                    continue;
                }
                resourcesMarkedForDeletion.add(resource);
            }

            LOGGER.log(getNormalLoggingLevel(), "cleanLeakedResources: %d resources marked for deletion"
                + resourcesMarkedForDeletion.size());

            while (!resourcesMarkedForDeletion.isEmpty()) {
                try {
                    final GenericResource resource = resourcesMarkedForDeletion.poll();
                    if (resource == null) {
                        LOGGER.log(getNormalLoggingLevel(), "cleanLeakedResources: resource was null continuing");
                        continue;
                    }
                    LOGGER.log(getNormalLoggingLevel(),
                        "cleanLeakedResources: looking at {0} from resource group {1}",
                        new Object[]{resource.name(), resourceGroup});

                    URI osDiskURI = null;
                    String managedOsDiskId = null;
                    if (StringUtils.containsIgnoreCase(resource.type(), "virtualMachine")) {
                        LOGGER.log(getNormalLoggingLevel(),
                            "cleanLeakedResources: retrieving VM {0} from resource group {1}",
                            new Object[]{resource.name(), resourceGroup});
                        VirtualMachine virtualMachine = azureClient.virtualMachines().getById(resource.id());
                        if (!virtualMachine.isManagedDiskEnabled()) {
                            osDiskURI = new URI(virtualMachine.osUnmanagedDiskVhdUri());
                        } else {
                            managedOsDiskId = virtualMachine.osDiskId();
                        }
                        LOGGER.log(getNormalLoggingLevel(),
                            "cleanLeakedResources: completed retrieving VM {0} from resource group {1}",
                            new Object[]{resource.name(), resourceGroup});
                    }

                    LOGGER.log(getNormalLoggingLevel(),
                            "cleanLeakedResources: deleting {0} from resource group {1}",
                            new Object[]{resource.name(), resourceGroup});
                    azureClient.genericResources().deleteById(resource.id());
                    if (osDiskURI != null) {
                        serviceDelegate.removeStorageBlob(osDiskURI, resourceGroup);
                    }
                    if (managedOsDiskId != null) {
                        azureClient.disks().deleteById(managedOsDiskId);
                        serviceDelegate.removeImage(azureClient, resource.name(), resourceGroup);
                    }
                    LOGGER.log(getNormalLoggingLevel(),
                        "cleanLeakedResources: deleted {0} from resource group {1}",
                        new Object[]{resource.name(), resourceGroup});
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING,
                            "AzureVMAgentCleanUpTask: cleanLeakedResources: failed to clean resource ",
                            e);
                }
            }
        } catch (Exception e) {
            // No need to throw exception back, just log and move on.
            LOGGER.log(Level.WARNING,
                    "AzureVMAgentCleanUpTask: cleanLeakedResources: failed to clean leaked resources ",
                    e);
        }
    }

    private void cleanVMs() {
        cleanVMs(new ExecutionEngine());
    }

    private void cleanVMs(ExecutionEngine executionEngine) {
        LOGGER.log(getNormalLoggingLevel(), "AzureVMAgentCleanUpTask: cleanVMs: beginning");
        for (Computer computer : Jenkins.get().getComputers()) {
            if (computer instanceof AzureVMComputer) {
                AzureVMComputer azureComputer = (AzureVMComputer) computer;
                final AzureVMAgent agentNode = azureComputer.getNode();

                // If the machine is not offline, then don't do anything.
                if (!azureComputer.isOffline()) {
                    continue;
                }

                // If the machine is not idle, don't do anything.
                // Could have been taken offline by the plugin while still running
                // builds.
                if (!azureComputer.isIdle()) {
                    continue;
                }

                // Even if offline, a machine that has been temporarily marked offline
                // should stay (this could be for investigation).
                if (azureComputer.isSetOfflineByUser()) {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureVMAgentCleanUpTask: cleanVMs: node {0} was set offline by user, skipping",
                            agentNode.getDisplayName());
                    continue;
                }

                // If the machine is in "keep" state, skip
                if (agentNode.isCleanUpBlocked()) {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureVMAgentCleanUpTask: cleanVMs: node {0} blocked to cleanup",
                            agentNode.getDisplayName());
                    continue;
                }

                // Check if the virtual machine exists.  If not, it could have been
                // deleted in the background.  Remove from Jenkins if that is the case.
                if (!AzureVMManagementServiceDelegate.virtualMachineExists(agentNode)) {
                    LOGGER.log(getNormalLoggingLevel(),
                            "AzureVMAgentCleanUpTask: cleanVMs: node {0} doesn't exist, removing",
                            agentNode.getDisplayName());
                    try {
                        Jenkins.get().removeNode(agentNode);
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING,
                                "AzureVMAgentCleanUpTask: cleanVMs: node {0} could not be removed: {1}",
                                new Object[]{agentNode.getDisplayName(), e.getMessage()});
                    }
                    continue;
                }

                // Machine exists but is in either DELETE or SHUTDOWN state.
                // Execute that action.
                Callable<Void> task = () -> {
                    // Depending on the cleanup action, run the appropriate
                    if (agentNode.getCleanUpAction() == CleanUpAction.DELETE) {
                        LOGGER.log(getNormalLoggingLevel(),
                                "AzureVMAgentCleanUpTask: cleanVMs: deleting {0}",
                                agentNode.getDisplayName());
                        agentNode.deprovision(agentNode.getCleanUpReason());
                    } else if (agentNode.getCleanUpAction() == CleanUpAction.SHUTDOWN) {
                        LOGGER.log(getNormalLoggingLevel(),
                                "AzureVMAgentCleanUpTask: cleanVMs: shutting down {0}",
                                agentNode.getDisplayName());
                        agentNode.shutdown(agentNode.getCleanUpReason());
                        // We shut down the agent properly.  Mark the agent
                        // as "KEEP" so that it doesn't get deleted.
                        agentNode.blockCleanUpAction();
                    } else {
                        throw new IllegalStateException("Unknown cleanup action");
                    }
                    return null;
                };

                try {
                    final int maxRetries = 3;
                    final int waitInterval = 10;
                    final int defaultTimeOutInSeconds = 30 * 60;
                    executionEngine.executeAsync(task, new DefaultRetryStrategy(
                            maxRetries,
                            waitInterval,
                            defaultTimeOutInSeconds
                    ));
                } catch (AzureCloudException exception) {
                    // No need to throw exception back, just log and move on.
                    LOGGER.log(Level.WARNING,
                            "AzureVMAgentCleanUpTask: cleanVMs: failed to shutdown/delete "
                                    + agentNode.getDisplayName(),
                            exception);
                    // In case the node had a non-delete cleanup action before,
                    // set the cleanup action to delete
                    agentNode.setCleanUpAction(CleanUpAction.DELETE, Messages._Failed_Initial_Shutdown_Or_Delete());
                }
            }
        }
        LOGGER.log(getNormalLoggingLevel(), "AzureVMAgentCleanUpTask: cleanVMs: completed");
    }

    public void cleanCloudStatistics() {
        Jenkins jenkins = Jenkins.get();
        LOGGER.log(getNormalLoggingLevel(), "AzureVMAgentCleanUpTask: cleanCloudStatistics: beginning");

        Set<ProvisioningActivity.Id> plannedNodesSet = new HashSet<>();
        for (NodeProvisioner.PlannedNode node : jenkins.unlabeledNodeProvisioner.getPendingLaunches()) {
            if (node instanceof TrackedItem) {
                plannedNodesSet.add(((TrackedItem) node).getId());
            }
        }
        for (Label l : jenkins.getLabels()) {
            for (NodeProvisioner.PlannedNode node : l.nodeProvisioner.getPendingLaunches()) {
                if (node instanceof TrackedItem) {
                    plannedNodesSet.add(((TrackedItem) node).getId());
                }
            }
        }

        for (Node node : jenkins.getNodes()) {
            if (node instanceof TrackedItem) {
                plannedNodesSet.add(((TrackedItem) node).getId());
            }
        }

        Collection<ProvisioningActivity> activities = CloudStatistics.get().getNotCompletedActivities();
        for (ProvisioningActivity activity : activities) {
            if (activity.getCurrentPhase().equals(ProvisioningActivity.Phase.PROVISIONING)
                    && !plannedNodesSet.contains(activity.getId())) {
                Exception e = new Exception(String.format("Node %s has lost. Mark as failure",
                        activity.getId()));
                CloudStatistics.ProvisioningListener.get().onFailure(activity.getId(), e);
            }
        }
        LOGGER.log(getNormalLoggingLevel(), "AzureVMAgentCleanUpTask: cleanCloudStatistics: completed");
    }

    public AzureVMCloud getCloud(String cloudName) {
        return Jenkins.getInstanceOrNull() == null ? null : (AzureVMCloud) Jenkins.get().getCloud(cloudName);
    }

    private void clean() {
        cleanVMs();
        // Clean up the deployments
        cleanDeployments();

        cleanLeakedResources();

        cleanCloudStatistics();
    }

    @Override
    public void execute(TaskListener arg0) throws InterruptedException {
        LOGGER.log(getNormalLoggingLevel(), "AzureVMAgentCleanUpTask: execute: start");

        Callable<Void> callClean = () -> {
            clean();
            return null;
        };

        Future<Void> result = AzureVMCloud.getThreadPool().submit(callClean);

        try {
            LOGGER.log(getNormalLoggingLevel(), String.format("AzureVMAgentCleanUpTask: execute: Running clean with %s"
                    + " minute timeout", CLEAN_TIMEOUT_IN_MINUTES));
            // Get will block until time expires or until task completes
            result.get(CLEAN_TIMEOUT_IN_MINUTES, TimeUnit.MINUTES);
        } catch (ExecutionException executionException) {
            LOGGER.log(Level.SEVERE,
                    "AzureVMAgentCleanUpTask: execute: Got execution exception while cleaning",
                    executionException);
        } catch (TimeoutException timeoutException) {
            LOGGER.log(Level.SEVERE,
                    "AzureVMAgentCleanUpTask: execute: Hit timeout while cleaning",
                    timeoutException);
        } catch (Exception others) {
            LOGGER.log(Level.SEVERE,
                    "AzureVMAgentCleanUpTask: execute: Hit other exception while cleaning",
                    others);
        }

        LOGGER.log(getNormalLoggingLevel(), "AzureVMAgentCleanUpTask: execute: end");
    }

    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD_IN_MILLIS;
    }

    @Override
    protected Level getNormalLoggingLevel() {
        return Level.FINE;
    }

    public static String loadProperty(final String name) {
        final String value = System.getProperty(name);
        if (StringUtils.isBlank(value)) {
            return loadEnv(name);
        }
        return value;
    }

    public static String loadEnv(final String name) {
        final String value = System.getenv(name);
        if (StringUtils.isBlank(value)) {
            return "";
        }
        return value;
    }
}
