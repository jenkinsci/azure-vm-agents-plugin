/*
 * Copyright 2017.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.util.Constants;
import hudson.model.Node;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ITAzureVMCloud extends IntegrationTest {

    private static final Logger LOGGER = Logger.getLogger(ITAzureVMCloud.class.getName());

    @Test
    void createProvisionedAgentThrowsExceptionWhenNoDeployments() {
        try {
            final String vmName = "fakeVM";
            final String deploymentName = "fakeDeployment";
            final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(vmName, deploymentName);
            AzureVMAgentTemplate templateMock = mock(AzureVMAgentTemplate.class);
            AzureVMCloud cloudMock = spy( new AzureVMCloud("", "xyz", "42", "0", "new", testEnv.azureResourceGroup, null,null));

            when(templateMock.retrieveAzureCloudReference()).thenReturn(cloudMock);

            assertThrows(AzureCloudException.class, () -> cloudMock.createProvisionedAgent(provisioningId, templateMock, vmName, deploymentName));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Disabled
    @Test
    void createProvisionedAgentWorksWhenDeploymentExists() {
        try {
            final AzureVMDeploymentInfo deploymentInfo = createDefaultDeployment(1, null);
            final String vmName = deploymentInfo.getVmBaseName() + "0";
            final String vmDNS = azureClient.virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, vmName).getPrimaryPublicIPAddress().fqdn();
            final String deploymentName = deploymentInfo.getDeploymentName();
            final String templateName = "createTemplate";
            final String templateDesc = "createTemplateDesc";
            final String agentWorkspace = "createTemplateAgentWorkspace";
            final int noOfParallelJobs = 2;
            final Node.Mode useAgentAlwaysIfAvail = Node.Mode.NORMAL;
            final String templateLabels = "label1 label2";
            final String credentialsId = "cred_id";
            final String jvmOptions = "";
            final boolean isShutdownOnIdle = false;
            final int retentionTimeInMin = 30;
            final String initScript = "";
            final String terminateScript = "";
            final String agentLaunchMethod = Constants.LAUNCH_METHOD_SSH;
            final boolean executeInitScriptAsRoot = true;
            final boolean doNotUseMachineIfInitFails = true;
            final boolean enableMSI = false;
            final boolean enableUAMI = false;
            final boolean ephemeralOSDisk = false;
            final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(vmName, deploymentName);
            final boolean disableWindowsUpdates = true;

            AzureVMAgentTemplate templateMock = mock(AzureVMAgentTemplate.class);
            AzureVMCloud cloudMock = spy( new AzureVMCloud("", credentialsId, "42", "30", "new", testEnv.azureResourceGroup, null, null));

            when(templateMock.retrieveAzureCloudReference()).thenReturn(cloudMock);
            when(templateMock.getTemplateName()).thenReturn(templateName);
            when(templateMock.getTemplateDesc()).thenReturn(templateDesc);
            when(templateMock.getAgentWorkspace()).thenReturn(agentWorkspace);
            when(templateMock.getNoOfParallelJobs()).thenReturn(noOfParallelJobs);
            when(templateMock.getUsageMode()).thenReturn(useAgentAlwaysIfAvail);
            when(templateMock.getLabels()).thenReturn(templateLabels);
            when(templateMock.getCredentialsId()).thenReturn(credentialsId);
            when(templateMock.getJvmOptions()).thenReturn(jvmOptions);
            when(templateMock.isShutdownOnIdle()).thenReturn(isShutdownOnIdle);
            when(templateMock.getRetentionTimeInMin()).thenReturn(retentionTimeInMin);
            when(templateMock.getInitScript()).thenReturn(initScript);
            when(templateMock.getTerminateScript()).thenReturn(terminateScript);
            when(templateMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
            when(templateMock.getExecuteInitScriptAsRoot()).thenReturn(executeInitScriptAsRoot);
            when(templateMock.getDoNotUseMachineIfInitFails()).thenReturn(doNotUseMachineIfInitFails);
            when(templateMock.isEnableMSI()).thenReturn(enableMSI);
            when(templateMock.isEnableUAMI()).thenReturn(enableUAMI);
            when(templateMock.isEphemeralOSDisk()).thenReturn(ephemeralOSDisk);
            when(templateMock.isDisableWindowsUpdates()).thenReturn(disableWindowsUpdates);

            AzureVMAgent newAgent = cloudMock.createProvisionedAgent(provisioningId, templateMock, vmName, deploymentName);

            assertEquals(vmDNS, newAgent.getPublicDNSName());
            assertEquals(Constants.DEFAULT_SSH_PORT, newAgent.getSshPort());
            assertEquals(templateName, newAgent.getTemplateName());
            assertEquals(templateDesc, newAgent.getNodeDescription());
            assertEquals(noOfParallelJobs, newAgent.getNumExecutors());
            assertEquals(useAgentAlwaysIfAvail, newAgent.getMode());
            assertEquals(templateLabels, newAgent.getLabelString());
            assertEquals(jvmOptions, newAgent.getJvmOptions());
            assertEquals(retentionTimeInMin, newAgent.getRetentionTimeInMin());
            assertEquals(initScript, newAgent.getInitScript());
            assertEquals(terminateScript, newAgent.getTerminateScript());
            assertEquals(agentLaunchMethod, newAgent.getAgentLaunchMethod());
            assertEquals(executeInitScriptAsRoot, newAgent.getExecuteInitScriptAsRoot());
            assertEquals(doNotUseMachineIfInitFails, newAgent.getDoNotUseMachineIfInitFails());
            assertEquals(enableMSI, newAgent.isEnableMSI());
            assertEquals(enableUAMI, newAgent.isEnableUAMI());
            assertEquals(ephemeralOSDisk, newAgent.isEphemeralOSDisk());
            assertEquals(disableWindowsUpdates, newAgent.isDisableWindowsUpdates());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

}
