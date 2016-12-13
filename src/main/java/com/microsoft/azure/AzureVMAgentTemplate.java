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
package com.microsoft.azure;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.microsoft.azure.Messages;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.util.AzureCredentials.ServicePrincipal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.microsoft.azure.util.AzureUtil;
import com.microsoft.azure.util.Constants;
import com.microsoft.azure.util.FailureStage;
import com.microsoft.windowsazure.core.utils.Base64;

import hudson.Extension;
import hudson.RelativePath;
import hudson.model.Describable;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.logging.Level;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;

/**
 * This class defines the configuration of Azure instance templates
 *
 * @author Suresh Nallamilli
 *
 */
public class AzureVMAgentTemplate implements Describable<AzureVMAgentTemplate> {

    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentTemplate.class.getName());

    // General Configuration
    private final String templateName;

    private final String templateDesc;

    private final String labels;

    private final String location;

    private final String virtualMachineSize;

    private String storageAccountName;

    private final int noOfParallelJobs;

    private final Node.Mode useAgentAlwaysIfAvail;

    private final boolean shutdownOnIdle;

    // Image Configuration
    private final String imageReferenceType;

    private final String image;

    private final String osType;

    private final String imagePublisher;

    private final String imageOffer;

    private final String imageSku;

    private final String imageVersion;

    private final String agentLaunchMethod;

    private final String initScript;

    private final String credentialsId;

    private final String agentWorkspace;

    private final int retentionTimeInMin;

    private String virtualNetworkName;

    private String subnetName;

    private final String jvmOptions;

    // Indicates whether the template is disabled.
    // If disabled, will not attempt to verify or use
    private boolean templateDisabled;

    private String templateStatusDetails;

    private transient AzureVMCloud azureCloud;

    private transient Set<LabelAtom> labelDataSet;

    private boolean templateVerified;

    private boolean executeInitScriptAsRoot;

    private boolean doNotUseMachineIfInitFails;

    @DataBoundConstructor
    public AzureVMAgentTemplate(
            final String templateName,
            final String templateDesc,
            final String labels,
            final String location,
            final String virtualMachineSize,
            final String storageAccountName,
            final String noOfParallelJobs,
            final Node.Mode useAgentAlwaysIfAvail,
            final String imageReferenceType,
            final String image,
            final String osType,
            final boolean imageReference,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion,
            final String agentLaunchMethod,
            final String initScript,
            final String credentialsId,
            final String virtualNetworkName,
            final String subnetName,
            final String agentWorkspace,
            final String jvmOptions,
            final String retentionTimeInMin,
            final boolean shutdownOnIdle,
            final boolean templateDisabled,
            final String templateStatusDetails,
            final boolean executeInitScriptAsRoot,
            final boolean doNotUseMachineIfInitFails) {
        this.templateName = templateName;
        this.templateDesc = templateDesc;
        this.labels = labels;
        this.location = location;
        this.virtualMachineSize = virtualMachineSize;
        this.storageAccountName = storageAccountName;

        if (StringUtils.isBlank(noOfParallelJobs) || !noOfParallelJobs.matches(Constants.REG_EX_DIGIT)
                || noOfParallelJobs.
                        trim().equals("0")) {
            this.noOfParallelJobs = 1;
        } else {
            this.noOfParallelJobs = Integer.parseInt(noOfParallelJobs);
        }
        this.useAgentAlwaysIfAvail = useAgentAlwaysIfAvail;
        this.imageReferenceType = imageReferenceType;
        this.image = image;
        this.osType = osType;
        this.imagePublisher = imagePublisher;
        this.imageOffer = imageOffer;
        this.imageSku = imageSku;
        this.imageVersion = imageVersion;
        this.shutdownOnIdle = shutdownOnIdle;
        this.initScript = initScript;
        this.agentLaunchMethod = agentLaunchMethod;
        this.credentialsId = credentialsId;
        this.virtualNetworkName = virtualNetworkName;
        this.subnetName = subnetName;
        this.agentWorkspace = agentWorkspace;
        this.jvmOptions = jvmOptions;
        this.executeInitScriptAsRoot = executeInitScriptAsRoot;
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
        if (StringUtils.isBlank(retentionTimeInMin) || !retentionTimeInMin.matches(Constants.REG_EX_DIGIT)) {
            this.retentionTimeInMin = Constants.DEFAULT_IDLE_TIME;
        } else {
            this.retentionTimeInMin = Integer.parseInt(retentionTimeInMin);
        }
        this.templateDisabled = templateDisabled;
        this.templateStatusDetails = "";

        // Reset the template verification status.
        this.templateVerified = false;

        // Forms data which is not persisted
        readResolve();
    }

    public String isType(final String type) {
        return type != null && type.equalsIgnoreCase(this.imageReferenceType) ? "true" : "false";
    }

    private Object readResolve() {
        labelDataSet = Label.parse(labels);
        return this;
    }

    public String getLabels() {
        return labels;
    }

    public String getLocation() {
        return location;
    }

    public String getVirtualMachineSize() {
        return virtualMachineSize;
    }

    public String getStorageAccountName() {
        return storageAccountName;
    }

    public void setStorageAccountName(final String storageAccountName) {
        this.storageAccountName = storageAccountName;
    }

    public Node.Mode getUseAgentAlwaysIfAvail() {
        return useAgentAlwaysIfAvail;
    }

    public boolean isShutdownOnIdle() {
        return shutdownOnIdle;
    }

    public String getImageReferenceType() {
        return imageReferenceType;
    }

    public String getImage() {
        return image;
    }

    public String getOsType() {
        return osType;
    }

    public String getImagePublisher() {
        return imagePublisher;
    }

    public String getImageOffer() {
        return imageOffer;
    }

    public String getImageSku() {
        return imageSku;
    }

    public String getImageVersion() {
        return imageVersion;
    }

    public String getInitScript() {
        return initScript;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getVirtualNetworkName() {
        return virtualNetworkName;
    }

    public void setVirtualNetworkName(String virtualNetworkName) {
        this.virtualNetworkName = virtualNetworkName;
    }

    public String getSubnetName() {
        return subnetName;
    }

    public void setSubnetName(String subnetName) {
        this.subnetName = subnetName;
    }

    public String getAgentWorkspace() {
        return agentWorkspace;
    }

    public int getRetentionTimeInMin() {
        return retentionTimeInMin;
    }

    public String getJvmOptions() {
        return jvmOptions;
    }

    public AzureVMCloud getAzureCloud() {
        return azureCloud;
    }

    public void setAzureCloud(AzureVMCloud cloud) {
        azureCloud = cloud;
        if (StringUtils.isBlank(storageAccountName)) {
            storageAccountName = AzureVMAgentTemplate.GenerateUniqueStorageAccountName(azureCloud.getResourceGroupName(), azureCloud.getServicePrincipal());
            
        }
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getTemplateDesc() {
        return templateDesc;
    }

    public int getNoOfParallelJobs() {
        return noOfParallelJobs;
    }

    public String getAgentLaunchMethod() {
        return agentLaunchMethod;
    }

    /**
     * Returns true if this template is disabled and cannot be used, false
     * otherwise.
     *
     * @return True/false
     */
    public boolean isTemplateDisabled() {
        return this.templateDisabled;
    }

    /**
     * Is the template set up and verified?
     *
     * @return True if the template is set up and verified, false otherwise.
     */
    public boolean isTemplateVerified() {
        return templateVerified;
    }

    /**
     * Set the template verification status
     *
     * @param isValid True for verified + valid, false otherwise.
     */
    public void setTemplateVerified(boolean isValid) {
        templateVerified = isValid;
    }

    public String getTemplateStatusDetails() {
        return templateStatusDetails;
    }

    public void setTemplateStatusDetails(String templateStatusDetails) {
        this.templateStatusDetails = templateStatusDetails;
    }

    public String getResourceGroupName() {
        // Allow overriding?
        return getAzureCloud().getResourceGroupName();
    }

    public boolean getExecuteInitScriptAsRoot() {
        return executeInitScriptAsRoot;
    }

    public void setExecuteInitScriptAsRoot(boolean executeAsRoot) {
        executeInitScriptAsRoot = executeAsRoot;
    }

    public boolean getDoNotUseMachineIfInitFails() {
        return doNotUseMachineIfInitFails;
    }

    public void setDoNotUseMachineIfInitFails(boolean doNotUseMachineIfInitFails) {
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Descriptor<AzureVMAgentTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public Set<LabelAtom> getLabelDataSet() {
        return labelDataSet;
    }

    /**
     * Provision new agents using this template.
     *
     * @param listener
     * @param numberOfAgents Number of agents to provision
     * @return New deployment info if the provisioning was successful.
     * @throws Exception May throw if provisioning was not successful.
     */
    public AzureVMDeploymentInfo provisionAgents(final TaskListener listener, int numberOfAgents) throws Exception {
        return AzureVMManagementServiceDelegate.createDeployment(this, numberOfAgents);
    }

    /**
     * If provisioning failed, handle the status and queue the template for
     * verification.
     *
     * @param message Failure message
     * @param failureStep Stage that failure occurred
     */
    public void handleTemplateProvisioningFailure(final String message, final FailureStage failureStep) {
        // The template is bad.  It should have already been verified, but
        // perhaps something changed (VHD gone, etc.).  Queue for verification.
        setTemplateVerified(false);
        AzureVMCloudVerificationTask.registerTemplate(this);
        // Set the details so that it's easier to see what's going on from the configuration UI.
        setTemplateStatusDetails(message);
    }

    /**
     * Verify that this template is correct and can be allocated.
     *
     * @return Empty list if this template is valid, list of errors otherwise
     * @throws Exception
     */
    public List<String> verifyTemplate() throws Exception {
        return AzureVMManagementServiceDelegate.verifyTemplate(
                azureCloud.getServicePrincipal(),
                templateName,
                labels,
                location,
                virtualMachineSize,
                storageAccountName,
                noOfParallelJobs + "",
                image,
                osType,
                imagePublisher,
                imageOffer,
                imageSku,
                imageVersion,
                agentLaunchMethod,
                initScript,
                credentialsId,
                virtualNetworkName,
                subnetName,
                retentionTimeInMin + "",
                jvmOptions,
                getResourceGroupName(),
                true);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<AzureVMAgentTemplate> {

        @Override
        public String getDisplayName() {
            return null;
        }

        private synchronized List<String> getVMSizes(final String location) {
            return AzureVMManagementServiceDelegate.getVMSizes(location);
        }

        public ListBoxModel doFillVirtualMachineSizeItems(@QueryParameter final String location)
                throws IOException, ServletException {

            ListBoxModel model = new ListBoxModel();
            List<String> vmSizes = AzureVMManagementServiceDelegate.getVMSizes(location);

            for (String vmSize : vmSizes) {
                model.add(vmSize);
            }

            return model;
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item owner) {
            // when configuring the job, you only want those credentials that are available to ACL.SYSTEM selectable
            // as we cannot select from a user's credentials unless they are the only user submitting the build
            // (which we cannot assume) thus ACL.SYSTEM is correct here.
            return new StandardListBoxModel().withAll(CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, owner, ACL.SYSTEM, Collections.<DomainRequirement>emptyList()));
        }

        public ListBoxModel doFillOsTypeItems() throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.OS_TYPE_LINUX);
            model.add(Constants.OS_TYPE_WINDOWS);
            return model;
        }

        public ListBoxModel doFillLocationItems(
                @RelativePath("..") @QueryParameter final String serviceManagementURL)
                throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();

            Map<String, String> locations = AzureVMManagementServiceDelegate.getVirtualMachineLocations(serviceManagementURL);

            // This map contains display name -> actual location name.  We
            // need the actual location name later, but just grab the keys of
            // the map for the model.
            for (String location : locations.keySet()) {
                model.add(location);
            }

            return model;
        }

        public ListBoxModel doFillAgentLaunchMethodItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.LAUNCH_METHOD_SSH);
            model.add(Constants.LAUNCH_METHOD_JNLP);

            return model;
        }

        public FormValidation doCheckInitScript(
                @QueryParameter final String value,
                @QueryParameter final String agentLaunchMethod) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.warningWithMarkup(Messages.Azure_GC_InitScript_Warn_Msg());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckStorageAccountName(@QueryParameter final String value) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.ok(Messages.SA_Blank_Create_New());
            }
            return FormValidation.ok();
        }

        public FormValidation doAgentLaunchMethod(@QueryParameter final String value) {
            if (Constants.LAUNCH_METHOD_JNLP.equals(value)) {
                return FormValidation.warning(Messages.Azure_GC_LaunchMethod_Warn_Msg());
            }
            return FormValidation.ok();
        }

        /**
         * Check the template's name. Name must conform to restrictions on VM
         * naming
         *
         * @param value Current name
         * @param templateDisabled Is the template disabled
         * @param osType OS type
         * @return
         */
        public FormValidation doCheckTemplateName(
                @QueryParameter final String value, @QueryParameter final boolean templateDisabled,
                @QueryParameter final String osType) {
            List<FormValidation> errors = new ArrayList<FormValidation>();
            // Check whether the template name is valid, and then check
            // whether it would be shortened on VM creation.
            if (!AzureUtil.isValidTemplateName(value)) {
                errors.add(FormValidation.error(Messages.Azure_GC_Template_Name_Not_Valid()));
            }

            if (templateDisabled) {
                errors.add(FormValidation.warning(Messages.Azure_GC_TemplateStatus_Warn_Msg()));
            }

            if (errors.size() > 0) {
                return FormValidation.aggregate(errors);
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckNoOfParallelJobs(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                String result = AzureVMManagementServiceDelegate.verifyNoOfExecutors(value);

                if (result.equalsIgnoreCase(Constants.OP_SUCCESS)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(result);
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckRetentionTimeInMin(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                String result = AzureVMManagementServiceDelegate.verifyRetentionTime(value);

                if (result.equalsIgnoreCase(Constants.OP_SUCCESS)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(result);
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckAdminPassword(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                if (AzureUtil.isValidPassword(value)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(Messages.Azure_GC_Password_Err());
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckJvmOptions(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                if (AzureUtil.isValidJvmOption(value)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(Messages.Azure_GC_JVM_Option_Err());
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doVerifyConfiguration(
                @RelativePath("..") @QueryParameter String azureCredentialsId,
                @RelativePath("..") @QueryParameter String resourceGroupName,
                @RelativePath("..") @QueryParameter String maxVirtualMachinesLimit,
                @RelativePath("..") @QueryParameter String deploymentTimeout,
                @QueryParameter String templateName,
                @QueryParameter String labels,
                @QueryParameter String location,
                @QueryParameter String virtualMachineSize,
                @QueryParameter String storageAccountName,
                @QueryParameter String noOfParallelJobs,
                @QueryParameter String image,
                @QueryParameter String osType,
                @QueryParameter String imagePublisher,
                @QueryParameter String imageOffer,
                @QueryParameter String imageSku,
                @QueryParameter String imageVersion,
                @QueryParameter String agentLaunchMethod,
                @QueryParameter String initScript,
                @QueryParameter String credentialsId,
                @QueryParameter String virtualNetworkName,
                @QueryParameter String subnetName,
                @QueryParameter String retentionTimeInMin,
                @QueryParameter String jvmOptions) {

            AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(azureCredentialsId);
            if(storageAccountName.trim().isEmpty())
                storageAccountName = AzureVMAgentTemplate.GenerateUniqueStorageAccountName(resourceGroupName, servicePrincipal);

            LOGGER.log(Level.INFO,
                    "Verify configuration:\n\t"
                    + "subscriptionId: {0};\n\t"
                    + "clientId: {1};\n\t"
                    + "clientSecret: {2};\n\t"
                    + "serviceManagementURL: {3};\n\t"
                    + "resourceGroupName: {4};\n\t."
                    + "templateName: {5};\n\t"
                    + "labels: {6};\n\t"
                    + "location: {7};\n\t"
                    + "virtualMachineSize: {8};\n\t"
                    + "storageAccountName: {9};\n\t"
                    + "noOfParallelJobs: {10};\n\t"
                    + "image: {11};\n\t"
                    + "osType: {12};\n\t"
                    + "imagePublisher: {13};\n\t"
                    + "imageOffer: {14};\n\t"
                    + "imageSku: {15};\n\t"
                    + "imageVersion: {16};\n\t"
                    + "agentLaunchMethod: {17};\n\t"
                    + "initScript: {18};\n\t"
                    + "credentialsId: {19};\n\t"
                    + "virtualNetworkName: {20};\n\t"
                    + "subnetName: {21};\n\t"
                    + "retentionTimeInMin: {22};\n\t"
                    + "jvmOptions: {23};",
                    new Object[]{
                        servicePrincipal.subscriptionId.getPlainText(),
                        (StringUtils.isNotBlank(servicePrincipal.clientId.getPlainText()) ? "********" : null),
                        (StringUtils.isNotBlank(servicePrincipal.clientSecret.getPlainText()) ? "********" : null),
                        servicePrincipal.serviceManagementURL,
                        resourceGroupName,
                        templateName,
                        labels,
                        location,
                        virtualMachineSize,
                        storageAccountName,
                        noOfParallelJobs,
                        image,
                        osType,
                        imagePublisher,
                        imageOffer,
                        imageSku,
                        imageVersion,
                        agentLaunchMethod,
                        initScript,
                        credentialsId,
                        virtualNetworkName,
                        subnetName,
                        retentionTimeInMin,
                        jvmOptions});

            // First validate the subscription info.  If it is not correct,
            // then we can't validate the 
            String result = AzureVMManagementServiceDelegate.verifyConfiguration(servicePrincipal, resourceGroupName, 
                    maxVirtualMachinesLimit, deploymentTimeout);
            if (!result.equals(Constants.OP_SUCCESS)) {
                return FormValidation.error(result);
            }

            final List<String> errors = AzureVMManagementServiceDelegate.verifyTemplate(
                    servicePrincipal,
                    templateName,
                    labels,
                    location,
                    virtualMachineSize,
                    storageAccountName,
                    noOfParallelJobs,
                    image,
                    osType,
                    imagePublisher,
                    imageOffer,
                    imageSku,
                    imageVersion,
                    agentLaunchMethod,
                    initScript,
                    credentialsId,
                    virtualNetworkName,
                    subnetName,
                    retentionTimeInMin,
                    jvmOptions,
                    resourceGroupName,
                    false);

            if (errors.size() > 0) {
                StringBuilder errorString = new StringBuilder(Messages.Azure_GC_Template_Error_List()).append("\n");

                for (int i = 0; i < errors.size(); i++) {
                    errorString.append(i + 1).append(": ").append(errors.get(i)).append("\n");
                }

                return FormValidation.error(errorString.toString());

            } else {
                return FormValidation.ok(Messages.Azure_Template_Config_Success());
            }
        }

        public String getDefaultNoOfExecutors() {
            return "1";
        }
    }

    public void setVirtualMachineDetails(final AzureVMAgent agent) throws Exception {
        AzureVMManagementServiceDelegate.setVirtualMachineDetails(agent, this);
    }

    public static String GenerateUniqueStorageAccountName(final String resourceGroupName, final ServicePrincipal servicePrincipal) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            if (null != servicePrincipal && null != servicePrincipal.subscriptionId)
                md.update(servicePrincipal.subscriptionId.getPlainText().getBytes());
            if (null != resourceGroupName)
                md.update(resourceGroupName.getBytes());
            String uid = Base64.encode(md.digest());
            uid = uid.substring(0, 22);
            uid = uid.toLowerCase();
            uid = uid.replaceAll("[^a-z0-9]","a");
            return "jn" + uid;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not genetare UID from the resource group name. Will fallback on using the resource group name.", e);
            return "";
        }
    }
}
