package com.microsoft.azure.vmagent.test.jcasc;

import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import com.microsoft.azure.vmagent.AzureVMCloud;
import com.microsoft.azure.vmagent.AzureVMCloudRetensionStrategy;
import hudson.model.Node;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.model.CNode;
import org.junit.ClassRule;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static io.jenkins.plugins.casc.misc.Util.toYamlString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class BasicConfigAsCodeTest {

    @ClassRule
    @ConfiguredWithCode("basic.yaml")
    public static JenkinsConfiguredWithCodeRule r = new JenkinsConfiguredWithCodeRule();

    @Test
    public void importBasicConfiguration() {
        AzureVMCloud cloud = (AzureVMCloud) r.jenkins.clouds.get(0);

        // cloud
        assertThat(cloud.getCloudName(), is("azure"));
        assertThat(cloud.getAzureCredentialsId(), is("azure-cred"));
        assertThat(cloud.getDeploymentTimeout(), is(1200));
        assertThat(cloud.getMaxVirtualMachinesLimit(), is(10));

        assertThat(cloud.getNewResourceGroupName(), is("vm-agent"));
        assertThat(cloud.getResourceGroupReferenceType(), is("new"));

        // vmTemplate
        AzureVMAgentTemplate template = cloud.getVmTemplates().get(0);

        assertThat(template.getAgentLaunchMethod(), is("SSH"));
        assertThat(template.getBuiltInImage(), is("Ubuntu 16.14 LTS"));
        assertThat(template.getCredentialsId(), is("admin-cred"));
        assertThat(template.getDiskType(), is("managed"));
        assertThat(template.getDoNotUseMachineIfInitFails(), is(true));
        assertThat(template.isEnableMSI(), is(false));
        assertThat(template.isEnableUAMI(), is(false));
        assertThat(template.isEphemeralOSDisk(), is(false));
        assertThat(template.getExecuteInitScriptAsRoot(), is(true));

        AzureVMAgentTemplate.ImageReferenceTypeClass imageReference = template.getImageReference();
        assertThat(imageReference.getVersion(), is("latest"));

        assertThat(template.getImageTopLevelType(), is("basic"));

        assertThat(template.isInstallDocker(), is(true));
        assertThat(template.isInstallGit(), is(true));
        assertThat(template.isInstallMaven(), is(true));

        assertThat(template.getLabels(), is("ubuntu"));
        assertThat(template.getLocation(), is("East US"));
        assertThat(template.getNewStorageAccountName(), is("agent-storage"));

        assertThat(template.getNoOfParallelJobs(), is(1));
        assertThat(template.getOsDiskSize(), is(0));
        assertThat(template.getOsType(), is("Linux"));

        assertThat(template.isPreInstallSsh(), is(true));

        AzureVMCloudRetensionStrategy retentionStrategy = (AzureVMCloudRetensionStrategy) template.getRetentionStrategy();
        assertThat(retentionStrategy.getIdleTerminationMinutes(), is(60L));

        assertThat(template.isShutdownOnIdle(), is(false));

        assertThat(template.getStorageAccountNameReferenceType(), is("new"));
        assertThat(template.getStorageAccountType(), is("Standard_LRS"));

        assertThat(template.isTemplateDisabled(), is(false));
        assertThat(template.getTemplateName(), is("ubuntu"));

        assertThat(template.getUsageMode(), is(Node.Mode.NORMAL));
        assertThat(template.getUsePrivateIP(), is(false));

        assertThat(template.getVirtualMachineSize(), is("Standard_DS2_v2"));
    }

    @Test
    public void exportBasicConfiguration() throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        final CNode cloud = getJenkinsRoot(context).get("clouds");

        String exportedCloud = toYamlString(cloud);

        String expectedYaml = new String(Files.readAllBytes(Paths.get(getClass()
                .getResource("expectedBasic.yaml").toURI())));

        assertThat(exportedCloud, is(expectedYaml));
    }
}
