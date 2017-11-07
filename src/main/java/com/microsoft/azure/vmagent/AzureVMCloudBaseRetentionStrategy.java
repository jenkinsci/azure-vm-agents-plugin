package com.microsoft.azure.vmagent;

import hudson.slaves.RetentionStrategy;

import java.io.Serializable;

public abstract class AzureVMCloudBaseRetentionStrategy extends RetentionStrategy<AzureVMComputer>
        implements Serializable {

}
