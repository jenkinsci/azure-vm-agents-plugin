package com.microsoft.azure.vmagent.availability;

import com.microsoft.azure.vmagent.Messages;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

public class NoAvailabilityRequired extends AzureAvailabilityType {

    @DataBoundConstructor
    public NoAvailabilityRequired() {
    }

    @Extension
    @Symbol("none")
    public static final class DescriptorImpl extends Descriptor<AzureAvailabilityType> {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.noAvailabilityRequired();
        }
    }
}
