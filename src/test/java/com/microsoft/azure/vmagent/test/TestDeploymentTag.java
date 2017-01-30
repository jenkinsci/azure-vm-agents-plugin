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

package com.microsoft.azure.vmagent.test;

import com.microsoft.azure.vmagent.util.AzureUtil;
import org.jvnet.hudson.test.JenkinsRule;
import jenkins.model.Jenkins;
import junit.framework.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;


public class TestDeploymentTag extends AzureUtil.DeploymentTag{
    @ClassRule public static JenkinsRule j = new JenkinsRule();

    private String jenkinsId = "";

    @Before
    public void setUp() {
        jenkinsId = Jenkins.getInstance().getLegacyInstanceId();
    }

    @Test
    public void constructAndGet() {
        final long ts = 1234;
        Assert.assertEquals(jenkinsId + "/" + Long.toString(ts), tag(ts).get());
    }

    @Test
    public void constructFromString() {
        final long ts = 1234;
        final String tagStr = jenkinsId + "/" + Long.toString(ts);
        final AzureUtil.DeploymentTag tag = new AzureUtil.DeploymentTag(tagStr);
        Assert.assertEquals(tagStr, tag.get());
    }

    @Test()
    public void constructFromInvalidString() {
        Assert.assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "/")).get());
        Assert.assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "/-1")).get());
        Assert.assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "/abc")).get());
        Assert.assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "/123abc")).get());
        Assert.assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "/-123abc")).get());
        Assert.assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "/abc123")).get());
        Assert.assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "//123")).get());
        Assert.assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "//")).get());
        Assert.assertEquals(jenkinsId + "/123", (new AzureUtil.DeploymentTag(jenkinsId + "/123/456")).get());
        Assert.assertEquals("/1", (new AzureUtil.DeploymentTag("/1")).get());
        Assert.assertEquals("/0", (new AzureUtil.DeploymentTag("/-1")).get());
        Assert.assertEquals("/0", (new AzureUtil.DeploymentTag("/abc")).get());
        Assert.assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId)).get());
        Assert.assertEquals("/0", (new AzureUtil.DeploymentTag("")).get());
        Assert.assertEquals("/0", (new AzureUtil.DeploymentTag(null)).get());
    }

    @Test
    public void match() {
        Assert.assertTrue(tag(0).matches(tag(1), 0));
        Assert.assertTrue(tag(1).matches(tag(0), 0));
        Assert.assertTrue(tag(15).matches(tag(100), 20));
        Assert.assertTrue(tag(100).matches(tag(15), 20));
        Assert.assertTrue(tag(15).matches( new AzureUtil.DeploymentTag(jenkinsId + "/100"), 20));
        Assert.assertFalse(tag(0).matches(tag(1), 1));
        Assert.assertFalse(tag(1).matches(tag(0), 1));
        Assert.assertFalse(tag(100).matches(tag(450), 999));
        Assert.assertFalse(tag(450).matches(tag(100), 999));
        Assert.assertFalse(tag(15).matches( new AzureUtil.DeploymentTag("wrong_id/100"), 20));
        
    }
    
    private AzureUtil.DeploymentTag tag(long timestamp) {
        return new AzureUtil.DeploymentTag(timestamp){};
    }
}
