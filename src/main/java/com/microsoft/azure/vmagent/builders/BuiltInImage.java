package com.microsoft.azure.vmagent.builders;

public class BuiltInImage {
    private String builtInImage;

    private boolean isInstallGit;

    private boolean isInstallMaven;

    private boolean isInstallDocker;

    public BuiltInImage(String builtInImage,
                        boolean isInstallGit,
                        boolean isInstallMaven,
                        boolean isInstallDocker) {
        this.builtInImage = builtInImage;
        this.isInstallGit = isInstallGit;
        this.isInstallMaven = isInstallMaven;
        this.isInstallDocker = isInstallDocker;
    }
}
