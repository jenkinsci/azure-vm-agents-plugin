/*
 Copyright 2014 Microsoft Open Technologies, Inc.
 
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
package com.microsoftopentechnologies.azure;

import static com.microsoftopentechnologies.azure.Messages._Build_Action_Shutdown_Slave;
import com.microsoftopentechnologies.azure.util.CleanUpAction;
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
import hudson.slaves.OfflineCause;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ListBoxModel;
import java.util.logging.Level;

public class AzureSlavePostBuildAction extends Recorder {

    /** Windows Azure Storage Account Name. */
    private final String slavePostBuildAction;

    private static final Logger LOGGER = Logger.getLogger(AzureSlavePostBuildAction.class.getName());

    @DataBoundConstructor
    public AzureSlavePostBuildAction(final String slavePostBuildAction) {
        super();
        this.slavePostBuildAction = slavePostBuildAction;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
            BuildListener listener) throws InterruptedException, IOException {
        Computer computer = Computer.currentComputer();
        Node node = computer.getNode();

        if (!(node instanceof AzureSlave)) {
            // We don't own this node.  Nothing to do.
            return true;
        }
        
        AzureSlave slave = (AzureSlave)node;
        AzureComputer azureComputer = (AzureComputer)computer;
        LOGGER.log(Level.INFO,
                "AzureSlavePostBuildAction: perform: build action {0} for slave {1}",
                new Object [] { slavePostBuildAction, slave.getNodeName() });
        
        // If the node has been taken offline by the user, skip the postbuild task.
        if (azureComputer.isSetOfflineByUser()) {
            LOGGER.log(Level.INFO,
                "AzureSlavePostBuildAction: perform: slave {0} was taken offline by user, skipping postbuild",
                slave.getNodeName());
            return true;
        }
        
        azureComputer.setAcceptingTasks(false);
        
        // The post build action cannot immediately delete the node
        // Doing so would cause the post build action to show some wacky errors
        // and potentially fail.  We also don't want to delete the machine if
        // other stuff was running at the moment.  The cleanup action will set the machine
        // offline and it will 
        if (Messages.Build_Action_Shutdown_Slave().equalsIgnoreCase(slavePostBuildAction)) {
            slave.setCleanUpAction(CleanUpAction.SHUTDOWN, Messages._Build_Action_Shutdown_Slave());
        } 
        else if (Messages.Build_Action_Delete_Slave_If_Not_Success().equalsIgnoreCase(
                        slavePostBuildAction) && (build.getResult() != Result.SUCCESS)) {
            slave.setCleanUpAction(CleanUpAction.DELETE, Messages._Build_Action_Delete_Slave_If_Not_Success());
        }
        else if (Messages.Build_Action_Delete_Slave().equalsIgnoreCase(slavePostBuildAction)) {
            slave.setCleanUpAction(CleanUpAction.DELETE, Messages._Build_Action_Delete_Slave());
        }
        
        return true;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.STEP;
    }

    @Extension
    public static final class AzureSlavePostBuildDescriptor extends
            BuildStepDescriptor<Publisher> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> arg0) {
            return true;
        }

        public ListBoxModel doFillSlavePostBuildActionItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Messages.Build_Action_Shutdown_Slave());
            model.add(Messages.Build_Action_Delete_Slave());
            model.add(Messages.Build_Action_Delete_Slave_If_Not_Success());
            return model;
        }

        @Override
        public String getDisplayName() {
            // TODO Auto-generated method stub
            return Messages.Azure_Slave_Post_Build_Action();
        }

    }

}
