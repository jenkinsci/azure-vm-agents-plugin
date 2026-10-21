package com.microsoft.azure.vmagent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

class AzureVMManagementServiceDelegateAutoLogonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USERNAME = "azureuser";
    private static final String PASSWORD = "P@ssw0rd123!";

    @Test
    void addAutoLoginWindowsConfigurationGivenCredentialsThenPassesAutoLogonAsSecureParameter() throws Exception {
        // Given
        JsonNode template = windowsVmTemplate();
        ObjectNode parameters = MAPPER.createObjectNode();

        // When
        AzureVMManagementServiceDelegate.addAutoLoginWindowsConfiguration(
                template, parameters, USERNAME, PASSWORD, List.of());

        // Then
        JsonNode entry = findUnattendEntry(template, "AutoLogon");
        assertThat(entry.get("content").asText(), equalTo("[parameters('windowsAutoLogonContent')]"));
        assertThat(template.get("parameters").get("windowsAutoLogonContent").get("type").asText(),
                equalTo("secureString"));

        String content = parameters.get("windowsAutoLogonContent").get("value").asText();
        assertThat(content, not(containsString(PASSWORD)));
        Element autoLogon = parseXml(content).getDocumentElement();
        assertThat(textOf(autoLogon, "Enabled"), equalTo("true"));
        assertThat(textOf(autoLogon, "Username"), equalTo(USERNAME));
        assertThat(textOf(autoLogon, "PlainText"), equalTo("false"));
        // Windows unattend expects the non-plaintext password as base64(UTF-16LE(password + "Password"))
        String decodedPassword = new String(
                Base64.getDecoder().decode(textOf(autoLogon, "Value")), StandardCharsets.UTF_16LE);
        assertThat(decodedPassword, equalTo(PASSWORD + "Password"));
    }

    @Test
    void addAutoLoginWindowsConfigurationGivenCommandsThenAddsThemInOrder() throws Exception {
        // Given
        List<FirstLogonCommand> commands = List.of(
                new FirstLogonCommand("ssh-keygen.exe -A"),
                new FirstLogonCommand("cmd.exe /c echo <a> & <b>"));
        JsonNode template = windowsVmTemplate();

        // When
        AzureVMManagementServiceDelegate.addAutoLoginWindowsConfiguration(
                template, MAPPER.createObjectNode(), USERNAME, PASSWORD, commands);

        // Then
        JsonNode entry = findUnattendEntry(template, "FirstLogonCommands");
        assertThat(entry.get("passName").asText(), equalTo("OobeSystem"));
        assertThat(entry.get("componentName").asText(), equalTo("Microsoft-Windows-Shell-Setup"));

        NodeList synchronousCommands = parseXml(entry.get("content").asText())
                .getElementsByTagName("SynchronousCommand");
        assertThat(synchronousCommands.getLength(), equalTo(2));
        assertCommand(synchronousCommands.item(0), "1", "ssh-keygen.exe -A");
        assertCommand(synchronousCommands.item(1), "2", "cmd.exe /c echo <a> & <b>");
    }

    @Test
    void addAutoLoginWindowsConfigurationGivenBlankCommandsThenOmitsThem() throws Exception {
        // Given
        List<FirstLogonCommand> commands = List.of(
                new FirstLogonCommand("first"),
                new FirstLogonCommand(" "),
                new FirstLogonCommand(null),
                new FirstLogonCommand("second"));
        JsonNode template = windowsVmTemplate();

        // When
        AzureVMManagementServiceDelegate.addAutoLoginWindowsConfiguration(
                template, MAPPER.createObjectNode(), USERNAME, PASSWORD, commands);

        // Then
        NodeList synchronousCommands = parseXml(findUnattendEntry(template, "FirstLogonCommands")
                .get("content").asText()).getElementsByTagName("SynchronousCommand");
        assertThat(synchronousCommands.getLength(), equalTo(2));
        assertCommand(synchronousCommands.item(0), "1", "first");
        assertCommand(synchronousCommands.item(1), "2", "second");
    }

    @Test
    void addAutoLoginWindowsConfigurationGivenOnlyBlankCommandsThenOnlyAddsAutoLogon() {
        // Given
        JsonNode template = windowsVmTemplate();

        // When
        AzureVMManagementServiceDelegate.addAutoLoginWindowsConfiguration(
                template, MAPPER.createObjectNode(), USERNAME, PASSWORD, List.of(new FirstLogonCommand(" ")));

        // Then
        assertThat(additionalUnattendContent(template).size(), equalTo(1));
        assertThat(findUnattendEntry(template, "FirstLogonCommands"), nullValue());
    }

    @Test
    void addAutoLoginWindowsConfigurationGivenNullCommandsThenOnlyAddsAutoLogon() {
        // Given
        JsonNode template = windowsVmTemplate();

        // When
        AzureVMManagementServiceDelegate.addAutoLoginWindowsConfiguration(
                template, MAPPER.createObjectNode(), USERNAME, PASSWORD, null);

        // Then
        assertThat(additionalUnattendContent(template).size(), equalTo(1));
        assertThat(findUnattendEntry(template, "FirstLogonCommands"), nullValue());
    }

    @Test
    void addAutoLoginWindowsConfigurationGivenExistingWindowsConfigurationThenPreservesIt() {
        // Given windowsConfiguration already set by "Disable Windows Updates"
        JsonNode template = windowsVmTemplate();
        osProfile(template).set("windowsConfiguration",
                MAPPER.createObjectNode().put("enableAutomaticUpdates", false));

        // When
        AzureVMManagementServiceDelegate.addAutoLoginWindowsConfiguration(
                template, MAPPER.createObjectNode(), USERNAME, PASSWORD, List.of());

        // Then
        JsonNode windowsConfiguration = osProfile(template).get("windowsConfiguration");
        assertThat(windowsConfiguration.get("enableAutomaticUpdates").asBoolean(), is(false));
        assertThat(additionalUnattendContent(template).size(), equalTo(1));
    }

    @Test
    void addAutoLoginWindowsConfigurationGivenNoOsProfileThenThrows() {
        // Given
        ObjectNode template = windowsVmTemplate();
        ((ObjectNode) template.get("resources").get(0).get("properties")).remove("osProfile");

        // When
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzureVMManagementServiceDelegate.addAutoLoginWindowsConfiguration(
                        template, MAPPER.createObjectNode(), USERNAME, PASSWORD, List.of()));

        // Then
        assertThat(e.getMessage(), containsString("osProfile"));
    }

    @Test
    void addAutoLoginWindowsConfigurationGivenCommandOver1024CharsThenThrows() {
        // Given
        List<FirstLogonCommand> commands = List.of(new FirstLogonCommand("x".repeat(1025)));

        // When
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzureVMManagementServiceDelegate.addAutoLoginWindowsConfiguration(
                        windowsVmTemplate(), MAPPER.createObjectNode(), USERNAME, PASSWORD, commands));

        // Then
        assertThat(e.getMessage(), containsString("1,024"));
    }

    @Test
    void addAutoLoginWindowsConfigurationGivenCommandsOver4KBThenThrows() {
        // Given
        List<FirstLogonCommand> commands = Collections.nCopies(6, new FirstLogonCommand("x".repeat(700)));

        // When
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AzureVMManagementServiceDelegate.addAutoLoginWindowsConfiguration(
                        windowsVmTemplate(), MAPPER.createObjectNode(), USERNAME, PASSWORD, commands));

        // Then
        assertThat(e.getMessage(), containsString("4 KB"));
    }

    private static ObjectNode windowsVmTemplate() {
        ObjectNode vm = MAPPER.createObjectNode();
        vm.put("type", "Microsoft.Compute/virtualMachines");
        vm.set("properties", MAPPER.createObjectNode().set("osProfile", MAPPER.createObjectNode()));
        ObjectNode template = MAPPER.createObjectNode();
        template.set("parameters", MAPPER.createObjectNode());
        template.set("resources", MAPPER.createArrayNode().add(vm));
        return template;
    }

    private static ObjectNode osProfile(JsonNode template) {
        return (ObjectNode) template.get("resources").get(0).get("properties").get("osProfile");
    }

    private static ArrayNode additionalUnattendContent(JsonNode template) {
        return (ArrayNode) osProfile(template).get("windowsConfiguration").get("additionalUnattendContent");
    }

    private static JsonNode findUnattendEntry(JsonNode template, String settingName) {
        for (JsonNode entry : additionalUnattendContent(template)) {
            if (settingName.equals(entry.path("settingName").asText())) {
                return entry;
            }
        }
        return null;
    }

    private static void assertCommand(Node node, String order, String commandLine) {
        Element command = (Element) node;
        assertThat(textOf(command, "Order"), equalTo(order));
        assertThat(textOf(command, "CommandLine"), equalTo(commandLine));
    }

    private static Document parseXml(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String textOf(Element parent, String tagName) {
        return parent.getElementsByTagName(tagName).item(0).getTextContent();
    }
}
