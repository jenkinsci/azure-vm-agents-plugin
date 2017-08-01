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

import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.retry.NoRetryStrategy;
import com.microsoft.azure.vmagent.util.ExecutionEngine;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AzureVMComputer extends AbstractCloudComputer<AzureVMAgent> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(AzureVMComputer.class.getName());

    private final ProvisioningActivity.Id provisioningId;

    private boolean setOfflineByUser = false;

    public AzureVMComputer(AzureVMAgent agent) {
        super(agent);
        this.provisioningId = agent.getId();
    }

    @Override
    public HttpResponse doDoDelete() throws IOException {
        return doDoDelete(new ExecutionEngine());
    }

    protected HttpResponse doDoDelete(ExecutionEngine executionEngine) throws IOException {
        checkPermission(DELETE);
        this.setAcceptingTasks(false);
        final AzureVMAgent agent = getNode();

        if (agent != null) {
            Callable<Void> task = new Callable<Void>() {
                @Override
                public Void call() throws Exception {
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
                }
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
        return setOfflineByUser;
    }

    public void setSetOfflineByUser(boolean setOfflineByUser) {
        this.setOfflineByUser = setOfflineByUser;
    }

    /**
     * Wait until the node is online.
     *
     * @throws InterruptedException
     */
    @Override
    public void waitUntilOnline() throws InterruptedException {
        super.waitUntilOnline();
    }

    /**
     * We use temporary offline settings to do investigation of machines.
     * To avoid deletion, we assume this came through a user call and set a bit.  Where
     * this plugin might set things temp-offline (vs. disconnect), we'll reset the bit
     * after calling setTemporarilyOffline
     *
     * @param setOffline
     * @param oc
     */
    @Override
    public void setTemporarilyOffline(boolean setOffline, OfflineCause oc) {
        setSetOfflineByUser(setOffline);
        super.setTemporarilyOffline(setOffline, oc);
    }

    /**
     * We use temporary offline settings to do investigation of machines.
     * To avoid deletion, we assume this came through a user call and set a bit.  Where
     * this plugin might set things temp-offline (vs. disconnect), we'll reset the bit
     * after calling setTemporarilyOffline
     *
     * @param setOffline
     */
    @Override
    public void setTemporarilyOffline(boolean setOffline) {
        setSetOfflineByUser(setOffline);
        super.setTemporarilyOffline(setOffline);
    }

    @Nullable
    @Override
    public ProvisioningActivity.Id getId() {
        return provisioningId;
    }
}
