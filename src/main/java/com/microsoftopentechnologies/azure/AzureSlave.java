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
import com.microsoftopentechnologies.azure.util.CleanUpAction;
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
import org.jvnet.localizer.Localizable;

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

    private CleanUpAction cleanUpAction;
    
    private Localizable cleanUpReason;
    
    private String resourceGroupName;

    private static final Logger LOGGER = Logger.getLogger(AzureSlave.class.getName());

    private final boolean executeInitScriptAsRoot;
    
    private final boolean doNotUseMachineIfInitFails;
    
    private boolean eligibleForReuse;

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
            final boolean eligibleForReuse,
            final String deploymentName,
            final int retentionTimeInMin,
            final String initScript,
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String managementURL,
            final String slaveLaunchMethod,
            final CleanUpAction cleanUpAction,
            final Localizable cleanUpReason,
            final String resourceGroupName,
            final boolean executeInitScriptAsRoot,
            final boolean doNotUseMachineIfInitFails) throws FormException, IOException {

        super(name, nodeDescription, remoteFS, numExecutors, mode, label, launcher, retentionStrategy, nodeProperties);

        this.cloudName = cloudName;
        this.templateName = templateName;
        this.adminUserName = adminUserName;
        this.sshPrivateKey = sshPrivateKey;
        this.sshPassPhrase = sshPassPhrase;
        this.adminPassword = adminPassword;
        this.jvmOptions = jvmOptions;
        this.shutdownOnIdle = shutdownOnIdle;
        this.eligibleForReuse = eligibleForReuse;
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
        this.setCleanUpAction(cleanUpAction);
        this.setCleanupReason(cleanUpReason);
        this.resourceGroupName = resourceGroupName;
        this.executeInitScriptAsRoot = executeInitScriptAsRoot;
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
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
            final boolean eligibleForReuse,
            final String deploymentName,
            final int retentionTimeInMin,
            final String initScript,
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String managementURL,
            final String slaveLaunchMethod,
            final CleanUpAction cleanUpAction,
            final Localizable cleanUpReason,
            final String resourceGroupName,
            final boolean executeInitScriptAsRoot,
            final boolean doNotUseMachineIfInitFails) throws FormException, IOException {

        this(name,
                templateName,
                nodeDescription,
                osType,
                remoteFS,
                numExecutors,
                mode,
                label,
                slaveLaunchMethod.equalsIgnoreCase("SSH") ? 
                    new AzureSSHLauncher() : new JNLPLauncher(),
                new AzureCloudRetensionStrategy(retentionTimeInMin),
                Collections.<NodeProperty<?>>emptyList(),
                cloudName,
                adminUserName,
                sshPrivateKey,
                sshPassPhrase,
                adminPassword,
                jvmOptions,
                shutdownOnIdle,
                eligibleForReuse,
                deploymentName,
                retentionTimeInMin,
                initScript,
                subscriptionId,
                clientId,
                clientSecret,
                oauth2TokenEndpoint,
                managementURL,
                slaveLaunchMethod,
                cleanUpAction,
                cleanUpReason,
                resourceGroupName,
                executeInitScriptAsRoot,
                doNotUseMachineIfInitFails);
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

    public CleanUpAction getCleanUpAction() {
        return cleanUpAction;
    }
    
    public Localizable getCleanUpReason() {
        return cleanUpReason;
    }
    
    /**
     * @param cleanUpReason 
     */
    private void setCleanUpAction(CleanUpAction cleanUpAction) {
        // Translate a default cleanup action into what we want for a particular
        // node
        if (cleanUpAction == CleanUpAction.DEFAULT) {
            if (isShutdownOnIdle()) {
                cleanUpAction = CleanUpAction.SHUTDOWN;
            }
            else {
                cleanUpAction = CleanUpAction.DELETE;
            }
        }
        this.cleanUpAction = cleanUpAction;
    }
    
    /**
     * @param cleanUpReason 
     */
    private void setCleanupReason(Localizable cleanUpReason) {
        this.cleanUpReason = cleanUpReason;
    }
    
    /**
     * Clear the cleanup action and reset to the default behavior
     */
    public void clearCleanUpAction() {
        setCleanUpAction(CleanUpAction.DEFAULT);
        setCleanupReason(null);
    }
    
    /**
     * Block any cleanup from happening
     */
    public void blockCleanUpAction() {
        setCleanUpAction(CleanUpAction.BLOCK);
        setCleanupReason(null);
    }
    
    public boolean isCleanUpBlocked() {
        return getCleanUpAction() == CleanUpAction.BLOCK;
    }

    public void setCleanUpAction(CleanUpAction cleanUpAction, Localizable cleanUpReason) {
        if (cleanUpAction != CleanUpAction.DELETE && cleanUpAction != CleanUpAction.SHUTDOWN) {
            throw new IllegalStateException("Only use this method to set explicit cleanup operations");
        }
        if (this.toComputer()!= null) {
            AzureComputer computer = (AzureComputer)this.toComputer();
            // Set the machine temporarily offline machine with an offline reason.
            computer.setTemporarilyOffline(true, OfflineCause.create(cleanUpReason));
            // Reset the "by user" bit.
            computer.setSetOfflineByUser(false);
        }
        setCleanUpAction(cleanUpAction);
        setCleanupReason(cleanUpReason);
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

    public boolean isEligibleForReuse() {
        return eligibleForReuse;
    }

    public void setEligibleForReuse(boolean eligibleForReuse) {
        this.eligibleForReuse = eligibleForReuse;
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
    
    public String getResourceGroupName() {
        return resourceGroupName;
    }
    
    public boolean getExecuteInitScriptAsRoot() {
        return executeInitScriptAsRoot;
    }

    public boolean getDoNotUseMachineIfInitFails() {
        return doNotUseMachineIfInitFails;
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

    public AzureCloud getCloud() {
        return (AzureCloud) Jenkins.getInstance().getCloud(cloudName);
    }
    
    public void shutdown(Localizable reason) {
        LOGGER.log(Level.INFO, "AzureSlave: shutdown: shutting down slave {0}", this.
                getDisplayName());
        this.getComputer().setAcceptingTasks(false);
        this.getComputer().disconnect(OfflineCause.create(reason));
        AzureManagementServiceDelegate.shutdownVirtualMachine(this);
        // After shutting down succesfully, set the node as eligible for
        // reuse.
        setEligibleForReuse(true);
    }

    /**
     * Delete node in Azure and in Jenkins
     * @throws Exception 
     */
    public void deprovision(Localizable reason) throws Exception {
        LOGGER.log(Level.INFO, "AzureSlave: deprovision: Deprovision called for slave {0}", this.getDisplayName());
        this.getComputer().setAcceptingTasks(false);
        this.getComputer().disconnect(OfflineCause.create(reason));
        AzureManagementServiceDelegate.terminateVirtualMachine(this);
        LOGGER.log(Level.INFO, "AzureSlave: deprovision: {0} has been deprovisioned. Remove node ...",
                this.getDisplayName());
        // Adjust parent VM count up by one.
        AzureCloud parentCloud = getCloud();
        if (parentCloud != null) {
            parentCloud.adjustVirtualMachineCount(1);
        }
        Jenkins.getInstance().removeNode(this);
    }

    public boolean isVMAliveOrHealthy() throws Exception {
        return AzureManagementServiceDelegate.isVMAliveOrHealthy(this);
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
                + "\n\tcleanUpAction=" + cleanUpAction
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
