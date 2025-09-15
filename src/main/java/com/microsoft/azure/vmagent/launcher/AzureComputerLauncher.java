package com.microsoft.azure.vmagent.launcher;

import hudson.ExtensionPoint;
import hudson.model.Describable;

import java.io.Serializable;

public abstract class AzureComputerLauncher
        implements ExtensionPoint, Serializable, Describable<AzureComputerLauncher> {
}
