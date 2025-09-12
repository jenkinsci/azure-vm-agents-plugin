package com.microsoft.azure.vmagent.availability;

import com.azure.core.management.Region;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.ComputeResourceType;
import com.azure.resourcemanager.resources.fluentcore.arm.AvailabilityZoneId;
import com.microsoft.azure.vmagent.AzureVMCloud;
import com.microsoft.azure.vmagent.Messages;
import com.microsoft.jenkins.credentials.AzureResourceManagerCache;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.RelativePath;
import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class AvailabilityZone extends AzureAvailabilityType {

    private static final Logger LOGGER = Logger.getLogger(AvailabilityZone.class.getName());
    public static final String AZURE_SELECTED = "AZURE_SELECTED";

    private final String zone;

    @DataBoundConstructor
    public AvailabilityZone(String zone) {
        this.zone = zone;
    }

    public String getZone() {
        return zone;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AvailabilityZone that = (AvailabilityZone) o;
        return Objects.equals(zone, that.zone);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(zone);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<AzureAvailabilityType> {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.availabilityZone();
        }

        @POST
        public ListBoxModel doFillZoneItems(
                @RelativePath("..") @QueryParameter("cloudName") String cloudName,
                @RelativePath("..") @QueryParameter String location,
                @RelativePath("..") @QueryParameter String virtualMachineSize
        ) {
            Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);

            ListBoxModel model = new ListBoxModel();

            AzureVMCloud cloud = getAzureCloud(cloudName);
            if (cloud == null) {
                return model;
            }

            String azureCredentialsId = cloud.getAzureCredentialsId();
            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }

            try {
                AzureResourceManager azureClient = AzureResourceManagerCache.get(azureCredentialsId);
                if (azureClient == null) {
                    return model;
                }
                Region region = Region.fromName(location);
                Set<String> zones = getZonesBy(virtualMachineSize, region, azureClient);

                List<ListBoxModel.Option> options = zones.stream()
                        .map(zone -> new ListBoxModel.Option(zone, zone))
                        .toList();

                model = new ListBoxModel(options);
                model.add(Messages.AvailabilityZone_azureSelected(), AZURE_SELECTED);

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Cannot list availability set: ", e);
            }
            return model;
        }

        private static Set<String> getZonesBy(
                String virtualMachineSize,
                Region region,
                AzureResourceManager azureClient
        ) {
            return azureClient.computeSkus()
                    .listByRegionAndResourceType(region, ComputeResourceType.VIRTUALMACHINES)
                    .stream()
                    // quite an expensive operation currently
                    // as we need to filter by a size and can't request that in the API call
                    // possibly should be cached
                    .filter(sku -> sku.name().toString().equalsIgnoreCase(virtualMachineSize))
                    .map(sku -> sku.zones().get(region))
                    .filter(Objects::nonNull)
                    .flatMap(Set::stream)
                    .map(AvailabilityZoneId::toString)
                    .collect(Collectors.toSet());
        }

        private AzureVMCloud getAzureCloud(String cloudName) {
            Cloud cloud = Jenkins.get().getCloud(cloudName);

            if (cloud instanceof AzureVMCloud azureCloud) {
                return azureCloud;
            }

            return null;
        }

    }
}
