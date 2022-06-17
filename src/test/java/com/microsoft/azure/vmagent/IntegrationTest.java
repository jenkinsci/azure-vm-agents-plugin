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

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.management.exception.ManagementException;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.KnownLinuxVirtualMachineImage;
import com.azure.resourcemanager.compute.models.StorageAccountTypes;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.resources.models.Deployment;
import com.azure.resourcemanager.resources.models.DeploymentOperation;
import com.azure.resourcemanager.resources.models.ResourceGroups;
import com.azure.resourcemanager.storage.models.SkuName;
import com.azure.resourcemanager.storage.models.StorageAccount;
import com.azure.resourcemanager.storage.models.StorageAccountKey;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.BlobUrlParts;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.util.Constants;
import hudson.util.Secret;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.Timeout;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
To execute the integration tests you need to set the credentials env variables (the ones that don't have a default)
and run mvn failsafe:integration-test.
You will also need to run 'az vm image terms accept --offer kali-linux --plan kali --publish kali-linux' one time.
*/
public class IntegrationTest {
    protected static final String OS_TYPE = Constants.OS_TYPE_LINUX;

    @ClassRule
    public static JenkinsRule jenkinsRule() {
        JenkinsRule j = new JenkinsRule();
        j.timeout = 20 * 60;
        return j;
    }

    @Rule
    public Timeout globalTimeout = Timeout.seconds(20 * 60); // integration tests are very slow
    private static final Logger LOGGER = Logger.getLogger(IntegrationTest.class.getName());


    protected static class TestEnvironment {
        public final String subscriptionId;
        public final String clientId;
        public final String clientSecret;
        public final String tenantId;
        public final String serviceManagementURL;
        public final String authenticationEndpoint;
        public final String resourceManagerEndpoint;
        public final String azureLocation;
        public String availabilityType;
        public String availabilitySet;
        public final String azureResourceGroup;
        public final String azureResourceGroupReferenceType;
        public final String azureStorageAccountName;
        public final String azureStorageAccountType;
        public final String azureImageId;
        public String azureImagePublisher;
        public String azureImageOffer;
        public String azureImageSku;
        public String azureImageVersion;
        public String galleryName;
        public String galleryImageDefinition;
        public String galleryImageVersion;
        public boolean galleryImageSpecialized;
        public String gallerySubscriptionId;
        public String galleryResourceGroup;
        public List<AzureTagPair> customTags;
        public List<AzureTagPair> templateTags;
        public int osDiskSize;
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

            tenantId = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_TENANT_ID");
            serviceManagementURL = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_AZURE_URL", "https://management.core.windows.net/");
            authenticationEndpoint = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_AZURE_AUTH_URL", "https://login.microsoftonline.com/");
            resourceManagerEndpoint = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_AZURE_RESOURCE_URL", "https://management.azure.com/");

            azureLocation = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_LOCATION", "East US");
            azureResourceGroup = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_RESOURCE_GROUP_PREFIX", "vmagents-tst") + "-" + TestEnvironment.GenerateRandomString(16);
            azureResourceGroupReferenceType = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_RESOURCE_REFERENCE_TYPE", Constants.RESOURCE_GROUP_REFERENCE_TYPE_NEW);
            azureStorageAccountName = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_STORAGE_NAME_PREFIX", "vmtst") + TestEnvironment.GenerateRandomString(19);
            azureStorageAccountType = SkuName.STANDARD_LRS.toString();
            azureImageId = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_ID", "");
            azureImagePublisher = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_PUBLISHER", "Canonical");
            azureImageOffer = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_OFFER", "UbuntuServer");
            azureImageSku = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_SKU", "18.04-LTS");
            azureImageVersion = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_VERSION", "latest");
            galleryName = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_GALLERY_NAME", "");
            galleryImageDefinition = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_GALLERY_IMAGE_DEFINITION", "");
            galleryImageVersion = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_GALLERY_IMAGE_VERSION", "");
            gallerySubscriptionId = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_GALLERY_SUBSCRIPTION_ID", "");
            galleryResourceGroup = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_GALLERY_RESOURCE_GROUP", "");
            azureImageSize = TestEnvironment.loadFromEnv("VM_AGENTS_TEST_DEFAULT_IMAGE_SIZE", "Standard_A1_v2");
            osDiskSize = 0;
            availabilityType = AvailabilityType.UNKNOWN.getName();
            availabilitySet = "";
            customTags = new ArrayList<>();
            customTags.add(new AzureTagPair("author", "gavin"));
            templateTags = new ArrayList<>();
            templateTags.add(new AzureTagPair("env", "test"));
            blobEndpointSuffixForTemplate = new HashMap<>();
            blobEndpointSuffixForTemplate.put(AZUREPUBLIC, ".blob.core.windows.net/");
            blobEndpointSuffixForTemplate.put(AZURECHINA, ".blob.core.chinacloudapi.cn/");
            blobEndpointSuffixForTemplate.put(AZUREUSGOVERMENT, ".blob.core.usgovcloudapi.net/");
            blobEndpointSuffixForTemplate.put(AZUREGERMAN, ".blob.core.cloudapi.de/");
            blobEndpointSuffixForCloudStorageAccount = new HashMap<>();
            blobEndpointSuffixForCloudStorageAccount.put(AZUREPUBLIC, "core.windows.net/");
            blobEndpointSuffixForCloudStorageAccount.put(AZURECHINA, "core.chinacloudapi.cn/");
            blobEndpointSuffixForCloudStorageAccount.put(AZUREUSGOVERMENT, "core.usgovcloudapi.net/");
            blobEndpointSuffixForCloudStorageAccount.put(AZUREGERMAN, "core.cloudapi.de/");
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

    protected AzureResourceManager azureClient;
    protected AzureVMManagementServiceDelegate delegate;
    protected TestEnvironment testEnv = null;

    protected void addAzureCredentials(String id, String description, String subscriptionId, String clientId, String clientSecret) {
        Map<Domain, List<Credentials>> domainCredentialsMap = SystemCredentialsProvider.getInstance().getDomainCredentialsMap();
        List<Credentials> credentials = domainCredentialsMap.get(Domain.global());
        AzureCredentials cred = new AzureCredentials(CredentialsScope.GLOBAL, id, description, subscriptionId, clientId, Secret.fromString(clientSecret));
        cred.setTenant(testEnv.tenantId);
        credentials.add(cred);
        SystemCredentialsProvider.getInstance().setDomainCredentialsMap(domainCredentialsMap);
    }

    @Before
    public void setUp() {
        testEnv = new TestEnvironment();
        LOGGER.log(Level.INFO, "=========================== {0}", testEnv.azureResourceGroup);

        String azureCredentialsId = "testId";
        addAzureCredentials(azureCredentialsId, "test", testEnv.subscriptionId, testEnv.clientId, testEnv.clientSecret);


        azureClient = AzureClientUtil.getClient(azureCredentialsId);

        delegate = AzureVMManagementServiceDelegate.getInstance(azureClient, azureCredentialsId);
        clearAzureResources();
    }

    @After
    public void tearDown() {
        clearAzureResources();
    }

    protected void clearAzureResources() {
        try {

            ResourceGroups rgs = azureClient.resourceGroups();
            if (rgs.getByName(testEnv.azureResourceGroup) != null) {
                rgs.deleteByName(testEnv.azureResourceGroup);
            }
        } catch (ManagementException e) {
            if (e.getResponse().getStatusCode() != 404) {
                LOGGER.log(Level.SEVERE, null, e);
            }
        }

    }

    protected String downloadFromAzure(String resourceGroup, String storageAccountName, String containerName, String fileName)
            throws IOException {
        StorageAccount storageAccount = azureClient.storageAccounts().getByResourceGroup(resourceGroup, storageAccountName);
        List<StorageAccountKey> storageKeys = storageAccount.getKeys();
        String storageAccountKey = storageKeys.get(0).value();
        BlobServiceClient account = new BlobServiceClientBuilder()
                .credential(new StorageSharedKeyCredential(storageAccountName, storageAccountKey))
                .endpoint(storageAccount.endPoints().primary().blob())
                .buildClient();
        BlobContainerClient blobClient = account.getBlobContainerClient(containerName);
        if (!blobClient.exists()) {
            blobClient.create();
        }
        BlobClient blob = blobClient.getBlobClient(fileName);

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        blob.download(byteArrayOutputStream);

        return byteArrayOutputStream.toString(StandardCharsets.UTF_8.name());
    }

    protected boolean blobExists(String storageURI) {
        try {
            BlobUrlParts blobUrlParts = BlobUrlParts.parse(storageURI);
            String storageAccountName = blobUrlParts.getAccountName();
            final String containerName = blobUrlParts.getBlobContainerName();
            final String blobName = blobUrlParts.getBlobName();

            StorageAccount storageAccount = azureClient.storageAccounts()
                    .getByResourceGroup(testEnv.azureResourceGroup, storageAccountName);
            List<StorageAccountKey> storageKeys = storageAccount
                    .getKeys();

            if (storageKeys.isEmpty()) {
                return false;
            } else {
                String storageAccountKey = storageKeys.get(0).value();
                BlobServiceClient account = new BlobServiceClientBuilder()
                        .credential(new StorageSharedKeyCredential(storageAccountName, storageAccountKey))
                        .endpoint(storageAccount.endPoints().primary().blob())
                        .buildClient();
                BlobContainerClient blobClient = account.getBlobContainerClient(containerName);
                return blobClient.getBlobClient(blobName).exists();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            return false;
        }
    }

    protected boolean containerExists(String storageURI) {
        try {
            BlobUrlParts blobUrlParts = BlobUrlParts.parse(storageURI);
            String storageAccountName = blobUrlParts.getAccountName();
            final String containerName = blobUrlParts.getBlobContainerName();

            StorageAccount storageAccount = azureClient.storageAccounts()
                    .getByResourceGroup(testEnv.azureResourceGroup, storageAccountName);
            List<StorageAccountKey> storageKeys = storageAccount
                    .getKeys();

            if (storageKeys.isEmpty()) {
                return false;
            } else {
                String storageAccountKey = storageKeys.get(0).value();
                BlobServiceClient account = new BlobServiceClientBuilder()
                        .credential(new StorageSharedKeyCredential(storageAccountName, storageAccountKey))
                        .endpoint(storageAccount.endPoints().primary().blob())
                        .buildClient();
                BlobContainerClient blobClient = account.getBlobContainerClient(containerName);
                return blobClient.exists();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
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
            String templateName,
            int numberOfAgents,
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar
    ) throws AzureCloudException, IOException, Exception {
        return createDefaultDeployment(templateName, numberOfAgents, true, deploymentRegistrar);
    }

    protected AzureVMDeploymentInfo createDefaultDeployment(
            int numberOfAgents,
            boolean usePrivateIP,
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar
    ) throws AzureCloudException, IOException, Exception {
        return createDefaultDeployment(numberOfAgents, usePrivateIP, false, false, false, "", deploymentRegistrar);
    }

    protected AzureVMDeploymentInfo createDefaultDeployment(
            String templateName,
            int numberOfAgents,
            boolean usePrivateIP,
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar
    ) throws AzureCloudException, IOException, Exception {
        return createDefaultDeployment(templateName, numberOfAgents, usePrivateIP, false, false, false, "", deploymentRegistrar);
    }

    protected AzureVMDeploymentInfo createDefaultDeployment(
            int numberOfAgents,
            String nsgName,
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar
    ) throws AzureCloudException, IOException, Exception {
        return createDefaultDeployment(numberOfAgents, true, false, false, false, nsgName, deploymentRegistrar);
    }

    protected AzureVMDeploymentInfo createDefaultDeployment(
            int numberOfAgents,
            boolean usePrivateIP,
            boolean enableMSI,
            boolean enableUAMI,
            boolean ephemeralOSDisk,
            String nsgName,
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar
    ) throws Exception {
        final String templateName = "t" + TestEnvironment.GenerateRandomString(7);
        return createDefaultDeployment(templateName, numberOfAgents, usePrivateIP, enableMSI, enableUAMI, ephemeralOSDisk, nsgName, deploymentRegistrar);
    }

    protected AzureVMDeploymentInfo createDefaultDeployment(
            String templateName,
            int numberOfAgents,
            boolean usePrivateIP,
            boolean enableMSI,
            boolean enableUAMI,
            boolean ephemeralOSDisk,
            String nsgName,
            AzureVMAgentCleanUpTask.DeploymentRegistrar deploymentRegistrar
    ) throws Exception {
        final String osType = OS_TYPE;
        final String initScript = "echo \"" + UUID.randomUUID().toString() + "\"";
        final String terminateScript = "echo \"" + UUID.randomUUID().toString() + "\"";
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
        when(cloudMock.getCloudTags()).thenReturn(testEnv.customTags);
        when(templateMock.getResourceGroupName()).thenReturn(testEnv.azureResourceGroup);
        when(templateMock.getResourceGroupReferenceType()).thenReturn(testEnv.azureResourceGroupReferenceType);
        when(templateMock.getStorageAccountName()).thenReturn(testEnv.azureStorageAccountName);
        when(templateMock.getLocation()).thenReturn(testEnv.azureLocation);
        when(templateMock.getAvailabilityType()).thenReturn(new AzureVMAgentTemplate.AvailabilityTypeClass(testEnv.availabilitySet));
        when(templateMock.getTemplateName()).thenReturn(templateName);
        when(templateMock.getOsType()).thenReturn(osType);
        when(templateMock.getInitScript()).thenReturn(initScript);
        when(templateMock.getTerminateScript()).thenReturn(terminateScript);
        when(templateMock.getAgentLaunchMethod()).thenReturn(launchMethod);
        when(templateMock.getImageTopLevelType()).thenReturn(Constants.IMAGE_TOP_LEVEL_ADVANCED);
        when(templateMock.isTopLevelType(Constants.IMAGE_TOP_LEVEL_BASIC)).thenReturn(false);
        when(templateMock.getTags()).thenReturn(testEnv.templateTags);

        when(templateMock.getImageReference()).thenReturn(new AzureVMAgentTemplate.ImageReferenceTypeClass(
                null,
                testEnv.azureImageId,
                testEnv.azureImagePublisher,
                testEnv.azureImageOffer,
                testEnv.azureImageSku,
                testEnv.azureImageVersion,
                null,
                null,
                null,
                null,
                null
        ));

        when(templateMock.getVirtualMachineSize()).thenReturn(testEnv.azureImageSize);
        when(templateMock.getVMCredentials()).thenReturn(vmCredentials);
        when(templateMock.retrieveAzureCloudReference()).thenReturn(cloudMock);
        when(templateMock.getUsePrivateIP()).thenReturn(!usePrivateIP);
        when(templateMock.getNsgName()).thenReturn(nsgName);
        when(templateMock.getStorageAccountType()).thenReturn(storageType);
        when(templateMock.getOsDiskStorageAccountType()).thenReturn(storageType);
        when(templateMock.getDiskType()).thenReturn(Constants.DISK_MANAGED);
        when(templateMock.getOsDiskSize()).thenReturn(testEnv.osDiskSize);
        when(templateMock.isPreInstallSsh()).thenReturn(true);
        when(templateMock.isEnableMSI()).thenReturn(enableMSI);
        when(templateMock.isEnableUAMI()).thenReturn(enableUAMI);
        when(templateMock.isEphemeralOSDisk()).thenReturn(ephemeralOSDisk);

        AzureVMDeploymentInfo ret = delegate.createDeployment(templateMock, numberOfAgents, deploymentRegistrar);
        List<String> vmNames = new ArrayList<>();
        for (int i = 0; i < numberOfAgents; i++) {
            vmNames.add(ret.getVmBaseName() + i);
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
        PagedIterable<Deployment> deployments = azureClient.deployments().listByResourceGroup(testEnv.azureResourceGroup);
        for (Deployment dep : deployments) {
            PagedIterable<DeploymentOperation> ops = dep.deploymentOperations().list();
            for (DeploymentOperation op : ops) {
                if (op.targetResource() == null) {
                    continue;
                }
                final String resource = op.targetResource().resourceName();
                final String state = op.provisioningState();
                if (op.targetResource().resourceType().contains("virtualMachine")) {
                    switch (state.toLowerCase()) {
                        case "creating":
                        case "running":
                            return false;
                        case "succeeded":
                            for (String vmName : vmNames) {
                                if (resource.equalsIgnoreCase(vmName)) {
                                    deployedVMs++;
                                    break;
                                }
                            }
                            break;
                        default:
                            throw new IllegalStateException(
                                    String.format("the state of VM %s is '%s', message: %s", resource, state, op.statusMessage()));
                    }
                }
            }
        }
        return deployedVMs == vmNames.size();
    }

    protected VirtualMachine createAzureVM(String vmName) throws IOException, AzureCloudException {
        return createAzureVM(vmName, "JenkinsTag", "testing");
    }

    protected VirtualMachine createAzureVM(final String vmName, final String tagName, final String tagValue) {
        return azureClient.virtualMachines()
                .define(vmName)
                .withRegion(testEnv.azureLocation)
                .withNewResourceGroup(testEnv.azureResourceGroup)
                .withNewPrimaryNetwork("10.0.0.0/28")
                .withPrimaryPrivateIPAddressDynamic()
                .withoutPrimaryPublicIPAddress()
                .withPopularLinuxImage(KnownLinuxVirtualMachineImage.UBUNTU_SERVER_16_04_LTS)
                .withRootUsername(TestEnvironment.GenerateRandomString(8))
                .withRootPassword(TestEnvironment.GenerateRandomString(16) + "AA@@12345@#$%^&*-_!+=[]{}|\\:`,.?/~\\\"();\'") //don't try this at home
                .withOSDiskStorageAccountType(StorageAccountTypes.STANDARD_LRS)
                .withSize(testEnv.azureImageSize)
                .withTag(Constants.AZURE_JENKINS_TAG_NAME, Constants.AZURE_JENKINS_TAG_VALUE)
                .withTag(tagName, tagValue)
                .create();
    }

    protected String uploadFile(StorageAccount storageAccount, String uploadFileName, String writtenData, String containerName) throws Exception {
        List<StorageAccountKey> storageKeys = storageAccount.getKeys();
        if (storageKeys.isEmpty()) {
            throw new Exception("Can't find key");
        }

        String storageAccountKey = storageKeys.get(0).value();

        BlobServiceClient account = new BlobServiceClientBuilder()
                .credential(new StorageSharedKeyCredential(testEnv.azureStorageAccountName, storageAccountKey))
                .endpoint(storageAccount.endPoints().primary().blob())
                .buildClient();
        BlobContainerClient container = account.getBlobContainerClient(containerName);
        if (!container.exists()) {
            container.create();
        }
        BlobClient blob = container.getBlobClient(uploadFileName);
        ByteArrayInputStream stream = new ByteArrayInputStream(writtenData.getBytes(StandardCharsets.UTF_8));
        blob.upload(stream, writtenData.length());

        return blob.getBlobUrl();
    }
}
