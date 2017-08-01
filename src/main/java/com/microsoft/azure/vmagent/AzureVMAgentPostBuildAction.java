/*
 Copyright 2016 Microsoft, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.util.CleanUpAction;

import java.io.IOException;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ListBoxModel;

import java.util.logging.Level;

public class AzureVMAgentPostBuildAction extends Recorder {

    /**
     * Windows Azure Storage Account Name.
     */
    private final String agentPostBuildAction;

    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentPostBuildAction.class.getName());

    @DataBoundConstructor
    public AzureVMAgentPostBuildAction(String agentPostBuildAction) {
        super();
        this.agentPostBuildAction = agentPostBuildAction;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                           BuildListener listener) throws InterruptedException, IOException {
        Computer computer = Computer.currentComputer();
        Node node = computer.getNode();

        if (!(node instanceof AzureVMAgent)) {
            // We don't own this node.  Nothing to do.
            return true;
        }

        AzureVMAgent agent = (AzureVMAgent) node;
        AzureVMComputer azureComputer = (AzureVMComputer) computer;
        LOGGER.log(Level.INFO,
                "AzureVMAgentPostBuildAction: perform: build action {0} for agent {1}",
                new Object[]{agentPostBuildAction, agent.getNodeName()});

        // If the node has been taken offline by the user, skip the postbuild task.
        if (azureComputer.isSetOfflineByUser()) {
            LOGGER.log(Level.INFO,
                    "AzureVMAgentPostBuildAction: perform: agent {0} was taken offline by user, skipping postbuild",
                    agent.getNodeName());
            return true;
        }

        azureComputer.setAcceptingTasks(false);

        // The post build action cannot immediately delete the node
        // Doing so would cause the post build action to show some wacky errors
        // and potentially fail.  We also don't want to delete the machine if
        // other stuff was running at the moment.  The cleanup action will set the machine
        // offline and it will
        if (Messages.Build_Action_Shutdown_Agent().equalsIgnoreCase(agentPostBuildAction)) {
            agent.setCleanUpAction(CleanUpAction.SHUTDOWN, Messages._Build_Action_Shutdown_Agent());
        } else if (Messages.Build_Action_Delete_Agent_If_Not_Success().equalsIgnoreCase(
                agentPostBuildAction) && (build.getResult() != Result.SUCCESS)) {
            agent.setCleanUpAction(CleanUpAction.DELETE, Messages._Build_Action_Delete_Agent_If_Not_Success());
        } else if (Messages.Build_Action_Delete_Agent().equalsIgnoreCase(agentPostBuildAction)) {
            agent.setCleanUpAction(CleanUpAction.DELETE, Messages._Build_Action_Delete_Agent());
        }

        return true;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.STEP;
    }

    @Extension
    public static final class AzureAgentPostBuildDescriptor extends
            BuildStepDescriptor<Publisher> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> arg0) {
            return true;
        }

        public ListBoxModel doFillAgentPostBuildActionItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Messages.Build_Action_Shutdown_Agent());
            model.add(Messages.Build_Action_Delete_Agent());
            model.add(Messages.Build_Action_Delete_Agent_If_Not_Success());
            return model;
        }

        @Override
        public String getDisplayName() {
            // TODO Auto-generated method stub
            return Messages.Azure_Agent_Post_Build_Action();
        }

    }

}
