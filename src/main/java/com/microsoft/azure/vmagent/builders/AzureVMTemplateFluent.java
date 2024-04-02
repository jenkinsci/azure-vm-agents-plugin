package com.microsoft.azure.vmagent.builders;

import com.microsoft.azure.vmagent.AzureTagPair;
import com.microsoft.azure.vmagent.AzureVMCloudBaseRetentionStrategy;
import com.microsoft.azure.vmagent.AzureVMCloudPoolRetentionStrategy;
import com.microsoft.azure.vmagent.AzureVMCloudRetensionStrategy;
import com.microsoft.azure.vmagent.util.Constants;
import hudson.model.Node;

import java.util.ArrayList;
import java.util.List;

public class AzureVMTemplateFluent<T extends AzureVMTemplateFluent<T>> {

    private String name;

    private String description;

    private String workspace;

    private String labels;

    private String location;

    private Availability availability;

    private String virtualMachineSize;

    private String storageAccountType;

    private String storageAccountNameReferenceType;

    private String diskType;

    private boolean ephemeralOSDisk;

    private boolean encryptionAtHost;

    private int osDiskSize;

    private String newStorageAccountName;

    private String existingStorageAccountName;

    private AzureVMCloudBaseRetentionStrategy retentionStrategy;

    private boolean shutdownOnIdle;

    private Node.Mode usageMode;

    private String imageTopLevelType;

    private BuiltInImage builtInImage;

    private AdvancedImage advancedImage;

    private String credentialsId;

    private List<AzureTagPair> cloudTags;

    public AzureVMTemplateFluent() {
        location = "Japan West";
        virtualMachineSize = "Standard_A0";
        storageAccountType = "Standard_LRS";
        storageAccountNameReferenceType = "new";
        diskType = Constants.DISK_MANAGED;
        osDiskSize = 0;
        retentionStrategy = new AzureVMCloudRetensionStrategy(Constants.DEFAULT_IDLE_RETENTION_TIME);
        shutdownOnIdle = false;
        usageMode = Node.Mode.NORMAL;
        imageTopLevelType = Constants.IMAGE_TOP_LEVEL_BASIC;
        availability = new AvailabilityBuilder().build();
        builtInImage = new BuiltInImageBuilder().build();
        advancedImage = new AdvancedImageBuilder().build();
        cloudTags = new ArrayList<>();
    }

    //CHECKSTYLE:OFF
    public T withName(String name) {
        this.name = name;
        return (T) this;
    }

    public T withTags(List<AzureTagPair> tags) {
        this.cloudTags = new ArrayList<>(tags);
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

    public T withAvailability(Availability availability) {
        this.availability = availability;
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

    public T withEphemeralOSDisk(boolean isEphemeral) {
        this.ephemeralOSDisk = isEphemeral;
        return (T) this;
    }

    public T withEncryptionAtHost(boolean isEncryptionAtHost) {
        this.encryptionAtHost = isEncryptionAtHost;
        return (T) this;
    }

    public T withOsDiskSize(int osDiskSize) {
        this.osDiskSize = osDiskSize;
        return (T) this;
    }

    public T withRetentionStrategy(AzureVMCloudBaseRetentionStrategy retentionStrategy) {
        this.retentionStrategy = retentionStrategy;
        return (T) this;
    }

    public T addNewIdleRetentionStrategy(String retentionTime) {
        this.retentionStrategy = new AzureVMCloudRetensionStrategy(Integer.parseInt(retentionTime));
        return (T) this;
    }

    public T addNewPoolRetentionStrategy(String retentionTime, String poolSize, boolean singleUseAgents) {
        AzureVMCloudPoolRetentionStrategy retentionStrategy1 = new AzureVMCloudPoolRetentionStrategy(Integer.parseInt(retentionTime),
                Integer.parseInt(poolSize));
        retentionStrategy1.setSingleUseAgents(singleUseAgents);
        this.retentionStrategy = retentionStrategy1;
        return (T) this;
    }

    public T withShutdownOnIdle(boolean isShutdown) {
        this.shutdownOnIdle = isShutdown;
        return (T) this;
    }

    public T withUsageMode(Node.Mode usageMode) {
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

    public BuiltInImageNested addNewBuiltInImageLike(BuiltInImage image) {
        return new BuiltInImageNested(image);
    }

    public T withAdvancedImage(AdvancedImage advancedImage) {
        this.imageTopLevelType = Constants.IMAGE_TOP_LEVEL_ADVANCED;
        this.advancedImage = advancedImage;
        return (T) this;
    }

    public AdvancedImageNested addNewAdvancedImage() {
        return new AdvancedImageNested();
    }

    public AdvancedImageNested addNewAdvancedImageLike(AdvancedImage image) {
        return new AdvancedImageNested(image);
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

    public Availability getAvailability() {
        return availability;
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

    public boolean isEphemeralOSDisk() {
        return ephemeralOSDisk;
    }

    public boolean isEncryptionAtHost() {
        return encryptionAtHost;
    }

    public int getOsDiskSize() {
        return osDiskSize;
    }

    public String getNewStorageAccountName() {
        return newStorageAccountName;
    }

    public String getExistingStorageAccountName() {
        return existingStorageAccountName;
    }

    public AzureVMCloudBaseRetentionStrategy getRetentionStrategy() {
        return retentionStrategy;
    }

    public boolean isShutdownOnIdle() {
        return shutdownOnIdle;
    }

    public Node.Mode getUsageMode() {
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

    List<AzureTagPair> getCloudTags() {
        return new ArrayList<>(cloudTags);
    }

    public class BuiltInImageNested extends BuiltInImageFluent<BuiltInImageNested> {

        private final BuiltInImageBuilder builder;

        BuiltInImageNested() {
            this.builder = new BuiltInImageBuilder(this);
        }

        BuiltInImageNested(BuiltInImage image) {
            this.builder = new BuiltInImageBuilder(this, image);
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

        AdvancedImageNested(AdvancedImage image) {
            this.builder = new AdvancedImageBuilder(this, image);
        }

        public T endAdvancedImage() {
            return AzureVMTemplateFluent.this.withAdvancedImage(builder.build());
        }
    }
}
