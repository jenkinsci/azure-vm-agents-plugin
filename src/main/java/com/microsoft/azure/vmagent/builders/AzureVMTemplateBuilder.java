package com.microsoft.azure.vmagent.builders;

import com.microsoft.azure.vmagent.util.Constants;
import hudson.model.Node;

public class AzureVMTemplateBuilder {

    private String name;

    private String description;

    private String workspace;

    private String labels;

    private String location;

    private String virtualMachineSize;

    private String storageAccountType;

    private String storageAccountNameReferenceType;

    private String diskType;

    private String newStorageAccountName;

    private String existingStorageAccountName;

    private int retentionTime;

    private boolean shutdownOnIdle;

    private String usageMode;

    private String imageTopLevelType;

    private BuiltInImage builtInImage;

    private AdvancedImage advancedImage;

    private String credentialsId;

    public AzureVMTemplateBuilder() {
        location = "Japan West";
        virtualMachineSize = "Standard_A0";
        storageAccountType = "Standard_LRS";
        storageAccountNameReferenceType = "new";
        diskType = Constants.DISK_MANAGED;
        retentionTime = 60;
        shutdownOnIdle = false;
        usageMode = "Use this node as much as possible";
        imageTopLevelType = Constants.IMAGE_TOP_LEVEL_BASIC;
        builtInImage = new BuiltInImage(Constants.WINDOWS_SERVER_2016, false, false, false);
    }

    public AzureVMTemplateBuilder withName(String name) {
        this.name = name;
        return this;
    }

    public AzureVMTemplateBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public AzureVMTemplateBuilder withWorkspace(String workspace) {
        this.workspace = workspace;
        return this;
    }

    public AzureVMTemplateBuilder withLabels(String labels) {
        this.labels = labels;
        return this;
    }

    public AzureVMTemplateBuilder withLocation(String location) {
        this.location = location;
        return this;
    }

    public AzureVMTemplateBuilder withVirtualMachineSize(String virtualMachineSize) {
        this.virtualMachineSize = virtualMachineSize;
        return this;
    }

    public AzureVMTemplateBuilder withStorageAccountType(String storageAccountType) {
        this.storageAccountType = storageAccountType;
        return this;
    }

    public AzureVMTemplateBuilder withNewStorageAccount(String storageAccountName) {
        this.storageAccountNameReferenceType = "new";
        this.newStorageAccountName = storageAccountName;
        return this;
    }

    public AzureVMTemplateBuilder withExistingStorageAccount(String storageAccountName) {
        this.storageAccountNameReferenceType = "existing";
        this.existingStorageAccountName = storageAccountName;
        return this;
    }

    public AzureVMTemplateBuilder withDiskType(String diskType) {
        this.diskType = diskType;
        return this;
    }

    public AzureVMTemplateBuilder withRetentionTime(int retentionTime) {
        this.retentionTime = retentionTime;
        return this;
    }

    public AzureVMTemplateBuilder withShutdownOnIdle(boolean isShutdown) {
        this.shutdownOnIdle = isShutdown;
        return this;
    }

    public AzureVMTemplateBuilder withUsageMode(String usageMode) {
        this.usageMode = usageMode;
        return this;
    }

    public AzureVMTemplateBuilder withBuiltInImage(BuiltInImage builtInImage) {
        this.imageTopLevelType = Constants.IMAGE_TOP_LEVEL_BASIC;
        this.builtInImage = builtInImage;
        return this;
    }

    public AzureVMTemplateBuilder.BuiltInImageNested withNewBuiltInImage() {
        return new BuiltInImageNested();
    }

    public AzureVMTemplateBuilder withAdvancedImage(AdvancedImage advancedImage) {
        this.imageTopLevelType = Constants.IMAGE_TOP_LEVEL_ADVANCED;
        this.advancedImage = advancedImage;
        return this;
    }

    public AzureVMTemplateBuilder.AdvancedImageNested withNewAdvancedImage() {
        return new AdvancedImageNested();
    }

    public AzureVMTemplateBuilder withAdminCredential(String credentialsId) {
        this.credentialsId = credentialsId;
        return this;
    }


    public class BuiltInImageNested {

        private final BuiltInImageBuilder builder;

        BuiltInImageNested() {
            this.builder = new BuiltInImageBuilder();
        }

        public BuiltInImageNested withBuiltInImage(String builtInImage) {
            builder.withBuiltInImage(builtInImage);
            return this;
        }

        public BuiltInImageNested withInstallGit(boolean installGit) {
            builder.withInstallGit(installGit);
            return this;
        }

        public BuiltInImageNested withInstallMaven(boolean installMaven) {
            builder.withInstallMaven(installMaven);
            return this;
        }

        public BuiltInImageNested withInstallDocker(boolean installDocker) {
            builder.withInstallDocker(installDocker);
            return this;
        }

        public AzureVMTemplateBuilder endBuiltInImage() {
            return AzureVMTemplateBuilder.this.withBuiltInImage(builder.build());
        }
    }

    public class AdvancedImageNested {

        private final AdvancedImageBuilder builder;

        AdvancedImageNested() {
            this.builder = new AdvancedImageBuilder();
        }

        public AdvancedImageNested withOsType(String osType) {
            builder.osType = osType;
            return this;
        }

        public AdvancedImageNested withLaunchMethod(String launchMethod) {
            builder.agentLaunchMethod = launchMethod;
            return this;
        }

        public AdvancedImageNested withPreInstallSsh(boolean preInstallSsh) {
            builder.preInstallSsh = preInstallSsh;
            return this;
        }

        public AdvancedImageNested withInitScript(String initScript) {
            builder.initScript = initScript;
            return this;
        }

        public AdvancedImageNested withRunScriptAsRoot(boolean executeInitScriptAsRoot) {
            builder.executeInitScriptAsRoot = executeInitScriptAsRoot;
            return this;
        }

        public AdvancedImageNested withDoNotUseMachineIfInitFails(boolean doNotUseMachineIfInitFails) {
            builder.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
            return this;
        }

        public AdvancedImageNested withVirtualNetworkName(String virtualNetworkName) {
            builder.virtualNetworkName = virtualNetworkName;
            return this;
        }

        public AdvancedImageNested withVirtualNetworkResourceGroupName(String virtualNetworkResourceGroupName) {
            builder.virtualNetworkResourceGroupName = virtualNetworkResourceGroupName;
            return this;
        }

        public AdvancedImageNested withSubnetName(String subnetName) {
            builder.subnetName = subnetName;
            return this;
        }

        public AdvancedImageNested withUsePrivateIP(boolean usePrivateIP) {
            builder.usePrivateIP = usePrivateIP;
            return this;
        }

        public AdvancedImageNested withNetworkSecurityGroupName(String nsgName) {
            builder.nsgName = nsgName;
            return this;
        }

        public AdvancedImageNested withJvmOptions(String jvmOptions) {
            builder.jvmOptions = jvmOptions;
            return this;
        }

        public AdvancedImageNested withNumberOfExecutors(int noOfParallelJobs) {
            builder.noOfParallelJobs = noOfParallelJobs;
            return this;
        }
        public AdvancedImageNested withDisableTemplate(boolean templateDisabled) {
            builder.templateDisabled = templateDisabled;
            return this;
        }

        public AzureVMTemplateBuilder endAdvancedImage() {
            return AzureVMTemplateBuilder.this.withAdvancedImage(builder.build());
        }
    }

}
