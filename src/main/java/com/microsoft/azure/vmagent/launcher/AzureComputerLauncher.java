package com.microsoft.azure.vmagent.launcher;

import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import java.io.Serializable;

public abstract class AzureComputerLauncher extends AbstractDescribableImpl<AzureComputerLauncher>
        implements ExtensionPoint, Serializable {
}
