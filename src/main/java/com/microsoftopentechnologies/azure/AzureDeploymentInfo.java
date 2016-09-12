/*
 * Copyright 2016 mmitche.
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
package com.microsoftopentechnologies.azure;

/**
 * Simple class with info from a new Azure deployment
 * @author mmitche
 */
public class AzureDeploymentInfo {
    private String deploymentName;
    private String vmBaseName;
    private int vmCount;

    public AzureDeploymentInfo(String deploymentName, String vmBaseName, int vmCount) {
        this.deploymentName = deploymentName;
        this.vmBaseName = vmBaseName;
        this.vmCount = vmCount;
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public String getVmBaseName() {
        return vmBaseName;
    }

    public int getVmCount() {
        return vmCount;
    }
    
    
}
