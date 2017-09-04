package com.microsoft.azure.vmagent.builders;

import com.microsoft.azure.vmagent.util.Constants;

public class BuiltInImageBuilder {

    private String builtInImage;

    private boolean isInstallGit;

    private boolean isInstallMaven;

    private boolean isInstallDocker;

    public BuiltInImageBuilder() {
        builtInImage = Constants.WINDOWS_SERVER_2016;
        isInstallDocker = false;
        isInstallMaven = false;
        isInstallGit = false;
    }

    public BuiltInImageBuilder withBuiltInImage(String builtInImage) {
        this.builtInImage = builtInImage;
        return this;
    }

    public BuiltInImageBuilder withInstallGit(boolean installGit) {
        this.isInstallGit = installGit;
        return this;
    }

    public BuiltInImageBuilder withInstallMaven(boolean installMaven) {
        this.isInstallMaven = installMaven;
        return this;
    }

    public BuiltInImageBuilder withInstallDocker(boolean installDocker) {
        this.isInstallDocker = installDocker;
        return this;
    }

    public BuiltInImage build() {
        return new BuiltInImage(builtInImage, isInstallGit, isInstallMaven, isInstallDocker);
    }
}

