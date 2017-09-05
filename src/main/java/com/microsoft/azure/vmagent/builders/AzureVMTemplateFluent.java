package com.microsoft.azure.vmagent.builders;

import com.microsoft.azure.vmagent.util.Constants;

public class AzureVMTemplateFluent<T extends AzureVMTemplateFluent<T>> {

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

    private String retentionTime;

    private boolean shutdownOnIdle;

    private String usageMode;

    private String imageTopLevelType;

    private BuiltInImage builtInImage;

    private AdvancedImage advancedImage;

    private String credentialsId;

    public AzureVMTemplateFluent() {
        location = "Japan West";
        virtualMachineSize = "Standard_A0";
        storageAccountType = "Standard_LRS";
        storageAccountNameReferenceType = "new";
        diskType = Constants.DISK_MANAGED;
        retentionTime = "60";
        shutdownOnIdle = false;
        usageMode = "Use this node as much as possible";
        imageTopLevelType = Constants.IMAGE_TOP_LEVEL_BASIC;
        builtInImage = new BuiltInImageBuilder().build();
        advancedImage = new AdvancedImageBuilder().build();
    }

    //CHECKSTYLE:OFF
    public T withName(String name) {
        this.name = name;
        return (T) this;
    }

    public T withDescription(String description) {
        this.description = description;
        return (T) this;
    }

    public T withWorkspace(String workspace) {
        this.workspace = workspace;
        return (T) this;
    }

    public T withLabels(String labels) {
        this.labels = labels;
        return (T) this;
    }

    public T withLocation(String location) {
        this.location = location;
        return (T) this;
    }

    public T withVirtualMachineSize(String virtualMachineSize) {
        this.virtualMachineSize = virtualMachineSize;
        return (T) this;
    }

    public T withStorageAccountType(String storageAccountType) {
        this.storageAccountType = storageAccountType;
        return (T) this;
    }

    public T withNewStorageAccount(String storageAccountName) {
        this.storageAccountNameReferenceType = "new";
        this.newStorageAccountName = storageAccountName;
        return (T) this;
    }

    public T withExistingStorageAccount(String storageAccountName) {
        this.storageAccountNameReferenceType = "existing";
        this.existingStorageAccountName = storageAccountName;
        return (T) this;
    }

    public T withDiskType(String diskType) {
        this.diskType = diskType;
        return (T) this;
    }

    public T withRetentionTime(String retentionTime) {
        this.retentionTime = retentionTime;
        return (T) this;
    }

    public T withShutdownOnIdle(boolean isShutdown) {
        this.shutdownOnIdle = isShutdown;
        return (T) this;
    }

    public T withUsageMode(String usageMode) {
        this.usageMode = usageMode;
        return (T) this;
    }

    public T withBuiltInImage(BuiltInImage builtInImage) {
        this.imageTopLevelType = Constants.IMAGE_TOP_LEVEL_BASIC;
        this.builtInImage = builtInImage;
        return (T) this;
    }

    public BuiltInImageNested addNewBuiltInImage() {
        return new BuiltInImageNested();
    }

    public T withAdvancedImage(AdvancedImage advancedImage) {
        this.imageTopLevelType = Constants.IMAGE_TOP_LEVEL_ADVANCED;
        this.advancedImage = advancedImage;
        return (T) this;
    }

    public AdvancedImageNested addNewAdvancedImage() {
        return new AdvancedImageNested();
    }

    public T withAdminCredential(String credentialsId) {
        this.credentialsId = credentialsId;
        return (T) this;
    }
    //CHECKSTYLE:ON

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getWorkspace() {
        return workspace;
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

    public String getStorageAccountType() {
        return storageAccountType;
    }

    public String getStorageAccountNameReferenceType() {
        return storageAccountNameReferenceType;
    }

    public String getDiskType() {
        return diskType;
    }

    public String getNewStorageAccountName() {
        return newStorageAccountName;
    }

    public String getExistingStorageAccountName() {
        return existingStorageAccountName;
    }

    public String getRetentionTime() {
        return retentionTime;
    }

    public boolean isShutdownOnIdle() {
        return shutdownOnIdle;
    }

    public String getUsageMode() {
        return usageMode;
    }

    public String getImageTopLevelType() {
        return imageTopLevelType;
    }

    public BuiltInImage getBuiltInImage() {
        return builtInImage;
    }

    public AdvancedImage getAdvancedImage() {
        return advancedImage;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public class BuiltInImageNested {

        private final BuiltInImageBuilder builder;

        BuiltInImageNested() {
            this.builder = new BuiltInImageBuilder();
        }

        public BuiltInImageNested withBuiltInImageName(String builtInImageName) {
            builder.withBuiltInImageName(builtInImageName);
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

        public T endBuiltInImage() {
            return AzureVMTemplateFluent.this.withBuiltInImage(builder.build());
        }
    }

    public class AdvancedImageNested extends AdvancedImageFluent<AdvancedImageNested> {

        private final AdvancedImageBuilder builder;

        AdvancedImageNested() {
            this.builder = new AdvancedImageBuilder(this);
        }

        public T endAdvancedImage() {
            return AzureVMTemplateFluent.this.withAdvancedImage(builder.build());
        }
    }
}
