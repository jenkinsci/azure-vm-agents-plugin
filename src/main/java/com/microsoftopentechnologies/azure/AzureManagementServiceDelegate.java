/*
 Copyright 2014 Microsoft Open Technologies, Inc.
 
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
package com.microsoftopentechnologies.azure;

import com.fasterxml.jackson.databind.JsonNode;
import hudson.model.Descriptor.FormException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.microsoft.azure.management.compute.ComputeManagementClient;
import com.microsoft.azure.management.compute.VirtualMachineImageOperations;
import com.microsoft.azure.management.compute.models.VirtualMachineGetResponse;
import com.microsoft.azure.management.compute.models.InstanceViewStatus;
import com.microsoft.azure.management.compute.models.ListParameters;
import com.microsoft.azure.management.compute.models.VirtualMachineImageGetParameters;
import com.microsoft.azure.management.network.NetworkResourceProviderClient;
import com.microsoft.azure.management.network.NetworkResourceProviderService;
import com.microsoft.azure.management.network.models.NetworkInterface;
import com.microsoft.azure.management.network.models.NetworkInterfaceGetResponse;
import com.microsoft.azure.management.network.models.PublicIpAddress;
import com.microsoft.azure.management.network.models.PublicIpAddressGetResponse;
import com.microsoft.azure.management.network.models.Subnet;
import com.microsoft.azure.management.network.models.VirtualNetwork;
import com.microsoft.azure.management.network.models.VirtualNetworkListResponse;
import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.models.Deployment;
import com.microsoft.azure.management.resources.models.DeploymentMode;
import com.microsoft.azure.management.resources.models.DeploymentProperties;
import com.microsoft.azure.management.resources.models.ResourceGroup;
import com.microsoft.azure.management.storage.StorageManagementClient;
import com.microsoft.azure.management.storage.StorageManagementService;
import com.microsoft.azure.management.storage.models.StorageAccount;
import com.microsoft.azure.management.storage.models.StorageAccountListResponse;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.xml.sax.SAXException;

import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.exception.ServiceException;
import com.microsoft.windowsazure.management.ManagementClient;
import com.microsoft.windowsazure.management.models.LocationsListResponse;
import com.microsoft.windowsazure.management.models.LocationsListResponse.Location;
import com.microsoft.windowsazure.management.models.RoleSizeListResponse;
import com.microsoft.windowsazure.management.models.RoleSizeListResponse.RoleSize;
import com.microsoftopentechnologies.azure.exceptions.AzureCloudException;
import com.microsoftopentechnologies.azure.exceptions.UnrecoverableCloudException;
import com.microsoftopentechnologies.azure.retry.ExponentialRetryStrategy;
import com.microsoftopentechnologies.azure.retry.NoRetryStrategy;
import com.microsoftopentechnologies.azure.util.AzureUtil;
import com.microsoftopentechnologies.azure.util.Constants;
import com.microsoftopentechnologies.azure.util.ExecutionEngine;
import com.microsoftopentechnologies.azure.util.FailureStage;
import java.io.InputStream;
import java.util.Arrays;
import java.util.logging.Level;

import org.apache.commons.lang.StringUtils;

/**
 * Business delegate class which handles calls to Azure management service SDK.
 *
 * @author Suresh Nallamilli (snallami@gmail.com)
 *
 */
public class AzureManagementServiceDelegate {

    private static final Logger LOGGER = Logger.getLogger(AzureManagementServiceDelegate.class.getName());

    private static final String EMBEDDED_TEMPLATE_FILENAME = "/templateValue.json";

    private static final String EMBEDDED_TEMPLATE_IMAGE_FILENAME = "/templateImageValue.json";

    private static final String IMAGE_CUSTOM_REFERENCE = "custom";

    private static final Map<String, List<String>> AVAILABLE_ROLE_SIZES = getAvailableRoleSizes();

    private static final List<String> AVAILABLE_LOCATIONS_STD = getAvailableLocations();

    private static final List<String> AVAILABLE_LOCATIONS_CHINA = getAvailableLocationsChina();
    
    public static String deployment(final AzureSlaveTemplate template, final int numberOfslaves)
            throws AzureCloudException {
        try {
            LOGGER.log(Level.INFO,
                    "AzureManagementServiceDelegate: deployment: Initializing deployment for slaveTemaple {0}",
                    template.getTemplateName());

            final ResourceManagementClient client = ServiceDelegateHelper.getResourceManagementClient(
                    ServiceDelegateHelper.getConfiguration(template));

            final long ts = System.currentTimeMillis();

            client.getResourceGroupsOperations().createOrUpdate(
                    Constants.RESOURCE_GROUP_NAME,
                    new ResourceGroup(template.getLocation()));

            final Deployment deployment = new Deployment();
            final DeploymentProperties properties = new DeploymentProperties();
            deployment.setProperties(properties);

            final InputStream embeddedTemplate;

            // check if a custom image id has been provided otherwise work with publisher and offer
            if (template.getImageReferenceType().equals(IMAGE_CUSTOM_REFERENCE)
                    && StringUtils.isNotBlank(template.getImage())) {
                LOGGER.log(Level.INFO, "Use embedded deployment template {0}", EMBEDDED_TEMPLATE_IMAGE_FILENAME);
                embeddedTemplate
                        = AzureManagementServiceDelegate.class.getResourceAsStream(EMBEDDED_TEMPLATE_IMAGE_FILENAME);
            } else {
                LOGGER.log(Level.INFO, "Use embedded deployment template {0}", EMBEDDED_TEMPLATE_FILENAME);
                embeddedTemplate
                        = AzureManagementServiceDelegate.class.getResourceAsStream(EMBEDDED_TEMPLATE_FILENAME);
            }

            final ObjectMapper mapper = new ObjectMapper();
            final JsonNode tmp = mapper.readTree(embeddedTemplate);

            // Add count variable for loop....
            final ObjectNode count = mapper.createObjectNode();
            count.put("type", "int");
            count.put("defaultValue", numberOfslaves);
            ObjectNode.class.cast(tmp.get("parameters")).replace("count", count);

            if (StringUtils.isBlank(template.getTemplateName())) {
                throw new AzureCloudException(
                        String.format("Invalid template name '%s'", template.getTemplateName()));
            }

            final String name = String.format("%s%d", template.getTemplateName(), ts);
            ObjectNode.class.cast(tmp.get("variables")).put("vmName", name);
            ObjectNode.class.cast(tmp.get("variables")).put("location", template.getLocation());

            if (StringUtils.isNotBlank(template.getImagePublisher())) {
                ObjectNode.class.cast(tmp.get("variables")).put("imagePublisher", template.getImagePublisher());
            }

            if (StringUtils.isNotBlank(template.getImageOffer())) {
                ObjectNode.class.cast(tmp.get("variables")).put("imageOffer", template.getImageOffer());
            }

            if (StringUtils.isNotBlank(template.getImageSku())) {
                ObjectNode.class.cast(tmp.get("variables")).put("imageSku", template.getImageSku());
            }

            if (StringUtils.isNotBlank(template.getOsType())) {
                ObjectNode.class.cast(tmp.get("variables")).put("osType", template.getOsType());
            }

            if (StringUtils.isNotBlank(template.getImage())) {
                ObjectNode.class.cast(tmp.get("variables")).put("image", template.getImage());
            }

            ObjectNode.class.cast(tmp.get("variables")).put("vmSize", template.getVirtualMachineSize());
            ObjectNode.class.cast(tmp.get("variables")).put("adminUsername", template.getAdminUserName());
            ObjectNode.class.cast(tmp.get("variables")).put("adminPassword", template.getAdminPassword());

            if (StringUtils.isNotBlank(template.getStorageAccountName())) {
                ObjectNode.class.cast(tmp.get("variables")).put("storageAccountName", template.getStorageAccountName());
            }

            if (StringUtils.isNotBlank(template.getVirtualNetworkName())) {
                ObjectNode.class.cast(tmp.get("variables")).put("virtualNetworkName", template.getVirtualNetworkName());
            }

            if (StringUtils.isNotBlank(template.getSubnetName())) {
                ObjectNode.class.cast(tmp.get("variables")).put("subnetName", template.getSubnetName());
            }

            // Deployment ....
            properties.setMode(DeploymentMode.Incremental);
            properties.setTemplate(tmp.toString());

            final String deploymentName = String.valueOf(ts);
            client.getDeploymentsOperations().createOrUpdate(Constants.RESOURCE_GROUP_NAME, deploymentName, deployment);
            return deploymentName;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AzureManagementServiceDelegate: deployment: Unable to deploy", e);
            throw new AzureCloudException(e);
        }
    }

    /**
     * Handle provisioning errors.
     *
     * @param ex
     * @param template
     * @param deploymentName
     * @throws AzureCloudException
     */
    public static void handleProvisioningException(
            final Exception ex, final AzureSlaveTemplate template, final String deploymentName)
            throws AzureCloudException {
        // conflict error - wait for 1 minute and try again
        if (AzureUtil.isConflictError(ex.getMessage())) {
            if (AzureUtil.isDeploymentAlreadyOccupied(ex.getMessage())) {
                LOGGER.info("AzureManagementServiceDelegate: handleProvisioningServiceException: "
                        + "Deployment already occupied");

                // Throw exception so that in retry this will go through
                throw new AzureCloudException(
                        "Provisioning Failure: Exception occured while creating virtual machine. Root cause: " + ex.
                        getMessage());
            } else {
                LOGGER.info("AzureManagementServiceDelegate: handleProvisioningServiceException: "
                        + "conflict error: waiting for a minute and will try again");
                try {
                    Thread.sleep(60 * 1000);
                } catch (InterruptedException e) {
                    //ignore
                }
            }
        } else if (AzureUtil.isBadRequestOrForbidden(ex.getMessage())) {
            LOGGER.info(
                    "AzureManagementServiceDelegate: handleProvisioningServiceException: Got bad request or forbidden");
            // no point in retrying
            template.handleTemplateStatus(
                    "Provisioning Failure: Exception occured while creating virtual machine. Root cause: " + ex.
                    getMessage(), FailureStage.PROVISIONING, null);
            throw new AzureCloudException(
                    "Provisioning Failure: Exception occured while creating virtual machine. Root cause: " + ex.
                    getMessage());
        } else if (AzureUtil.isDeploymentNotFound(ex.getMessage(), deploymentName)) {
            LOGGER.info("AzureManagementServiceDelegate: handleProvisioningServiceException: Deployment not found");

            // Throw exception so that in retry this will go through
            throw new AzureCloudException(
                    "Provisioning Failure: Exception occured while creating virtual machine. Root cause: " + ex.
                    getMessage());
        } else {
            LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: handleProvisioningException: {0}", ex);
            // set template status to disabled so that jenkins won't provision more slaves
            template.handleTemplateStatus(
                    "Provisioning Failure: Exception occured while creating virtual machine. Root cause: " + ex.
                    getMessage(), FailureStage.PROVISIONING, null);
            // wait for 10 seconds and then retry
            try {
                Thread.sleep(10 * 1000);
            } catch (InterruptedException e) {
                //ignore
            }
        }
    }

    /**
     * JSON string custom script public config value
     *
     * @param sasURL
     * @param fileName
     * @param jenkinsServerURL
     * @param vmName
     * @param jnlpSecret
     * @return
     * @throws Exception
     */
    public static String getCustomScriptPublicConfigValue(
            final String sasURL,
            final String fileName,
            final String jenkinsServerURL,
            final String vmName,
            final String jnlpSecret) throws Exception {
        JsonFactory factory = new JsonFactory();
        StringWriter stringWriter = new StringWriter();
        JsonGenerator json = factory.createJsonGenerator(stringWriter);

        json.writeStartObject();
        json.writeArrayFieldStart("fileUris");
        json.writeString(sasURL);
        json.writeEndArray();
        json.writeStringField("commandToExecute", "powershell -ExecutionPolicy Unrestricted -file " + fileName
                + " " + jenkinsServerURL + " " + vmName + " " + jnlpSecret + "  " + " 2>>c:\\error.log");
        json.writeEndObject();
        json.close();
        return stringWriter.toString();
    }

    /**
     * JSON string for custom script private config value.
     *
     * @param storageAccountName
     * @param storageAccountKey
     * @return
     * @throws Exception
     */
    public static String getCustomScriptPrivateConfigValue(
            final String storageAccountName,
            final String storageAccountKey)
            throws Exception {
        JsonFactory factory = new JsonFactory();
        StringWriter stringWriter = new StringWriter();
        JsonGenerator json = factory.createJsonGenerator(stringWriter);

        json.writeStartObject();
        json.writeStringField("storageAccountName", storageAccountName);
        json.writeStringField("storageAccountKey", storageAccountKey);
        json.writeEndObject();
        json.close();
        return stringWriter.toString();
    }

    /**
     * Sets properties of virtual machine like IP and ssh port
     *
     * @param azureSlave
     * @param template
     * @throws Exception
     */
    public static void setVirtualMachineDetails(
            final AzureSlave azureSlave, final AzureSlaveTemplate template) throws Exception {
        final Configuration config = ServiceDelegateHelper.getConfiguration(template);

        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);

        final VirtualMachineGetResponse vm
                = client.getVirtualMachinesOperations().get(Constants.RESOURCE_GROUP_NAME, azureSlave.getNodeName());

        final String ipRef = vm.getVirtualMachine().getNetworkProfile().getNetworkInterfaces().get(0).
                getReferenceUri();

        final NetworkInterface netIF = NetworkResourceProviderService.create(config).
                getNetworkInterfacesOperations().get(
                        Constants.RESOURCE_GROUP_NAME,
                        ipRef.substring(ipRef.lastIndexOf("/") + 1, ipRef.length())).
                getNetworkInterface();

        final String nicRef = netIF.getIpConfigurations().get(0).getPublicIpAddress().getId();

        final PublicIpAddress pubIP = NetworkResourceProviderService.create(config).
                getPublicIpAddressesOperations().get(
                        Constants.RESOURCE_GROUP_NAME,
                        nicRef.substring(nicRef.lastIndexOf("/") + 1, nicRef.length())).
                getPublicIpAddress();

        // Getting the first virtual IP
        azureSlave.setPublicDNSName(pubIP.getDnsSettings().getFqdn());
        azureSlave.setSshPort(Constants.DEFAULT_SSH_PORT);

        LOGGER.log(Level.INFO, "Azure slave details: {0}", azureSlave);
    }

    public static boolean virtualMachineExists(final AzureSlave slave) {
        final String name = slave.getNodeName();

        LOGGER.log(Level.INFO, "{0}: virtualMachineExists: check for {1}",
                new Object[] { AzureManagementServiceDelegate.class.getSimpleName(), name });

        try {
            final ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);
            client.getVirtualMachinesOperations().get(Constants.RESOURCE_GROUP_NAME, name);
            LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: virtualMachineExists: {0} exists", name);
            return true;
        } catch (ServiceException se) {
            if (Constants.ERROR_CODE_RESOURCE_NF.equalsIgnoreCase(se.getError().getCode())) {
                LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: virtualMachineExists: {0} doesn't exist", name);
                return false;
            }
        } catch (UnrecoverableCloudException uce) {
            if (uce.getCause() instanceof UnrecoverableCloudException) {
                LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: virtualMachineExists: unrecoverable VM", uce);
                return false;
            }
        } catch (Exception e) {
            //For rest of the errors just assume vm exists
        }

        LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: virtualMachineExists: {0} may exist", name);
        return true;
    }

    /**
     * Creates Azure slave object with necessary info.
     *
     * @param vmname
     * @param deploymentName
     * @param template
     * @param osType
     * @return
     * @throws AzureCloudException
     */
    public static AzureSlave parseResponse(
            final String vmname,
            final String deploymentName,
            final AzureSlaveTemplate template,
            final String osType) throws AzureCloudException {
        try {

            LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: parseDeploymentResponse: \n"
                    + "\tfound slave {0}\n"
                    + "\tOS type {1}\n"
                    + "\tnumber of executors {2}",
                    new Object[] { vmname, osType, template.getNoOfParallelJobs() });

            AzureCloud azureCloud = template.getAzureCloud();

            return new AzureSlave(
                    vmname,
                    template.getTemplateName(),
                    template.getTemplateDesc(),
                    osType,
                    template.getSlaveWorkSpace(),
                    template.getNoOfParallelJobs(),
                    template.getUseSlaveAlwaysIfAvail(),
                    template.getLabels(),
                    template.getAzureCloud().getDisplayName(),
                    template.getAdminUserName(),
                    null,
                    null,
                    template.getAdminPassword(),
                    template.getJvmOptions(),
                    template.isShutdownOnIdle(),
                    deploymentName,
                    template.getRetentionTimeInMin(),
                    template.getInitScript(),
                    azureCloud.getSubscriptionId(),
                    azureCloud.getClientId(),
                    azureCloud.getClientSecret(),
                    azureCloud.getOauth2TokenEndpoint(),
                    azureCloud.getServiceManagementURL(),
                    template.getSlaveLaunchMethod(),
                    false);
        } catch (FormException e) {
            throw new AzureCloudException("AzureManagementServiceDelegate: parseResponse: "
                    + "Exception occured while creating slave object", e);
        } catch (IOException e) {
            throw new AzureCloudException("AzureManagementServiceDelegate: parseResponse: "
                    + "Exception occured while creating slave object", e);
        }
    }

    public static List<String> getStorageAccountsInfo(final Configuration config) throws Exception {
        List<String> storageAccounts = new ArrayList<String>();
        StorageManagementClient client = StorageManagementService.create(config);

        StorageAccountListResponse response = client.getStorageAccountsOperations().list();
        for (StorageAccount sa : response.getStorageAccounts()) {
            storageAccounts.add(sa.getName() + " (" + sa.getLocation() + ")");
        }
        return storageAccounts;
    }

    private static List<String> getAvailableLocations() {
        return Arrays.asList(new String[] { "East US","West US","South Central US","Central US","North Central US","East US 2","North Europe","West Europe","Southeast Asia","East Asia","Japan West","Japan East","Brazil South","Australia Southeast","Australia East","Central India","South India","West India" });
    }
    
    private static List<String> getAvailableLocationsChina() {
        return Arrays.asList(new String[] { "China North","China East" });
    }
    
    /**
     * Creates a map containing location -> vm role size list.
     * This is hard coded and should be removed eventually once a transition to
     * the 1.0.0 SDK is made
     * @return New map 
     */
    private static Map<String, List<String>> getAvailableRoleSizes() {
        final Map<String, List<String>> sizes = new HashMap<String, List<String>>();
        sizes.put("East US", Arrays.asList(new String[] {"A10","A11","A5","A6","A7","A8","A9","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s"}));
        sizes.put("West US", Arrays.asList(new String[] {"A10","A11","A5","A6","A7","A8","A9","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s","Standard_G1","Standard_G2","Standard_G3","Standard_G4","Standard_G5","Standard_GS1","Standard_GS2","Standard_GS3","Standard_GS4","Standard_GS5"}));
        sizes.put("South Central US", Arrays.asList(new String[] {"A10","A11","A5","A6","A7","A8","A9","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s"}));
        sizes.put("Central US", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s"}));
        sizes.put("North Central US", Arrays.asList(new String[] {"A10","A11","A5","A6","A7","A8","A9","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1_v2","Standard_DS11_v2","Standard_DS12_v2","Standard_DS13_v2","Standard_DS14_v2","Standard_DS2_v2","Standard_DS3_v2","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s"}));
        sizes.put("East US 2", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s","Standard_G1","Standard_G2","Standard_G3","Standard_G4","Standard_G5","Standard_GS1","Standard_GS2","Standard_GS3","Standard_GS4","Standard_GS5"}));
        sizes.put("North Europe", Arrays.asList(new String[] {"A10","A11","A5","A6","A7","A8","A9","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s"}));
        sizes.put("West Europe", Arrays.asList(new String[] {"A10","A11","A5","A6","A7","A8","A9","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s","Standard_G1","Standard_G2","Standard_G3","Standard_G4","Standard_G5","Standard_GS1","Standard_GS2","Standard_GS3","Standard_GS4","Standard_GS5"}));
        sizes.put("Southeast Asia", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s","Standard_G1","Standard_G2","Standard_G3","Standard_G4","Standard_G5","Standard_GS1","Standard_GS2","Standard_GS3","Standard_GS4","Standard_GS5"}));
        sizes.put("East Asia", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS11","Standard_DS12","Standard_DS13","Standard_DS14","Standard_DS2","Standard_DS3","Standard_DS4","Standard_F1","Standard_F16","Standard_F2","Standard_F4","Standard_F8"}));
        sizes.put("Japan West", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s"}));
        sizes.put("Japan East", Arrays.asList(new String[] {"A10","A11","A5","A6","A7","A8","A9","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s"}));
        sizes.put("Brazil South", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1_v2","Standard_DS11_v2","Standard_DS12_v2","Standard_DS13_v2","Standard_DS14_v2","Standard_DS2_v2","Standard_DS3_v2","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s"}));
        sizes.put("Australia Southeast", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s"}));
        sizes.put("Australia East", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s","Standard_G1","Standard_G2","Standard_G3","Standard_G4","Standard_G5","Standard_GS1","Standard_GS2","Standard_GS3","Standard_GS4","Standard_GS5"}));
        sizes.put("Central India", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1_v2","Standard_D11_v2","Standard_D12_v2","Standard_D13_v2","Standard_D14_v2","Standard_D2_v2","Standard_D3_v2","Standard_D4_v2","Standard_D5_v2","Standard_DS1_v2","Standard_DS11_v2","Standard_DS12_v2","Standard_DS13_v2","Standard_DS14_v2","Standard_DS2_v2","Standard_DS3_v2","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s"}));
        sizes.put("South India", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1_v2","Standard_D11_v2","Standard_D12_v2","Standard_D13_v2","Standard_D14_v2","Standard_D2_v2","Standard_D3_v2","Standard_D4_v2","Standard_D5_v2","Standard_DS1_v2","Standard_DS11_v2","Standard_DS12_v2","Standard_DS13_v2","Standard_DS14_v2","Standard_DS2_v2","Standard_DS3_v2","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s"}));
        sizes.put("West India", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1_v2","Standard_D11_v2","Standard_D12_v2","Standard_D13_v2","Standard_D14_v2","Standard_D2_v2","Standard_D3_v2","Standard_D4_v2","Standard_D5_v2","Standard_F1","Standard_F16","Standard_F2","Standard_F4","Standard_F8"}));

        // China sizes, may not be exact
        sizes.put("China North", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS1_v2","Standard_DS11","Standard_DS11_v2","Standard_DS12","Standard_DS12_v2","Standard_DS13","Standard_DS13_v2","Standard_DS14","Standard_DS14_v2","Standard_DS2","Standard_DS2_v2","Standard_DS3","Standard_DS3_v2","Standard_DS4","Standard_DS4_v2","Standard_DS5_v2","Standard_F1","Standard_F16","Standard_F16s","Standard_F1s","Standard_F2","Standard_F2s","Standard_F4","Standard_F4s","Standard_F8","Standard_F8s","Standard_G1","Standard_G2","Standard_G3","Standard_G4","Standard_G5","Standard_GS1","Standard_GS2","Standard_GS3","Standard_GS4","Standard_GS5"}));
        sizes.put("China East", Arrays.asList(new String[] {"A5","A6","A7","Basic_A0","Basic_A1","Basic_A2","Basic_A3","Basic_A4","ExtraLarge","ExtraSmall","Large","Medium","Small","Standard_D1","Standard_D1_v2","Standard_D11","Standard_D11_v2","Standard_D12","Standard_D12_v2","Standard_D13","Standard_D13_v2","Standard_D14","Standard_D14_v2","Standard_D2","Standard_D2_v2","Standard_D3","Standard_D3_v2","Standard_D4","Standard_D4_v2","Standard_D5_v2","Standard_DS1","Standard_DS11","Standard_DS12","Standard_DS13","Standard_DS14","Standard_DS2","Standard_DS3","Standard_DS4","Standard_F1","Standard_F16","Standard_F2","Standard_F4","Standard_F8"}));
        
        return sizes;
    }
    
    /**
     * Gets list of Azure datacenter locations which supports Persistent VM role.
     * Today this is hardcoded pulling from the array, because the old form of
     * certificate based auth appears to be required.
     */
    public static List<String> getVirtualMachineLocations(String serviceManagementUrl) {
        if (serviceManagementUrl != null && serviceManagementUrl.toLowerCase().contains("china")) {
            return AVAILABLE_LOCATIONS_CHINA;
        }
        return AVAILABLE_LOCATIONS_STD;
    }
    
    /**
     * Gets list of virtual machine sizes.
     * Currently hardcoded because the old vm size API does not support 
     * the new  method of authentication
     * @param location Location to obtain VM sizes for
     */
    public static List<String> getVMSizes(final String location) {
        return AVAILABLE_ROLE_SIZES.get(location);
    }

    /**
     * Validates certificate configuration.
     *
     * @param subscriptionId
     * @param clientId
     * @param oauth2TokenEndpoint
     * @param clientSecret
     * @param serviceManagementURL
     * @return
     */
    public static String verifyConfiguration(
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String serviceManagementURL) {
        try {
            return verifyConfiguration(ServiceDelegateHelper.loadConfiguration(
                    subscriptionId,
                    clientId,
                    clientSecret,
                    oauth2TokenEndpoint,
                    serviceManagementURL));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error validating configuration", e);
            return "Failure: Exception occured while validating subscription configuration " + e;
        }
    }

    public static String verifyConfiguration(final Configuration config) {
        Callable<String> task = new Callable<String>() {

            @Override
            public String call() throws Exception {
                ServiceDelegateHelper.getStorageManagementClient(config).getStorageAccountsOperations().
                        checkNameAvailability("CI_SYSTEM");
                return Constants.OP_SUCCESS;
            }
        };

        try {
            return ExecutionEngine.executeWithRetry(task,
                    new ExponentialRetryStrategy(
                            3, //Max retries
                            2 // Max wait interval between retries
                    ));
        } catch (AzureCloudException e) {
            LOGGER.log(Level.SEVERE, "Error validating configuration", e);
            return "Failure: Exception occured while validating subscription configuration " + e;
        }
    }

    /**
     * Gets current status of virtual machine
     *
     * @param config
     * @param vmName
     * @return
     * @throws Exception
     */
    public static String getVirtualMachineStatus(final Configuration config, final String vmName)
            throws Exception {
        String powerstatus = StringUtils.EMPTY;
        String provisioning = StringUtils.EMPTY;

        final VirtualMachineGetResponse vm = ServiceDelegateHelper.getComputeManagementClient(config).
                getVirtualMachinesOperations().getWithInstanceView(Constants.RESOURCE_GROUP_NAME, vmName);

        for (InstanceViewStatus instanceStatus : vm.getVirtualMachine().getInstanceView().getStatuses()) {
            if (instanceStatus.getCode().startsWith("ProvisioningState/")) {
                provisioning = instanceStatus.getCode().replace("ProvisioningState/", "");
            }
            if (instanceStatus.getCode().startsWith("PowerState/")) {
                powerstatus = instanceStatus.getCode().replace("PowerState/", "");
            }
        }

        LOGGER.log(Level.INFO, "Statuses:\n\tPowerState: {0}\n\tProvisioning: {1}",
                new Object[] { powerstatus, provisioning });

        return "succeeded".equalsIgnoreCase(provisioning)
                ? powerstatus.toUpperCase() : Constants.STOPPED_DEALLOCATED_VM_STATUS;
    }

    /**
     * Checks if VM is reachable and in a valid state to connect.
     *
     * @param slave
     * @return
     * @throws Exception
     */
    public static boolean isVMAliveOrHealthy(final AzureSlave slave) throws Exception {
        Configuration config = ServiceDelegateHelper.getConfiguration(slave);
        String status = getVirtualMachineStatus(config, slave.getNodeName());
        LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: isVMAliveOrHealthy: status {0}", status);
        // if VM status is DeletingVM/StoppedVM/StoppingRole/StoppingVM then consider VM to be not healthy

        return !(Constants.DELETING_VM_STATUS.equalsIgnoreCase(status)
                || Constants.STOPPED_VM_STATUS.equalsIgnoreCase(status)
                || Constants.STOPPING_VM_STATUS.equalsIgnoreCase(status)
                || Constants.STOPPING_ROLE_STATUS.equalsIgnoreCase(status)
                || Constants.STOPPED_DEALLOCATED_VM_STATUS.equalsIgnoreCase(status));
    }

    /**
     * Retrieves count of virtual machine in a azure subscription.
     *
     * @param client
     * @return
     * @throws Exception
     */
    public static int getVirtualMachineCount(final ComputeManagementClient client) throws Exception {
        try {
            return client.getVirtualMachinesOperations().listAll(new ListParameters()).getVirtualMachines().size();
        } catch (Exception e) {
            LOGGER.log(Level.INFO,
                    "AzureManagementServiceDelegate: getVirtualMachineCount: Got exception while getting hosted "
                    + "services info, assuming that there are no hosted services {0}", e);
            return 0;
        }
    }

    /**
     * Shutdowns Azure virtual machine.
     *
     * @param slave
     * @throws Exception
     */
    public static void shutdownVirtualMachine(final AzureSlave slave) throws Exception {
        ServiceDelegateHelper.getComputeManagementClient(slave).
                getVirtualMachinesOperations().powerOff(Constants.RESOURCE_GROUP_NAME, slave.getNodeName());
    }

    /**
     * Deletes Azure virtual machine.
     *
     * @param slave
     * @param sync
     * @throws Exception
     */
    public static void terminateVirtualMachine(final AzureSlave slave, final boolean sync) throws Exception {
        LOGGER.log(Level.INFO, "{0}: terminateVirtualMachine: called for {1} - asynchronous {2}",
                new Object[] { AzureManagementServiceDelegate.class.getSimpleName(), slave.getDisplayName(), !sync });

        if (sync) {
            terminateVirtualMachine(slave);
        } else {
            ExecutionEngine.executeAsync(new Callable<Void>() {

                @Override
                public Void call() throws Exception {
                    terminateVirtualMachine(slave);
                    return null;
                }
            }, new NoRetryStrategy());
        }
    }

    private static void terminateVirtualMachine(final AzureSlave slave) throws Exception {
        try {

            try {
                if (slave != null && StringUtils.isNotBlank(slave.getNodeName()) && virtualMachineExists(slave)) {
                    final ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);

                    LOGGER.log(Level.INFO, "Remove virtual machine {0}", slave.getNodeName());
                    client.getVirtualMachinesOperations().delete(Constants.RESOURCE_GROUP_NAME, slave.getNodeName());
                }
            } catch (ExecutionException ee) {
                LOGGER.log(Level.INFO,
                        "AzureManagementServiceDelegate: terminateVirtualMachine: while deleting VM", ee);

                if (!(ee.getCause() instanceof IllegalArgumentException)) {
                    throw ee;
                }

                // assume VM is no longer available
            } catch (ServiceException se) {
                LOGGER.log(Level.INFO,
                        "AzureManagementServiceDelegate: terminateVirtualMachine: while deleting VM", se);

                // Check if VM is already deleted: if VM is already deleted then just ignore exception.
                if (!Constants.ERROR_CODE_RESOURCE_NF.equalsIgnoreCase(se.getError().getCode())) {
                    throw se;
                }
            } finally {
                LOGGER.log(Level.INFO, "Clean operation starting for {0} NIC and IP", slave.getNodeName());
                ExecutionEngine.executeAsync(new Callable<Void>() {

                    @Override
                    public Void call() throws Exception {
                        removeIPName(slave);
                        return null;
                    }
                }, new NoRetryStrategy());
            }
        } catch (UnrecoverableCloudException uce) {
            LOGGER.log(Level.INFO,
                    "AzureManagementServiceDelegate: terminateVirtualMachine: unrecoverable exception deleting VM",
                    uce);
        }
    }

    private static void removeIPName(final AzureSlave slave) throws AzureCloudException {
        try {
            final NetworkResourceProviderClient client = ServiceDelegateHelper.getNetworkManagementClient(slave);

            final String nic = slave.getNodeName() + "NIC";
            try {
                LOGGER.log(Level.INFO, "Remove NIC {0}", nic);
                final NetworkInterfaceGetResponse obj
                        = client.getNetworkInterfacesOperations().get(Constants.RESOURCE_GROUP_NAME, nic);

                if (obj == null) {
                    LOGGER.log(Level.INFO, "NIC {0} already deprovisioned", nic);
                } else {
                    client.getNetworkInterfacesOperations().delete(Constants.RESOURCE_GROUP_NAME, nic);
                }
            } catch (Exception ignore) {
                LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: removeIPName: while deleting NIC", ignore);
            }

            final String ip = slave.getNodeName() + "IPName";
            try {
                LOGGER.log(Level.INFO, "Remove IP {0}", ip);
                final PublicIpAddressGetResponse obj
                        = client.getPublicIpAddressesOperations().get(Constants.RESOURCE_GROUP_NAME, ip);
                if (obj == null) {
                    LOGGER.log(Level.INFO, "IP {0} already deprovisioned", ip);
                } else {
                    client.getPublicIpAddressesOperations().delete(Constants.RESOURCE_GROUP_NAME, ip);
                }
            } catch (Exception ignore) {
                LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: removeIPName: while deleting IPName", ignore);
            }
        } catch (UnrecoverableCloudException uce) {
            LOGGER.log(Level.INFO,
                    "AzureManagementServiceDelegate: removeIPName: unrecoverable exception deleting IPName", uce);
        }
    }

    /**
     * Restarts Azure virtual machine.
     *
     * @param slave
     * @throws Exception
     */
    public static void restartVirtualMachine(final AzureSlave slave) throws Exception {
        ServiceDelegateHelper.getComputeManagementClient(slave).getVirtualMachinesOperations().
                restart(Constants.RESOURCE_GROUP_NAME, slave.getNodeName());
    }

    /**
     * Starts Azure virtual machine.
     *
     * @param slave
     * @throws Exception
     */
    public static void startVirtualMachine(final AzureSlave slave) throws Exception {
        LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: startVirtualMachine: {0}", slave.getNodeName());
        int retryCount = 0;
        boolean successful = false;

        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(slave);

        while (!successful) {
            try {
                client.getVirtualMachinesOperations().start(Constants.RESOURCE_GROUP_NAME, slave.getNodeName());
                successful = true; // may be we can just return
            } catch (Exception e) {
                LOGGER.log(Level.INFO, "AzureManagementServiceDelegate: startVirtualMachine: got exception while "
                        + "starting VM {0}. Will retry again after 30 seconds. Current retry count {1} / {2}\n",
                        new Object[] { slave.getNodeName(), retryCount, Constants.MAX_PROV_RETRIES });
                if (retryCount > Constants.MAX_PROV_RETRIES) {
                    throw e;
                } else {
                    retryCount++;
                    Thread.sleep(30 * 1000); // wait for 30 seconds
                }
            }
        }
    }

    /**
     * Returns virtual network site properties.
     *
     * @param config
     * @param virtualNetworkName
     * @return
     */
    private static VirtualNetwork getVirtualNetwork(
            final Configuration config, final String virtualNetworkName) {
        try {
            final NetworkResourceProviderClient client = ServiceDelegateHelper.getNetworkManagementClient(config);

            final VirtualNetworkListResponse listResponse
                    = client.getVirtualNetworksOperations().list(Constants.RESOURCE_GROUP_NAME);

            if (listResponse != null) {
                for (VirtualNetwork vnet : listResponse.getVirtualNetworks()) {
                    if (virtualNetworkName.equalsIgnoreCase(vnet.getName())) {
                        return vnet;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AzureManagementServiceDelegate: getVirtualNetworkInfo: "
                    + "Got exception while getting virtual network info {0}", virtualNetworkName);
        }
        return null;
    }

    /**
     * Verifies template configuration by making server calls if needed.
     *
     * @param subscriptionId
     * @param clientId
     * @param clientSecret
     * @param oauth2TokenEndpoint
     * @param serviceManagementURL
     * @param maxVirtualMachinesLimit
     * @param templateName
     * @param labels
     * @param location
     * @param virtualMachineSize
     * @param storageAccountName
     * @param noOfParallelJobs
     * @param image
     * @param osType
     * @param imagePublisher
     * @param imageOffer
     * @param imageSku
     * @param imageVersion
     * @param slaveLaunchMethod
     * @param initScript
     * @param adminUserName
     * @param adminPassword
     * @param virtualNetworkName
     * @param subnetName
     * @param retentionTimeInMin
     * @param templateStatus
     * @param jvmOptions
     * @param returnOnSingleError
     * @return
     */
    public static List<String> verifyTemplate(
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint,
            final String serviceManagementURL,
            final String maxVirtualMachinesLimit,
            final String templateName,
            final String labels,
            final String location,
            final String virtualMachineSize,
            final String storageAccountName,
            final String noOfParallelJobs,
            final String image,
            final String osType,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion,
            final String slaveLaunchMethod,
            final String initScript,
            final String adminUserName,
            final String adminPassword,
            final String virtualNetworkName,
            final String subnetName,
            final String retentionTimeInMin,
            //            final String cloudServiceName,
            final String templateStatus,
            final String jvmOptions,
            final boolean returnOnSingleError) {

        List<String> errors = new ArrayList<String>();
        Configuration config = null;

        // Load configuration
        try {
            config = ServiceDelegateHelper.loadConfiguration(
                    subscriptionId, clientId, clientSecret, oauth2TokenEndpoint, serviceManagementURL);

            // Verify if profile configuration is valid
            String validationResult = verifyAzureProfileConfiguration(
                    config, subscriptionId, clientId, clientSecret, oauth2TokenEndpoint);
            if (!validationResult.equalsIgnoreCase(Constants.OP_SUCCESS)) {
                errors.add(validationResult);
                // If profile validation failed , no point in validating rest of the field , just return error
                return errors;
            }

            //Verify number of parallel jobs
            if (returnOnSingleError) {
                validationResult = verifyNoOfExecutors(noOfParallelJobs);
                addValidationResultIfFailed(validationResult, errors);
                if (returnOnSingleError && errors.size() > 0) {
                    return errors;
                }
            }

            validationResult = verifyRetentionTime(retentionTimeInMin);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            //verify password
            validationResult = verifyAdminPassword(adminPassword);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            //verify JVM Options
            validationResult = verifyJvmOptions(jvmOptions);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            validationResult = verifyImage(image, osType, imagePublisher, imageOffer, imageSku, imageVersion);
            addValidationResultIfFailed(validationResult, errors);
            if (returnOnSingleError && errors.size() > 0) {
                return errors;
            }

            verifyTemplateAsync(
                    config,
                    templateName,
                    maxVirtualMachinesLimit,
                    location,
                    image,
                    osType,
                    imagePublisher,
                    imageOffer,
                    imageSku,
                    imageVersion,
                    slaveLaunchMethod,
                    storageAccountName,
                    virtualNetworkName,
                    subnetName,
                    errors,
                    returnOnSingleError
            );

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error validating template", e);
            errors.add("Error occured while validating Azure Profile");
        }

        return errors;
    }

    private static void verifyTemplateAsync(
            final Configuration config,
            final String templateName,
            final String maxVirtualMachinesLimit,
            final String location,
            final String image,
            final String osType,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion,
            final String slaveLaunchMethod,
            final String storageAccountName,
            final String virtualNetworkName,
            final String subnetName,
            final List<String> errors,
            final boolean returnOnSingleError) {

        List<Callable<String>> verificationTaskList = new ArrayList<Callable<String>>();

        // Callable for max virtual limit
        if (returnOnSingleError) {
            Callable<String> callVerifyMaxVirtualMachineLimit = new Callable<String>() {

                @Override
                public String call() throws Exception {
                    return verifyMaxVirtualMachineLimit(config, maxVirtualMachinesLimit);
                }
            };
            verificationTaskList.add(callVerifyMaxVirtualMachineLimit);
        }
        // Callable for virtual network.
        Callable<String> callVerifyVirtualNetwork = new Callable<String>() {

            @Override
            public String call() throws Exception {
                return verifyVirtualNetwork(config, virtualNetworkName, subnetName);
            }
        };
        verificationTaskList.add(callVerifyVirtualNetwork);

        // Callable for VM image.
        Callable<String> callVerifyVirtualMachineImage = new Callable<String>() {

            @Override
            public String call() throws Exception {
                return verifyVirtualMachineImage(config,
                        image, location, imagePublisher, imageOffer, imageSku, imageVersion);
            }
        };
        verificationTaskList.add(callVerifyVirtualMachineImage);

        ExecutorService executorService = Executors.newFixedThreadPool(verificationTaskList.size());

        try {
            for (Future<String> validationResult : executorService.invokeAll(verificationTaskList)) {
                try {
                    // Get will block until time expires or until task completes
                    String result = validationResult.get(60, TimeUnit.SECONDS);
                    addValidationResultIfFailed(result, errors);
                } catch (ExecutionException executionException) {
                    errors.add("Exception occured while validating temaplate " + executionException);
                } catch (TimeoutException timeoutException) {
                    errors.add("Exception occured while validating temaplate " + timeoutException);
                }
            }
        } catch (InterruptedException interruptedException) {
            errors.add("Exception occured while validating temaplate " + interruptedException);
        }
    }

    private static void addValidationResultIfFailed(final String validationResult, final List<String> errors) {
        if (!validationResult.equalsIgnoreCase(Constants.OP_SUCCESS)) {
            errors.add(validationResult);
        }
    }

    private static String verifyAzureProfileConfiguration(
            final Configuration config,
            final String subscriptionId,
            final String clientId,
            final String clientSecret,
            final String oauth2TokenEndpoint) {

        if (StringUtils.isBlank(subscriptionId)
                || StringUtils.isBlank(clientId)
                || StringUtils.isBlank(oauth2TokenEndpoint)
                || StringUtils.isBlank(clientSecret)) {

            return Messages.Azure_GC_Template_Val_Profile_Missing();
        } else {
            if (!verifyConfiguration(config).equals(Constants.OP_SUCCESS)) {
                return Messages.Azure_GC_Template_Val_Profile_Err();
            }
        }
        return Constants.OP_SUCCESS;
    }

    private static String verifyMaxVirtualMachineLimit(
            final Configuration config, final String maxVirtualMachinesLimit) {

        boolean considerDefaultVMLimit = false;
        int maxVMs = 0;
        if (StringUtils.isBlank(maxVirtualMachinesLimit)) {
            considerDefaultVMLimit = true;
        } else {
            try {
                maxVMs = Integer.parseInt(maxVirtualMachinesLimit);

                if (maxVMs <= 0) {
                    considerDefaultVMLimit = true;
                }
            } catch (Exception e) {
                considerDefaultVMLimit = true;
            }
        }

        ComputeManagementClient client = ServiceDelegateHelper.getComputeManagementClient(config);
        maxVMs = considerDefaultVMLimit ? Constants.DEFAULT_MAX_VM_LIMIT : maxVMs;
        try {
            int currentCount = getVirtualMachineCount(client);

            if (currentCount < maxVMs) {
                return Constants.OP_SUCCESS;
            } else {
                if (considerDefaultVMLimit) {
                    return Messages.Azure_GC_Template_max_VM_Err(currentCount, Constants.DEFAULT_MAX_VM_LIMIT);
                } else {
                    return Messages.Azure_GC_Template_max_VM_Err(currentCount, maxVirtualMachinesLimit);
                }
            }
        } catch (Exception e) {
            return ("Exception occured while validating max virtual machines limit value");
        }
    }

    public static String verifyNoOfExecutors(final String noOfExecutors) {
        try {
            if (StringUtils.isBlank(noOfExecutors)) {
                return Messages.Azure_GC_Template_Executors_Null_Or_Empty();
            } else {
                AzureUtil.isPositiveInteger(noOfExecutors);
                return Constants.OP_SUCCESS;
            }
        } catch (IllegalArgumentException e) {
            return Messages.Azure_GC_Template_Executors_Not_Positive();
        }
    }

    public static String verifyRetentionTime(final String retentionTimeInMin) {
        try {
            if (StringUtils.isBlank(retentionTimeInMin)) {
                return Messages.Azure_GC_Template_RT_Null_Or_Empty();
            } else {
                AzureUtil.isNonNegativeInteger(retentionTimeInMin);
                return Constants.OP_SUCCESS;
            }
        } catch (IllegalArgumentException e) {
            return Messages.Azure_GC_Template_RT_Not_Positive();
        }
    }

    private static String verifyVirtualNetwork(
            final Configuration config,
            final String virtualNetworkName,
            final String subnetName) {
        if (StringUtils.isNotBlank(virtualNetworkName)) {
            VirtualNetwork virtualNetwork = getVirtualNetwork(config, virtualNetworkName);

            if (virtualNetwork == null) {
                return Messages.Azure_GC_Template_VirtualNetwork_NotFound(virtualNetworkName);
            }

            if (StringUtils.isNotBlank(subnetName)) {
                List<Subnet> subnets = virtualNetwork.getSubnets();
                if (subnets != null) {
                    boolean found = false;
                    for (Subnet subnet : subnets) {
                        if (subnet.getName().equalsIgnoreCase(subnetName)) {
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        return Messages.Azure_GC_Template_subnet_NotFound(subnetName);
                    }
                }
            }
        } else if (StringUtils.isNotBlank(subnetName)) {
            return Messages.Azure_GC_Template_VirtualNetwork_Null_Or_Empty();
        }
        return Constants.OP_SUCCESS;
    }

    private static String verifyVirtualMachineImage(
            final Configuration config,
            final String location,
            final String image,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion) {
        try {
            if (StringUtils.isNotBlank(image)) {
                // to be defined
            } else {
                final VirtualMachineImageOperations client
                        = ServiceDelegateHelper.getComputeManagementClient(config).getVirtualMachineImagesOperations();

                final VirtualMachineImageGetParameters params = new VirtualMachineImageGetParameters();
                params.setLocation(location);
                params.setPublisherName(imagePublisher);
                params.setOffer(imageOffer);
                params.setSkus(imageSku);
                params.setVersion(imageVersion);

                client.get(params);
            }

            return Constants.OP_SUCCESS;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Invalid virtual machine image", e);
            return Messages.Azure_GC_Template_ImageFamilyOrID_Not_Valid();
        }
    }

    private static String verifyAdminPassword(final String adminPassword) {
        if (StringUtils.isBlank(adminPassword)) {
            return Messages.Azure_GC_Template_PWD_Null_Or_Empty();
        }

        if (AzureUtil.isValidPassword(adminPassword)) {
            return Constants.OP_SUCCESS;
        } else {
            return Messages.Azure_GC_Template_PWD_Not_Valid();
        }
    }

    private static String verifyJvmOptions(final String jvmOptions) {
        if (StringUtils.isBlank(jvmOptions) || AzureUtil.isValidJvmOption(jvmOptions)) {
            return Constants.OP_SUCCESS;
        } else {
            return Messages.Azure_GC_JVM_Option_Err();
        }
    }

    private static String verifyImage(
            final String image,
            final String osType,
            final String imagePublisher,
            final String imageOffer,
            final String imageSku,
            final String imageVersion) {
        if ((StringUtils.isNotBlank(image) && StringUtils.isNotBlank(osType))
                || (StringUtils.isNotBlank(imagePublisher)
                && StringUtils.isNotBlank(imageOffer)
                && StringUtils.isNotBlank(imageSku)
                && StringUtils.isNotBlank(imageVersion))) {
            return Constants.OP_SUCCESS;
        } else {
            return Messages.Azure_GC_Template_ImageFamilyOrID_Not_Valid();
        }
    }
}
