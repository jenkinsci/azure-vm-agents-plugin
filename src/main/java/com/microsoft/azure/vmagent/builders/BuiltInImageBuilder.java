package com.microsoft.azure.vmagent.builders;


public class BuiltInImageBuilder extends BuiltInImageFluent<BuiltInImageBuilder> {

    private BuiltInImageFluent<?> fluent;

    public BuiltInImageBuilder() {
        this.fluent = this;
    }

    public BuiltInImageBuilder(BuiltInImage image) {
        this.fluent = this;
        fluent.withBuiltInImageName(image.getBuiltInImage());
        fluent.withInstallDocker(image.isInstallDocker());
        fluent.withInstallGit(image.isInstallGit());
        fluent.withInstallMaven(image.isInstallMaven());
        fluent.withInstallQemu(image.isInstallQemu());
    }

    public BuiltInImageBuilder(BuiltInImageFluent<?> fluent) {
        this.fluent = fluent;
    }

    public BuiltInImageBuilder(BuiltInImageFluent<?> fluent, BuiltInImage image) {
        this.fluent = fluent;
        fluent.withBuiltInImageName(image.getBuiltInImage());
        fluent.withInstallDocker(image.isInstallDocker());
        fluent.withInstallGit(image.isInstallGit());
        fluent.withInstallMaven(image.isInstallMaven());
        fluent.withInstallQemu(image.isInstallQemu());
    }

    public BuiltInImage build() {
        return new BuiltInImage(fluent.getBuiltInImage(),
                fluent.isInstallGit(),
                fluent.isInstallMaven(),
                fluent.isInstallDocker(),
                fluent.isInstallQemu());
    }
}
