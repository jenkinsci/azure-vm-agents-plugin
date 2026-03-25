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

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.management.exception.ManagementException;
import com.azure.resourcemanager.resources.models.GenericResource;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.Constants;
import hudson.model.TaskListener;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ITAzureVMAgentCleanUpTask extends IntegrationTest {

    @Test
    void cleanDeploymentsTest() throws Exception {
        final AzureVMDeploymentInfo deploymentInfo = createDefaultDeployment(1, null);
        final String cloudName = "fake_cloud_name";
        final AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar = AzureVMAgentCleanUpTask.DeploymentRegistrar.getInstance();

        deploymentRegistrar.registerDeployment(cloudName, testEnv.azureResourceGroup, deploymentInfo.getDeploymentName(),
                null, false);
        AzureVMAgentCleanUpTask cleanUpMock = spy(AzureVMAgentCleanUpTask.class);
        AzureVMCloud cloudMock = mock(AzureVMCloud.class);
        when(cloudMock.getCloudName()).thenReturn(cloudName);
        when(cloudMock.getAzureClient()).thenReturn(azureClient);
        when(cloudMock.getServiceDelegate()).thenReturn(delegate);

        when(cleanUpMock.getCloud(cloudName)).thenReturn(cloudMock);
        doNothing().when(cleanUpMock).execute(any(TaskListener.class));

        cleanUpMock.cleanDeployments(60 * 24, -1); // should be a no-op, the timeout is 1 day
        assertTrue(azureClient.deployments().checkExistence(testEnv.azureResourceGroup, deploymentInfo.getDeploymentName()));

        cleanUpMock.cleanDeployments(-1, -1); // should delete all deployments
        Thread.sleep(10 * 1000); // give time for azure to realize that the deployment was deleted

        assertFalse(azureClient.deployments().checkExistence(testEnv.azureResourceGroup, deploymentInfo.getDeploymentName()));
    }

    @Test
    void cleanLeakedResourcesRemovesVM() {
        final String vmName = "tstleak";
        final String tagName = Constants.AZURE_RESOURCES_TAG_NAME;
        final AzureUtil.DeploymentTag tagValue = new AzureUtil.DeploymentTag("some_value/123");
        final AzureUtil.DeploymentTag nonMatchingTagValue1 = new AzureUtil.DeploymentTag("some_value/124");
        final AzureUtil.DeploymentTag nonMatchingTagValue2 = new AzureUtil.DeploymentTag("some_other_value/9999123");
        final AzureUtil.DeploymentTag matchingTagValue = new AzureUtil.DeploymentTag("some_value/9999123");
        final String cloudName = "some_cloud_name";
        final List<String> emptyValidVMsList = Collections.emptyList();
        AzureVMCloud cloud = mock(AzureVMCloud.class);
        when(cloud.getCloudName()).thenReturn(cloudName);
        when(cloud.getAzureClient()).thenReturn(azureClient);
        when(cloud.getServiceDelegate()).thenReturn(delegate);
        AzureVMAgentCleanUpTask cleanUpTask = spy(AzureVMAgentCleanUpTask.class);
        when(cleanUpTask.getValidVMs()).thenReturn(emptyValidVMsList);

        final AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrarMock_nonMatching1 = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
        when(deploymentRegistrarMock_nonMatching1.getDeploymentTag()).thenReturn(nonMatchingTagValue1);
        final AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrarMock_nonMatching2 = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
        when(deploymentRegistrarMock_nonMatching2.getDeploymentTag()).thenReturn(nonMatchingTagValue2);
        final AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrarMock_matching = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
        when(deploymentRegistrarMock_matching.getDeploymentTag()).thenReturn(matchingTagValue);

        createAzureVM(vmName, tagName, tagValue.get());

        cleanUpTask.cleanLeakedResources(cloud, testEnv.azureResourceGroup, deploymentRegistrarMock_nonMatching1);
        assertNotNull(azureClient.virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, vmName));

        cleanUpTask.cleanLeakedResources(cloud, testEnv.azureResourceGroup, deploymentRegistrarMock_nonMatching2);
        assertNotNull(azureClient.virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, vmName));

        cleanUpTask.cleanLeakedResources(cloud, testEnv.azureResourceGroup, deploymentRegistrarMock_matching);

        ManagementException managementException = assertThrows(ManagementException.class,
                () -> azureClient.virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, vmName));
        assertThat(managementException.getResponse().getStatusCode(), equalTo(404));
    }

    @Test
    void cleanLeakedResourcesRemovesDeployedResources() throws Exception {
        final AzureUtil.DeploymentTag tagValue = new AzureUtil.DeploymentTag("some_value/123");
        final AzureUtil.DeploymentTag matchingTagValue = new AzureUtil.DeploymentTag("some_value/9999123");
        final String cloudName = "some_cloud_name";
        AzureVMCloud cloud = mock(AzureVMCloud.class);
        when(cloud.getCloudName()).thenReturn(cloudName);
        when(cloud.getAzureClient()).thenReturn(azureClient);
        when(cloud.getServiceDelegate()).thenReturn(delegate);

        final AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrarMock = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
        when(deploymentRegistrarMock.getDeploymentTag()).thenReturn(tagValue);
        final AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrarMock_matching = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
        when(deploymentRegistrarMock_matching.getDeploymentTag()).thenReturn(matchingTagValue);

        final AzureVMDeploymentInfo deployment = createDefaultDeployment(2, deploymentRegistrarMock);
        final List<String> validVMs = Collections.singletonList(deployment.getVmBaseName() + "0");

        AzureVMAgentCleanUpTask cleanUpTask = spy(AzureVMAgentCleanUpTask.class);
        when(cleanUpTask.getValidVMs()).thenReturn(validVMs);

        cleanUpTask.cleanLeakedResources(cloud, testEnv.azureResourceGroup, deploymentRegistrarMock_matching); //should remove second deployment

        Thread.sleep(20 * 1000); // give time for azure to realize that some resources are missing
        PagedIterable<GenericResource> resources = azureClient.genericResources().listByResourceGroup(testEnv.azureResourceGroup);
        for (GenericResource resource : resources) {
            if (StringUtils.containsIgnoreCase(resource.type(), "storageAccounts") ||
                    StringUtils.containsIgnoreCase(resource.type(), "virtualNetworks")) {
                continue;
            }
            if (resource.tags().get(Constants.AZURE_RESOURCES_TAG_NAME) != null &&
                    matchingTagValue.matches(new AzureUtil.DeploymentTag(resource.tags().get(Constants.AZURE_RESOURCES_TAG_NAME)))) {
                String resourceName = resource.name();
                String depl = deployment.getVmBaseName() + "0";
                assertTrue(resourceName.contains(depl), "Resource shouldn't exist: " + resourceName + " (vmbase: " + depl + " )");
            }
        }
    }
}
