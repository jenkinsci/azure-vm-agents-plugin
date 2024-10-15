package com.microsoft.azure.vmagent.builders;

import com.microsoft.azure.vmagent.util.Constants;

public class BuiltInImageFluent<T extends BuiltInImageFluent<T>> {
    private String builtInImage;

    private boolean isInstallGit;

    private boolean isInstallMaven;

    private boolean isInstallDocker;

    private boolean isInstallQemu;

    public BuiltInImageFluent() {
        builtInImage = Constants.WINDOWS_SERVER_2016;
        isInstallDocker = false;
        isInstallMaven = false;
        isInstallGit = false;
        isInstallQemu = false;
    }

    //CHECKSTYLE:OFF
    public T withBuiltInImageName(String builtInImage) {
        this.builtInImage = builtInImage;
        return (T) this;
    }

    public T withInstallGit(boolean installGit) {
        this.isInstallGit = installGit;
        return (T) this;
    }

    public T withInstallMaven(boolean installMaven) {
        this.isInstallMaven = installMaven;
        return (T) this;
    }

    public T withInstallDocker(boolean installDocker) {
        this.isInstallDocker = installDocker;
        return (T) this;
    }

    public T withInstallQemu(boolean installQemu) {
        this.isInstallQemu = installQemu;
        return (T) this;
    }
    //CHECKSTYLE:ON

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
