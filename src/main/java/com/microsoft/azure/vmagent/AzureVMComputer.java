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

import com.azure.resourcemanager.AzureResourceManager;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.retry.NoRetryStrategy;
import com.microsoft.azure.vmagent.util.ExecutionEngine;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;

import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AzureVMComputer extends AbstractCloudComputer<AzureVMAgent> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(AzureVMComputer.class.getName());

    private final ProvisioningActivity.Id provisioningId;

    public AzureVMComputer(AzureVMAgent agent) {
        super(agent);
        this.provisioningId = agent.getId();
    }

    @Override
    public HttpResponse doDoDelete() {
        return doDoDelete(new ExecutionEngine());
    }

    protected HttpResponse doDoDelete(ExecutionEngine executionEngine) {
        checkPermission(DELETE);
        this.setAcceptingTasks(false);
        final AzureVMAgent agent = getNode();

        if (agent != null) {
            Callable<Void> task = () -> {
                LOGGER.log(Level.INFO, "AzureVMComputer: doDoDelete called for agent {0}", agent.getNodeName());
                try {
                    // Deprovision
                    agent.deprovision(Messages._User_Delete());
                } catch (Exception e) {
                    LOGGER.log(Level.INFO,
                        "AzureVMComputer: doDoDelete: Exception occurred while deleting agent",
                        e);
                    throw AzureCloudException.create(
                        "AzureVMComputer: doDoDelete: Exception occurred while deleting agent",
                        e);
                }
                return null;
            };

            try {
                executionEngine.executeAsync(task, new NoRetryStrategy());
            } catch (AzureCloudException exception) {
                // No need to throw exception back, just log and move on.
                LOGGER.log(Level.INFO,
                    "AzureVMComputer: execute: failed to shutdown/delete " + agent.getDisplayName(),
                    exception);
            }
        }

        return new HttpRedirect("..");
    }

    public boolean isSetOfflineByUser() {
        return (this.getOfflineCause() instanceof OfflineCause.UserCause);
    }

    /**
     * Waits until the node is online.
     *
     * @throws InterruptedException If interrupted while waiting
     */
    @Override
    public void waitUntilOnline() throws InterruptedException {
        super.waitUntilOnline();
    }

    @Nullable
    @Override
    public ProvisioningActivity.Id getId() {
        return provisioningId;
    }

    @Restricted(NoExternalUse.class) // UI only
    public String getPublicDNSName() {
        AzureVMAgent agent = getNode();
        if (agent != null) {
            return agent.getPublicDNSName();
        }
        return null;
    }

    @Restricted(NoExternalUse.class) // UI only
    public String getPublicIP() {
        AzureVMAgent agent = getNode();
        if (agent != null) {
            return agent.getPublicIP();
        }
        return null;
    }

    @Restricted(NoExternalUse.class) // UI only
    public String getPrivateIP() {
        AzureVMAgent agent = getNode();
        if (agent != null) {
            return agent.getPrivateIP();
        }
        return null;
    }

    @Restricted(NoExternalUse.class) // UI only
    public String getAzurePortalLink() {
        AzureVMAgent agent = getNode();

        if (agent != null) {
            AzureVMCloud cloud = agent.getCloud();
            if (cloud != null) {
                AzureResourceManager azureClient = cloud.getAzureClient();
                String subscriptionId = azureClient.getCurrentSubscription().subscriptionId();
                String resourceGroup = agent.getResourceGroupName();
                // can't see a way to guarantee getting the tenant ID, this should be enough for now anyway
                return String.format("https://portal.azure.com/#resource/subscriptions/%s/resourceGroups/%s/"
                    + "providers/Microsoft.Compute/virtualMachines/%s", subscriptionId,
                    resourceGroup, nodeName);
            }
        }
        return null;
    }
}
