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

package com.microsoft.azure.vmagent.test;

import com.microsoft.azure.vmagent.AzureVMAgent;
import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import com.microsoft.azure.vmagent.AzureVMCloud;
import com.microsoft.azure.vmagent.AzureVMDeploymentInfo;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.util.Constants;
import hudson.model.Node;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import static org.mockito.Mockito.*;

public class ITAzureVMCloud extends IntegrationTest {

    private static final Logger LOGGER = Logger.getLogger(ITAzureVMCloud.class.getName());

    @Test
    public void createProvisionedAgentThowsExceptionWhenNoDeployments() {
        try {
            final String vmName = "fakeVM";
            final String deploymentName = "fakeDeployment";
            final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(vmName, deploymentName);
            AzureVMAgentTemplate templateMock = mock(AzureVMAgentTemplate.class);
            AzureVMCloud cloudMock = spy( new AzureVMCloud(servicePrincipal, "xyz", "42", "0", testEnv.azureResourceGroup, null));

            when(templateMock.getAzureCloud()).thenReturn(cloudMock);
            when(cloudMock.getServicePrincipal()).thenReturn(servicePrincipal);

            try {
                cloudMock.createProvisionedAgent(provisioningId, templateMock, vmName, deploymentName);
                Assert.assertTrue(false);
            } catch (AzureCloudException ex) {
                Assert.assertTrue(true);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Ignore
    @Test
    public void createProvisionedAgentWorksWhenDeploymentExists() {
        try {
            final AzureVMDeploymentInfo deploymentInfo = createDefaultDeployment(1, null);
            final String vmName = deploymentInfo.getVmBaseName() + "0";
            final String vmDNS = customTokenCache.getAzureClient().virtualMachines().getByGroup(testEnv.azureResourceGroup, vmName).getPrimaryPublicIpAddress().fqdn();
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
            final String agentLaunchMethod = Constants.LAUNCH_METHOD_SSH;
            final boolean executeInitScriptAsRoot = true;
            final boolean doNotUseMachineIfInitFails = true;
            final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(vmName, deploymentName);

            AzureVMAgentTemplate templateMock = mock(AzureVMAgentTemplate.class);
            AzureVMCloud cloudMock = spy( new AzureVMCloud(servicePrincipal, credentialsId, "42", "30", testEnv.azureResourceGroup, null));

            when(cloudMock.getServicePrincipal()).thenReturn(servicePrincipal);
            when(templateMock.getAzureCloud()).thenReturn(cloudMock);
            when(templateMock.getTemplateName()).thenReturn(templateName);
            when(templateMock.getTemplateDesc()).thenReturn(templateDesc);
            when(templateMock.getAgentWorkspace()).thenReturn(agentWorkspace);
            when(templateMock.getNoOfParallelJobs()).thenReturn(noOfParallelJobs);
            when(templateMock.getUseAgentAlwaysIfAvail()).thenReturn(useAgentAlwaysIfAvail);
            when(templateMock.getLabels()).thenReturn(templateLabels);
            when(templateMock.getCredentialsId()).thenReturn(credentialsId);
            when(templateMock.getJvmOptions()).thenReturn(jvmOptions);
            when(templateMock.isShutdownOnIdle()).thenReturn(isShutdownOnIdle);
            when(templateMock.getRetentionTimeInMin()).thenReturn(retentionTimeInMin);
            when(templateMock.getInitScript()).thenReturn(initScript);
            when(templateMock.getAgentLaunchMethod()).thenReturn(agentLaunchMethod);
            when(templateMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
            when(templateMock.getExecuteInitScriptAsRoot()).thenReturn(executeInitScriptAsRoot);
            when(templateMock.getDoNotUseMachineIfInitFails()).thenReturn(doNotUseMachineIfInitFails);

            AzureVMAgent newAgent = cloudMock.createProvisionedAgent(provisioningId, templateMock, vmName, deploymentName);

            Assert.assertEquals(vmDNS, newAgent.getPublicDNSName());
            Assert.assertEquals(Constants.DEFAULT_SSH_PORT, newAgent.getSshPort());
            Assert.assertEquals(templateName, newAgent.getTemplateName());
            Assert.assertEquals(templateDesc, newAgent.getNodeDescription());
            Assert.assertEquals(noOfParallelJobs, newAgent.getNumExecutors());
            Assert.assertEquals(useAgentAlwaysIfAvail, newAgent.getMode());
            Assert.assertEquals(templateLabels, newAgent.getLabelString());
            Assert.assertEquals(jvmOptions, newAgent.getJvmOptions());
            Assert.assertEquals(retentionTimeInMin, newAgent.getRetentionTimeInMin());
            Assert.assertEquals(initScript, newAgent.getInitScript());
            Assert.assertEquals(agentLaunchMethod, newAgent.getAgentLaunchMethod());
            Assert.assertEquals(executeInitScriptAsRoot, newAgent.getExecuteInitScriptAsRoot());
            Assert.assertEquals(doNotUseMachineIfInitFails, newAgent.getDoNotUseMachineIfInitFails());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

}
