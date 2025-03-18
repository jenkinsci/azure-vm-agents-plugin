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

import com.azure.core.management.exception.ManagementException;
import com.azure.resourcemanager.compute.models.AvailabilitySet;
import com.azure.resourcemanager.compute.models.AvailabilitySetSkuTypes;
import com.azure.resourcemanager.compute.models.PowerState;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.network.models.Network;
import com.azure.resourcemanager.network.models.NetworkInterface;
import com.azure.resourcemanager.network.models.NetworkSecurityGroup;
import com.azure.resourcemanager.network.models.PublicIpAddress;
import com.azure.resourcemanager.storage.models.SkuName;
import com.azure.resourcemanager.storage.models.StorageAccount;
import com.azure.resourcemanager.storage.models.StorageAccountSkuType;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.retry.RetryStrategy;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.ExecutionEngine;
import org.apache.commons.lang.StringUtils;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.text.IsEqualIgnoringCase.equalToIgnoringCase;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ITAzureVMManagementServiceDelegate extends IntegrationTest {

    private static final Logger LOGGER = Logger.getLogger(ITAzureVMManagementServiceDelegate.class.getName());

    @Test
    void uploadCustomScriptTest() {
        /*
        run twice
        first the resources are missing, the second time the resources should be there
        */
        final String uploadFileName = UUID.randomUUID() + ".txt";
        clearAzureResources(); //make sure the azure resources are missing

        uploadCustomScript(uploadFileName, UUID.randomUUID().toString());
        uploadCustomScript(uploadFileName, UUID.randomUUID().toString());
    }

    private void uploadCustomScript(String uploadFileName, String writtenData) {

        AzureVMAgentTemplate templateMock = mock(AzureVMAgentTemplate.class);
        when(templateMock.getStorageAccountName()).thenReturn(testEnv.azureStorageAccountName);
        when(templateMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
        when(templateMock.getLocation()).thenReturn(testEnv.azureLocation);
        when(templateMock.getInitScript()).thenReturn(writtenData);
        when(templateMock.getTerminateScript()).thenReturn(writtenData);
        when(templateMock.getStorageAccountType()).thenReturn(SkuName.STANDARD_LRS.toString());
        when(templateMock.getResourceGroupReferenceType()).thenReturn(testEnv.azureResourceGroupReferenceType);
        AzureVMCloud cloudMock = mock(AzureVMCloud.class);
        when(cloudMock.getCloudName()).thenReturn("testCloudName");
        when(templateMock.retrieveAzureCloudReference()).thenReturn(cloudMock);

        try {
            delegate.uploadCustomScript(templateMock, uploadFileName);

            final String downloadedData = downloadFromAzure(
                    testEnv.azureResourceGroup,
                    testEnv.azureStorageAccountName,
                    Constants.CONFIG_CONTAINER_NAME,
                    uploadFileName
            );
            /*Data padded before upload to Page Blob so we need to use strip*/
            assertEquals(StringUtils.strip(writtenData), StringUtils.strip(downloadedData));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void createDeploymentTest() throws Exception {
        Random rand = new Random();
        final int numberOfAgents = rand.nextInt(4) + 1;
        AzureVMDeploymentInfo deploymentInfo = null;

        AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
        when(deploymentRegistrar.getDeploymentTag()).thenReturn(new AzureUtil.DeploymentTag("some_tag/123"));
        deploymentInfo = createDefaultDeployment(numberOfAgents, deploymentRegistrar);

        verify(deploymentRegistrar).registerDeployment("testCloud", testEnv.azureResourceGroup,
                deploymentInfo.getDeploymentName(), null, false);
        Network actualVNet = null;
        StorageAccount actualStorageAcc = null;
        try {
            actualVNet = azureClient.networks().getByResourceGroup(testEnv.azureResourceGroup, "jenkinsarm-vnet");
            actualStorageAcc = azureClient.storageAccounts().getByResourceGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
        }
        assertNotNull(actualVNet, "The deployed VNet doesn't exist: " + testEnv.azureResourceGroup);
        assertNotNull(actualStorageAcc, "The deployed Storage Account doesn't exist: " + testEnv.azureResourceGroup);

        for (int i = 0; i < numberOfAgents; i++) {
            final String baseName = deploymentInfo.getVmBaseName() + i;
            final String commonAssertMsg = testEnv.azureResourceGroup + ":" + baseName;
            VirtualMachine actualVM = null;
            NetworkInterface actualNetIface = null;
            PublicIpAddress actualIP = null;
            try {
                actualVM = azureClient
                        .virtualMachines()
                        .getByResourceGroup(testEnv.azureResourceGroup, baseName);

                actualNetIface = azureClient
                        .networkInterfaces()
                        .getByResourceGroup(testEnv.azureResourceGroup, baseName + "NIC");

                actualIP = azureClient
                        .publicIpAddresses()
                        .getByResourceGroup(testEnv.azureResourceGroup, baseName + "IPName");

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, null, e);
            }
            assertNotNull(actualVM, "The deployed VM doesn't exist: " + commonAssertMsg);
            assertNotNull(actualNetIface, "The deployed Network interface doesn't exist: " + commonAssertMsg);
            assertNotNull(actualIP, "The deployed public IP doesn't exist: " + commonAssertMsg);
            assertFalse(actualVM.isManagedServiceIdentityEnabled());
            assertEquals(getDefaultOsDiskSize(OS_TYPE), actualVM.osDiskSize());

            assertEquals("gavin", actualVM.tags().get("author"));
            assertEquals("test", actualVM.tags().get("env"));

            assertEquals("gavin", actualIP.tags().get("author"));
            assertEquals("test", actualIP.tags().get("env"));

            assertEquals("gavin", actualNetIface.tags().get("author"));
            assertEquals("test", actualNetIface.tags().get("env"));

            assertEquals("gavin", actualStorageAcc.tags().get("author"));
            assertEquals("test", actualStorageAcc.tags().get("env"));

            assertEquals("gavin", actualVNet.tags().get("author"));
            assertEquals("test", actualVNet.tags().get("env"));
        }
    }

    private int getDefaultOsDiskSize(String osType) {
	    return switch (osType) {
		    case Constants.OS_TYPE_LINUX -> 30;
		    case Constants.OS_TYPE_WINDOWS -> 127;
		    default -> 0;
	    };
    }

    @Test
    void createDeploymentWithPrivateIPTest() throws Exception {
        AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
        when(deploymentRegistrar.getDeploymentTag()).thenReturn(new AzureUtil.DeploymentTag("some_tag/123"));
        AzureVMDeploymentInfo deploymentInfo = createDefaultDeployment(1, false, deploymentRegistrar);

        verify(deploymentRegistrar).registerDeployment("testCloud", testEnv.azureResourceGroup, deploymentInfo.getDeploymentName(), null, false);
        Network actualVNet = null;
        StorageAccount actualStorageAcc = null;
        try {
            actualVNet = azureClient.networks().getByResourceGroup(testEnv.azureResourceGroup, "jenkinsarm-vnet");
            actualStorageAcc = azureClient.storageAccounts().getByResourceGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
        }
        assertNotNull(actualVNet, "The deployed VNet doesn't exist: " + testEnv.azureResourceGroup);
        assertNotNull(actualStorageAcc, "The deployed Storage Account doesn't exist: " + testEnv.azureResourceGroup);

        final String baseName = deploymentInfo.getVmBaseName() + "0";
        VirtualMachine actualVM = null;
        NetworkInterface actualNetIface = null;
        PublicIpAddress actualIP = null;
        String privateIP = "";
        try {
            actualVM = azureClient
                    .virtualMachines()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName);

            actualNetIface = azureClient
                    .networkInterfaces()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName + "NIC");

            privateIP = actualNetIface.primaryPrivateIP();

            actualIP = azureClient
                    .publicIpAddresses()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName + "IPName");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
        }
        assertNotNull(actualVM, "The deployed VM doesn't exist");
        assertNotNull(actualNetIface, "The deployed Network interface doesn't exist");
        assertTrue(privateIP != null && !privateIP.isEmpty(), "The deployed VM doesn't have a private IP");
        assertNull(actualIP, "The deployed VM shouldn't have a public IP");

    }

    /**
     * This test requires legal terms accepted in order for it to pass
     * You can run: az vm image accept-terms --urn kali-linux:kali-linux:kali:2018.4.0
     */
    @Test
    void createDeploymentWithPurchasePlan() throws Exception {
        testEnv.azureImagePublisher = "kali-linux";
        testEnv.azureImageOffer = "kali-linux";
        testEnv.azureImageSku = "kali";

        AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
        when(deploymentRegistrar.getDeploymentTag()).thenReturn(new AzureUtil.DeploymentTag("some_tag/123"));
        AzureVMDeploymentInfo deploymentInfo;
        deploymentInfo = createDefaultDeployment(1, deploymentRegistrar);

        verify(deploymentRegistrar).registerDeployment("testCloud", testEnv.azureResourceGroup, deploymentInfo.getDeploymentName(), null, false);
        Network actualVNet = null;
        StorageAccount actualStorageAcc = null;
        try {
            actualVNet = azureClient.networks().getByResourceGroup(testEnv.azureResourceGroup, "jenkinsarm-vnet");
            actualStorageAcc = azureClient.storageAccounts().getByResourceGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
        }
        assertNotNull(actualVNet, "The deployed VNet doesn't exist: " + testEnv.azureResourceGroup);
        assertNotNull(actualStorageAcc, "The deployed Storage Account doesn't exist: " + testEnv.azureResourceGroup);

        final String baseName = deploymentInfo.getVmBaseName() + "0";
        final String commonAssertMsg = testEnv.azureResourceGroup + ":" + baseName;
        VirtualMachine actualVM = null;
        NetworkInterface actualNetIface = null;
        PublicIpAddress actualIP = null;
        try {
            actualVM = azureClient
                    .virtualMachines()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName);

            actualNetIface = azureClient
                    .networkInterfaces()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName + "NIC");

            actualIP = azureClient
                    .publicIpAddresses()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName + "IPName");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
        }
        assertNotNull(actualVM, "The deployed VM doesn't exist: " + commonAssertMsg);
        assertNotNull(actualNetIface, "The deployed Network interface doesn't exist: " + commonAssertMsg);
        assertNotNull(actualIP, "The deployed public IP doesn't exist: " + commonAssertMsg);
    }

    @Test
    void createDeploymentWithMSI() throws Exception {
        AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
        when(deploymentRegistrar.getDeploymentTag()).thenReturn(new AzureUtil.DeploymentTag("some_tag/123"));
        AzureVMDeploymentInfo deploymentInfo;
        deploymentInfo = createDefaultDeployment(1, true, true, false, false, "", deploymentRegistrar);

        verify(deploymentRegistrar).registerDeployment("testCloud", testEnv.azureResourceGroup, deploymentInfo.getDeploymentName(), null, false);
        Network actualVNet = null;
        StorageAccount actualStorageAcc = null;
        try {
            actualVNet = azureClient.networks().getByResourceGroup(testEnv.azureResourceGroup, "jenkinsarm-vnet");
            actualStorageAcc = azureClient.storageAccounts().getByResourceGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
        }
        assertNotNull(actualVNet, "The deployed VNet doesn't exist: " + testEnv.azureResourceGroup);
        assertNotNull(actualStorageAcc, "The deployed Storage Account doesn't exist: " + testEnv.azureResourceGroup);

        final String baseName = deploymentInfo.getVmBaseName() + "0";
        final String commonAssertMsg = testEnv.azureResourceGroup + ":" + baseName;
        VirtualMachine actualVM = null;
        NetworkInterface actualNetIface = null;
        PublicIpAddress actualIP = null;
        try {
            actualVM = azureClient
                    .virtualMachines()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName);

            actualNetIface = azureClient
                    .networkInterfaces()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName + "NIC");

            actualIP = azureClient
                    .publicIpAddresses()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName + "IPName");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
        }
        assertNotNull(actualVM, "The deployed VM doesn't exist: " + commonAssertMsg);
        assertNotNull(actualNetIface, "The deployed Network interface doesn't exist: " + commonAssertMsg);
        assertNotNull(actualIP, "The deployed public IP doesn't exist: " + commonAssertMsg);
        assertTrue(actualVM.isManagedServiceIdentityEnabled());
    }

    @Test
    void createDeploymentWithAvailabilitySet() throws Exception {
        azureClient.resourceGroups()
                .define(testEnv.azureResourceGroup)
                .withRegion(testEnv.azureLocation)
                .create();
        AvailabilitySet availabilitySet = azureClient.availabilitySets().define("test-av-set")
                .withRegion(testEnv.azureLocation)
                .withExistingResourceGroup(testEnv.azureResourceGroup)
                .withFaultDomainCount(2)
                .withUpdateDomainCount(4)
                .withSku(AvailabilitySetSkuTypes.ALIGNED)
                .create();
        assertNotNull(availabilitySet, "Failed to create availability set in resourceGroup " + testEnv.azureResourceGroup);
        testEnv.availabilityType = AvailabilityType.AVAILABILITY_SET.getName();
        testEnv.availabilitySet = availabilitySet.name();

        AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
        when(deploymentRegistrar.getDeploymentTag()).thenReturn(new AzureUtil.DeploymentTag("some_tag/123"));
        AzureVMDeploymentInfo deploymentInfo;
        deploymentInfo = createDefaultDeployment(1, deploymentRegistrar);

        verify(deploymentRegistrar).registerDeployment("testCloud", testEnv.azureResourceGroup, deploymentInfo.getDeploymentName(), null, false);
        Network actualVNet = null;
        StorageAccount actualStorageAcc = null;
        try {
            actualVNet = azureClient.networks().getByResourceGroup(testEnv.azureResourceGroup, "jenkinsarm-vnet");
            actualStorageAcc = azureClient.storageAccounts().getByResourceGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
        }
        assertNotNull(actualVNet, "The deployed VNet doesn't exist: " + testEnv.azureResourceGroup);
        assertNotNull(actualStorageAcc, "The deployed Storage Account doesn't exist: " + testEnv.azureResourceGroup);

        final String baseName = deploymentInfo.getVmBaseName() + "0";
        final String commonAssertMsg = testEnv.azureResourceGroup + ":" + baseName;
        VirtualMachine actualVM = null;
        NetworkInterface actualNetIface = null;
        PublicIpAddress actualIP = null;
        try {
            actualVM = azureClient
                    .virtualMachines()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName);

            actualNetIface = azureClient
                    .networkInterfaces()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName + "NIC");

            actualIP = azureClient
                    .publicIpAddresses()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName + "IPName");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
        }
        assertNotNull(actualVM, "The deployed VM doesn't exist: " + commonAssertMsg);
        assertNotNull(actualNetIface, "The deployed Network interface doesn't exist: " + commonAssertMsg);
        assertNotNull(actualIP, "The deployed public IP doesn't exist: " + commonAssertMsg);
        assertThat(availabilitySet.id(), is(equalToIgnoringCase(actualVM.availabilitySetId())));
    }

    @Test
    void createDeploymentWithSpecificOsDiskSize() throws Exception {
        int osDiskSize = 100;
        testEnv.osDiskSize = osDiskSize;
        AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
        when(deploymentRegistrar.getDeploymentTag()).thenReturn(new AzureUtil.DeploymentTag("some_tag/123"));
        AzureVMDeploymentInfo deploymentInfo;
        deploymentInfo = createDefaultDeployment(1, deploymentRegistrar);

        verify(deploymentRegistrar).registerDeployment("testCloud", testEnv.azureResourceGroup, deploymentInfo.getDeploymentName(), null, false);
        Network actualVNet = null;
        StorageAccount actualStorageAcc = null;
        try {
            actualVNet = azureClient.networks().getByResourceGroup(testEnv.azureResourceGroup, "jenkinsarm-vnet");
            actualStorageAcc = azureClient.storageAccounts().getByResourceGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
        }
        assertNotNull(actualVNet, "The deployed VNet doesn't exist: " + testEnv.azureResourceGroup);
        assertNotNull(actualStorageAcc, "The deployed Storage Account doesn't exist: " + testEnv.azureResourceGroup);

        final String baseName = deploymentInfo.getVmBaseName() + "0";
        final String commonAssertMsg = testEnv.azureResourceGroup + ":" + baseName;
        VirtualMachine actualVM = null;
        NetworkInterface actualNetIface = null;
        PublicIpAddress actualIP = null;
        try {
            actualVM = azureClient
                    .virtualMachines()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName);

            actualNetIface = azureClient
                    .networkInterfaces()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName + "NIC");

            actualIP = azureClient
                    .publicIpAddresses()
                    .getByResourceGroup(testEnv.azureResourceGroup, baseName + "IPName");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
        }
        assertNotNull(actualVM, "The deployed VM doesn't exist: " + commonAssertMsg);
        assertNotNull(actualNetIface, "The deployed Network interface doesn't exist: " + commonAssertMsg);
        assertNotNull(actualIP, "The deployed public IP doesn't exist: " + commonAssertMsg);
        assertEquals(osDiskSize, actualVM.osDiskSize());
    }

    @Test
    void canDeployMultipleTimes() {
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
                actualVNet = azureClient.networks().getByResourceGroup(testEnv.azureResourceGroup, "jenkinsarm-vnet");
                actualStorageAcc = azureClient.storageAccounts().getByResourceGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, null, e);
            }
            assertNotNull(actualVNet, "The deployed VNet doesn't exist: " + testEnv.azureResourceGroup);
            assertNotNull(actualStorageAcc, "The deployed Storage Account doesn't exist: " + testEnv.azureResourceGroup);
            final List<String> baseVMNames = Arrays.asList(firstDeployment.getVmBaseName(), secondDeployment.getVmBaseName(), thirdDeployment.getVmBaseName());
            for (String base : baseVMNames) {
                final String baseName = base + "0";
                final String commonAssertMsg = testEnv.azureResourceGroup + ":" + baseName;
                VirtualMachine actualVM = null;
                NetworkInterface actualNetIface = null;
                PublicIpAddress actualIP = null;
                try {
                    actualVM = azureClient
                            .virtualMachines()
                            .getByResourceGroup(testEnv.azureResourceGroup, baseName);

                    actualNetIface = azureClient
                            .networkInterfaces()
                            .getByResourceGroup(testEnv.azureResourceGroup, baseName + "NIC");

                    actualIP = azureClient
                            .publicIpAddresses()
                            .getByResourceGroup(testEnv.azureResourceGroup, baseName + "IPName");

                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, null, e);
                }
                assertNotNull(actualVM, "The deployed VM doesn't exist: " + commonAssertMsg);
                assertNotNull(actualNetIface, "The deployed Network interface doesn't exist: " + commonAssertMsg);
                assertNotNull(actualIP, "The deployed public IP doesn't exist: " + commonAssertMsg);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    private void setVirtualMachineDetailsCommonVerification(String vmName, String fqdn, String privateIP, String publicIp) throws Exception {
        AzureVMAgent agentMock = mock(AzureVMAgent.class);
        AzureVMAgentTemplate templateMock = mock(AzureVMAgentTemplate.class);
        AzureVMCloud cloudMock = mock(AzureVMCloud.class);

        when(templateMock.retrieveAzureCloudReference()).thenReturn(cloudMock);
        when(templateMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
        when(agentMock.getNodeName()).thenReturn(vmName);

        delegate.setVirtualMachineDetails(agentMock, templateMock);

        verify(agentMock).setPublicDNSName(fqdn);
        verify(agentMock).setSshPort(Constants.DEFAULT_SSH_PORT);
        verify(agentMock).setPublicIP(publicIp);
        verify(agentMock).setPrivateIP(privateIP);
    }

    @Test
    void setVirtualMachineDetailsWithPublicIP() {
        try {
            final AzureVMDeploymentInfo deploymentInfo = createDefaultDeployment(1, null);
            final String nodeName = deploymentInfo.getVmBaseName() + "0";
            final PublicIpAddress publicIP = azureClient
                    .publicIpAddresses()
                    .getByResourceGroup(testEnv.azureResourceGroup, nodeName + "IPName");
            final String privateIP = azureClient
                    .virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, nodeName)
                    .getPrimaryNetworkInterface().primaryPrivateIP();

            setVirtualMachineDetailsCommonVerification(nodeName, publicIP.fqdn(), privateIP, publicIP.ipAddress());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void setVirtualMachineDetailsWithPrivateIP() {
        try {
            final AzureVMDeploymentInfo deploymentInfo = createDefaultDeployment(1, false, null);
            final String nodeName = deploymentInfo.getVmBaseName() + "0";
            final String ip = azureClient
                    .virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, nodeName)
                    .getPrimaryNetworkInterface().primaryPrivateIP();

            setVirtualMachineDetailsCommonVerification(nodeName, ip, ip, "");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void attachPublicIP() {
        try {
            final AzureVMDeploymentInfo deploymentInfo = createDefaultDeployment(1, false, null);
            final String nodeName = deploymentInfo.getVmBaseName() + "0";
            final String privateIP = azureClient
                    .virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, nodeName)
                    .getPrimaryNetworkInterface().primaryPrivateIP();
            AzureVMAgent agentMock = mock(AzureVMAgent.class);
            AzureVMAgentTemplate templateMock = mock(AzureVMAgentTemplate.class);
            AzureVMCloud cloudMock = mock(AzureVMCloud.class);
            when(templateMock.retrieveAzureCloudReference()).thenReturn(cloudMock);
            when(templateMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
            when(templateMock.getLocation()).thenReturn(testEnv.azureLocation);
            when(agentMock.getNodeName()).thenReturn(nodeName);

            delegate.attachPublicIP(agentMock, templateMock);

            final PublicIpAddress publicIP = azureClient
                    .virtualMachines()
                    .getByResourceGroup(testEnv.azureResourceGroup, nodeName)
                    .getPrimaryPublicIPAddress();
            assertNotNull(publicIP);
            verify(agentMock).setPublicDNSName(publicIP.fqdn());
            verify(agentMock).setSshPort(Constants.DEFAULT_SSH_PORT);
            verify(agentMock).setPublicIP(publicIP.ipAddress());
            verify(agentMock).setPrivateIP(privateIP);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void attachPublicIPIsNoOpWhenAlreadyExists() {
        try {
            final AzureVMDeploymentInfo deploymentInfo = createDefaultDeployment(1, true, null);
            final String nodeName = deploymentInfo.getVmBaseName() + "0";

            AzureVMAgent agentMock = mock(AzureVMAgent.class);
            AzureVMAgentTemplate templateMock = mock(AzureVMAgentTemplate.class);
            AzureVMCloud cloudMock = mock(AzureVMCloud.class);
            when(templateMock.retrieveAzureCloudReference()).thenReturn(cloudMock);
            when(templateMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
            when(templateMock.getLocation()).thenReturn(testEnv.azureLocation);
            when(agentMock.getNodeName()).thenReturn(nodeName);

            final String initialPublicIPId = azureClient
                    .virtualMachines()
                    .getByResourceGroup(testEnv.azureResourceGroup, nodeName)
                    .getPrimaryPublicIPAddress().id();

            delegate.attachPublicIP(agentMock, templateMock);

            final PublicIpAddress publicIP = azureClient
                    .virtualMachines()
                    .getByResourceGroup(testEnv.azureResourceGroup, nodeName)
                    .getPrimaryPublicIPAddress();

            assertNotNull(publicIP);
            assertEquals(initialPublicIPId, publicIP.id());

            verify(agentMock, never()).setPublicDNSName(any(String.class));
            verify(agentMock, never()).setSshPort(any(int.class));
            verify(agentMock, never()).setPublicIP(any(String.class));
            verify(agentMock, never()).setPrivateIP(any(String.class));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void virtualMachineExistsTest() {
        final String vmName = "vmexists";
        createAzureVM(vmName);

        AzureVMAgent agentMock = mock(AzureVMAgent.class);
        when(agentMock.getNodeName()).thenReturn(vmName);
        when(agentMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
        when(agentMock.getServiceDelegate()).thenReturn(delegate);
        assertTrue(AzureVMManagementServiceDelegate.virtualMachineExists(agentMock),
                "The deployed VM doesn't exist: " + vmName);

        when(agentMock.getNodeName()).thenReturn(vmName + "a"); //invalid VM name
        assertFalse(AzureVMManagementServiceDelegate.virtualMachineExists(agentMock),
                "The deployed VM exists: " + vmName);
    }

    @Test
    void isVMAliveOrHealthyTest() {
        try {
            final String vmName = "vmexists";
            VirtualMachine vm = createAzureVM(vmName);
            assertNotNull(vm);

            AzureVMAgent agentMock = mock(AzureVMAgent.class);
            when(agentMock.getNodeName()).thenReturn(vmName);
            when(agentMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);

            assertTrue(delegate.isVMAliveOrHealthy(agentMock));

            vm.powerOff();
            assertFalse(delegate.isVMAliveOrHealthy(agentMock));

            vm.deallocate();
            assertFalse(delegate.isVMAliveOrHealthy(agentMock));

            azureClient.virtualMachines().deleteByResourceGroup(testEnv.azureResourceGroup, vmName);
            try {
                delegate.isVMAliveOrHealthy(agentMock);
                fail("isVMAliveOrHealthy should have thrown an exception");
            } catch (Exception e) {
                assertTrue(true);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void getVirtualMachineCountTest() throws Exception {
        // Given
        Random rand = new Random();
        final int numberOfAgents = rand.nextInt(4) + 1;
        final String vmName = "vmnotcounted";
        createDefaultDeployment(numberOfAgents, null);
        createAzureVM(vmName);
        final int expectedVMCountForRG = numberOfAgents;
        final int expectedVMCountForMissingRG = 0;

        // When
        final int actualVMCountForRG = delegate.getVirtualMachineCount("testCloud", testEnv.azureResourceGroup);
        final int actualVMCountForMissingRG = delegate.getVirtualMachineCount("testCloud", testEnv.azureResourceGroup + "-missing");

        // Then
        assertEquals(expectedVMCountForRG, actualVMCountForRG);
        assertEquals(expectedVMCountForMissingRG, actualVMCountForMissingRG);
    }

    @Test
    void getVirtualMachineCountsByTemplateTest() throws Exception {
        // Given
        Random rand = new Random();
        final String templateName = "myTemplate";
        final int numberOfAgents = rand.nextInt(4) + 1;
        final String vmName = "vmnotcounted";
        createDefaultDeployment(templateName, numberOfAgents, null);
        createAzureVM(vmName);
        final Map<String, Integer> expectedVMCountForRG = Collections.singletonMap(templateName, numberOfAgents);
        final Map<String, Integer> expectedVMCountForMissingRG = Collections.emptyMap();

        // When
        final Map<String, Integer> actualVMCountForRG = delegate.getVirtualMachineCountsByTemplate("testCloud", testEnv.azureResourceGroup);
        final Map<String, Integer> actualVMCountForMissingRG = delegate.getVirtualMachineCountsByTemplate("testCloud", testEnv.azureResourceGroup + "-missing");

        // Then
        assertEquals(expectedVMCountForRG, actualVMCountForRG);
        assertEquals(expectedVMCountForMissingRG, actualVMCountForMissingRG);
    }

    @Test
    void terminateVirtualMachineTest() {
        try {
            final String vmName = "vmterminate";
            VirtualMachine vm = createAzureVM(vmName);
            ExecutionEngine executionEngineMock = mock(ExecutionEngine.class);

            delegate.terminateVirtualMachine(vmName, testEnv.azureResourceGroup, false);

            verify(executionEngineMock).executeAsync(any(Callable.class), any(RetryStrategy.class));

            ManagementException managementException = assertThrows(ManagementException.class, () -> azureClient.virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, vmName));
            assertThat(managementException.getResponse().getStatusCode(), equalTo(404));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void terminateVirtualMachineTest_MissingVM() {
        try {
            final String vmName = "invalidvm";
            ExecutionEngine executionEngineMock = mock(ExecutionEngine.class);

            //VM is missing so terminateVirtualMachine should be a no-op and no exception should be thrown
            delegate.terminateVirtualMachine(vmName, testEnv.azureResourceGroup, false);
            verify(executionEngineMock).executeAsync(any(Callable.class), any(RetryStrategy.class));

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void restartVMTest() throws AzureCloudException {
        final String vmName = "vmrestart";
        VirtualMachine vm = createAzureVM(vmName);
        assertEquals(PowerState.RUNNING, vm.powerState());

        AzureVMAgent agentMock = mock(AzureVMAgent.class);
        when(agentMock.getNodeName()).thenReturn(vmName);
        when(agentMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);

        delegate.restartVirtualMachine(agentMock);
        PowerState state = azureClient.virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, vmName).powerState();
        assertTrue(state.equals(PowerState.RUNNING) || state.equals(PowerState.STARTING));

        azureClient.virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, vmName).powerOff();
        PowerState state2 = azureClient.virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, vmName).powerState();
        assertTrue(state2.equals(PowerState.STOPPED) || state2.toString().equalsIgnoreCase("powerstate/stopping"), state2.toString());

        try {
            delegate.restartVirtualMachine(agentMock); // restart throws exception when the VM is already stopped
            fail("Expect throwing AzureCloudException but not");
        } catch (AzureCloudException ex) {
            // Expect exception
        }
    }

    @Test
    void startVMTest() {
        try {
            final String vmName = "vmstart";
            VirtualMachine vm = createAzureVM(vmName);
            assertEquals(PowerState.RUNNING, vm.powerState());

            AzureVMAgent agentMock = mock(AzureVMAgent.class);
            when(agentMock.getNodeName()).thenReturn(vmName);
            when(agentMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);

            delegate.startVirtualMachine(agentMock);
            assertEquals(PowerState.RUNNING, azureClient.virtualMachines()
                    .getByResourceGroup(testEnv.azureResourceGroup, vmName).powerState());

            azureClient.virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, vmName).powerOff();
            PowerState state2 = azureClient.virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, vmName).powerState();
            assertTrue(state2.equals(PowerState.STOPPED) || state2.toString().equalsIgnoreCase("powerstate/stopping"), state2.toString());

            delegate.startVirtualMachine(agentMock);
            PowerState state = azureClient.virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, vmName).powerState();
            assertTrue(state.equals(PowerState.RUNNING) || state.equals(PowerState.STARTING));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void shutdownVMTest() {
        try {
            final String vmName = "vmshutdown";
            VirtualMachine vm = createAzureVM(vmName);
            assertEquals(PowerState.RUNNING, vm.powerState());

            AzureVMAgent agentMock = mock(AzureVMAgent.class);
            when(agentMock.getNodeName()).thenReturn(vmName);
            when(agentMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);

            delegate.shutdownVirtualMachine(agentMock);
            PowerState state = azureClient.virtualMachines().getByResourceGroup(testEnv.azureResourceGroup, vmName).powerState();
            assertThat(state, anyOf(
                    is(PowerState.STOPPING),
                    is(PowerState.STOPPED),
                    is(PowerState.DEALLOCATING),
                    is(PowerState.DEALLOCATED)));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void getVirtualNetworkTest() {
        try {
            createDefaultDeployment(1, null);

            Network vnet = delegate.getVirtualNetwork("jenkinsarm-vnet", testEnv.azureResourceGroup);
            assertNotNull(vnet);

            Network missing_vnet = delegate.getVirtualNetwork("missing", testEnv.azureResourceGroup);
            assertNull(missing_vnet);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void verifyVirtualNetworkTest() {
        try {
            final String vnetName = "jenkinsarm-vnet";
            final String vnetResourceGroup = "";
            final String subnetName = "jenkinsarm-snet";
            createDefaultDeployment(1, null);

            assertEquals(Constants.OP_SUCCESS,
                    delegate
                            .verifyVirtualNetwork(vnetName, vnetResourceGroup, subnetName, false, testEnv.azureResourceGroup));

            final String wrongVnet = vnetName + "wrong";
            assertEquals(Messages.Azure_GC_Template_VirtualNetwork_NotFound(wrongVnet, testEnv.azureResourceGroup),
                    delegate
                            .verifyVirtualNetwork(wrongVnet, vnetResourceGroup, subnetName, false, testEnv.azureResourceGroup));

            final String wrongSnet = subnetName + "wrong";
            assertEquals(Messages.Azure_GC_Template_subnet_NotFound(wrongSnet),
                    delegate
                            .verifyVirtualNetwork(vnetName, vnetResourceGroup, wrongSnet, false, testEnv.azureResourceGroup));

            assertEquals(Messages.Azure_GC_Template_VirtualNetwork_Null_Or_Empty(),
                    delegate
                            .verifyVirtualNetwork("", vnetResourceGroup, subnetName, false, testEnv.azureResourceGroup));

            assertEquals(Constants.OP_SUCCESS,
                    delegate
                            .verifyVirtualNetwork("", vnetResourceGroup, "", false, testEnv.azureResourceGroup));

            assertEquals(Messages.Azure_GC_Template_VirtualNetwork_Null_Or_Empty(),
                    delegate
                            .verifyVirtualNetwork("", vnetResourceGroup, "", true, testEnv.azureResourceGroup));

            assertEquals(Messages.Azure_GC_Template_subnet_Empty(),
                    delegate
                            .verifyVirtualNetwork(vnetName, vnetResourceGroup, "", false, testEnv.azureResourceGroup));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void verifyVirtualMachineImageTest() {
        try {
            assertEquals(Constants.OP_SUCCESS, delegate
                    .verifyVirtualMachineImage(
                            testEnv.azureLocation,
                            "",
                            Constants.IMAGE_TOP_LEVEL_ADVANCED,
                            new AzureVMAgentTemplate.ImageReferenceTypeClass(
                                    "",
                                    "",
                                    testEnv.azureImagePublisher,
                                    testEnv.azureImageOffer,
                                    testEnv.azureImageSku,
                                    "latest",
                                    testEnv.galleryName,
                                    testEnv.galleryImageDefinition,
                                    testEnv.galleryImageVersion,
                                    testEnv.gallerySubscriptionId,
                                    testEnv.galleryResourceGroup
                            ),
                            ""
                    ));

            assertEquals(Constants.OP_SUCCESS, delegate
                    .verifyVirtualMachineImage(
                            testEnv.azureLocation,
                            "",
                            Constants.IMAGE_TOP_LEVEL_ADVANCED,
                            new AzureVMAgentTemplate.ImageReferenceTypeClass(
                                    "",
                                    "",
                                    testEnv.azureImagePublisher,
                                    testEnv.azureImageOffer,
                                    testEnv.azureImageSku,
                                    "",
                                    testEnv.galleryName,
                                    testEnv.galleryImageDefinition,
                                    testEnv.galleryImageVersion,
                                    testEnv.gallerySubscriptionId,
                                    testEnv.galleryResourceGroup
                            ),
                            ""
                    ));

            assertNotEquals(Constants.OP_SUCCESS, delegate
                    .verifyVirtualMachineImage(
                            testEnv.azureLocation,
                            "",
                            Constants.IMAGE_TOP_LEVEL_ADVANCED,
                            new AzureVMAgentTemplate.ImageReferenceTypeClass(
                                    "",
                                    "",
                                    testEnv.azureImagePublisher,
                                    testEnv.azureImageOffer,
                                    testEnv.azureImageSku,
                                    "wrong_version",
                                    testEnv.galleryName,
                                    testEnv.galleryImageDefinition,
                                    testEnv.galleryImageVersion,
                                    testEnv.gallerySubscriptionId,
                                    testEnv.galleryResourceGroup
                            ),
                            ""
                    ));

            assertNotEquals(Constants.OP_SUCCESS, delegate
                    .verifyVirtualMachineImage(
                            testEnv.azureLocation + "wrong",
                            "",
                            Constants.IMAGE_TOP_LEVEL_ADVANCED,
                            new AzureVMAgentTemplate.ImageReferenceTypeClass(
                                    "",
                                    "",
                                    testEnv.azureImagePublisher,
                                    testEnv.azureImageOffer,
                                    testEnv.azureImageSku,
                                    "",
                                    testEnv.galleryName,
                                    testEnv.galleryImageDefinition,
                                    testEnv.galleryImageVersion,
                                    testEnv.gallerySubscriptionId,
                                    testEnv.galleryResourceGroup
                            ),
                            ""
                    ));

            assertNotEquals(Constants.OP_SUCCESS, delegate
                    .verifyVirtualMachineImage(
                            testEnv.azureLocation,
                            "",
                            Constants.IMAGE_TOP_LEVEL_ADVANCED,
                            new AzureVMAgentTemplate.ImageReferenceTypeClass(
                                    "",
                                    "",
                                    testEnv.azureImagePublisher + "wrong",
                                    testEnv.azureImageOffer,
                                    testEnv.azureImageSku,
                                    "latest",
                                    testEnv.galleryName,
                                    testEnv.galleryImageDefinition,
                                    testEnv.galleryImageVersion,
                                    testEnv.gallerySubscriptionId,
                                    testEnv.galleryResourceGroup
                            ),
                            ""
                    ));

            assertNotEquals(Constants.OP_SUCCESS, delegate
                    .verifyVirtualMachineImage(
                            testEnv.azureLocation,
                            "",
                            Constants.IMAGE_TOP_LEVEL_ADVANCED,
                            new AzureVMAgentTemplate.ImageReferenceTypeClass(
                                    "",
                                    "",
                                    testEnv.azureImagePublisher,
                                    testEnv.azureImageOffer + "wrong",
                                    testEnv.azureImageSku,
                                    "",
                                    testEnv.galleryName,
                                    testEnv.galleryImageDefinition,
                                    testEnv.galleryImageVersion,
                                    testEnv.gallerySubscriptionId,
                                    testEnv.galleryResourceGroup
                            ),
                            ""
                    ));

            assertNotEquals(Constants.OP_SUCCESS, delegate
                    .verifyVirtualMachineImage(
                            testEnv.azureLocation,
                            "",
                            Constants.IMAGE_TOP_LEVEL_ADVANCED,
                            new AzureVMAgentTemplate.ImageReferenceTypeClass(
                                    "",
                                    "",
                                    testEnv.azureImagePublisher,
                                    testEnv.azureImageOffer,
                                    testEnv.azureImageSku + "wrong",
                                    "latest",
                                    testEnv.galleryName,
                                    testEnv.galleryImageDefinition,
                                    testEnv.galleryImageVersion,
                                    testEnv.gallerySubscriptionId,
                                    testEnv.galleryResourceGroup
                            ),
                            ""
                    ));

            assertNotEquals(Constants.OP_SUCCESS, delegate
                    .verifyVirtualMachineImage(
                            testEnv.azureLocation,
                            "",
                            Constants.IMAGE_TOP_LEVEL_ADVANCED,
                            new AzureVMAgentTemplate.ImageReferenceTypeClass(
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    "",
                                    testEnv.galleryName,
                                    testEnv.galleryImageDefinition,
                                    testEnv.galleryImageVersion,
                                    testEnv.gallerySubscriptionId,
                                    testEnv.galleryResourceGroup
                            ),
                            ""
                    ));

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void deploymentWorksIfStorageAccountIsCreatedBefore() {
        /*
        uploadCustomScript creates the storage account if it's not available.
        The SDK will always create it using the latest API version.
        The deployment has an hardcoded API version that might be lower than the one in the SDK, thus failing the deployment.
        This test makes sure the deployment JSON is up to date API version-wise
        */
        try {
            final String uploadFileName = UUID.randomUUID() + ".txt";
            uploadCustomScript(uploadFileName, UUID.randomUUID().toString());
            createDefaultDeployment(1, null);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void removeStorageBlobRemovesEmptyJenkinsContainers() {
        try {
            final String fileName = "abc.txt";
            final String data = "5gadfgbsdafsdg";
            final String containerName_from_jenkins = "jnkshouldgetdeleted"; // we deploy the init script in containers starting with jnk
            final String containerName_from_user = "notstartingwithjnk"; // we shouldn't delete containers not deployed by us

            azureClient.resourceGroups()
                    .define(testEnv.azureResourceGroup)
                    .withRegion(testEnv.azureLocation)
                    .create();
            StorageAccount storageAccount = azureClient.storageAccounts().define(testEnv.azureStorageAccountName)
                    .withRegion(testEnv.azureLocation)
                    .withExistingResourceGroup(testEnv.azureResourceGroup)
                    .withSku(StorageAccountSkuType.STANDARD_LRS)
                    .create();

            final String deletedContainerBlobURI = uploadFile(storageAccount, fileName, data, containerName_from_jenkins);
            final String existingContainerBlobURI = uploadFile(storageAccount, fileName, data, containerName_from_user);

            delegate.removeStorageBlob(new URI(deletedContainerBlobURI), testEnv.azureResourceGroup, null, false);
            delegate.removeStorageBlob(new URI(existingContainerBlobURI), testEnv.azureResourceGroup, null, false);

            assertFalse(containerExists(deletedContainerBlobURI)); // both container and blob are missing
            assertTrue(containerExists(existingContainerBlobURI)); // the container is there, but the blob is missing
            assertFalse(blobExists(existingContainerBlobURI));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    //Add Test for global first, will add test for mooncake later
    @Test
    void getBlobEndpointSuffixForArmTemplateForGlobal() {
        try {
            azureClient.storageAccounts()
                    .define(testEnv.azureStorageAccountName)
                    .withRegion(testEnv.azureLocation)
                    .withNewResourceGroup(testEnv.azureResourceGroup)
                    .create();
            StorageAccount storageAccount = azureClient.storageAccounts().getByResourceGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName);
            String endSuffix = AzureVMManagementServiceDelegate.getBlobEndpointSuffixForTemplate(storageAccount);
            assertEquals(endSuffix, testEnv.blobEndpointSuffixForTemplate.get(TestEnvironment.AZUREPUBLIC));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void removeStorageBlobKeepsContainerIfNotEmpty() {
        try {
            final String fileName1 = "abc1.txt";
            final String fileName2 = "abc2.txt";
            final String data = "5gadfgbsdafsdg";
            final String containerName = "jnkshouldgetdeleted";

            azureClient.resourceGroups()
                    .define(testEnv.azureResourceGroup)
                    .withRegion(testEnv.azureLocation)
                    .create();
            StorageAccount storageAccount = azureClient.storageAccounts().define(testEnv.azureStorageAccountName)
                    .withRegion(testEnv.azureLocation)
                    .withExistingResourceGroup(testEnv.azureResourceGroup)
                    .withSku(StorageAccountSkuType.STANDARD_LRS)
                    .create();

            final String blobToBeDeleted = uploadFile(storageAccount, fileName1, data, containerName);
            final String notDeletedBlob = uploadFile(storageAccount, fileName2, data, containerName);

            delegate.removeStorageBlob(new URI(blobToBeDeleted), testEnv.azureResourceGroup, null, false);

            assertTrue(containerExists(blobToBeDeleted));
            assertFalse(blobExists(blobToBeDeleted));
            assertTrue(blobExists(notDeletedBlob));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    //Add Test for global first, will add test for mooncake later
    @Test
    void getBlobEndpointSuffixForCloudStorageAccountForGlobal() {
        try {
            azureClient.storageAccounts()
                    .define(testEnv.azureStorageAccountName)
                    .withRegion(testEnv.azureLocation)
                    .withNewResourceGroup(testEnv.azureResourceGroup)
                    .create();
            StorageAccount storageAccount = azureClient.storageAccounts().getByResourceGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName);
            String endSuffix = AzureVMManagementServiceDelegate.getBlobEndpointSuffixForCloudStorageAccount(storageAccount);
            assertEquals(endSuffix, testEnv.blobEndpointSuffixForCloudStorageAccount.get(TestEnvironment.AZUREPUBLIC));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }

    @Test
    void createDeploymentWithExistingNSG() {
        try {
            final String nsgName = TestEnvironment.GenerateRandomString(12);
            NetworkSecurityGroup nsg = azureClient.networkSecurityGroups()
                    .define(nsgName)
                    .withRegion(testEnv.azureLocation)
                    .withNewResourceGroup(testEnv.azureResourceGroup)
                    .create();

            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar = mock(AzureVMAgentCleanUpTask.DeploymentRegistrar.class);
            when(deploymentRegistrar.getDeploymentTag()).thenReturn(new AzureUtil.DeploymentTag("some_tag/123"));
            AzureVMDeploymentInfo deploymentInfo = createDefaultDeployment(1, nsgName, deploymentRegistrar);

            VirtualMachine deployedVM = azureClient
                    .virtualMachines()
                    .getByResourceGroup(testEnv.azureResourceGroup, deploymentInfo.getVmBaseName() + "0");

            final String actualNSGId = deployedVM.getPrimaryNetworkInterface().getNetworkSecurityGroup().id();
            assertEquals(nsg.id(), actualNSGId);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, null, e);
            fail(e.getMessage());
        }
    }
}
