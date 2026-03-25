package com.microsoft.azure.vmagent.builders;

public class BuiltInImage {

    private String builtInImage;

    private boolean isInstallGit;

    private boolean isInstallMaven;

    private boolean isInstallDocker;

    private boolean isInstallQemu;

    public BuiltInImage(String builtInImage,
                        boolean isInstallGit,
                        boolean isInstallMaven,
                        boolean isInstallDocker,
                        boolean isInstallQemu) {
        this.builtInImage = builtInImage;
        this.isInstallGit = isInstallGit;
        this.isInstallMaven = isInstallMaven;
        this.isInstallDocker = isInstallDocker;
        this.isInstallQemu = isInstallQemu;
    }

    public String getBuiltInImage() {
        return builtInImage;
    }

    public boolean isInstallGit() {
        return isInstallGit;
    }

    public boolean isInstallMaven() {
        return isInstallMaven;
    }

    public boolean isInstallDocker() {
        return isInstallDocker;
    }

    public boolean isInstallQemu() {
        return isInstallQemu;
    }
}
