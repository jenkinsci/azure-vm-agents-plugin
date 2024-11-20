package com.microsoft.azure.vmagent.availability;

import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import java.io.Serializable;

public abstract class AzureAvailabilityType extends AbstractDescribableImpl<AzureAvailabilityType>
        implements ExtensionPoint, Serializable {

}
