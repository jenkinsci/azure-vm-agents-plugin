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

import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.resources.Deployment;
import com.microsoft.azure.management.resources.GenericResource;
import com.microsoft.azure.util.AzureCredentials.ServicePrincipal;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.retry.DefaultRetryStrategy;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.ExecutionEngine;
import com.microsoft.azure.vmagent.util.TokenCache;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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

        DeploymentInfo(String cloudName, String resourceGroupName, String deploymentName, int deleteAttempts) {
            this.cloudName = cloudName;
            this.deploymentName = deploymentName;
            this.resourceGroupName = resourceGroupName;
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

        boolean hasAttemptsRemaining() {
            return attemptsRemaining > 0;
        }

        void decrementAttemptsRemaining() {
            attemptsRemaining--;
        }

        private String cloudName;
        private String deploymentName;
        private String resourceGroupName;
        private int attemptsRemaining;
    }

    private static final int CLEAN_TIMEOUT_IN_MINUTES = 15;
    private static final int RECURRENCE_PERIOD_IN_MILLIS = 5 * MILLIS_IN_MINUTE;  // 5 minutes

    private static final long SUCCESFULL_DEPLOYMENT_TIMEOUT_IN_MINUTES = 60;
    private static final long FAILING_DEPLOYMENT_TIMEOUT_IN_MINUTES = 60 * 8;
    private static final int MAX_DELETE_ATTEMPTS = 3;
    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentCleanUpTask.class.getName());

    public AzureVMAgentCleanUpTask() {
        super("Azure VM Agents Clean Task");
    }

    public static final class DeploymentRegistrar {

        private static final String OUTPUT_FILE
                = Paths.get(loadProperty("JENKINS_HOME"), "deployment.out").toString();

        private static DeploymentRegistrar deploymentRegistrar = null;

        private ConcurrentLinkedQueue<DeploymentInfo> deploymentsToClean =
                new ConcurrentLinkedQueue<DeploymentInfo>();

        private DeploymentRegistrar() {
            ObjectInputStream ois = null;
            try {

                ois = new ObjectInputStream(new FileInputStream(OUTPUT_FILE));
                deploymentsToClean = (ConcurrentLinkedQueue<DeploymentInfo>) ois.readObject();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "AzureVMAgentCleanUpTask: readResolve: Cannot deserialize deploymentsToClean", e);
                deploymentsToClean = new ConcurrentLinkedQueue<DeploymentInfo>();
            } finally {
                IOUtils.closeQuietly(ois);
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
                                                    String deploymentName) {
            LOGGER.log(Level.INFO,
                    "AzureVMAgentCleanUpTask: registerDeployment: Registering deployment {0} in {1}",
                    new Object[]{deploymentName, resourceGroupName});
            DeploymentInfo newDeploymentToClean =
                    new DeploymentInfo(cloudName, resourceGroupName, deploymentName, MAX_DELETE_ATTEMPTS);
            deploymentsToClean.add(newDeploymentToClean);

            syncDeploymentsToClean();
        }

        public synchronized void syncDeploymentsToClean() {
            ObjectOutputStream oos = null;
            try {
                oos = new ObjectOutputStream(new FileOutputStream(OUTPUT_FILE));
                oos.writeObject(deploymentsToClean);
            } catch (FileNotFoundException e) {
                LOGGER.log(Level.WARNING,
                        "AzureVMAgentCleanUpTask: registerDeployment: Cannot open deployment output file"
                                + OUTPUT_FILE);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "AzureVMAgentCleanUpTask: registerDeployment: Serialize failed", e);
            } finally {
                IOUtils.closeQuietly(oos);
            }
        }

        public AzureUtil.DeploymentTag getDeploymentTag() {
            return new AzureUtil.DeploymentTag();
        }
    }

    public void cleanDeployments() {
        cleanDeployments(SUCCESFULL_DEPLOYMENT_TIMEOUT_IN_MINUTES, FAILING_DEPLOYMENT_TIMEOUT_IN_MINUTES);
    }

    public void cleanDeployments(long successTimeoutInMinutes, long failTimeoutInMinutes) {
        LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Cleaning deployments");
        // Walk the queue, popping and pushing until we reach an item that we've already
        // dealt with or the queue is empty.
        DeploymentInfo firstBackInQueue = null;
        ConcurrentLinkedQueue<DeploymentInfo> deploymentsToClean
                = DeploymentRegistrar.getInstance().getDeploymentsToClean();
        while (!deploymentsToClean.isEmpty() && firstBackInQueue != deploymentsToClean.peek()) {
            DeploymentInfo info = deploymentsToClean.remove();

            LOGGER.log(Level.INFO,
                    "AzureVMAgentCleanUpTask: cleanDeployments: Checking deployment {0}",
                    info.getDeploymentName());

            AzureVMCloud cloud = getCloud(info.getCloudName());

            if (cloud == null) {
                // Cloud could have been deleted, skip
                continue;
            }

            try {
                final Azure azureClient = TokenCache.getInstance(cloud.getServicePrincipal()).getAzureClient();

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
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanDeployments: Deployment not found, skipping");
                    continue;
                }
                if (deployment == null) {
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanDeployments: Deployment not found, skipping");
                    continue;
                }

                DateTime deploymentTime = deployment.timestamp();

                LOGGER.log(Level.INFO,
                        "AzureVMAgentCleanUpTask: cleanDeployments: Deployment created on {0}",
                        deploymentTime.toDate());
                long deploymentTimeInMillis = deploymentTime.getMillis();

                // Compare to now
                Calendar nowTime = Calendar.getInstance(deploymentTime.getZone().toTimeZone());
                long nowTimeInMillis = nowTime.getTimeInMillis();

                long diffTime = nowTimeInMillis - deploymentTimeInMillis;
                long diffTimeInMinutes = diffTime / MILLIS_IN_MINUTE;

                String state = deployment.provisioningState();

                if (!state.equalsIgnoreCase("succeeded") && diffTimeInMinutes > failTimeoutInMinutes) {
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanDeployments: "
                                    + "Failed deployment older than {0} minutes, deleting",
                            failTimeoutInMinutes);
                    // Delete the deployment
                    azureClient.deployments()
                            .deleteByResourceGroup(info.getResourceGroupName(), info.getDeploymentName());
                } else if (state.equalsIgnoreCase("succeeded")
                        && diffTimeInMinutes > successTimeoutInMinutes) {
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanDeployments: "
                                    + "Succesfull deployment older than {0} minutes, deleting",
                            successTimeoutInMinutes);
                    // Delete the deployment
                    azureClient.deployments()
                            .deleteByResourceGroup(info.getResourceGroupName(), info.getDeploymentName());
                } else {
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanDeployments: Deployment newer than timeout, keeping");

                    if (firstBackInQueue == null) {
                        firstBackInQueue = info;
                    }
                    // Put it back
                    deploymentsToClean.add(info);
                }
            } catch (Exception e) {
                LOGGER.log(Level.INFO,
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

        LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: cleanDeployments: Done cleaning deployments");
    }

    /* There are some edge-cases where we might loose track of the provisioned resources:
        1. the process stops right after we start provisioning
        2. some Azure error blocks us from deleting the resource
       This method will look into the resource group and remove all resources that have our tag
       and are not accounted for.
    */
    public void cleanLeakedResources() {
        Jenkins instance = Jenkins.getInstance();
        if (instance == null) {
            return;
        }
        for (AzureVMCloud cloud : instance.clouds.getAll(AzureVMCloud.class)) {
            cleanLeakedResources(
                    cloud.getResourceGroupName(),
                    cloud.getServicePrincipal(),
                    cloud.name,
                    DeploymentRegistrar.getInstance());
        }
    }

    public List<String> getValidVMs() {
        List<String> vms = new ArrayList<>();
        Jenkins instance = Jenkins.getInstance();
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
            String resourceGroup,
            ServicePrincipal servicePrincipal,
            String cloudName,
            DeploymentRegistrar deploymentRegistrar) {
        try {
            final List<String> validVMs = getValidVMs();
            final Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();
            // can't use listByTag because for some reason that method strips all the tags from the outputted resources
            // (https://github.com/Azure/azure-sdk-for-java/issues/1436)
            final PagedList<GenericResource> resources = azureClient.genericResources()
                    .listByResourceGroup(resourceGroup);

            if (resources == null || resources.isEmpty()) {
                return;
            }

            final PriorityQueue<GenericResource> resourcesMarkedForDeletion = new PriorityQueue<>(resources.size(),
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

            while (!resourcesMarkedForDeletion.isEmpty()) {
                try {
                    final GenericResource resource = resourcesMarkedForDeletion.poll();
                    if (resource == null) {
                        continue;
                    }

                    URI osDiskURI = null;
                    String managedOsDiskId = null;
                    if (StringUtils.containsIgnoreCase(resource.type(), "virtualMachine")) {
                        if (!azureClient.virtualMachines().getById(resource.id()).isManagedDiskEnabled()) {
                            osDiskURI = new URI(
                                    azureClient.virtualMachines().getById(resource.id()).osUnmanagedDiskVhdUri());
                        } else {
                            managedOsDiskId = azureClient.virtualMachines().getById(resource.id()).osDiskId();
                        }
                    }

                    LOGGER.log(Level.INFO,
                            "cleanLeakedResources: deleting {0} from resource group {1}",
                            new Object[]{resource.name(), resourceGroup});
                    azureClient.genericResources().deleteById(resource.id());
                    if (osDiskURI != null) {
                        AzureVMManagementServiceDelegate.removeStorageBlob(azureClient, osDiskURI, resourceGroup);
                    }
                    if (managedOsDiskId != null) {
                        azureClient.disks().deleteById(managedOsDiskId);
                        AzureVMManagementServiceDelegate.removeImage(azureClient, resource.name(), resourceGroup);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanLeakedResources: failed to clean resource ",
                            e);
                }
            }
        } catch (Exception e) {
            // No need to throw exception back, just log and move on.
            LOGGER.log(Level.INFO,
                    "AzureVMAgentCleanUpTask: cleanLeakedResources: failed to clean leaked resources ",
                    e);
        }
    }

    private void cleanVMs() {
        cleanVMs(new ExecutionEngine());
    }

    private void cleanVMs(ExecutionEngine executionEngine) {
        for (Computer computer : Jenkins.getInstance().getComputers()) {
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
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanVMs: node {0} was set offline by user, skipping",
                            agentNode.getDisplayName());
                    continue;
                }

                // If the machine is in "keep" state, skip
                if (agentNode.isCleanUpBlocked()) {
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanVMs: node {0} blocked to cleanup",
                            agentNode.getDisplayName());
                    continue;
                }

                // Check if the virtual machine exists.  If not, it could have been
                // deleted in the background.  Remove from Jenkins if that is the case.
                if (!AzureVMManagementServiceDelegate.virtualMachineExists(agentNode)) {
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanVMs: node {0} doesn't exist, removing",
                            agentNode.getDisplayName());
                    try {
                        Jenkins.getInstance().removeNode(agentNode);
                    } catch (IOException e) {
                        LOGGER.log(Level.INFO,
                                "AzureVMAgentCleanUpTask: cleanVMs: node {0} could not be removed: {1}",
                                new Object[]{agentNode.getDisplayName(), e.getMessage()});
                    }
                    continue;
                }

                // Machine exists but is in either DELETE or SHUTDOWN state.
                // Execute that action.
                Callable<Void> task = new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        // Depending on the cleanup action, run the appropriate
                        if (agentNode.getCleanUpAction() == CleanUpAction.DELETE) {
                            LOGGER.log(Level.INFO,
                                    "AzureVMAgentCleanUpTask: cleanVMs: deleting {0}",
                                    agentNode.getDisplayName());
                            agentNode.deprovision(agentNode.getCleanUpReason());
                        } else if (agentNode.getCleanUpAction() == CleanUpAction.SHUTDOWN) {
                            LOGGER.log(Level.INFO,
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
                    }
                };

                try {
                    final int maxRetries = 3;
                    final int waitIntervanl = 10;
                    final int defaultTimeOutInSeconds = 30 * 60;
                    executionEngine.executeAsync(task, new DefaultRetryStrategy(
                            maxRetries,
                            waitIntervanl,
                            defaultTimeOutInSeconds
                    ));
                } catch (AzureCloudException exception) {
                    // No need to throw exception back, just log and move on.
                    LOGGER.log(Level.INFO,
                            "AzureVMAgentCleanUpTask: cleanVMs: failed to shutdown/delete "
                                    + agentNode.getDisplayName(),
                            exception);
                    // In case the node had a non-delete cleanup action before,
                    // set the cleanup action to delete
                    agentNode.setCleanUpAction(CleanUpAction.DELETE, Messages._Failed_Initial_Shutdown_Or_Delete());
                }
            }
        }
    }

    public AzureVMCloud getCloud(String cloudName) {
        return Jenkins.getInstance() == null ? null : (AzureVMCloud) Jenkins.getInstance().getCloud(cloudName);
    }

    private void clean() {
        cleanVMs();
        // Clean up the deployments
        cleanDeployments();

        cleanLeakedResources();
    }

    @Override
    public void execute(TaskListener arg0) throws InterruptedException {
        LOGGER.log(Level.INFO, "AzureVMAgentCleanUpTask: execute: start");

        Callable<Void> callClean = new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                clean();
                return null;
            }
        };

        Future<Void> result = AzureVMCloud.getThreadPool().submit(callClean);

        try {
            LOGGER.info("AzureVMAgentCleanUpTask: execute: Running clean with 15 minute timeout");
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

        LOGGER.info("AzureVMAgentCleanUpTask: execute: end");
    }

    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD_IN_MILLIS;
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
