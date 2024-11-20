package com.microsoft.azure.vmagent.test.datamigration;

import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import com.microsoft.azure.vmagent.AzureVMCloud;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.core.Is.is;

public class AzureVMAgentTemplateReadResolveTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    @LocalData
    public void readResolve() {
        Jenkins.CloudList clouds = j.jenkins.clouds;

        assertThat(clouds, hasSize(1));

        AzureVMCloud cloud = (AzureVMCloud) clouds.get(0);

        assertThat(cloud.getCloudName(), is("myAzure"));

        assertThat(cloud.getVmTemplates(), hasSize(1));

        AzureVMAgentTemplate template = cloud.getVmTemplates().get(0);
        assertThat(template.getAdvancedImageInside().getSshConfig(), is("RemoteForward 1234 target.machine:1234"));
    }
}
