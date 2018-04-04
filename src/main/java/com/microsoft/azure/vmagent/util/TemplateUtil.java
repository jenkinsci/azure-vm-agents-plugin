package com.microsoft.azure.vmagent.util;

import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import org.apache.commons.lang3.StringUtils;

public final class TemplateUtil {

    public static boolean checkSame(AzureVMAgentTemplate a, AzureVMAgentTemplate b) {
        if (StringUtils.equals(a.getTemplateName(), b.getTemplateName())
                && StringUtils.equals(a.getLabels(), b.getLabels())
                && StringUtils.equals(a.getAgentWorkspace(), b.getAgentWorkspace())
                && StringUtils.equals(a.getLocation(), b.getLocation())
                && StringUtils.equals(a.getVirtualMachineSize(), b.getVirtualMachineSize())
                && StringUtils.equals(a.getStorageAccountType(), b.getStorageAccountType())
                && StringUtils.equals(a.getStorageAccountNameReferenceType(), b.getStorageAccountNameReferenceType())
                && StringUtils.equals(a.getNewStorageAccountName(), b.getNewStorageAccountName())
                && StringUtils.equals(a.getExistingStorageAccountName(), b.getExistingStorageAccountName())
                && StringUtils.equals(a.getDiskType(), b.getDiskType())
                && StringUtils.equals(a.getCredentialsId(), b.getCredentialsId())
                && StringUtils.equals(a.getImageTopLevelType(), b.getImageTopLevelType())
                && StringUtils.equals(a.getBuiltInImage(), b.getBuiltInImage())
                && a.getIsInstallDocker() == b.getIsInstallDocker()
                && a.getIsInstallGit() == b.getIsInstallGit()
                && a.getIsInstallMaven() == b.getIsInstallMaven()
                && StringUtils.equals(a.getImageReferenceType(), b.getImageReferenceType())
                && StringUtils.equals(a.getImage(), b.getImage())
                && StringUtils.equals(a.getOsType(), b.getOsType())
                && StringUtils.equals(a.getImageId(), b.getImageId())
                && StringUtils.equals(a.getImagePublisher(), b.getImagePublisher())
                && StringUtils.equals(a.getImageOffer(), b.getImageOffer())
                && StringUtils.equals(a.getImageSku(), b.getImageSku())
                && StringUtils.equals(a.getImageVersion(), b.getImageVersion())
                && StringUtils.equals(a.getAgentLaunchMethod(), b.getAgentLaunchMethod())
                && a.getPreInstallSsh() == b.getPreInstallSsh()
                && StringUtils.equals(a.getInitScript(), b.getInitScript())
                && a.getExecuteInitScriptAsRoot() == b.getExecuteInitScriptAsRoot()
                && a.getDoNotUseMachineIfInitFails() == b.getDoNotUseMachineIfInitFails()
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
