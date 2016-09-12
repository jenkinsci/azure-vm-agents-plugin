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

import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.retry.NoRetryStrategy;
import com.microsoftopentechnologies.azure.util.CleanUpAction;
import com.microsoftopentechnologies.azure.util.ExecutionEngine;
import java.io.IOException;
import java.util.logging.Logger;

import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;

import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import java.util.concurrent.Callable;
import java.util.logging.Level;

public class AzureComputer extends AbstractCloudComputer<AzureSlave> {

    private static final Logger LOGGER = Logger.getLogger(AzureComputer.class.getName());

    private boolean setOfflineByUser = false;

    public AzureComputer(final AzureSlave slave) {
        super(slave);
    }

    @Override
    public HttpResponse doDoDelete() throws IOException {
        checkPermission(DELETE);
        this.setAcceptingTasks(false);
        final AzureSlave slave = getNode();
        
        if (slave != null) {
            Callable<Void> task = new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    LOGGER.log(Level.INFO, "AzureComputer: doDoDelete called for slave {0}", slave.getNodeName());
                    try {
                        // Deprovision
                        slave.deprovision(Messages._User_Delete());
                    } catch (Exception e) {
                        LOGGER.log(Level.INFO, "AzureComputer: doDoDelete: Exception occurred while deleting slave", e);
                        throw new AzureCloudException("AzureComputer: doDoDelete: Exception occurred while deleting slave", e);
                    }
                    return null;
                }
            };

            try {
                ExecutionEngine.executeAsync(task, new NoRetryStrategy());
            } catch (AzureCloudException exception) {
                // No need to throw exception back, just log and move on. 
                LOGGER.log(Level.INFO,
                        "AzureSlaveCleanUpTask: execute: failed to shutdown/delete " + slave.getDisplayName(),
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
     * Wait until the node is online
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
     * @param setOffline
     * @param oc 
     */
    @Override
    public void setTemporarilyOffline(boolean setOffline) {
        setSetOfflineByUser(setOffline);
        super.setTemporarilyOffline(setOffline);
    }
}
