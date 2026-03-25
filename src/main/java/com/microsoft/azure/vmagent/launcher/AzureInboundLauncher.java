package com.microsoft.azure.vmagent.launcher;

import com.microsoft.azure.vmagent.Messages;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

public class AzureInboundLauncher extends AzureComputerLauncher {

    private static final long serialVersionUID = 6562610892063268131L;

    @DataBoundConstructor
    public AzureInboundLauncher() {
    }

    @Override
    public String toString() {
        return "AzureInboundLauncher{}";
    }

    @Extension
    @Symbol("inbound")
    public static class DescriptorImpl extends Descriptor<AzureComputerLauncher> {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.AzureInboundLauncher_DisplayName();
        }
    }
}
