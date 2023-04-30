package com.microsoft.azure.vmagent.launcher;

import com.microsoft.azure.vmagent.Messages;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import java.util.Objects;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class AzureSSHLauncher extends AzureComputerLauncher {

    private static final long serialVersionUID = 6562610892063268131L;

    private String sshConfig;
    private boolean preInstallSsh;

    @DataBoundConstructor
    public AzureSSHLauncher() {
    }

    public String getSshConfig() {
        return sshConfig;
    }

    @DataBoundSetter
    public void setSshConfig(String sshConfig) {
        this.sshConfig = sshConfig;
    }

    public boolean isPreInstallSsh() {
        return preInstallSsh;
    }

    @Override
    public String toString() {
        return String.format("AzureSSHLauncher{sshConfig='%s', preInstallSsh=%s}",
                sshConfig, preInstallSsh);
    }

    @DataBoundSetter
    public void setPreInstallSsh(boolean preInstallSsh) {
        this.preInstallSsh = preInstallSsh;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AzureSSHLauncher that = (AzureSSHLauncher) o;
        return preInstallSsh == that.preInstallSsh && Objects.equals(sshConfig, that.sshConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sshConfig, preInstallSsh);
    }

    @Extension
    @Symbol("ssh")
    public static class DescriptorImpl extends Descriptor<AzureComputerLauncher> {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.AzureSSHLauncher_DisplayName();
        }
    }
}
