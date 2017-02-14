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

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.microsoft.azure.CloudException;
import com.microsoft.azure.PagedList;
import com.microsoft.azure.management.compute.KnownLinuxVirtualMachineImage;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.resources.Deployment;
import com.microsoft.azure.management.resources.DeploymentOperation;
import com.microsoft.azure.management.storage.SkuName;
import com.microsoft.azure.management.storage.StorageAccountKey;
import com.microsoft.azure.storage.AccessCondition;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.core.PathUtility;
import com.microsoft.azure.util.AzureCredentials.ServicePrincipal;
import com.microsoft.azure.vmagent.AzureVMAgentCleanUpTask;
import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import com.microsoft.azure.vmagent.AzureVMCloud;
import com.microsoft.azure.vmagent.AzureVMDeploymentInfo;
import com.microsoft.azure.vmagent.AzureVMManagementServiceDelegate;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.TokenCache;
import hudson.util.Secret;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.*;
import org.junit.rules.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
To execute the integration tests you need to set the credentials env variables (the ones that don't have a default) and run mvn failsafe:integration-test
*/
public class IntegrationTest {
    @ClassRule public static JenkinsRule j = new JenkinsRule();
    @Rule public Timeout globalTimeout = Timeout.seconds(20 * 60); // integration tests are very slow
    private static final Logger LOGGER = Logger.getLogger(IntegrationTest.class.getName());
    

    protected static class TestEnvironment {
        public final String subscriptionId;
        public final String clientId;
        public final String clientSecret;
        public final String oauth2TokenEndpoint;
        public final String serviceManagementURL;
        public final String authenticationEndpoint;
        public final String resourceManagerEndpoint;
        public final String graphEndpoint;
        public final String azureLocation;
        public final String azureResourceGroup;
        public final String azureStorageAccountName;
        public final String azureImagePublisher;
        public final String azureImageOffer;
        public final String azureImageSku;
        public final String azureImageSize;

        TestEnvironment() {
            subscriptionId = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_SUBSCRIPTION_ID");
            clientId = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_CLIENT_ID");
            clientSecret = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_CLIENT_SECRET");
            oauth2TokenEndpoint = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_TOKEN_ENDPOINT");
            serviceManagementURL = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_AZURE_URL");
            authenticationEndpoint = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_AZURE_AUTH_URL");
            resourceManagerEndpoint = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_AZURE_RESOURCE_URL");
            graphEndpoint = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_AZURE_GRAPH_URL");

            azureLocation = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_LOCATION", "East US");
            azureResourceGroup = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_RESOURCE_GROUP_PREFIX", "vmagents-tst") + "-" + TestEnvironment.GenerateRandomString(16);
            azureStorageAccountName = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_STORAGE_NAME_PREFIX", "vmtst") + TestEnvironment.GenerateRandomString(19);
            azureImagePublisher = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_PUBLISHER", "Canonical");
            azureImageOffer = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_OFFER", "UbuntuServer");
            azureImageSku = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_SKU", "14.04.5-LTS");
            azureImageSize = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_SIZE", "Basic_A0");
        }

        private static String loadFromEnv(final String name) {
            return TestEnvironment.loadFromEnv(name, "");
        }

        private static String loadFromEnv(final String name, final String defaultValue)
        {
            final String value = System.getenv(name);
            if (value == null || value.isEmpty()) {
                return defaultValue;
            } else {
                return value;
            }
        }

        public static String GenerateRandomString(int length) {
            String uuid = UUID.randomUUID().toString();
            return uuid.replaceAll("[^a-z0-9]","a").substring(0, length);
        }
    }

    protected TokenCache customTokenCache = null;
    protected TestEnvironment testEnv = null;
    protected ServicePrincipal servicePrincipal = null;

    @Before
    public void setUp() {
        testEnv = new TestEnvironment();
        LOGGER.log(Level.INFO, "=========================== {0}", testEnv.azureResourceGroup);
        servicePrincipal = new ServicePrincipal(
            testEnv.subscriptionId,
            testEnv.clientId,
            testEnv.clientSecret,
            testEnv.oauth2TokenEndpoint,
            testEnv.serviceManagementURL,
            testEnv.authenticationEndpoint,
            testEnv.resourceManagerEndpoint,
            testEnv.graphEndpoint);
        customTokenCache = TokenCache.getInstance(servicePrincipal);
        clearAzureResources();
    }

    @After
    public void tearDown() {
        clearAzureResources();
    }

    protected void clearAzureResources() {
        try {
            customTokenCache.getAzureClient().resourceGroups().deleteByName(testEnv.azureResourceGroup);
        } catch (CloudException e) {
            if (e.getResponse().code() != 404) {
                LOGGER.log(Level.SEVERE, null, e);
            }
        }
        
    }

    protected String downloadFromAzure(final String resourceGroup, final String storageAccountName, final String containerName, final String fileName) throws URISyntaxException, StorageException, IOException {
        List<StorageAccountKey> storageKeys = customTokenCache.getAzureClient().storageAccounts().getByGroup(resourceGroup, storageAccountName).getKeys();
        String storageAccountKey = storageKeys.get(0).value();
        CloudStorageAccount account = new CloudStorageAccount(new StorageCredentialsAccountAndKey(storageAccountName, storageAccountKey));
        CloudBlobClient blobClient = account.createCloudBlobClient();
        CloudBlobContainer container = blobClient.getContainerReference(containerName);
        CloudBlockBlob blob = container.getBlockBlobReference(fileName);
        return blob.downloadText();
    }

    protected boolean blobExists(final URI storageURI){
        try {
            final String storageAccountName = storageURI.getHost().split("\\.")[0];
            final String containerName = PathUtility.getContainerNameFromUri(storageURI, false);
            final String blobName = PathUtility.getBlobNameFromURI(storageURI, false);

            List<StorageAccountKey> storageKeys = customTokenCache.getAzureClient().storageAccounts()
                .getByGroup(testEnv.azureResourceGroup, storageAccountName)
                .getKeys();

            if (storageKeys.isEmpty()) {
                return false;
            } else {
                String storageAccountKey = storageKeys.get(0).value();
                CloudStorageAccount account = new CloudStorageAccount(new StorageCredentialsAccountAndKey(storageAccountName, storageAccountKey));
                CloudBlobClient blobClient = account.createCloudBlobClient();
                return blobClient.getContainerReference(containerName).getBlockBlobReference(blobName).exists();
            }
        } catch(Exception e) {
            return false;
        }
    }

    protected boolean containerExists(final URI storageURI){
        try {
            final String storageAccountName = storageURI.getHost().split("\\.")[0];
            final String containerName = PathUtility.getContainerNameFromUri(storageURI, false);

            List<StorageAccountKey> storageKeys = customTokenCache.getAzureClient().storageAccounts()
                .getByGroup(testEnv.azureResourceGroup, storageAccountName)
                .getKeys();

            if (storageKeys.isEmpty()) {
                return false;
            } else {
                String storageAccountKey = storageKeys.get(0).value();
                CloudStorageAccount account = new CloudStorageAccount(new StorageCredentialsAccountAndKey(storageAccountName, storageAccountKey));
                CloudBlobClient blobClient = account.createCloudBlobClient();
                return blobClient.getContainerReference(containerName).exists();
            }
        } catch(Exception e) {
            return false;
        }
    }

    protected AzureVMDeploymentInfo createDefaultDeployment(final int numberOfAgents, AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar) throws AzureCloudException, IOException, Exception {
        final String templateName = "t" + TestEnvironment.GenerateRandomString(7);
        final String osType = Constants.OS_TYPE_LINUX;
        final String initScript = "echo \"" + UUID.randomUUID().toString() + "\"";
        final String launchMethod = Constants.LAUNCH_METHOD_SSH;
        final String vmUser = "tstVmUser";
        final Secret vmPassword = Secret.fromString(UUID.randomUUID().toString());

        StandardUsernamePasswordCredentials vmCredentials = mock(StandardUsernamePasswordCredentials.class);
        when(vmCredentials.getUsername()).thenReturn(vmUser);
        when(vmCredentials.getPassword()).thenReturn(vmPassword);

        if (deploymentRegistrar == null) {
            deploymentRegistrar = new AzureVMAgentCleanUpTask.DeploymentRegistrar();
        }

        AzureVMAgentTemplate templateMock = mock(AzureVMAgentTemplate.class);
        when(templateMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
        when(templateMock.getStorageAccountName()).thenReturn(testEnv.azureStorageAccountName);
        when(templateMock.getLocation()).thenReturn(testEnv.azureLocation);
        when(templateMock.getTemplateName()).thenReturn(templateName);
        when(templateMock.getOsType()).thenReturn(osType);
        when(templateMock.getInitScript()).thenReturn(initScript);
        when(templateMock.getAgentLaunchMethod()).thenReturn(launchMethod);
        when(templateMock.getImageReferenceType()).thenReturn("");
        when(templateMock.getImagePublisher()).thenReturn(testEnv.azureImagePublisher);
        when(templateMock.getImageOffer()).thenReturn(testEnv.azureImageOffer);
        when(templateMock.getImageSku()).thenReturn(testEnv.azureImageSku);
        when(templateMock.getVirtualMachineSize()).thenReturn(testEnv.azureImageSize);
        when(templateMock.getImage()).thenReturn("");
        when(templateMock.getVMCredentials()).thenReturn(vmCredentials);
        when(templateMock.getAzureCloud()).thenReturn(mock(AzureVMCloud.class));

        AzureVMDeploymentInfo ret = AzureVMManagementServiceDelegate.createDeployment(templateMock, numberOfAgents, customTokenCache,deploymentRegistrar);
        List<String> vmNames = new ArrayList<>();
        for(int i = 0; i< numberOfAgents; i++) {
            vmNames.add(ret.getVmBaseName() + String.valueOf(i));
        }

        //wait for deployment to complete
        final int maxTries = 20; //wait 10 minutes
        for(int i = 0; i < maxTries; i++) {
            if (areAllVMsDeployed(vmNames)) {
                return ret;
            }

            try {
                Thread.sleep(30 * 1000);
            } catch (InterruptedException ex) {
                // ignore
            }
        }
        throw new Exception("Deployment is not completed after 10 minutes");
    }
    
    protected boolean areAllVMsDeployed(final List<String> vmNames) {
        int deployedVMs = 0;
        PagedList<Deployment> deployments= customTokenCache.getAzureClient().deployments().listByGroup(testEnv.azureResourceGroup);
        for (Deployment dep : deployments) {
            PagedList<DeploymentOperation> ops = dep.deploymentOperations().list();
            for (DeploymentOperation op : ops) {
                if (op.targetResource() == null) {
                    continue;
                }
                final String resource = op.targetResource().resourceName();
                final String state = op.provisioningState();
                if (op.targetResource().resourceType().contains("virtualMachine")) {
                    if (!state.equalsIgnoreCase("creating")
                        && !state.equalsIgnoreCase("succeeded")
                        && !state.equalsIgnoreCase("running")){
                        return false;
                    }
                    else if (state.equalsIgnoreCase("succeeded")) {
                        for(String vmName: vmNames) {
                            if(resource.equalsIgnoreCase(vmName)) {
                                deployedVMs++;
                                break;
                            }
                        }
                    } else {
                        return false;
                    }
                }
            }
        }
        return deployedVMs == vmNames.size();
    }

    protected VirtualMachine createAzureVM(final String vmName) throws CloudException, IOException {
        return createAzureVM(vmName, "JenkinsTag", "testing");
    }
    protected VirtualMachine createAzureVM(final String vmName, final String tagName, final String tagValue) throws CloudException, IOException {
        return customTokenCache.getAzureClient().virtualMachines()
                .define(vmName)
                .withRegion(testEnv.azureLocation)
                .withNewResourceGroup(testEnv.azureResourceGroup)
                .withNewPrimaryNetwork("10.0.0.0/28")
                .withPrimaryPrivateIpAddressDynamic()
                .withoutPrimaryPublicIpAddress()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_14_04_LTS)
                .withRootUsername(TestEnvironment.GenerateRandomString(8))
                .withRootPassword(TestEnvironment.GenerateRandomString(16) + "AA@@12345") //don't try this at home
                .withTag(Constants.AZURE_JENKINS_TAG_NAME, Constants.AZURE_JENKINS_TAG_VALUE)
                .withTag(tagName, tagValue)
                .create();
    }

    protected URI uploadFile(final String uploadFileName, final String writtenData, final String containerName) throws Exception {
        customTokenCache.getAzureClient().resourceGroups()
            .define(testEnv.azureResourceGroup)
            .withRegion(testEnv.azureLocation)
            .create();
        customTokenCache.getAzureClient().storageAccounts().define(testEnv.azureStorageAccountName)
            .withRegion(testEnv.azureLocation)
            .withExistingResourceGroup(testEnv.azureResourceGroup)
            .withSku(SkuName.STANDARD_LRS)
            .create();
        List<StorageAccountKey> storageKeys = customTokenCache.getAzureClient().storageAccounts()
            .getByGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName)
            .getKeys();
        if(storageKeys.isEmpty()) {
            throw new Exception("Can't find key");
        }

        String storageAccountKey = storageKeys.get(0).value();

        CloudStorageAccount account = new CloudStorageAccount(new StorageCredentialsAccountAndKey(testEnv.azureStorageAccountName, storageAccountKey));
        CloudBlobClient blobClient = account.createCloudBlobClient();
        CloudBlobContainer container = blobClient.getContainerReference(containerName);
        container.createIfNotExists();
        CloudBlockBlob blob = container.getBlockBlobReference(uploadFileName);
        blob.uploadText(writtenData, "UTF-8", AccessCondition.generateEmptyCondition(), null, null);

        return blob.getUri();
    }
}
