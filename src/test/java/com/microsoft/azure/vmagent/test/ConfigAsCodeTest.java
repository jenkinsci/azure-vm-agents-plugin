package com.microsoft.azure.vmagent.test;

import com.microsoft.azure.vmagent.AzureVMCloud;
import com.microsoft.azure.vmagent.builders.AzureVMCloudBuilder;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

public class ConfigAsCodeTest {

    @Rule public JenkinsRule r = new JenkinsRule();

    @Test
    public void export_configuration() throws Exception {
        AzureVMCloud myCloud = new AzureVMCloudBuilder()
                .withCloudName("myAzure")
                .withAzureCredentialsId("<your azure credential ID>")
                .withNewResourceGroupName("<your Resource Group Name>")
                .addNewTemplate()
                .withName("ubuntu")
                .withLabels("ubuntu")
                .withLocation("East US")
                .withVirtualMachineSize("Standard_DS2_v2")
                .withNewStorageAccount("<your Storage Account Name>")
                .addNewBuiltInImage()
                .withBuiltInImageName("Ubuntu 16.14 LTS")
                .withInstallGit(true)
                .withInstallMaven(true)
                .withInstallDocker(true)
                .endBuiltInImage()
                .withAdminCredential("<your admin credential ID>")
                .endTemplate()
                .build();

        Jenkins.get().clouds.add(myCloud);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ConfigurationAsCode.get().export(outputStream);

        String output = outputStream.toString(StandardCharsets.UTF_8.name());

        System.out.println(output);
    }
}