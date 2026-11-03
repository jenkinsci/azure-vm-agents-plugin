package com.microsoft.azure.vmagent;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.Serializable;

public class AutoLogon implements Describable<AutoLogon>, Serializable {

    private static final long serialVersionUID = 1L;

    private String script;

    @DataBoundConstructor
    public AutoLogon() {
    }

    public String getScript() {
        return script;
    }

    @DataBoundSetter
    public void setScript(String script) {
        this.script = script;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<AutoLogon> {
    }
}
