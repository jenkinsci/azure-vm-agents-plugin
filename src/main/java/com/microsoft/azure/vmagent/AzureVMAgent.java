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

import com.microsoft.azure.management.compute.OperatingSystemTypes;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.vmagent.remote.AzureVMAgentSSHLauncher;
import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.Constants;
import hudson.Extension;
import hudson.model.Descriptor.FormException;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jvnet.localizer.Localizable;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AzureVMAgent extends AbstractCloudSlave implements TrackedItem {

    private static final long serialVersionUID = -760014706860995557L;

    private ProvisioningActivity.Id provisioningId;

    private final String cloudName;

    private final String vmCredentialsId;

    private final String azureCredentialsId;

    private final transient AzureCredentials.ServicePrincipal credentials;

    private final String sshPrivateKey;

    private final String sshPassPhrase;

    private final String jvmOptions;

    private boolean shutdownOnIdle;

    private final int retentionTimeInMin;

    private final String agentLaunchMethod;

    private final String initScript;

    private final String deploymentName;

    private final OperatingSystemTypes osType;

    // set during post create step
    private String publicDNSName;

    private String publicIP;

    private String privateIP;

    private int sshPort;

    private final Mode mode;

    private String templateName;

    private CleanUpAction cleanUpAction;

    private Localizable cleanUpReason;

    private String resourceGroupName;

    private static final Logger LOGGER = Logger.getLogger(AzureVMAgent.class.getName());

    private final boolean executeInitScriptAsRoot;

    private final boolean doNotUseMachineIfInitFails;

    private boolean eligibleForReuse;

    private final AzureVMAgentTemplate template;

    private long creationTime;

    @DataBoundConstructor
    public AzureVMAgent(
            String name,
            String templateName,
            String nodeDescription,
            OperatingSystemTypes osType,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String label,
            ComputerLauncher launcher,
            RetentionStrategy<AzureVMComputer> retentionStrategy,
            List<? extends NodeProperty<?>> nodeProperties,
            String cloudName,
            String vmCredentialsId,
            String sshPrivateKey,
            String sshPassPhrase,
            String jvmOptions,
            boolean shutdownOnIdle,
            boolean eligibleForReuse,
            String deploymentName,
            int retentionTimeInMin,
            String initScript,
            String azureCredentialsId,
            AzureCredentials.ServicePrincipal servicePrincipal,
            String agentLaunchMethod,
            CleanUpAction cleanUpAction,
            Localizable cleanUpReason,
            String resourceGroupName,
            boolean executeInitScriptAsRoot,
            boolean doNotUseMachineIfInitFails,
            AzureVMAgentTemplate template) throws FormException, IOException {

        super(name, nodeDescription, remoteFS, numExecutors, mode, label, launcher, retentionStrategy, nodeProperties);

        this.cloudName = cloudName;
        this.templateName = templateName;
        this.vmCredentialsId = vmCredentialsId;
        this.azureCredentialsId = azureCredentialsId;
        this.credentials = servicePrincipal;
        this.sshPrivateKey = sshPrivateKey;
        this.sshPassPhrase = sshPassPhrase;
        this.jvmOptions = jvmOptions;
        this.shutdownOnIdle = shutdownOnIdle;
        this.eligibleForReuse = eligibleForReuse;
        this.deploymentName = deploymentName;
        this.retentionTimeInMin = retentionTimeInMin;
        this.initScript = initScript;
        this.osType = osType;
        this.mode = mode;
        this.agentLaunchMethod = agentLaunchMethod;
        this.setCleanUpAction(cleanUpAction);
        this.setCleanUpReason(cleanUpReason);
        this.resourceGroupName = resourceGroupName;
        this.executeInitScriptAsRoot = executeInitScriptAsRoot;
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
        this.template = template;
        this.creationTime = System.currentTimeMillis();
    }

    public AzureVMAgent(
            ProvisioningActivity.Id id,
            String name,
            String templateName,
            String nodeDescription,
            OperatingSystemTypes osType,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String label,
            String cloudName,
            String vmCredentialsId,
            String sshPrivateKey,
            String sshPassPhrase,
            String jvmOptions,
            boolean shutdownOnIdle,
            boolean eligibleForReuse,
            String deploymentName,
            RetentionStrategy<AzureVMComputer> retentionStrategy,
            String initScript,
            String azureCredentialsId,
            AzureCredentials.ServicePrincipal servicePrincipal,
            String agentLaunchMethod,
            CleanUpAction cleanUpAction,
            Localizable cleanUpReason,
            String resourceGroupName,
            boolean executeInitScriptAsRoot,
            boolean doNotUseMachineIfInitFails,
            AzureVMAgentTemplate template,
            String fqdn) throws FormException, IOException {

        this(name,
                templateName,
                nodeDescription,
                osType,
                remoteFS,
                numExecutors,
                mode,
                label,
                agentLaunchMethod.equalsIgnoreCase("SSH")
                        ? new AzureVMAgentSSHLauncher() : new JNLPLauncher(),
                retentionStrategy,
                Arrays.asList(new EnvironmentVariablesNodeProperty(
                        new EnvironmentVariablesNodeProperty.Entry("FQDN", fqdn)
                )),
                cloudName,
                vmCredentialsId,
                sshPrivateKey,
                sshPassPhrase,
                jvmOptions,
                shutdownOnIdle,
                eligibleForReuse,
                deploymentName,
                template.getRetentionTimeInMin(),
                initScript,
                azureCredentialsId,
                servicePrincipal,
                agentLaunchMethod,
                cleanUpAction,
                cleanUpReason,
                resourceGroupName,
                executeInitScriptAsRoot,
                doNotUseMachineIfInitFails,
                template);

        this.provisioningId = id;
    }

    public String getCloudName() {
        return cloudName;
    }

    @Override
    public Mode getMode() {
        return mode;
    }

    public String getVMCredentialsId() {
        return vmCredentialsId;
    }

    public AzureCredentials.ServicePrincipal getServicePrincipal() {
        if (credentials == null && azureCredentialsId != null) {
            return AzureCredentials.getServicePrincipal(azureCredentialsId);
        }
        return credentials;
    }

    public String getSshPrivateKey() {
        return sshPrivateKey;
    }

    public OperatingSystemTypes getOsType() {
        return osType;
    }

    public String getSshPassPhrase() {
        return sshPassPhrase;
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public CleanUpAction getCleanUpAction() {
        return cleanUpAction;
    }

    public Localizable getCleanUpReason() {
        return cleanUpReason;
    }

    /**
     * @param cleanUpAction
     */
    private void setCleanUpAction(CleanUpAction cleanUpAction) {
        // Translate a default cleanup action into what we want for a particular
        // node
        if (cleanUpAction == CleanUpAction.DEFAULT) {
            if (isShutdownOnIdle()) {
                cleanUpAction = CleanUpAction.SHUTDOWN;
            } else {
                cleanUpAction = CleanUpAction.DELETE;
            }
        }
        this.cleanUpAction = cleanUpAction;
    }

    /**
     * @param cleanUpReason
     */
    private void setCleanUpReason(Localizable cleanUpReason) {
        this.cleanUpReason = cleanUpReason;
    }

    /**
     * Clear the cleanup action and reset to the default behavior.
     */
    public void clearCleanUpAction() {
        setCleanUpAction(CleanUpAction.DEFAULT);
        setCleanUpReason(null);
    }

    /**
     * Block any cleanup from happening.
     */
    public void blockCleanUpAction() {
        setCleanUpAction(CleanUpAction.BLOCK);
        setCleanUpReason(null);
    }

    public boolean isCleanUpBlocked() {
        return getCleanUpAction() == CleanUpAction.BLOCK;
    }

    public void setCleanUpAction(CleanUpAction action, Localizable reason) {
        if (action != CleanUpAction.DELETE && action != CleanUpAction.SHUTDOWN) {
            throw new IllegalStateException("Only use this method to set explicit cleanup operations");
        }
        AzureVMComputer computer = (AzureVMComputer) this.toComputer();
        if (computer != null) {
            // Set the machine temporarily offline machine with an offline reason.
            computer.setTemporarilyOffline(true, OfflineCause.create(reason));
        }
        setCleanUpAction(action);
        setCleanUpReason(reason);
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

    public String getPublicIP() {
        return publicIP;
    }

    public void setPublicIP(String publicIP) {
        this.publicIP = publicIP;
    }

    public String getPrivateIP() {
        return privateIP;
    }

    public void setPrivateIP(String privateIP) {
        this.privateIP = privateIP;
    }

    public int getRetentionTimeInMin() {
        return retentionTimeInMin;
    }

    public String getInitScript() {
        return initScript;
    }

    public String getAgentLaunchMethod() {
        return agentLaunchMethod;
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

    public AzureVMAgentTemplate getTemplate() {
        return template;
    }

    public long getCreationTime() {
        return creationTime;
    }

    @Override
    protected void _terminate(TaskListener arg0) throws IOException, InterruptedException {
        //TODO: Check when this method is getting called and code accordingly
        LOGGER.log(Level.INFO, "AzureVMAgent: _terminate: called for agent {0}", getNodeName());

        ProvisioningActivity activity = CloudStatistics.get().getActivityFor(this);
        if (activity != null) {
            activity.enterIfNotAlready(ProvisioningActivity.Phase.COMPLETED);
        }
    }

    @Override
    public AbstractCloudComputer<AzureVMAgent> createComputer() {
        LOGGER.log(Level.INFO, "AzureVMAgent: createComputer: start for agent {0}", this.getDisplayName());
        return new AzureVMComputer(this);
    }

    public AzureVMCloud getCloud() {
        return (AzureVMCloud) Jenkins.getInstance().getCloud(cloudName);
    }

    public synchronized void shutdown(Localizable reason) {
        if (isEligibleForReuse()) {
            LOGGER.log(Level.INFO, "AzureVMAgent: shutdown: agent {0} is always shut down", this.
                    getDisplayName());
            return;
        }

        LOGGER.log(Level.INFO, "AzureVMAgent: shutdown: shutting down agent {0}", this.
                getDisplayName());
        this.getComputer().setAcceptingTasks(false);
        this.getComputer().disconnect(OfflineCause.create(reason));
        AzureVMManagementServiceDelegate.shutdownVirtualMachine(this);
        // After shutting down succesfully, set the node as eligible for
        // reuse.
        setEligibleForReuse(true);

        final Map<String, String> properties = new HashMap<>();
        properties.put("Reason", reason == null ? "Unknown reason" : reason.toString());
        AzureVMAgentPlugin.sendEvent(Constants.AI_VM_AGENT, "ShutDown", properties);
    }

    /**
     * Delete node in Azure and in Jenkins.
     *
     * @throws Exception
     */
    public synchronized void deprovision(Localizable reason) throws Exception {
        if (Jenkins.getInstance().getNode(this.name) == null || this.getComputer() == null) {
            return;
        }

        LOGGER.log(Level.INFO, "AzureVMAgent: deprovision: Deprovision called for agent {0}, for reason: {1}",
                new Object[]{this.getDisplayName(), reason == null ? "Unknown reason" : reason.toString()});

        this.getComputer().setAcceptingTasks(false);
        this.getComputer().disconnect(OfflineCause.create(reason));

        AzureVMManagementServiceDelegate.terminateVirtualMachine(this);
        LOGGER.log(Level.INFO, "AzureVMAgent: deprovision: {0} has been deprovisioned. Remove node ...",
                this.getDisplayName());
        // Adjust estimated virtual machine count.
        AzureVMCloud parentCloud = getCloud();
        if (parentCloud != null) {
            parentCloud.adjustVirtualMachineCount(-1);
        }

        Jenkins.getInstance().removeNode(this);

        final Map<String, String> properties = new HashMap<>();
        properties.put("Reason", reason == null ? "Unknown reason" : reason.toString());
        AzureVMAgentPlugin.sendEvent(Constants.AI_VM_AGENT, "Deprovision", properties);
    }

    public boolean isVMAliveOrHealthy() throws Exception {
        return AzureVMManagementServiceDelegate.isVMAliveOrHealthy(this);
    }

    private final Object publicIPAttachLock = new Object();

    public String attachPublicIP() {
        if (!publicIP.isEmpty()) {
            return publicIP;
        }
        //attachPublicIP will try and provision a public IP or will return if one already exists
        // because of that we can just wait here until we have a public IP
        synchronized (publicIPAttachLock) {
            try {
                AzureVMCloud azureVMCloud = (AzureVMCloud) Jenkins.getInstance().getCloud(cloudName);
                AzureVMManagementServiceDelegate.attachPublicIP(this, azureVMCloud.getAzureAgentTemplate(templateName));
            } catch (Exception e) {
                LOGGER.log(
                        Level.INFO,
                        "AzureVMAgent: error while trying to attach a public IP to {0} : {1}",
                        new Object[]{getNodeName(), e});
            }
            return publicIP;
        }
    }

    @Override
    public String toString() {
        return "AzureVMAgent ["
                + "\n\tcloudName=" + cloudName
                + "\n\tVMCredentialsId=" + vmCredentialsId
                + "\n\tjvmOptions=" + jvmOptions
                + "\n\tshutdownOnIdle=" + shutdownOnIdle
                + "\n\tretentionTimeInMin=" + retentionTimeInMin
                + "\n\tagentLaunchMethod=" + agentLaunchMethod
                + "\n\tinitScript=" + initScript
                + "\n\tdeploymentName=" + deploymentName
                + "\n\tosType=" + osType
                + "\n\tpublicDNSName=" + publicDNSName
                + "\n\tsshPort=" + sshPort
                + "\n\tmode=" + mode
                + "\n\tmanagementURL=" + credentials.getServiceManagementURL()
                + "\n\ttemplateName=" + templateName
                + "\n\tcleanUpAction=" + cleanUpAction
                + "\n]";
    }

    @Nullable
    @Override
    public ProvisioningActivity.Id getId() {
        return provisioningId;
    }

    @Extension
    public static final class AzureVMAgentDescriptor extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return Constants.AZURE_VM_AGENT_CLOUD_DISPLAY_NAME;
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }

        //abusing a bit of f:validateButton because it has nice progress
        public FormValidation doAttachPublicIP(@QueryParameter String vmAgentName) {
            AzureVMAgent vmAgent = (AzureVMAgent) Jenkins.getInstance().getNode(vmAgentName);
            String publicIP = "";
            if (vmAgent != null) {
                publicIP = vmAgent.attachPublicIP();
            }

            if (publicIP.isEmpty()) {
                return FormValidation.error(Messages.Azure_VM_Agent_Attach_Public_IP_Failure());
            } else {
                return FormValidation.ok(Messages.Azure_VM_Agent_Attach_Public_IP_Success() + " ( " + publicIP + " ) ");
            }
        }
    }
}
