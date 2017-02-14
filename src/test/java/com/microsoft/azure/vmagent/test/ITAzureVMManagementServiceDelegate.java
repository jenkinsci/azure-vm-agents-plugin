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
package com.microsoft.azure.vmagent.test;

import com.microsoft.azure.CloudException;
import com.microsoft.azure.management.compute.PowerState;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.network.Network;
import com.microsoft.azure.management.network.NetworkInterface;
import com.microsoft.azure.management.network.PublicIpAddress;
import com.microsoft.azure.management.storage.StorageAccount;
import com.microsoft.azure.util.AzureCredentials.ServicePrincipal;
import com.microsoft.azure.vmagent.AzureVMAgent;
import com.microsoft.azure.vmagent.AzureVMAgentCleanUpTask;
import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import com.microsoft.azure.vmagent.AzureVMCloud;
import com.microsoft.azure.vmagent.AzureVMDeploymentInfo;
import com.microsoft.azure.vmagent.AzureVMManagementServiceDelegate;
import com.microsoft.azure.vmagent.Messages;
import com.microsoft.azure.vmagent.retry.RetryStrategy;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.ExecutionEngine;
import edu.emory.mathcs.backport.java.util.Arrays;
import java.net.URI;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Assert;
import org.junit.Test;
import static org.mockito.Mockito.*;

public class ITAzureVMManagementServiceDelegate extends IntegrationTest {

    private static final Logger LOGGER = Logger.getLogger(ITAzureVMManagementServiceDelegate.class.getName());

    @Test
    public void uploadCustomScriptTest() {
        /*
        run twice
        first the resources are missing, the second time the resources should be there
        */
        final String uploadFileName = UUID.randomUUID().toString() + ".txt";
        clearAzureResources(); //make sure the azure resources are missing

        uploadCustomScript(uploadFileName, UUID.randomUUID().toString());
        uploadCustomScript(uploadFileName, UUID.randomUUID().toString());
    }

    private void uploadCustomScript(final String uploadFileName, final String writtenData) {

        AzureVMAgentTemplate templateMock = mock(AzureVMAgentTemplate.class);
        when(templateMock.getStorageAccountName()).thenReturn(testEnv.azureStorageAccountName);
        when(templateMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
        when(templateMock.getLocation()).thenReturn(testEnv.azureLocation);
        when(templateMock.getInitScript()).thenReturn(writtenData);

        try {
            AzureVMManagementServiceDelegate.uploadCustomScript(templateMock, uploadFileName, customTokenCache);

            final String downloadedData = downloadFromAzure(
                    testEnv.azureResourceGroup,
                    testEnv.azureStorageAccountName,
                    Constants.CONFIG_CONTAINER_NAME,
                    uploadFileName
                    );

            Assert.assertEquals(writtenData, downloadedData);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void createDeploymentTest() {
        Random rand = new Random();
        final int numberOfAgents = rand.nextInt(4) + 1;
        AzureVMDeploymentInfo deploymentInfo = null;

        try {
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
            when(deploymentRegistrar.getDeploymentTag()).thenReturn(new AzureUtil.DeploymentTag("some_tag/123"));
            deploymentInfo = createDefaultDeployment(numberOfAgents, deploymentRegistrar);

            verify(deploymentRegistrar).registerDeployment(null, testEnv.azureResourceGroup, deploymentInfo.getDeploymentName());
            Network actualVNet = null;
            StorageAccount actualStorageAcc = null;
            try {
                actualVNet =  customTokenCache.getAzureClient().networks().getByGroup(testEnv.azureResourceGroup, "jenkinsarm-vnet");
                actualStorageAcc = customTokenCache.getAzureClient().storageAccounts().getByGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, null, e);
            }
            Assert.assertNotNull("The deployed VNet doesn't exist: "+ testEnv.azureResourceGroup, actualVNet);
            Assert.assertNotNull("The deployed Storage Account doesn't exist: "+ testEnv.azureResourceGroup, actualStorageAcc);

            for(int i = 0; i < numberOfAgents; i++) {
                final String baseName = deploymentInfo.getVmBaseName() + String.valueOf(i);
                final String commonAssertMsg = testEnv.azureResourceGroup +  ":" + baseName;
                VirtualMachine actualVM = null;
                NetworkInterface actualNetIface = null;
                PublicIpAddress actualIP = null;
                try {
                    actualVM = customTokenCache.getAzureClient()
                                .virtualMachines()
                                .getByGroup(testEnv.azureResourceGroup, baseName);

                    actualNetIface = customTokenCache.getAzureClient()
                                .networkInterfaces()
                                .getByGroup(testEnv.azureResourceGroup, baseName + "NIC");

                    actualIP = customTokenCache.getAzureClient()
                                .publicIpAddresses()
                                .getByGroup(testEnv.azureResourceGroup, baseName + "IPName");

                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, null, e);
                }
                Assert.assertNotNull("The deployed VM doesn't exist: "+ commonAssertMsg, actualVM);
                Assert.assertNotNull("The deployed Network interface doesn't exist: " + commonAssertMsg, actualNetIface);
                Assert.assertNotNull("The deployed public IP doesn't exist: " + commonAssertMsg, actualIP);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void canDeployMultipleTimes() {
        try {
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
            when(deploymentRegistrar.getDeploymentTag()).thenReturn(new AzureUtil.DeploymentTag("some_tag/123"));
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar2 = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
            when(deploymentRegistrar2.getDeploymentTag()).thenReturn(new AzureUtil.DeploymentTag("other_tag/123"));

            AzureVMDeploymentInfo firstDeployment = createDefaultDeployment(1, deploymentRegistrar);
            AzureVMDeploymentInfo secondDeployment = createDefaultDeployment(1, deploymentRegistrar);
            AzureVMDeploymentInfo thirdDeployment = createDefaultDeployment(1, deploymentRegistrar2);

            Network actualVNet = null;
            StorageAccount actualStorageAcc = null;
            try {
                actualVNet =  customTokenCache.getAzureClient().networks().getByGroup(testEnv.azureResourceGroup, "jenkinsarm-vnet");
                actualStorageAcc = customTokenCache.getAzureClient().storageAccounts().getByGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, null, e);
            }
            Assert.assertNotNull("The deployed VNet doesn't exist: "+ testEnv.azureResourceGroup, actualVNet);
            Assert.assertNotNull("The deployed Storage Account doesn't exist: "+ testEnv.azureResourceGroup, actualStorageAcc);
            final List<String> baseVMNames = Arrays.asList(new Object[]{ firstDeployment.getVmBaseName(), secondDeployment.getVmBaseName(), thirdDeployment.getVmBaseName() });
            for(String base: baseVMNames) {
                final String baseName = base + "0";
                final String commonAssertMsg = testEnv.azureResourceGroup +  ":" + baseName;
                VirtualMachine actualVM = null;
                NetworkInterface actualNetIface = null;
                PublicIpAddress actualIP = null;
                try {
                    actualVM = customTokenCache.getAzureClient()
                                .virtualMachines()
                                .getByGroup(testEnv.azureResourceGroup, baseName);

                    actualNetIface = customTokenCache.getAzureClient()
                                .networkInterfaces()
                                .getByGroup(testEnv.azureResourceGroup, baseName + "NIC");

                    actualIP = customTokenCache.getAzureClient()
                                .publicIpAddresses()
                                .getByGroup(testEnv.azureResourceGroup, baseName + "IPName");

                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, null, e);
                }
                Assert.assertNotNull("The deployed VM doesn't exist: "+ commonAssertMsg, actualVM);
                Assert.assertNotNull("The deployed Network interface doesn't exist: " + commonAssertMsg, actualNetIface);
                Assert.assertNotNull("The deployed public IP doesn't exist: " + commonAssertMsg, actualIP);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void setVirtualMachineDetailsTest() {
        try {
            final AzureVMDeploymentInfo deploymentInfo = createDefaultDeployment(1, null);
            final String nodeName = deploymentInfo.getVmBaseName() + "0";
            final String ip = customTokenCache.getAzureClient()
                    .publicIpAddresses()
                    .getByGroup(testEnv.azureResourceGroup, nodeName + "IPName")
                    .fqdn();

            AzureVMAgent agentMock = mock(AzureVMAgent.class);
            AzureVMAgentTemplate templateMock = mock(AzureVMAgentTemplate.class);
            AzureVMCloud cloudMock = mock(AzureVMCloud.class);

            when(templateMock.getAzureCloud()).thenReturn(cloudMock);
            when(templateMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
            when(cloudMock.getServicePrincipal()).thenReturn(servicePrincipal);
            when(agentMock.getNodeName()).thenReturn(nodeName);

            AzureVMManagementServiceDelegate.setVirtualMachineDetails(agentMock, templateMock);

            verify(agentMock).setPublicDNSName(ip);
            verify(agentMock).setSshPort(Constants.DEFAULT_SSH_PORT);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void virtualMachineExistsTest() {
        try {
            final String vmName = "vmexists";
            createAzureVM(vmName);

            AzureVMAgent agentMock = mock(AzureVMAgent.class);
            when(agentMock.getNodeName()).thenReturn(vmName);
            when(agentMock.getServicePrincipal()).thenReturn(servicePrincipal);
            when(agentMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
            Assert.assertTrue("The deployed VM doesn't exist: "+ vmName,
                    AzureVMManagementServiceDelegate.virtualMachineExists(agentMock));

            when(agentMock.getNodeName()).thenReturn(vmName + "a"); //invalid VM name
            Assert.assertFalse("The deployed VM exists: "+ vmName,
                AzureVMManagementServiceDelegate.virtualMachineExists(agentMock));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void isVMAliveOrHealthyTest() {
        try{
            final String vmName = "vmexists";
            VirtualMachine vm = createAzureVM(vmName);
            Assert.assertNotNull(vm);

            AzureVMAgent agentMock = mock(AzureVMAgent.class);
            when(agentMock.getNodeName()).thenReturn(vmName);
            when(agentMock.getServicePrincipal()).thenReturn(servicePrincipal);
            when(agentMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);

            Assert.assertTrue(AzureVMManagementServiceDelegate.isVMAliveOrHealthy(agentMock));

            vm.powerOff();
            Assert.assertFalse(AzureVMManagementServiceDelegate.isVMAliveOrHealthy(agentMock));

            vm.deallocate();
            Assert.assertFalse(AzureVMManagementServiceDelegate.isVMAliveOrHealthy(agentMock));

            customTokenCache.getAzureClient().virtualMachines().deleteByGroup(testEnv.azureResourceGroup, vmName);
            try {
                AzureVMManagementServiceDelegate.isVMAliveOrHealthy(agentMock);
                Assert.assertTrue("isVMAliveOrHealthy should have thrown an exception", false);
            } catch (Exception e) {
                Assert.assertTrue(true);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void getVirtualMachineCountTest() {
        try {
            Random rand = new Random();
            final int numberOfAgents = rand.nextInt(4) + 1;
            final String vmName = "vmnotcounted";
            createDefaultDeployment(numberOfAgents, null);
            createAzureVM(vmName);

            Assert.assertEquals(numberOfAgents, AzureVMManagementServiceDelegate.getVirtualMachineCount(servicePrincipal, testEnv.azureResourceGroup));
            Assert.assertEquals(0, AzureVMManagementServiceDelegate.getVirtualMachineCount(servicePrincipal, testEnv.azureResourceGroup + "-missing"));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void verifyConfigurationTest() {
        Assert.assertEquals(
                Constants.OP_SUCCESS,
                AzureVMManagementServiceDelegate.verifyConfiguration(servicePrincipal, testEnv.azureResourceGroup)
        );

        //the method is successful even if the resource group doesn't exists
        Assert.assertEquals(
                Constants.OP_SUCCESS,
                AzureVMManagementServiceDelegate.verifyConfiguration(servicePrincipal, testEnv.azureResourceGroup + "abcd")
        );

        //should fail if service principal is wrong
        ServicePrincipal wrongSP = new ServicePrincipal();
        Assert.assertNotEquals(
                Constants.OP_SUCCESS,
                AzureVMManagementServiceDelegate.verifyConfiguration(wrongSP, testEnv.azureResourceGroup)
        );
    }

    @Test
    public void terminateVirtualMachineTest() {
        try {
            final String vmName = "vmterminate";
            VirtualMachine vm = createAzureVM(vmName);
            final URI osDiskStorageAccount = new URI(vm.osDiskVhdUri());
            Assert.assertTrue(blobExists(osDiskStorageAccount));

            ExecutionEngine executionEngineMock = mock(ExecutionEngine.class);

            AzureVMManagementServiceDelegate.terminateVirtualMachine(servicePrincipal, vmName, testEnv.azureResourceGroup, executionEngineMock);

            verify(executionEngineMock).executeAsync(any(Callable.class), any(RetryStrategy.class));

            Assert.assertNull(customTokenCache.getAzureClient().virtualMachines().getByGroup(testEnv.azureResourceGroup, vmName));
            Assert.assertFalse(blobExists(osDiskStorageAccount));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void terminateVirtualMachineTest_MissingVM() {
        try {
            final String vmName = "invalidvm";
            ExecutionEngine executionEngineMock = mock(ExecutionEngine.class);

            //VM is missing so terminateVirtualMachine should be a no-op and no exception should be thrown
            AzureVMManagementServiceDelegate.terminateVirtualMachine(servicePrincipal, vmName, testEnv.azureResourceGroup, executionEngineMock);
            verify(executionEngineMock).executeAsync(any(Callable.class), any(RetryStrategy.class));

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void removeIPNameTest() {
        try {
            final AzureVMDeploymentInfo deploymentInfo = createDefaultDeployment(1, null);
            final String nodeName = deploymentInfo.getVmBaseName() + "0";

            AzureVMManagementServiceDelegate.removeIPName(servicePrincipal, testEnv.azureResourceGroup, nodeName);
            //should fail because the VM is still using them
            Assert.assertNotNull(
                    customTokenCache.getAzureClient()
                        .publicIpAddresses()
                        .getByGroup(testEnv.azureResourceGroup, nodeName + "IPName")
            );
            Assert.assertNotNull(
                    customTokenCache.getAzureClient()
                        .networkInterfaces()
                        .getByGroup(testEnv.azureResourceGroup, nodeName + "NIC")
            );

            //destory the vm first
            customTokenCache.getAzureClient().virtualMachines().deleteByGroup(testEnv.azureResourceGroup, nodeName);
            AzureVMManagementServiceDelegate.removeIPName(servicePrincipal, testEnv.azureResourceGroup, nodeName);
            Assert.assertNull(
                    customTokenCache.getAzureClient()
                        .publicIpAddresses()
                        .getByGroup(testEnv.azureResourceGroup, nodeName + "IPName")
            );
            Assert.assertNull(
                    customTokenCache.getAzureClient()
                        .networkInterfaces()
                        .getByGroup(testEnv.azureResourceGroup, nodeName + "NIC")
            );
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void restartVMTest() {
        try{
            final String vmName = "vmrestart";
            VirtualMachine vm = createAzureVM(vmName);
            Assert.assertEquals(PowerState.RUNNING, vm.powerState());

            AzureVMAgent agentMock = mock(AzureVMAgent.class);
            when(agentMock.getNodeName()).thenReturn(vmName);
            when(agentMock.getServicePrincipal()).thenReturn(servicePrincipal);
            when(agentMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);

            AzureVMManagementServiceDelegate.restartVirtualMachine(agentMock);
            PowerState state = customTokenCache.getAzureClient().virtualMachines().getByGroup(testEnv.azureResourceGroup,vmName).powerState();
            Assert.assertTrue(state.equals(PowerState.RUNNING) || state.equals(PowerState.STARTING));

           customTokenCache.getAzureClient().virtualMachines().getByGroup(testEnv.azureResourceGroup,vmName).powerOff();
           PowerState state2 = customTokenCache.getAzureClient().virtualMachines().getByGroup(testEnv.azureResourceGroup,vmName).powerState();
           Assert.assertTrue(state2.toString(),state2.equals(PowerState.STOPPED) || state2.toString().equalsIgnoreCase("powerstate/stopping"));

            try {
                AzureVMManagementServiceDelegate.restartVirtualMachine(agentMock); // restart throws exception when the VM is already stopped
                Assert.assertTrue(false);
            } catch (CloudException ex) {
                Assert.assertTrue(true);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void startVMTest() {
        try{
            final String vmName = "vmstart";
            VirtualMachine vm = createAzureVM(vmName);
            Assert.assertEquals(PowerState.RUNNING, vm.powerState());

            AzureVMAgent agentMock = mock(AzureVMAgent.class);
            when(agentMock.getNodeName()).thenReturn(vmName);
            when(agentMock.getServicePrincipal()).thenReturn(servicePrincipal);
            when(agentMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);

            AzureVMManagementServiceDelegate.startVirtualMachine(agentMock);
            Assert.assertEquals(PowerState.RUNNING, customTokenCache.getAzureClient().virtualMachines()
                    .getByGroup(testEnv.azureResourceGroup,vmName).powerState());

            customTokenCache.getAzureClient().virtualMachines().getByGroup(testEnv.azureResourceGroup,vmName).powerOff();
            PowerState state2 = customTokenCache.getAzureClient().virtualMachines().getByGroup(testEnv.azureResourceGroup,vmName).powerState();
           Assert.assertTrue(state2.toString(),state2.equals(PowerState.STOPPED) || state2.toString().equalsIgnoreCase("powerstate/stopping"));

            AzureVMManagementServiceDelegate.startVirtualMachine(agentMock);
            PowerState state = customTokenCache.getAzureClient().virtualMachines().getByGroup(testEnv.azureResourceGroup,vmName).powerState();
            Assert.assertTrue(state.equals(PowerState.RUNNING) || state.equals(PowerState.STARTING));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void shutdownVMTest() {
        try{
            final String vmName = "vmshutdown";
            VirtualMachine vm = createAzureVM(vmName);
            Assert.assertEquals(PowerState.RUNNING, vm.powerState());

            AzureVMAgent agentMock = mock(AzureVMAgent.class);
            when(agentMock.getNodeName()).thenReturn(vmName);
            when(agentMock.getServicePrincipal()).thenReturn(servicePrincipal);
            when(agentMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);

            AzureVMManagementServiceDelegate.shutdownVirtualMachine(agentMock);
            PowerState state = customTokenCache.getAzureClient().virtualMachines().getByGroup(testEnv.azureResourceGroup,vmName).powerState();
            Assert.assertTrue(state.toString(),state.equals(PowerState.STOPPED) || state.toString().equalsIgnoreCase("powerstate/stopping"));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void getVirtualNetworkTest() {
        try{
            createDefaultDeployment(1, null);

            Network vnet = AzureVMManagementServiceDelegate.getVirtualNetwork(servicePrincipal, "jenkinsarm-vnet", testEnv.azureResourceGroup);
            Assert.assertNotNull(vnet);

            Network missing_vnet = AzureVMManagementServiceDelegate.getVirtualNetwork(servicePrincipal, "missing", testEnv.azureResourceGroup);
            Assert.assertNull(missing_vnet);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void verifyVirtualNetworkTest() {
        try{
            final String vnetName = "jenkinsarm-vnet";
            final String subnetName = "jenkinsarm-snet";
            createDefaultDeployment(1, null);

           Assert.assertEquals(Constants.OP_SUCCESS,
                    AzureVMManagementServiceDelegate
                            .verifyVirtualNetwork(servicePrincipal, vnetName, subnetName, testEnv.azureResourceGroup));

            final String wrongVnet = vnetName+"wrong";
            Assert.assertEquals(Messages.Azure_GC_Template_VirtualNetwork_NotFound(wrongVnet),
                    AzureVMManagementServiceDelegate
                            .verifyVirtualNetwork(servicePrincipal, wrongVnet, subnetName, testEnv.azureResourceGroup));

            final String wrongSnet = subnetName+"wrong";
            Assert.assertEquals(Messages.Azure_GC_Template_subnet_NotFound(wrongSnet),
                    AzureVMManagementServiceDelegate
                            .verifyVirtualNetwork(servicePrincipal, vnetName, wrongSnet, testEnv.azureResourceGroup));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void verifyVirtualMachineImageTest() {
        try{
            Assert.assertEquals(Constants.OP_SUCCESS, AzureVMManagementServiceDelegate
                    .verifyVirtualMachineImage(servicePrincipal, testEnv.azureLocation, "", AzureVMAgentTemplate.ImageReferenceType.REFERENCE, "",
                            testEnv.azureImagePublisher, testEnv.azureImageOffer, testEnv.azureImageSku, "latest"));

            Assert.assertEquals(Constants.OP_SUCCESS, AzureVMManagementServiceDelegate
                    .verifyVirtualMachineImage(servicePrincipal, testEnv.azureLocation, "", AzureVMAgentTemplate.ImageReferenceType.REFERENCE, "",
                            testEnv.azureImagePublisher, testEnv.azureImageOffer, testEnv.azureImageSku, ""));

            Assert.assertNotEquals(Constants.OP_SUCCESS, AzureVMManagementServiceDelegate
                    .verifyVirtualMachineImage(servicePrincipal, testEnv.azureLocation, "", AzureVMAgentTemplate.ImageReferenceType.REFERENCE, "",
                            testEnv.azureImagePublisher, testEnv.azureImageOffer, testEnv.azureImageSku, "wrong_version"));

            Assert.assertNotEquals(Constants.OP_SUCCESS, AzureVMManagementServiceDelegate
                    .verifyVirtualMachineImage(servicePrincipal, testEnv.azureLocation +"wrong", "", AzureVMAgentTemplate.ImageReferenceType.REFERENCE, "",
                            testEnv.azureImagePublisher, testEnv.azureImageOffer, testEnv.azureImageSku, ""));

            Assert.assertNotEquals(Constants.OP_SUCCESS, AzureVMManagementServiceDelegate
                    .verifyVirtualMachineImage(servicePrincipal, testEnv.azureLocation, "", AzureVMAgentTemplate.ImageReferenceType.REFERENCE, "",
                            testEnv.azureImagePublisher + "wrong", testEnv.azureImageOffer, testEnv.azureImageSku, "latest"));

            Assert.assertNotEquals(Constants.OP_SUCCESS, AzureVMManagementServiceDelegate
                    .verifyVirtualMachineImage(servicePrincipal, testEnv.azureLocation, "", AzureVMAgentTemplate.ImageReferenceType.REFERENCE, "",
                            testEnv.azureImagePublisher, testEnv.azureImageOffer + "wrong", testEnv.azureImageSku, ""));

            Assert.assertNotEquals(Constants.OP_SUCCESS, AzureVMManagementServiceDelegate
                    .verifyVirtualMachineImage(servicePrincipal, testEnv.azureLocation, "", AzureVMAgentTemplate.ImageReferenceType.REFERENCE, "",
                            testEnv.azureImagePublisher, testEnv.azureImageOffer, testEnv.azureImageSku + "wrong", "latest"));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void verifyStorageAccountNameTest() {
        try{
            Assert.assertEquals(Constants.OP_SUCCESS, AzureVMManagementServiceDelegate
                    .verifyStorageAccountName(servicePrincipal, testEnv.azureResourceGroup, testEnv.azureStorageAccountName));

            customTokenCache.getAzureClient().storageAccounts()
                    .define(testEnv.azureStorageAccountName)
                    .withRegion(testEnv.azureLocation)
                    .withNewResourceGroup(testEnv.azureResourceGroup)
                    .create();

            Assert.assertEquals(Constants.OP_SUCCESS, AzureVMManagementServiceDelegate
                    .verifyStorageAccountName(servicePrincipal, testEnv.azureResourceGroup, testEnv.azureStorageAccountName));

            Assert.assertEquals(Messages.Azure_GC_Template_SA_Already_Exists(), AzureVMManagementServiceDelegate
                    .verifyStorageAccountName(new ServicePrincipal(), testEnv.azureResourceGroup+"fake", testEnv.azureStorageAccountName));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void deploymentWorksIfStorageAccountIsCreatedBefore() {
        /*
        uploadCustomScript creates the storage account if it's not available.
        The SDK will always create it using the latest API version.
        The deployment has an hardcoded API version that might be lower than the one in the SDK, thus failing the deployment.
        This test makes sure the deployment JSON is up to date API version-wise
        */
        try {
            final String uploadFileName = UUID.randomUUID().toString() + ".txt";
            uploadCustomScript(uploadFileName, UUID.randomUUID().toString());
            createDefaultDeployment(1, null);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void removeStorageBlobRemovesEmptyJenkinsContainers() {
        try {
            final String fileName = "abc.txt";
            final String data = "5gadfgbsdafsdg";
            final String containerName_from_jenkins = "jnkshouldgetdeleted"; // we deploy the init scrit in containers starting with jnk
            final String containerName_from_user = "notstartingwithjnk"; // we shouldn't delete containers not deployed by us
            final URI deletedContainerBlobURI = uploadFile(fileName, data, containerName_from_jenkins);
            final URI existingContainerBlobURI = uploadFile(fileName, data, containerName_from_user);

            AzureVMManagementServiceDelegate.removeStorageBlob(customTokenCache.getAzureClient(), deletedContainerBlobURI, testEnv.azureResourceGroup);
            AzureVMManagementServiceDelegate.removeStorageBlob(customTokenCache.getAzureClient(), existingContainerBlobURI, testEnv.azureResourceGroup);

            Assert.assertFalse(containerExists(deletedContainerBlobURI)); // both container and blob are missing
            Assert.assertTrue(containerExists(existingContainerBlobURI)); // the container is there, but the blob is missing
            Assert.assertFalse(blobExists(existingContainerBlobURI));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }

    @Test
    public void removeStorageBlobKeepsContainerIfNotEmpty() {
        try {
            final String fileName1 = "abc1.txt";
            final String fileName2 = "abc2.txt";
            final String data = "5gadfgbsdafsdg";
            final String containerName = "jnkshouldgetdeleted";
            final URI blobToBeDeleted = uploadFile(fileName1, data, containerName);
            final URI notDeletedBlob = uploadFile(fileName2, data, containerName);

            AzureVMManagementServiceDelegate.removeStorageBlob(customTokenCache.getAzureClient(), blobToBeDeleted, testEnv.azureResourceGroup);

            Assert.assertTrue(containerExists(blobToBeDeleted));
            Assert.assertFalse(blobExists(blobToBeDeleted));
            Assert.assertTrue(blobExists(notDeletedBlob));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            Assert.assertTrue(e.getMessage(), false);
        }
    }
}
