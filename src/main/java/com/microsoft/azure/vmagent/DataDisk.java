package com.microsoft.azure.vmagent;

import com.azure.resourcemanager.compute.models.CachingTypes;
import com.azure.resourcemanager.compute.models.DiskSkuTypes;
import com.azure.resourcemanager.storage.models.SkuName;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.Serializable;

public class DataDisk implements Describable<DataDisk>, Serializable {

    private static final long serialVersionUID = -9077525603499087689L;

    private final int diskSize;
    private final String diskCache;
    private final String storageAccountType;

    @DataBoundConstructor
    public DataDisk(int diskSize, String diskCache, String storageAccountType) {
        this.diskSize = diskSize;
        this.diskCache = diskCache;
        this.storageAccountType = storageAccountType;
    }

    public int getDiskSize() {
        return this.diskSize;
    }

    public String getDiskCache() {
        return StringUtils.isBlank(this.diskCache) ? CachingTypes.NONE.toString() : this.diskCache;
    }

    public String getStorageAccountType() {
        return StringUtils.isBlank(this.storageAccountType) ? SkuName.STANDARD_LRS.toString() : this.storageAccountType;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<DataDisk> {

        @Override
        public String getDisplayName() {
            return "Data Disk";
        }

        public ListBoxModel doFillDiskCacheItems() {
            ListBoxModel model = new ListBoxModel();
            model.add("--- Select Disk Cache ---", "");

            model.add(CachingTypes.NONE.toString());
            model.add(CachingTypes.READ_ONLY.toString());
            model.add(CachingTypes.READ_WRITE.toString());
            return model;
        }

        public ListBoxModel doFillStorageAccountTypeItems() {
            ListBoxModel model = new ListBoxModel();
            model.add("--- Select Storage Account Type ---", "");

            model.add(DiskSkuTypes.STANDARD_LRS.toString());
            model.add(DiskSkuTypes.STANDARD_SSD_LRS.toString());
            model.add(DiskSkuTypes.PREMIUM_LRS.toString());
            model.add(DiskSkuTypes.PREMIUM_V2_LRS.toString());
            model.add(DiskSkuTypes.ULTRA_SSD_LRS.toString());
            return model;
        }

        @POST
        public FormValidation doCheckDiskSize(@QueryParameter String value) {
            try {
                int diskSize = Integer.parseInt(value);
                if (diskSize < 1) {
                    return FormValidation.error("Disk size must be greater than 0 GB");
                }
            } catch (NumberFormatException e) {
                return FormValidation.error("Disk size must be a valid integer");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckStorageAccountType(
                @QueryParameter String value,
                @QueryParameter String diskCache) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.ok();
            }

            boolean isPremiumV2 = DiskSkuTypes.PREMIUM_V2_LRS.toString().equals(value);
            boolean isUltraSSD = DiskSkuTypes.ULTRA_SSD_LRS.toString().equals(value);
            if ((isPremiumV2 || isUltraSSD) && !StringUtils.isBlank(diskCache)
                    && !CachingTypes.NONE.toString().equals(diskCache)) {
                return FormValidation.error("Disk caching is not supported for the selected storage account type."
                        + " Please select 'None' for Disk Cache.");
            }

            return FormValidation.ok();
        }
    }
}
