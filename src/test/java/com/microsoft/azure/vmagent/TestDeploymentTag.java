/*
 * Copyright 2017.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.azure.vmagent;

import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.Constants;
import org.junit.Assert;
import org.jvnet.hudson.test.JenkinsRule;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;


import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Map;


public class TestDeploymentTag extends AzureUtil.DeploymentTag{
    @ClassRule public static JenkinsRule j = new JenkinsRule();

    private String jenkinsId = "";
    private String jenkinsUrl = "";

    @Before
    public void setUp() {
        jenkinsId = Jenkins.get().getLegacyInstanceId();
        JenkinsLocationConfiguration jenkinsLocation = JenkinsLocationConfiguration.get();
        jenkinsUrl = jenkinsLocation.getUrl();
    }

    @Test
    public void constructAndGet() {
        final long ts = 1234;
        Assert.assertEquals(jenkinsId + "|" + ts, tag(ts).get());
    }

    @Test
    public void constructFromString() {
        final long ts = 1234;
        final String tagStr = jenkinsUrl + "|" + ts;
        final AzureUtil.DeploymentTag tag = new AzureUtil.DeploymentTag(tagStr);
        Assert.assertEquals(tagStr, tag.get());
    }

    @Test()
    public void constructFromValidString() {
        Assert.assertEquals(jenkinsUrl + "|123456", (new AzureUtil.DeploymentTag(jenkinsUrl + "|123456")).get());
        Assert.assertEquals(jenkinsId + "|123456", (new AzureUtil.DeploymentTag(jenkinsId + "/123456")).get());
    }

    @Test()
    public void constructFromInvalidString() {
        Assert.assertEquals(jenkinsId + "|0", (new AzureUtil.DeploymentTag(jenkinsId + "|")).get());
        Assert.assertEquals(jenkinsId + "|0", (new AzureUtil.DeploymentTag(jenkinsId + "|-1")).get());
        Assert.assertEquals(jenkinsId + "|0", (new AzureUtil.DeploymentTag(jenkinsId + "|abc")).get());
        Assert.assertEquals(jenkinsId + "|0", (new AzureUtil.DeploymentTag(jenkinsId + "|123abc")).get());
        Assert.assertEquals(jenkinsId + "|0", (new AzureUtil.DeploymentTag(jenkinsId + "|-123abc")).get());
        Assert.assertEquals(jenkinsId + "|0", (new AzureUtil.DeploymentTag(jenkinsId + "|abc123")).get());
        Assert.assertEquals(jenkinsId + "|0", (new AzureUtil.DeploymentTag(jenkinsId + "||123")).get());
        Assert.assertEquals(jenkinsId + "|0", (new AzureUtil.DeploymentTag(jenkinsId + "||")).get());
        Assert.assertEquals(jenkinsId + "|123", (new AzureUtil.DeploymentTag(jenkinsId + "|123|456")).get());
        Assert.assertEquals("|1", (new AzureUtil.DeploymentTag("|1")).get());
        Assert.assertEquals("|0", (new AzureUtil.DeploymentTag("|-1")).get());
        Assert.assertEquals("|0", (new AzureUtil.DeploymentTag("|abc")).get());
        Assert.assertEquals(jenkinsId + "|0", (new AzureUtil.DeploymentTag(jenkinsId)).get());
        Assert.assertEquals("|0", (new AzureUtil.DeploymentTag("")).get());
        Assert.assertEquals("|0", (new AzureUtil.DeploymentTag(null)).get());
    }

    @Test
    public void isFromSameInstanceTests() {
        // deployTag = local resources tag
        // tags = all tags on the AVM
        // resourcesTag = value of the resource tag on the VM
        //AzureUtil.DeploymentTag deployTag = new AzureUtil.DeploymentTag().get();
        AzureUtil.DeploymentTag deployTag = new AzureUtil.DeploymentTag();
        Map<String, String> tags = Map.of("JenkinsResourceTag", "https://localhost.localdomain:123456/|123");
        String resourcesTag = tags.getOrDefault(Constants.AZURE_RESOURCES_TAG_NAME, null);

        Assert.assertEquals(resourcesTag, "https://localhost.localdomain:123456/|123");
        Assert.assertEquals(jenkinsUrl + "|123456", deployTag);
        Assert.assertTrue(deployTag.isFromSameInsance(new AzureUtil.DeploymentTag(resourcesTag)));
    }

   //       final String resourcesTag = tags.getOrDefault(Constants.AZURE_RESOURCES_TAG_NAME, null);
    //                final String cloudTag = tags.getOrDefault(Constants.AZURE_CLOUD_TAG_NAME, null);
    //                if ( !deployTag.isFromSameInstance(new AzureUtil.DeploymentTag(resourcesTag))) {


    @Test
    public void match() {
        Assert.assertTrue(tag(0).matches(tag(1), 0));
        Assert.assertTrue(tag(1).matches(tag(0), 0));
        Assert.assertTrue(tag(15).matches(tag(100), 20));
        Assert.assertTrue(tag(100).matches(tag(15), 20));
        Assert.assertTrue(tag(15).matches( new AzureUtil.DeploymentTag(jenkinsId + "|100"), 20));
        Assert.assertFalse(tag(0).matches(tag(1), 1));
        Assert.assertFalse(tag(1).matches(tag(0), 1));
        Assert.assertFalse(tag(100).matches(tag(450), 999));
        Assert.assertFalse(tag(450).matches(tag(100), 999));
        Assert.assertFalse(tag(15).matches( new AzureUtil.DeploymentTag("wrong_id|100"), 20));
        
    }
    
    private AzureUtil.DeploymentTag tag(long timestamp) {
        return new AzureUtil.DeploymentTag(timestamp){};
    }
}
