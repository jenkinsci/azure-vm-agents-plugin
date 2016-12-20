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

import com.microsoft.azure.management.resources.Deployment;
import com.microsoft.azure.vmagent.AzureVMAgentCleanUpTask;
import com.microsoft.azure.vmagent.AzureVMAgentCleanUpTask.DeploymentRegistrar;
import com.microsoft.azure.vmagent.AzureVMCloud;
import com.microsoft.azure.vmagent.AzureVMDeploymentInfo;
import hudson.model.TaskListener;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Assert;
import org.junit.Test;
import static org.mockito.Mockito.*;

public class ITAzureVMAgentCleanUpTask extends IntegrationTest {

    private static final Logger LOGGER = Logger.getLogger(ITAzureVMAgentCleanUpTask.class.getName());

    @Test
    public void cleanDeploymentsTest() {
        try{
            final AzureVMDeploymentInfo deploymentInfo = createDefaultDeployment(1, null);
            final String cloudName = "fake_cloud_name";
            final DeploymentRegistrar deploymentRegistrar = new DeploymentRegistrar();

            deploymentRegistrar.registerDeployment(cloudName, testEnv.azureResourceGroup, deploymentInfo.getDeploymentName());
            AzureVMAgentCleanUpTask cleanUpMock = spy(AzureVMAgentCleanUpTask.class);
            AzureVMCloud cloudMock = mock(AzureVMCloud.class);

            when(cleanUpMock.getCloud(cloudName)).thenReturn(cloudMock);
            doNothing().when(cleanUpMock).execute(any(TaskListener.class));
            when(cloudMock.getServicePrincipal()).thenReturn(servicePrincipal);

            cleanUpMock.cleanDeployments(60 * 24,-1); // should be a no-op, the timeout is 1 day
            Assert.assertNotNull(customTokenCache.getAzureClient().deployments().getByGroup(testEnv.azureResourceGroup, deploymentInfo.getDeploymentName()));

            cleanUpMock.cleanDeployments(-1,-1); // should delete all deployments
            try {
                Thread.sleep(10* 1000); // give time for azure to realize that the deployment was deleted
                Assert.assertNull(customTokenCache.getAzureClient().deployments().getByGroup(testEnv.azureResourceGroup, deploymentInfo.getDeploymentName()));
            } catch (Exception e) {
                Assert.assertTrue(true);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }
}
