package com.microsoft.azure.vmagent;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import java.io.Serializable;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

public class FirstLogonCommand implements Describable<FirstLogonCommand>, Serializable {

    private static final long serialVersionUID = 1L;

    private final String command;

    @DataBoundConstructor
    public FirstLogonCommand(String command) {
        this.command = command;
    }

    public String getCommand() {
        return command;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Descriptor<FirstLogonCommand> getDescriptor() {
        return Jenkins.get().getDescriptor(getClass());
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<FirstLogonCommand> {
        @Override
        public String getDisplayName() {
            return "First Logon Command";
        }
    }
}
