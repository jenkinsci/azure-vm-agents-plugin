package com.microsoft.azure.vmagent.availability;

import hudson.ExtensionPoint;
import hudson.model.Describable;

import java.io.Serializable;

public abstract class AzureAvailabilityType
        implements ExtensionPoint, Serializable, Describable<AzureAvailabilityType> {

}
