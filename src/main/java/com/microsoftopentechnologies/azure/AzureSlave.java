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
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;

import com.microsoftopentechnologies.azure.util.Constants;
import com.microsoftopentechnologies.azure.util.FailureStage;
import com.microsoftopentechnologies.azure.remote.AzureSSHLauncher;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Descriptor.FormException;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import java.util.logging.Level;

public class AzureSlave extends AbstractCloudSlave {

    private static final long serialVersionUID = -760014706860995556L;

    private final String cloudName;

    private final String adminUserName;

    private final String sshPrivateKey;

    private final String sshPassPhrase;

    private final String adminPassword;

    private final String jvmOptions;

    private boolean shutdownOnIdle;

    private final int retentionTimeInMin;

    private final String slaveLaunchMethod;

    private final String initScript;

    private final String deploymentName;

    private final String osType;

    // set during post create step
    private String publicDNSName;

    private int sshPort;

    private final Mode mode;

    private final String subscriptionId;

    private final String clientId;

    private final String clientSecret;

    private final String oauth2TokenEndpoint;

    private final String managementURL;

    private String templateName;

    private boolean deleteSlave;

    private static final Logger LOGGER = Logger.getLogger(AzureSlave.class.getName());

    @DataBoundConstructor
    public AzureSlave(
            final String name,
            final String templateName,
            final String nodeDescription,
            final String osType,
            final String remoteFS,
            final int numExecutors,
            final Mode mode,
            final String label,
            final ComputerLauncher launcher,
            final RetentionStrategy<AzureComputer> retentionStrategy,
            final List<? extends NodeProperty<?>> nodeProperties,
            final String cloudName,
            final String adminUserName,
            final String sshPrivateKey,
            final String sshPassPhrase,
            final String adminPassword,
            final String jvmOptions,
            final boolean shutdownOnIdle,
            final String deploymentName,
            final int retentionTimeInMin,
            final String initScript,
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String managementURL,
            final String slaveLaunchMethod,
            final boolean deleteSlave) throws FormException, IOException {

        super(name, nodeDescription, remoteFS, numExecutors, mode, label, launcher, retentionStrategy, nodeProperties);

        this.cloudName = cloudName;
        this.templateName = templateName;
        this.adminUserName = adminUserName;
        this.sshPrivateKey = sshPrivateKey;
        this.sshPassPhrase = sshPassPhrase;
        this.adminPassword = adminPassword;
        this.jvmOptions = jvmOptions;
        this.shutdownOnIdle = shutdownOnIdle;
        this.deploymentName = deploymentName;
        this.retentionTimeInMin = retentionTimeInMin;
        this.initScript = initScript;
        this.osType = osType;
        this.mode = mode;
        this.subscriptionId = subscriptionId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.oauth2TokenEndpoint = oauth2TokenEndpoint;
        this.managementURL = managementURL;
        this.slaveLaunchMethod = slaveLaunchMethod;
        this.deleteSlave = deleteSlave;
    }

    public AzureSlave(
            final String name,
            final String templateName,
            final String nodeDescription,
            final String osType,
            final String remoteFS,
            final int numExecutors,
            final Mode mode,
            final String label,
            final String cloudName,
            final String adminUserName,
            final String sshPrivateKey,
            final String sshPassPhrase,
            final String adminPassword,
            final String jvmOptions,
            final boolean shutdownOnIdle,
            final String deploymentName,
            final int retentionTimeInMin,
            final String initScript,
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String managementURL,
            final String slaveLaunchMethod,
            final boolean deleteSlave) throws FormException, IOException {

        this(name,
                templateName,
                nodeDescription,
                osType,
                remoteFS,
                numExecutors,
                mode,
                label,
                slaveLaunchMethod.equalsIgnoreCase("SSH")
                        ? osType.equalsIgnoreCase("Windows")
                                ? new AzureSSHLauncher()
                                : new AzureSSHLauncher()
                        : new JNLPLauncher(),
                new AzureCloudRetensionStrategy(retentionTimeInMin),
                Collections.<NodeProperty<?>>emptyList(),
                cloudName,
                adminUserName,
                sshPrivateKey,
                sshPassPhrase,
                adminPassword,
                jvmOptions,
                shutdownOnIdle,
                //                cloudServiceName,
                deploymentName,
                retentionTimeInMin,
                initScript,
                subscriptionId,
                clientId,
                clientSecret,
                oauth2TokenEndpoint,
                managementURL,
                slaveLaunchMethod,
                deleteSlave);
    }

    public String getCloudName() {
        return cloudName;
    }

    @Override
    public Mode getMode() {
        return mode;
    }

    public String getAdminUserName() {
        return adminUserName;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getOauth2TokenEndpoint() {
        return oauth2TokenEndpoint;
    }

    public String getManagementURL() {
        return managementURL;
    }

    public String getSshPrivateKey() {
        return sshPrivateKey;
    }

    public String getOsType() {
        return osType;
    }

    public String getSshPassPhrase() {
        return sshPassPhrase;
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public boolean isDeleteSlave() {
        return deleteSlave;
    }

    public void setDeleteSlave(boolean deleteSlave) {
        this.deleteSlave = deleteSlave;
    }

    public String getJvmOptions() {
        return jvmOptions;
    }

    public boolean isShutdownOnIdle() {
        return shutdownOnIdle;
    }

    public void setShutdownOnIdle(boolean shutdownOnIdle) {
        this.shutdownOnIdle = shutdownOnIdle;
    }

    public String getPublicDNSName() {
        return publicDNSName;
    }

    public void setPublicDNSName(String publicDNSName) {
        this.publicDNSName = publicDNSName;
    }

    public int getSshPort() {
        return sshPort;
    }

    public void setSshPort(int sshPort) {
        this.sshPort = sshPort;
    }

    public int getRetentionTimeInMin() {
        return retentionTimeInMin;
    }

    public String getInitScript() {
        return initScript;
    }

    public String getSlaveLaunchMethod() {
        return slaveLaunchMethod;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    @Override
    protected void _terminate(final TaskListener arg0) throws IOException, InterruptedException {
        //TODO: Check when this method is getting called and code accordingly
        LOGGER.log(Level.INFO, "AzureSlave: _terminate: called for slave {0}", getNodeName());
    }

    @Override
    public AbstractCloudComputer<AzureSlave> createComputer() {
        LOGGER.log(Level.INFO, "AzureSlave: createComputer: start for slave {0}", this.getDisplayName());
        return new AzureComputer(this);
    }

    public void idleTimeout() throws Exception {
        if (shutdownOnIdle) {
            // Call shutdown only if the slave is online
            if (this.getComputer().isOnline()) {
                LOGGER.log(Level.INFO, "AzureSlave: idleTimeout: shutdownOnIdle is true, shutting down slave {0}", this.
                        getDisplayName());
                this.getComputer().disconnect(OfflineCause.create(Messages._IDLE_TIMEOUT_SHUTDOWN()));
                AzureManagementServiceDelegate.shutdownVirtualMachine(this);
                setDeleteSlave(false);
            }
        } else {
            LOGGER.log(Level.INFO,
                    "AzureSlave: idleTimeout: shutdownOnIdle is false, deleting slave {0}", this.getDisplayName());
            setDeleteSlave(true);
            AzureManagementServiceDelegate.terminateVirtualMachine(this, true);
            Jenkins.getInstance().removeNode(this);
        }
    }

    public AzureCloud getCloud() {
        return (AzureCloud) Jenkins.getInstance().getCloud(cloudName);
    }

    public void deprovision() throws Exception {
        LOGGER.log(Level.INFO, "AzureSlave: deprovision: Deprovision called for slave {0}", this.getDisplayName());
        AzureManagementServiceDelegate.terminateVirtualMachine(this, true);
        LOGGER.log(Level.INFO, "AzureSlave: deprovision: {0} has been deprovisioned. Remove node ...",
                this.getDisplayName());
        setDeleteSlave(true);
        Jenkins.getInstance().removeNode(this);
    }

    public boolean isVMAliveOrHealthy() throws Exception {
        return AzureManagementServiceDelegate.isVMAliveOrHealthy(this);
    }

    public void setTemplateStatus(String templateStatus, String templateStatusDetails) {
        AzureCloud azureCloud = getCloud();
        AzureSlaveTemplate slaveTemplate = azureCloud.getAzureSlaveTemplate(templateName);

        slaveTemplate.handleTemplateStatus(templateStatusDetails, FailureStage.POSTPROVISIONING, this);
    }

    @Override
    public String toString() {
        return "AzureSlave ["
                + "\n\tcloudName=" + cloudName
                + "\n\tadminUserName=" + adminUserName
                + "\n\tsshPrivateKey=" + sshPrivateKey
                + "\n\tsshPassPhrase=" + sshPassPhrase
                + "\n\tadminPassword=" + adminPassword
                + "\n\tjvmOptions=" + jvmOptions
                + "\n\tshutdownOnIdle=" + shutdownOnIdle
                + "\n\tretentionTimeInMin=" + retentionTimeInMin
                + "\n\tslaveLaunchMethod=" + slaveLaunchMethod
                + "\n\tinitScript=" + initScript
                + "\n\tdeploymentName=" + deploymentName
                + "\n\tosType=" + osType
                + "\n\tpublicDNSName=" + publicDNSName
                + "\n\tsshPort=" + sshPort
                + "\n\tmode=" + mode
                + "\n\tsubscriptionId=" + subscriptionId
                + "\n\tclientId=" + clientId
                + "\n\tclientSecret=" + clientSecret
                + "\n\toauth2TokenEndpoint=" + oauth2TokenEndpoint
                + "\n\tmanagementURL=" + managementURL
                + "\n\ttemplateName=" + templateName
                + "\n\tdeleteSlave=" + deleteSlave
                + "\n]";
    }

    @Extension
    public static final class AzureSlaveDescriptor extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return Constants.AZURE_SLAVE_DISPLAY_NAME;
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
