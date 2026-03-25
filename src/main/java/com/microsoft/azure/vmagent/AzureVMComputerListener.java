package com.microsoft.azure.vmagent;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class AzureVMComputerListener extends ComputerListener {

    private static final Logger LOGGER = Logger.getLogger(AzureVMComputerListener.class.getName());

    @Override
    public void onOnline(Computer c, TaskListener listener) {
        if (c instanceof AzureVMComputer) {
            LOGGER.log(Level.FINE, "Azure VM agent online: {0}, triggering immediate queue maintenance", c.getName());
            AzureVMCloud.scheduleQueueMaintenance(0);
        }
    }
}
