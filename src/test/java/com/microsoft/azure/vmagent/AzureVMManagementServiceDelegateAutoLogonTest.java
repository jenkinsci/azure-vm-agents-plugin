package com.microsoft.azure.vmagent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class AzureVMManagementServiceDelegateAutoLogonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "P@ssw0rd123!";
    private static final String SECRET = "agentsecret0";

    @Test
    void addAutoLogonConfigurationGivenCredentialsThenKeepsSecretsOutOfTemplate() {
        // Given
        ObjectNode template = windowsVmTemplate();
        ObjectNode parameters = MAPPER.createObjectNode();

        // When
        AzureVMManagementServiceDelegate.addAutoLogonConfiguration(template, parameters,
                "azureuser", PASSWORD, "Write-Output hello", "https://jenkins.example.com/", List.of(SECRET));

        // Then
        assertThat(template.toString(), not(containsString(PASSWORD)));
        assertThat(template.toString(), not(containsString(SECRET)));
        assertThat(parameters.toString(), not(containsString(PASSWORD)));
        assertThat(parameters.toString(), containsString(SECRET));
    }

    @Test
    void addAutoLogonConfigurationGivenDisabledWindowsUpdatesThenKeepsBoth() {
        // Given
        ObjectNode template = windowsVmTemplate();
        ObjectNode windowsConfiguration = MAPPER.createObjectNode().put("enableAutomaticUpdates", false);
        osProfile(template).set("windowsConfiguration", windowsConfiguration);

        // When
        AzureVMManagementServiceDelegate.addAutoLogonConfiguration(
                template, MAPPER.createObjectNode(), "azureuser", PASSWORD, null, null, List.of());

        // Then
        assertThat(windowsConfiguration.get("enableAutomaticUpdates").asBoolean(), equalTo(false));
        assertThat(windowsConfiguration.get("additionalUnattendContent").get(0).get("settingName").asText(),
                equalTo("AutoLogon"));
    }

    private static ObjectNode windowsVmTemplate() {
        ObjectNode template = MAPPER.createObjectNode();
        template.putObject("parameters");
        template.putObject("variables");
        ObjectNode vm = template.putArray("resources").addObject();
        vm.put("type", "Microsoft.Compute/virtualMachines");
        vm.putObject("properties").putObject("osProfile");
        return template;
    }

    private static ObjectNode osProfile(JsonNode template) {
        return (ObjectNode) template.get("resources").get(0).get("properties").get("osProfile");
    }
}
