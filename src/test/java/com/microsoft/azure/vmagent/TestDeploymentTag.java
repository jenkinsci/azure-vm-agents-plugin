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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import jenkins.model.Jenkins;

import static org.junit.jupiter.api.Assertions.*;

import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class TestDeploymentTag {
    private static JenkinsRule j;

    private String jenkinsId = "";

    @BeforeAll
    static void setup(JenkinsRule rule) {
        j = rule;
    }

    @BeforeEach
    void setUp() {
        jenkinsId = Jenkins.get().getLegacyInstanceId();
    }

    @Test
    void constructAndGet() {
        final long ts = 1234;
        assertEquals(jenkinsId + "/" + ts, tag(ts).get());
    }

    @Test
    void constructFromString() {
        final long ts = 1234;
        final String tagStr = jenkinsId + "/" + ts;
        final AzureUtil.DeploymentTag tag = new AzureUtil.DeploymentTag(tagStr);
        assertEquals(tagStr, tag.get());
    }

    @Test
    void constructFromInvalidString() {
        assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "/")).get());
        assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "/-1")).get());
        assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "/abc")).get());
        assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "/123abc")).get());
        assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "/-123abc")).get());
        assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "/abc123")).get());
        assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "//123")).get());
        assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId + "//")).get());
        assertEquals(jenkinsId + "/123", (new AzureUtil.DeploymentTag(jenkinsId + "/123/456")).get());
        assertEquals("/1", (new AzureUtil.DeploymentTag("/1")).get());
        assertEquals("/0", (new AzureUtil.DeploymentTag("/-1")).get());
        assertEquals("/0", (new AzureUtil.DeploymentTag("/abc")).get());
        assertEquals(jenkinsId + "/0", (new AzureUtil.DeploymentTag(jenkinsId)).get());
        assertEquals("/0", (new AzureUtil.DeploymentTag("")).get());
        assertEquals("/0", (new AzureUtil.DeploymentTag(null)).get());
    }

    @Test
    void match() {
        assertTrue(tag(0).matches(tag(1), 0));
        assertTrue(tag(1).matches(tag(0), 0));
        assertTrue(tag(15).matches(tag(100), 20));
        assertTrue(tag(100).matches(tag(15), 20));
        assertTrue(tag(15).matches( new AzureUtil.DeploymentTag(jenkinsId + "/100"), 20));
        assertFalse(tag(0).matches(tag(1), 1));
        assertFalse(tag(1).matches(tag(0), 1));
        assertFalse(tag(100).matches(tag(450), 999));
        assertFalse(tag(450).matches(tag(100), 999));
        assertFalse(tag(15).matches( new AzureUtil.DeploymentTag("wrong_id/100"), 20));

    }

    private AzureUtil.DeploymentTag tag(long timestamp) {
        return new AzureUtil.DeploymentTag(timestamp){};
    }
}
