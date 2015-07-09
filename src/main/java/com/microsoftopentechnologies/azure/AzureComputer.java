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

import java.io.IOException;
import java.util.logging.Logger;

import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;

import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;

public class AzureComputer extends AbstractCloudComputer<AzureSlave> {

    private static final Logger LOGGER = Logger.getLogger(AzureComputer.class.getName());

    public AzureComputer(final AzureSlave slave) {
        super(slave);
    }

    public HttpResponse doDoDelete() throws IOException {
        LOGGER.info("AzureComputer: doDoDelete called for slave " + getNode().getNodeName());
        setTemporarilyOffline(true, OfflineCause.create(Messages._Delete_Slave()));
        getNode().setDeleteSlave(true);
        try {
            deleteSlave();
        } catch (Exception e) {
            LOGGER.info("AzureComputer: doDoDelete: Exception occurred while deleting slave " + e);
            throw new IOException(
                    "Error occurred while deleting node, jenkins will try to clean up node automatically after some time. "
                    + " \n Root cause: " + e.getMessage());
        }
        return new HttpRedirect("..");
    }

    public void deleteSlave() throws Exception, InterruptedException {
        LOGGER.info("AzureComputer : deleteSlave: Deleting " + getName() + " slave");

        AzureSlave slave = getNode();
        if (slave.getChannel() != null) {
            slave.getChannel().close();
        }
        try {
            slave.deprovision();
        } catch (Exception e) {

            LOGGER.severe("AzureComputer : Exception occurred while deleting  " + getName() + " slave");
            LOGGER.severe("Root cause " + e.getMessage());
            throw e;
        }
    }
}
