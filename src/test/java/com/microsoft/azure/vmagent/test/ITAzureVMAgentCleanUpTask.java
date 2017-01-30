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

import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.resources.GenericResource;
import com.microsoft.azure.management.storage.StorageAccount;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.ListBlobItem;
import com.microsoft.azure.vmagent.AzureVMAgentCleanUpTask;
import com.microsoft.azure.vmagent.AzureVMAgentCleanUpTask.DeploymentRegistrar;
import com.microsoft.azure.vmagent.AzureVMCloud;
import com.microsoft.azure.vmagent.AzureVMDeploymentInfo;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.Constants;
import edu.emory.mathcs.backport.java.util.Arrays;
import hudson.model.TaskListener;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;
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

    @Test
    public void cleanLeakedResourcesRemovesVM() {
        final String vmName = "tstleak";
        final String tagName = Constants.AZURE_RESOURCES_TAG_NAME;
        final AzureUtil.DeploymentTag tagValue = new AzureUtil.DeploymentTag("some_value/123");
        final AzureUtil.DeploymentTag nonMatchingTagValue1 = new AzureUtil.DeploymentTag("some_value/124");
        final AzureUtil.DeploymentTag nonMatchingTagValue2 = new AzureUtil.DeploymentTag("some_other_value/9999123");
        final AzureUtil.DeploymentTag matchingTagValue = new AzureUtil.DeploymentTag("some_value/9999123");
        final String cloudName = "some_cloud_name";
        final List<String> emptyValidVMsList = Arrays.asList(new Object[]{});
        try {
            AzureVMAgentCleanUpTask cleanUpTask = spy(AzureVMAgentCleanUpTask.class);
            when(cleanUpTask.getValidVMs(cloudName)).thenReturn(emptyValidVMsList);

            final DeploymentRegistrar deploymentRegistrarMock_nonMatching1 = mock(DeploymentRegistrar.class);
            when(deploymentRegistrarMock_nonMatching1.getDeploymentTag()).thenReturn(nonMatchingTagValue1);
            final DeploymentRegistrar deploymentRegistrarMock_nonMatching2 = mock(DeploymentRegistrar.class);
            when(deploymentRegistrarMock_nonMatching2.getDeploymentTag()).thenReturn(nonMatchingTagValue2);
            final DeploymentRegistrar deploymentRegistrarMock_matching = mock(DeploymentRegistrar.class);
            when(deploymentRegistrarMock_matching.getDeploymentTag()).thenReturn(matchingTagValue);

            createAzureVM(vmName, tagName, tagValue.get());

            cleanUpTask.cleanLeakedResources(testEnv.azureResourceGroup, servicePrincipal, cloudName, deploymentRegistrarMock_nonMatching1);
            Assert.assertNotNull(customTokenCache.getAzureClient().virtualMachines().getByGroup(testEnv.azureResourceGroup, vmName));

            cleanUpTask.cleanLeakedResources(testEnv.azureResourceGroup, servicePrincipal, cloudName, deploymentRegistrarMock_nonMatching2);
            Assert.assertNotNull(customTokenCache.getAzureClient().virtualMachines().getByGroup(testEnv.azureResourceGroup, vmName));

            cleanUpTask.cleanLeakedResources(testEnv.azureResourceGroup, servicePrincipal, cloudName, deploymentRegistrarMock_matching);
            Assert.assertNull(customTokenCache.getAzureClient().virtualMachines().getByGroup(testEnv.azureResourceGroup, vmName));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void cleanLeakedResourcesRemovesDeployedResources() {
        final AzureUtil.DeploymentTag tagValue = new AzureUtil.DeploymentTag("some_value/123");
        final AzureUtil.DeploymentTag matchingTagValue = new AzureUtil.DeploymentTag("some_value/9999123");
        final String cloudName = "some_cloud_name";
        try {

            final DeploymentRegistrar deploymentRegistrarMock = mock(DeploymentRegistrar.class);
            when(deploymentRegistrarMock.getDeploymentTag()).thenReturn(tagValue);
            final DeploymentRegistrar deploymentRegistrarMock_matching = mock(DeploymentRegistrar.class);
            when(deploymentRegistrarMock_matching.getDeploymentTag()).thenReturn(matchingTagValue);

            final AzureVMDeploymentInfo deployment = createDefaultDeployment(2, deploymentRegistrarMock);
            final List<String> validVMs = Arrays.asList(new Object[]{deployment.getVmBaseName() + "0"});

            AzureVMAgentCleanUpTask cleanUpTask = spy(AzureVMAgentCleanUpTask.class);
            when(cleanUpTask.getValidVMs(cloudName)).thenReturn(validVMs);

            cleanUpTask.cleanLeakedResources(testEnv.azureResourceGroup, servicePrincipal, cloudName, deploymentRegistrarMock_matching); //should remove second deployment

            Thread.sleep(20* 1000); // give time for azure to realize that some resources are missing
            StorageAccount jenkinsStorage = null;
            PagedList<GenericResource> resources = customTokenCache.getAzureClient().genericResources().listByGroup(testEnv.azureResourceGroup);
            for (GenericResource resource : resources) {
                if (StringUtils.containsIgnoreCase(resource.type(), "storageAccounts")) {
                    jenkinsStorage = customTokenCache.getAzureClient().storageAccounts().getById(resource.id());
                }
                if (resource.tags().get(Constants.AZURE_RESOURCES_TAG_NAME) != null &&
                        matchingTagValue.matches(new AzureUtil.DeploymentTag(resource.tags().get(Constants.AZURE_RESOURCES_TAG_NAME)))) {
                    String resourceName = resource.name();
                    String depl = deployment.getVmBaseName() + "0";
                    Assert.assertTrue("Resource shouldn't exist: " + resourceName +" (vmbase: " + depl + " )",resourceName.contains(depl));
                }
            }

            //check the OS disk was removed
            Assert.assertNotNull("The resource group doesn't have any storage account", jenkinsStorage);
            final String storageKey = jenkinsStorage.getKeys().get(0).value();
            CloudStorageAccount account = new CloudStorageAccount(new StorageCredentialsAccountAndKey(jenkinsStorage.name(), storageKey));
            CloudBlobClient blobClient = account.createCloudBlobClient();
            CloudBlobContainer container = blobClient.getContainerReference("vhds");
            Assert.assertTrue(container.exists());
            for (ListBlobItem blob : container.listBlobs()) {
                final String u = blob.getUri().toString();
                Assert.assertTrue("Blobl shouldn't exist: " + u ,u.contains(deployment.getVmBaseName() + "0"));
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }
}
