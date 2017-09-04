package com.microsoft.azure.vmagent.builders;

/**
 * Created by chenyl on 9/4/2017.
 */
public abstract class AdvancedImageParam {
    protected String imageReferenceType;

    protected String image;

    protected String osType;

    protected String imagePublisher;

    protected String imageOffer;

    protected String imageSku;

    protected String imageVersion;

    protected String agentLaunchMethod;

    protected boolean preInstallSsh;

    protected String initScript;

    protected boolean executeInitScriptAsRoot;

    protected boolean doNotUseMachineIfInitFails;

    protected String virtualNetworkName;

    protected String virtualNetworkResourceGroupName;

    protected String subnetName;

    protected boolean usePrivateIP;

    protected String nsgName;

    protected String jvmOptions;

    protected int noOfParallelJobs;

    protected boolean templateDisabled;
}
