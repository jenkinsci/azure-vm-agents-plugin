package com.microsoft.azure.vmagent.util;

import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import org.apache.commons.lang.StringUtils;

import java.util.Objects;

public final class TemplateUtil {

    public static boolean checkSame(AzureVMAgentTemplate a, AzureVMAgentTemplate b) {
        if (StringUtils.equals(a.getTemplateName(), b.getTemplateName())
                && StringUtils.equals(a.getLabels(), b.getLabels())
                && StringUtils.equals(a.getAgentWorkspace(), b.getAgentWorkspace())
                && StringUtils.equals(a.getLocation(), b.getLocation())
                && Objects.equals(a.getAvailabilityType(),
                b.getAvailabilityType())
                && StringUtils.equals(a.getVirtualMachineSize(), b.getVirtualMachineSize())
                && StringUtils.equals(a.getStorageAccountType(), b.getStorageAccountType())
                && StringUtils.equals(a.getStorageAccountNameReferenceType(), b.getStorageAccountNameReferenceType())
                && StringUtils.equals(a.getNewStorageAccountName(), b.getNewStorageAccountName())
                && StringUtils.equals(a.getExistingStorageAccountName(), b.getExistingStorageAccountName())
                && StringUtils.equals(a.getDiskType(), b.getDiskType())
                && a.getOsDiskSize() == b.getOsDiskSize()
                && StringUtils.equals(a.getCredentialsId(), b.getCredentialsId())
                && StringUtils.equals(a.getImageTopLevelType(), b.getImageTopLevelType())
                && StringUtils.equals(a.getBuiltInImage(), b.getBuiltInImage())
                && a.isInstallDocker() == b.isInstallDocker()
                && a.isInstallGit() == b.isInstallGit()
                && a.isInstallMaven() == b.isInstallMaven()
                && a.getImageReference().getType() == b.getImageReference().getType()
                && StringUtils.equals(a.getImageReference().getUri(), b.getImageReference().getUri())
                && StringUtils.equals(a.getOsType(), b.getOsType())
                && StringUtils.equals(a.getImageReference().getId(), b.getImageReference().getId())
                && StringUtils.equals(a.getImageReference().getPublisher(),
                b.getImageReference().getPublisher())
                && StringUtils.equals(a.getImageReference().getOffer(),
                b.getImageReference().getOffer())
                && StringUtils.equals(a.getImageReference().getSku(), b.getImageReference().getSku())
                && StringUtils.equals(a.getImageReference().getVersion(),
                b.getImageReference().getVersion())
                && StringUtils.equals(a.getAgentLaunchMethod(), b.getAgentLaunchMethod())
                && a.isPreInstallSsh() == b.isPreInstallSsh()
                && StringUtils.equals(a.getInitScript(), b.getInitScript())
                && StringUtils.equals(a.getTerminateScript(), b.getTerminateScript())
                && a.getExecuteInitScriptAsRoot() == b.getExecuteInitScriptAsRoot()
                && a.isEnableMSI() == b.isEnableMSI()
                && a.isEnableUAMI() == b.isEnableUAMI()
                && StringUtils.equals(a.getUamiID(), b.getUamiID())
                && StringUtils.equals(a.getVirtualNetworkName(), b.getVirtualNetworkName())
                && StringUtils.equals(a.getVirtualNetworkResourceGroupName(), b.getVirtualNetworkResourceGroupName())
                && StringUtils.equals(a.getSubnetName(), b.getSubnetName())
                && a.getUsePrivateIP() == b.getUsePrivateIP()
                && StringUtils.equals(a.getNsgName(), b.getNsgName())
                && StringUtils.equals(a.getJvmOptions(), b.getJvmOptions())
                && a.getNoOfParallelJobs() == b.getNoOfParallelJobs()
                && a.isTemplateDisabled() == b.isTemplateDisabled()) {
            return true;
        }
        return false;
    }

    private TemplateUtil() {

    }
}
