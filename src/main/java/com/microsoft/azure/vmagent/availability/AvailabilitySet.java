package com.microsoft.azure.vmagent.availability;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.microsoft.azure.vmagent.AzureVMCloud;
import com.microsoft.azure.vmagent.Messages;
import com.microsoft.jenkins.credentials.AzureResourceManagerCache;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.RelativePath;
import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import hudson.util.ListBoxModel;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class AvailabilitySet extends AzureAvailabilityType {

    private static final Logger LOGGER = Logger.getLogger(AvailabilitySet.class.getName());

    private final String name;

    @DataBoundConstructor
    public AvailabilitySet(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AvailabilitySet that = (AvailabilitySet) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<AzureAvailabilityType> {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.availabilitySet();
        }

        @POST
        public ListBoxModel doFillNameItems(
                @RelativePath("..") @QueryParameter("cloudName") String cloudName,
                @RelativePath("..") @QueryParameter String location) {
            Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);

            ListBoxModel model = new ListBoxModel();
            model.add("--- Select Availability Set in current resource group and location ---", "");

            AzureVMCloud cloud = getAzureCloud(cloudName);
            if (cloud == null) {
                return model;
            }

            String azureCredentialsId = cloud.getAzureCredentialsId();
            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }

            String resourceGroupReferenceType = cloud.getResourceGroupReferenceType();
            String newResourceGroupName = cloud.getNewResourceGroupName();
            String existingResourceGroupName = cloud.getExistingResourceGroupName();


            try {
                AzureResourceManager azureClient = AzureResourceManagerCache.get(azureCredentialsId);
                if (azureClient == null) {
                    return model;
                }
                String resourceGroupName = AzureVMCloud.getResourceGroupName(
                        resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
                PagedIterable<com.azure.resourcemanager.compute.models.AvailabilitySet> availabilitySets =
                        azureClient.availabilitySets()
                        .listByResourceGroup(resourceGroupName);
                for (com.azure.resourcemanager.compute.models.AvailabilitySet set : availabilitySets) {
                    String label = set.region().label();
                    if (label.equals(location)) {
                        model.add(set.name());
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Cannot list availability set: ", e);
            }
            return model;
        }

        private AzureVMCloud getAzureCloud(String cloudName) {
            Cloud cloud = Jenkins.get().getCloud(cloudName);

            if (cloud instanceof AzureVMCloud) {
                return (AzureVMCloud) cloud;
            }

            return null;
        }

    }
}
