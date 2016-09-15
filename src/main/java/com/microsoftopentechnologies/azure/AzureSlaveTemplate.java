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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.retry.DefaultRetryStrategy;
import com.microsoftopentechnologies.azure.retry.LinearRetryForAllExceptions;
import com.microsoftopentechnologies.azure.util.ExecutionEngine;
import com.microsoft.windowsazure.Configuration;
import com.microsoftopentechnologies.azure.util.AzureUtil;
import com.microsoftopentechnologies.azure.util.Constants;
import com.microsoftopentechnologies.azure.util.FailureStage;

import hudson.Extension;
import hudson.RelativePath;
import hudson.model.Describable;
import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.logging.Level;
import org.apache.commons.lang.StringUtils;

/**
 * This class defines the configuration of Azure instance templates
 *
 * @author Suresh Nallamilli
 *
 */
public class AzureSlaveTemplate implements Describable<AzureSlaveTemplate> {

    private static final Logger LOGGER = Logger.getLogger(AzureSlaveTemplate.class.getName());

    // General Configuration
    private final String templateName;

    private final String templateDesc;

    private final String labels;

    private final String location;

    private final String virtualMachineSize;

    private String storageAccountName;

    private final int noOfParallelJobs;

    private final Node.Mode useSlaveAlwaysIfAvail;

    private final boolean shutdownOnIdle;

    // Image Configuration
    private final String imageReferenceType;

    private final String image;

    private final String osType;

    private final String imagePublisher;

    private final String imageOffer;

    private final String imageSku;

    private final String imageVersion;

    private final String slaveLaunchMethod;

    private final String initScript;

    private final String adminUserName;

    private final String adminPassword;

    private final String slaveWorkSpace;

    private final int retentionTimeInMin;

    private String virtualNetworkName;

    private String subnetName;

    private final String jvmOptions;

    private String templateStatus;

    private String templateStatusDetails;

    public transient AzureCloud azureCloud;

    private transient Set<LabelAtom> labelDataSet;

    @DataBoundConstructor
    public AzureSlaveTemplate(
            final String templateName,
            final String templateDesc,
            final String labels,
            final String location,
            final String virtualMachineSize,
            final String storageAccountName,
            final String noOfParallelJobs,
            final Node.Mode useSlaveAlwaysIfAvail,
            final String imageReferenceType,
            final String image,
            final String osType,
            final boolean imageReference,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion,
            final String slaveLaunchMethod,
            final String initScript,
            final String adminUserName,
            final String adminPassword,
            final String virtualNetworkName,
            final String subnetName,
            final String slaveWorkSpace,
            final String jvmOptions,
            final String retentionTimeInMin,
            final boolean shutdownOnIdle,
            final String templateStatus,
            final String templateStatusDetails) {
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
        this.useSlaveAlwaysIfAvail = useSlaveAlwaysIfAvail;
        this.imageReferenceType = imageReferenceType;
        this.image = image;
        this.osType = osType;
        this.imagePublisher = imagePublisher;
        this.imageOffer = imageOffer;
        this.imageSku = imageSku;
        this.imageVersion = imageVersion;
        this.shutdownOnIdle = shutdownOnIdle;
        this.initScript = initScript;
        this.slaveLaunchMethod = slaveLaunchMethod;
        this.adminUserName = adminUserName;
        this.adminPassword = adminPassword;
        this.virtualNetworkName = virtualNetworkName;
        this.subnetName = subnetName;
        this.slaveWorkSpace = slaveWorkSpace;
        this.jvmOptions = jvmOptions;
        if (StringUtils.isBlank(retentionTimeInMin) || !retentionTimeInMin.matches(Constants.REG_EX_DIGIT)) {
            this.retentionTimeInMin = Constants.DEFAULT_IDLE_TIME;
        } else {
            this.retentionTimeInMin = Integer.parseInt(retentionTimeInMin);
        }
        this.templateStatus = templateStatus;

        if (templateStatus.equalsIgnoreCase(Constants.TEMPLATE_STATUS_ACTIVE)) {
            this.templateStatusDetails = "";
        } else {
            this.templateStatusDetails = templateStatusDetails;
        }

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

    public Node.Mode getUseSlaveAlwaysIfAvail() {
        return useSlaveAlwaysIfAvail;
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

    public String getAdminUserName() {
        return adminUserName;
    }

    public String getAdminPassword() {
        return adminPassword;
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

    public String getSlaveWorkSpace() {
        return slaveWorkSpace;
    }

    public int getRetentionTimeInMin() {
        return retentionTimeInMin;
    }

    public String getJvmOptions() {
        return jvmOptions;
    }

    public AzureCloud getAzureCloud() {
        return azureCloud;
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

    public String getSlaveLaunchMethod() {
        return slaveLaunchMethod;
    }

    public void setTemplateStatus(String templateStatus) {
        this.templateStatus = templateStatus;
    }

    public String getTemplateStatus() {
        return templateStatus;
    }

    public String getTemplateStatusDetails() {
        return templateStatusDetails;
    }

    public void setTemplateStatusDetails(String templateStatusDetails) {
        this.templateStatusDetails = templateStatusDetails;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Descriptor<AzureSlaveTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public Set<LabelAtom> getLabelDataSet() {
        return labelDataSet;
    }

    public String provisionSlaves(final TaskListener listener, int numberOfSlaves) throws Exception {
        // TODO: Get nodes with label and see if we can use existing slave 
        return AzureManagementServiceDelegate.deployment(this, numberOfSlaves);
    }

    public void waitForReadyRole(final AzureSlave slave) throws Exception {
        Callable<Void> task = new Callable<Void>() {

            @Override
            public Void call() throws Exception {
                String status = "NA";
                while (!status.equalsIgnoreCase(Constants.READY_ROLE_STATUS)) {
                    LOGGER.log(Level.INFO,
                            "AzureSlaveTemplate: waitForReadyRole: Current status of virtual machine {0} is {1}",
                            new Object[] { slave.getNodeName(), status });
                    Thread.sleep(30 * 1000);
                    status = AzureManagementServiceDelegate.getVirtualMachineStatus(
                            ServiceDelegateHelper.getConfiguration(azureCloud), slave.getNodeName());
                    LOGGER.info("AzureSlaveTemplate: waitForReadyRole: "
                            + "Waiting for 30 more seconds for role to be provisioned");
                }
                return null;
            }
        };

        try {
            ExecutionEngine.executeWithRetry(task,
                    new DefaultRetryStrategy(10 /* max retries */, 10 /* Default
                             * backoff */,
                            45 * 60 /* Max. timeout in seconds */));
            LOGGER.log(Level.INFO,
                    "AzureSlaveTemplate: waitForReadyRole: virtual machine {0} is in ready state", slave.getNodeName());
        } catch (AzureCloudException exception) {
            handleTemplateStatus("Got exception while checking for role availability " + exception,
                    FailureStage.PROVISIONING, slave);
            LOGGER.log(Level.INFO,
                    "AzureSlaveTemplate: waitForReadyRole: Got exception while checking for role availability",
                    exception);
            throw exception;
        }
    }

    public void handleTemplateStatus(final String message, final FailureStage failureStep, final AzureSlave slave) {
        // Delete slave in azure
        if (slave != null) {
            Callable<Void> task = new Callable<Void>() {

                @Override
                public Void call() throws Exception {
                    AzureManagementServiceDelegate.terminateVirtualMachine(slave, false);
                    return null;
                }
            };

            try {
                ExecutionEngine.executeWithRetry(task, new LinearRetryForAllExceptions(
                        3, // maxRetries
                        30, // waitinterval
                        2 * 60 // timeout
                ));
            } catch (AzureCloudException e) {
                LOGGER.log(Level.INFO, "AzureSlaveTemplate: handleTemplateStatus: could not terminate or shutdown {0}",
                        slave.getNodeName());
            }
        }

        // Disable template if applicable
        if (!templateStatus.equals(Constants.TEMPLATE_STATUS_ACTIVE_ALWAYS)) {
            setTemplateStatus(Constants.TEMPLATE_STATUS_DISBALED);
            // Register template for periodic check so that jenkins can make template active if validation errors
            // are corrected
            AzureTemplateMonitorTask.registerTemplate(this);
        } else {
            // Wait for a while before retry
            // Failure might be during Provisioning or post provisioning. back off for 5 minutes before retry.
            LOGGER.log(Level.INFO,
                    "AzureSlaveTemplate: handleTemplateStatus: Got {0} error, waiting for 5 minutes before retry",
                    failureStep);
            try {
                Thread.sleep(5 * 60 * 1000);
            } catch (InterruptedException e) {
            }
        }
        setTemplateStatusDetails(message);
    }

    public int getVirtualMachineCount() throws Exception {
        return AzureManagementServiceDelegate.getVirtualMachineCount(
                ServiceDelegateHelper.getComputeManagementClient(ServiceDelegateHelper.getConfiguration(azureCloud)));
    }

    public List<String> verifyTemplate() throws Exception {
        return AzureManagementServiceDelegate.verifyTemplate(
                azureCloud.getSubscriptionId(),
                azureCloud.getClientId(),
                azureCloud.getClientSecret(),
                azureCloud.getOauth2TokenEndpoint(),
                azureCloud.getServiceManagementURL(),
                azureCloud.getMaxVirtualMachinesLimit() + "",
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
                slaveLaunchMethod,
                initScript,
                adminUserName,
                adminPassword,
                virtualNetworkName,
                subnetName,
                retentionTimeInMin + "",
                templateStatus,
                jvmOptions,
                true);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<AzureSlaveTemplate> {

        @Override
        public String getDisplayName() {
            return null;
        }

        private synchronized List<String> getVMSizes(final String location) {
            return AzureManagementServiceDelegate.getVMSizes(location);
        }

        public ListBoxModel doFillVirtualMachineSizeItems(@QueryParameter final String location)
                throws IOException, ServletException {

            ListBoxModel model = new ListBoxModel();
            List<String> vmSizes = AzureManagementServiceDelegate.getVMSizes(location);

            for (String vmSize : vmSizes) {
                model.add(vmSize);
            }

            return model;
        }

        public ListBoxModel doFillOsTypeItems() throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            model.add("Linux");
            model.add("Windows");
            return model;
        }

        public ListBoxModel doFillLocationItems(
                @RelativePath("..") @QueryParameter final String serviceManagementURL)
                throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            
            List<String> locations = AzureManagementServiceDelegate.getVirtualMachineLocations(serviceManagementURL);
            
            for (String location : locations) {
                model.add(location);
            }

            return model;
        }

        public ListBoxModel doFillSlaveLaunchMethodItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.LAUNCH_METHOD_SSH);
            model.add(Constants.LAUNCH_METHOD_JNLP);

            return model;
        }

        public ListBoxModel doFillTemplateStatusItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.TEMPLATE_STATUS_ACTIVE);
            model.add(Constants.TEMPLATE_STATUS_ACTIVE_ALWAYS);
            model.add(Constants.TEMPLATE_STATUS_DISBALED);
            return model;
        }

        public FormValidation doCheckInitScript(
                @QueryParameter final String value,
                @QueryParameter final String slaveLaunchMethod) {
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

        public FormValidation doCheckSlaveLaunchMethod(@QueryParameter final String value) {
            if (Constants.LAUNCH_METHOD_JNLP.equals(value)) {
                return FormValidation.warning(Messages.Azure_GC_LaunchMethod_Warn_Msg());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckTemplateName(
                @QueryParameter final String value, @QueryParameter final String templateStatus) {
            if (templateStatus.equals(Constants.TEMPLATE_STATUS_DISBALED)) {
                return FormValidation.error(Messages.Azure_GC_TemplateStatus_Warn_Msg());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckAdminUserName(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                if (AzureUtil.isValidUserName(value)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(Messages.Azure_GC_UserName_Err());
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckNoOfParallelJobs(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                String result = AzureManagementServiceDelegate.verifyNoOfExecutors(value);

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
                String result = AzureManagementServiceDelegate.verifyRetentionTime(value);

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
                @RelativePath("..") @QueryParameter String subscriptionId,
                @RelativePath("..") @QueryParameter String clientId,
                @RelativePath("..") @QueryParameter String clientSecret,
                @RelativePath("..") @QueryParameter String oauth2TokenEndpoint,
                @RelativePath("..") @QueryParameter String serviceManagementURL,
                @RelativePath("..") @QueryParameter String maxVirtualMachinesLimit,
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
                @QueryParameter String slaveLaunchMethod,
                @QueryParameter String initScript,
                @QueryParameter String adminUserName,
                @QueryParameter String adminPassword,
                @QueryParameter String virtualNetworkName,
                @QueryParameter String subnetName,
                @QueryParameter String retentionTimeInMin,
                @QueryParameter String templateStatus,
                @QueryParameter String jvmOptions) {

            LOGGER.log(Level.INFO,
                    "Verify configuration:\n\t"
                    + "subscriptionId: {0};\n\t"
                    + "clientId: {1};\n\t"
                    + "clientSecret: {2};\n\t"
                    + "oauth2TokenEndpoint: {3};\n\t"
                    + "serviceManagementURL: {4};\n\t"
                    + "maxVirtualMachinesLimit: {5};\n\t"
                    + "templateName: {6};\n\t"
                    + "labels: {7};\n\t"
                    + "location: {8};\n\t"
                    + "virtualMachineSize: {9};\n\t"
                    + "storageAccountName: {10};\n\t"
                    + "noOfParallelJobs: {11};\n\t"
                    + "image: {12};\n\t"
                    + "osType: {13};\n\t"
                    + "imagePublisher: {14};\n\t"
                    + "imageOffer: {15};\n\t"
                    + "imageSku: {16};\n\t"
                    + "imageVersion: {17};\n\t"
                    + "slaveLaunchMethod: {18};\n\t"
                    + "initScript: {19};\n\t"
                    + "adminUserName: {20};\n\t"
                    + "adminPassword: {21};\n\t"
                    + "virtualNetworkName: {22};\n\t"
                    + "subnetName: {23};\n\t"
                    + "retentionTimeInMin: {24};\n\t"
                    + "templateStatus: {25};\n\t"
                    + "jvmOptions: {26}.",
                    new Object[] {
                        subscriptionId,
                        clientId,
                        (StringUtils.isNotBlank(clientSecret) ? "********" : null),
                        oauth2TokenEndpoint,
                        serviceManagementURL,
                        maxVirtualMachinesLimit,
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
                        slaveLaunchMethod,
                        initScript,
                        adminUserName,
                        (StringUtils.isNotBlank(adminPassword) ? "********" : null),
                        virtualNetworkName,
                        subnetName,
                        retentionTimeInMin,
                        templateStatus,
                        jvmOptions });

            final List<String> errors = AzureManagementServiceDelegate.verifyTemplate(
                    subscriptionId,
                    clientId,
                    clientSecret,
                    oauth2TokenEndpoint,
                    serviceManagementURL,
                    maxVirtualMachinesLimit,
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
                    slaveLaunchMethod,
                    initScript,
                    adminUserName,
                    adminPassword,
                    virtualNetworkName,
                    subnetName,
                    retentionTimeInMin,
                    templateStatus,
                    jvmOptions,
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
            return 1 + "";
        }
    }

    public void setVirtualMachineDetails(final AzureSlave slave) throws Exception {
        AzureManagementServiceDelegate.setVirtualMachineDetails(slave, this);
    }
}
