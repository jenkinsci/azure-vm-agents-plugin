package com.microsoft.azure.vmagent.test.jcasc;

import com.microsoft.azure.vmagent.AzureTagPair;
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
import static org.hamcrest.core.IsNull.nullValue;

public class AdvancedConfigAsCodeTest {

    @ClassRule
    @ConfiguredWithCode("advanced.yaml")
    public static JenkinsConfiguredWithCodeRule r = new JenkinsConfiguredWithCodeRule();

    @Test
    public void importAdvancedConfiguration() {
        AzureVMCloud cloud = (AzureVMCloud) r.jenkins.clouds.get(0);

        // cloud
        assertThat(cloud.getCloudName(), is("azure"));
        assertThat(cloud.getCloudTags().get(0), is(new AzureTagPair("author", "gavin")));
        assertThat(cloud.getAzureCredentialsId(), is("azure-cred"));
        assertThat(cloud.getDeploymentTimeout(), is(1200));
        assertThat(cloud.getMaxVirtualMachinesLimit(), is(10));

        assertThat(cloud.getNewResourceGroupName(), nullValue());
        assertThat(cloud.getExistingResourceGroupName(), is("vm-agents"));
        assertThat(cloud.getResourceGroupReferenceType(), is("existing"));

        // vmTemplate
        AzureVMAgentTemplate template = cloud.getVmTemplates().get(0);

        assertThat(template.getAgentLaunchMethod(), is("SSH"));
        assertThat(template.getBuiltInImage(), is("Windows Server 2016"));
        assertThat(template.getCredentialsId(), is("admin-cred"));
        assertThat(template.getDiskType(), is("managed"));
        assertThat(template.getDoNotUseMachineIfInitFails(), is(true));
        assertThat(template.isEnableMSI(), is(false));
        assertThat(template.isEnableUAMI(), is(false));
        assertThat(template.getExecuteInitScriptAsRoot(), is(true));

        AzureVMAgentTemplate.ImageReferenceTypeClass imageReference = template.getImageReference();
        assertThat(imageReference.getVersion(), nullValue());
        assertThat(imageReference.getGalleryImageVersion(), is("latest"));
        assertThat(imageReference.getGalleryImageDefinition(), is("Linux"));
        assertThat(imageReference.getGalleryName(), is("gallery"));
        assertThat(imageReference.getGalleryResourceGroup(), is("gallery"));
        assertThat(imageReference.getGallerySubscriptionId(), is("e5587777-5750-4d2e-9e45-d6fbae67b8ea"));

        assertThat(template.getImageTopLevelType(), is("advanced"));

        assertThat(template.isInstallDocker(), is(false));
        assertThat(template.isInstallGit(), is(false));
        assertThat(template.isInstallMaven(), is(false));

        assertThat(template.getLabels(), is("linux"));
        assertThat(template.getLocation(), is("UK South"));
        assertThat(template.getNewStorageAccountName(), is("agent-storage"));

        assertThat(template.getNoOfParallelJobs(), is(1));
        assertThat(template.getOsDiskSize(), is(40));
        assertThat(template.getOsType(), is("Linux"));

        assertThat(template.isPreInstallSsh(), is(false));

        AzureVMCloudRetensionStrategy retentionStrategy = (AzureVMCloudRetensionStrategy) template.getRetentionStrategy();
        assertThat(retentionStrategy.getIdleTerminationMinutes(), is(40L));

        assertThat(template.isShutdownOnIdle(), is(false));

        assertThat(template.getStorageAccountNameReferenceType(), is("new"));
        assertThat(template.getStorageAccountType(), is("Standard_LRS"));

        assertThat(template.isTemplateDisabled(), is(false));
        assertThat(template.getTemplateName(), is("azure"));

        assertThat(template.getUsageMode(), is(Node.Mode.NORMAL));
        assertThat(template.getUsePrivateIP(), is(true));

        assertThat(template.getVirtualMachineSize(), is("Standard_A2"));
    }

    @Test
    public void exportExportConfiguration() throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        final CNode cloud = getJenkinsRoot(context).get("clouds");

        String exportedCloud = toYamlString(cloud);

        String expectedYaml = new String(Files.readAllBytes(Paths.get(getClass()
                .getResource("expectedAdvanced.yaml").toURI())));

        assertThat(exportedCloud, is(expectedYaml));
    }
}
