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
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.KnownLinuxVirtualMachineImage;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.resources.Deployment;
import com.microsoft.azure.management.resources.DeploymentOperation;
import com.microsoft.azure.management.resources.ResourceGroups;
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
import com.microsoft.jenkins.azurecommons.core.AzureClientFactory;
import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsGlobalConfig;
import hudson.util.Secret;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.Timeout;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
To execute the integration tests you need to set the credentials env variables (the ones that don't have a default) and run mvn failsafe:integration-test
*/
public class IntegrationTest {
    @ClassRule
    public static JenkinsRule j = new JenkinsRule();
    @Rule
    public Timeout globalTimeout = Timeout.seconds(20 * 60); // integration tests are very slow
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
        public final String azureStorageAccountType;
        public final String azureImageId;
        public final String azureImagePublisher;
        public final String azureImageOffer;
        public final String azureImageSku;
        public final String azureImageSize;
        public final Map<String, String> blobEndpointSuffixForTemplate;
        public final Map<String, String> blobEndpointSuffixForCloudStorageAccount;
        public final static String AZUREPUBLIC = "azure public";
        public final static String AZURECHINA = "azure china";
        public final static String AZUREUSGOVERMENT = "azure us goverment";
        public final static String AZUREGERMAN = "azure german";

        TestEnvironment() {
            subscriptionId = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_SUBSCRIPTION_ID");
            clientId = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_CLIENT_ID");
            clientSecret = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_CLIENT_SECRET");

            oauth2TokenEndpoint = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_TOKEN_ENDPOINT");
            serviceManagementURL = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_AZURE_URL", "https://management.core.windows.net/");
            authenticationEndpoint = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_AZURE_AUTH_URL", "https://login.microsoftonline.com/");
            resourceManagerEndpoint = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_AZURE_RESOURCE_URL", "https://management.azure.com/");
            graphEndpoint = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_AZURE_GRAPH_URL", "https://graph.windows.net/");

            azureLocation = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_LOCATION", "East US");
            azureResourceGroup = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_RESOURCE_GROUP_PREFIX", "vmagents-tst") + "-" + TestEnvironment.GenerateRandomString(16);
            azureStorageAccountName = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_STORAGE_NAME_PREFIX", "vmtst") + TestEnvironment.GenerateRandomString(19);
            azureStorageAccountType = SkuName.STANDARD_LRS.toString();
            azureImageId = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_ID", "/subscriptions/vmagents-tst/resourceGroups/vmagents-tst/providers/Microsoft.Compute/images/MyJenkinsImage");
            azureImagePublisher = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_PUBLISHER", "Canonical");
            azureImageOffer = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_OFFER", "UbuntuServer");
            azureImageSku = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_SKU", "14.04.5-LTS");
            azureImageSize = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_SIZE", "Basic_A0");
            blobEndpointSuffixForTemplate = new HashMap<String, String>();
            blobEndpointSuffixForTemplate.put(AZUREPUBLIC, ".blob.core.windows.net/");
            blobEndpointSuffixForTemplate.put(AZURECHINA, ".blob.core.chinacloudapi.cn/");
            blobEndpointSuffixForTemplate.put(AZUREUSGOVERMENT, ".blob.core.usgovcloudapi.net/");
            blobEndpointSuffixForTemplate.put(AZUREGERMAN, ".blob.core.cloudapi.de/");
            blobEndpointSuffixForCloudStorageAccount = new HashMap<String, String>();
            blobEndpointSuffixForCloudStorageAccount.put(AZUREPUBLIC, "core.windows.net/");
            blobEndpointSuffixForCloudStorageAccount.put(AZURECHINA, "core.chinacloudapi.cn/");
            blobEndpointSuffixForCloudStorageAccount.put(AZUREUSGOVERMENT, "core.usgovcloudapi.net/");
            blobEndpointSuffixForCloudStorageAccount.put(AZUREGERMAN, "core.cloudapi.de/");

            // disable AI
            AppInsightsGlobalConfig.get().setAppInsightsEnabled(false);
        }

        private static String loadFromEnv(String name) {
            return TestEnvironment.loadFromEnv(name, "");
        }

        private static String loadFromEnv(String name, String defaultValue) {
            final String value = System.getenv(name);
            if (value == null || value.isEmpty()) {
                return defaultValue;
            } else {
                return value;
            }
        }

        public static String GenerateRandomString(int length) {
            String uuid = UUID.randomUUID().toString();
            return uuid.replaceAll("[^a-z0-9]", "a").substring(0, length);
        }
    }

    protected Azure azureClient;
    protected AzureVMManagementServiceDelegate delegate;
    protected TestEnvironment testEnv = null;

    @Before
    public void setUp() {
        testEnv = new TestEnvironment();
        LOGGER.log(Level.INFO, "=========================== {0}", testEnv.azureResourceGroup);
        ServicePrincipal servicePrincipal = new ServicePrincipal(
                testEnv.subscriptionId,
                testEnv.clientId,
                testEnv.clientSecret,
                testEnv.oauth2TokenEndpoint,
                testEnv.serviceManagementURL,
                testEnv.authenticationEndpoint,
                testEnv.resourceManagerEndpoint,
                testEnv.graphEndpoint);
        azureClient = AzureClientFactory.getClient(
                servicePrincipal.getClientId(),
                servicePrincipal.getClientSecret(),
                servicePrincipal.getTenant(),
                servicePrincipal.getSubscriptionId(),
                servicePrincipal.getAzureEnvironment());
        delegate = AzureVMManagementServiceDelegate.getInstance(azureClient);
        clearAzureResources();

        AppInsightsGlobalConfig.get().setAppInsightsEnabled(false);
    }

    @After
    public void tearDown() {
        clearAzureResources();
    }

    protected void clearAzureResources() {
        try {

            ResourceGroups rgs = azureClient.resourceGroups();
            if (rgs.checkExistence(testEnv.azureResourceGroup)) {
                rgs.deleteByName(testEnv.azureResourceGroup);
            }
        } catch (CloudException e) {
            if (e.response().code() != 404) {
                LOGGER.log(Level.SEVERE, null, e);
            }
        }

    }

    protected String downloadFromAzure(String resourceGroup, String storageAccountName, String containerName, String fileName)
            throws URISyntaxException, StorageException, IOException, AzureCloudException {
        List<StorageAccountKey> storageKeys = azureClient.storageAccounts().getByResourceGroup(resourceGroup, storageAccountName).getKeys();
        String storageAccountKey = storageKeys.get(0).value();
        CloudStorageAccount account = new CloudStorageAccount(new StorageCredentialsAccountAndKey(storageAccountName, storageAccountKey));
        CloudBlobClient blobClient = account.createCloudBlobClient();
        CloudBlobContainer container = blobClient.getContainerReference(containerName);
        CloudBlockBlob blob = container.getBlockBlobReference(fileName);
        return blob.downloadText();
    }

    protected boolean blobExists(URI storageURI) {
        try {
            final String storageAccountName = storageURI.getHost().split("\\.")[0];
            final String containerName = PathUtility.getContainerNameFromUri(storageURI, false);
            final String blobName = PathUtility.getBlobNameFromURI(storageURI, false);

            List<StorageAccountKey> storageKeys = azureClient.storageAccounts()
                    .getByResourceGroup(testEnv.azureResourceGroup, storageAccountName)
                    .getKeys();

            if (storageKeys.isEmpty()) {
                return false;
            } else {
                String storageAccountKey = storageKeys.get(0).value();
                CloudStorageAccount account = new CloudStorageAccount(new StorageCredentialsAccountAndKey(storageAccountName, storageAccountKey));
                CloudBlobClient blobClient = account.createCloudBlobClient();
                return blobClient.getContainerReference(containerName).getBlockBlobReference(blobName).exists();
            }
        } catch (Exception e) {
            return false;
        }
    }

    protected boolean containerExists(URI storageURI) {
        try {
            final String storageAccountName = storageURI.getHost().split("\\.")[0];
            final String containerName = PathUtility.getContainerNameFromUri(storageURI, false);

            List<StorageAccountKey> storageKeys = azureClient.storageAccounts()
                    .getByResourceGroup(testEnv.azureResourceGroup, storageAccountName)
                    .getKeys();

            if (storageKeys.isEmpty()) {
                return false;
            } else {
                String storageAccountKey = storageKeys.get(0).value();
                CloudStorageAccount account = new CloudStorageAccount(new StorageCredentialsAccountAndKey(storageAccountName, storageAccountKey));
                CloudBlobClient blobClient = account.createCloudBlobClient();
                return blobClient.getContainerReference(containerName).exists();
            }
        } catch (Exception e) {
            return false;
        }
    }

    protected AzureVMDeploymentInfo createDefaultDeployment(
            int numberOfAgents,
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar
    ) throws AzureCloudException, IOException, Exception {
        return createDefaultDeployment(numberOfAgents, true, deploymentRegistrar);
    }

    protected AzureVMDeploymentInfo createDefaultDeployment(
            int numberOfAgents,
            boolean usePrivateIP,
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar
    ) throws AzureCloudException, IOException, Exception {
        return createDefaultDeployment(numberOfAgents, usePrivateIP, "", deploymentRegistrar);
    }

    protected AzureVMDeploymentInfo createDefaultDeployment(
            int numberOfAgents,
            String nsgName,
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar
    ) throws AzureCloudException, IOException, Exception {
        return createDefaultDeployment(numberOfAgents, true, nsgName, deploymentRegistrar);
    }

    protected AzureVMDeploymentInfo createDefaultDeployment(
            int numberOfAgents,
            boolean usePrivateIP,
            String nsgName,
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar
    ) throws AzureCloudException, IOException, Exception {
        final String templateName = "t" + TestEnvironment.GenerateRandomString(7);
        final String osType = Constants.OS_TYPE_LINUX;
        final String initScript = "echo \"" + UUID.randomUUID().toString() + "\"";
        final String launchMethod = Constants.LAUNCH_METHOD_SSH;
        final String vmUser = "tstVmUser";
        final Secret vmPassword = Secret.fromString(TestEnvironment.GenerateRandomString(16) + "AA@@12345@#$%^&*-_!+=[]{}|\\:`,.?/~\\\"();\'");
        final String storageType = SkuName.STANDARD_LRS.toString();

        StandardUsernamePasswordCredentials vmCredentials = mock(StandardUsernamePasswordCredentials.class);
        when(vmCredentials.getUsername()).thenReturn(vmUser);
        when(vmCredentials.getPassword()).thenReturn(vmPassword);

        if (deploymentRegistrar == null) {
            deploymentRegistrar = AzureVMAgentCleanUpTask.DeploymentRegistrar.getInstance();
        }

        AzureVMAgentTemplate templateMock = mock(AzureVMAgentTemplate.class);
        AzureVMCloud cloudMock = mock(AzureVMCloud.class);
        when(cloudMock.getCloudName()).thenReturn("testCloud");
        when(templateMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
        when(templateMock.getStorageAccountName()).thenReturn(testEnv.azureStorageAccountName);
        when(templateMock.getLocation()).thenReturn(testEnv.azureLocation);
        when(templateMock.getTemplateName()).thenReturn(templateName);
        when(templateMock.getOsType()).thenReturn(osType);
        when(templateMock.getInitScript()).thenReturn(initScript);
        when(templateMock.getAgentLaunchMethod()).thenReturn(launchMethod);
        when(templateMock.getImageTopLevelType()).thenReturn(Constants.IMAGE_TOP_LEVEL_ADVANCED);
        when(templateMock.isTopLevelType(Constants.IMAGE_TOP_LEVEL_BASIC)).thenReturn(false);
        when(templateMock.getImageReferenceType()).thenReturn("reference");
        when(templateMock.getImageId()).thenReturn(testEnv.azureImageId);
        when(templateMock.getImagePublisher()).thenReturn(testEnv.azureImagePublisher);
        when(templateMock.getImageOffer()).thenReturn(testEnv.azureImageOffer);
        when(templateMock.getImageSku()).thenReturn(testEnv.azureImageSku);
        when(templateMock.getVirtualMachineSize()).thenReturn(testEnv.azureImageSize);
        when(templateMock.getImage()).thenReturn("");
        when(templateMock.getVMCredentials()).thenReturn(vmCredentials);
        when(templateMock.getAzureCloud()).thenReturn(cloudMock);
        when(templateMock.getUsePrivateIP()).thenReturn(!usePrivateIP);
        when(templateMock.getNsgName()).thenReturn(nsgName);
        when(templateMock.getStorageAccountType()).thenReturn(storageType);
        when(templateMock.getDiskType()).thenReturn(Constants.DISK_UNMANAGED);
        when(templateMock.getPreInstallSsh()).thenReturn(true);

        AzureVMDeploymentInfo ret = delegate.createDeployment(templateMock, numberOfAgents, deploymentRegistrar);
        List<String> vmNames = new ArrayList<>();
        for (int i = 0; i < numberOfAgents; i++) {
            vmNames.add(ret.getVmBaseName() + String.valueOf(i));
        }

        //wait for deployment to complete
        final int maxTries = 20; //wait 10 minutes
        for (int i = 0; i < maxTries; i++) {
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

    protected boolean areAllVMsDeployed(final List<String> vmNames) throws AzureCloudException {
        int deployedVMs = 0;
        PagedList<Deployment> deployments = azureClient.deployments().listByResourceGroup(testEnv.azureResourceGroup);
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
                            && !state.equalsIgnoreCase("running")) {
                        return false;
                    } else if (state.equalsIgnoreCase("succeeded")) {
                        for (String vmName : vmNames) {
                            if (resource.equalsIgnoreCase(vmName)) {
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

    protected VirtualMachine createAzureVM(String vmName)
            throws CloudException, IOException, AzureCloudException {
        return createAzureVM(vmName, "JenkinsTag", "testing");
    }

    protected VirtualMachine createAzureVM(final String vmName, final String tagName, final String tagValue)
            throws CloudException, IOException, AzureCloudException {
        return azureClient.virtualMachines()
                .define(vmName)
                .withRegion(testEnv.azureLocation)
                .withNewResourceGroup(testEnv.azureResourceGroup)
                .withNewPrimaryNetwork("10.0.0.0/28")
                .withPrimaryPrivateIPAddressDynamic()
                .withoutPrimaryPublicIPAddress()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_14_04_LTS)
                .withRootUsername(TestEnvironment.GenerateRandomString(8))
                .withRootPassword(TestEnvironment.GenerateRandomString(16) + "AA@@12345@#$%^&*-_!+=[]{}|\\:`,.?/~\\\"();\'") //don't try this at home
                .withUnmanagedDisks()
                .withTag(Constants.AZURE_JENKINS_TAG_NAME, Constants.AZURE_JENKINS_TAG_VALUE)
                .withTag(tagName, tagValue)
                .create();
    }

    protected URI uploadFile(String uploadFileName, String writtenData, String containerName) throws Exception {
        azureClient.resourceGroups()
                .define(testEnv.azureResourceGroup)
                .withRegion(testEnv.azureLocation)
                .create();
        azureClient.storageAccounts().define(testEnv.azureStorageAccountName)
                .withRegion(testEnv.azureLocation)
                .withExistingResourceGroup(testEnv.azureResourceGroup)
                .withSku(SkuName.STANDARD_LRS)
                .create();
        List<StorageAccountKey> storageKeys = azureClient.storageAccounts()
                .getByResourceGroup(testEnv.azureResourceGroup, testEnv.azureStorageAccountName)
                .getKeys();
        if (storageKeys.isEmpty()) {
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
