# Configure the plugin with Groovy

It is recommended that you use the `configuration-as-code` plugin for automating the plugin configuration. If you can't do that for some reason then you can use groovy script

Here is a sample groovy script that creates a new Azure cloud and VM template. You can run it in Manage Jenkins -> Script Console.

## Built-in image

```groovy
//Configure cloud with built-in image
import com.microsoft.azure.vmagent.builders.*
def myCloud = new AzureVMCloudBuilder()
.withCloudName("myAzure")
.withAzureCredentialsId("<your azure credential ID>")
.withNewResourceGroupName("<your Resource Group Name>")
.addNewTemplate()
    .withName("ubuntu")
    .withLabels("ubuntu")
    .withLocation("East US")
    .withVirtualMachineSize("Standard_DS2_v2")
    .withNewStorageAccount("<your Storage Account Name>")
    .addNewBuiltInImage()
        .withBuiltInImageName("Ubuntu 16.14 LTS")
        .withInstallGit(true)
        .withInstallMaven(true)
        .withInstallDocker(true)
        .withInstallQemu(true)
    .endBuiltInImage()
    .withAdminCredential("<your admin credential ID>")
.endTemplate()
.build()
Jenkins.getInstance().clouds.add(myCloud)
```

## Advanced image

```groovy
//Configure cloud with mutli-template of advanced images
import com.microsoft.azure.vmagent.builders.*
def firstTemplate = new AzureVMTemplateBuilder()
.withName("first-template")
.withLabels("ubuntu")
.withLocation("East US")
.withVirtualMachineSize("Standard_DS2_v2")
.withNewStorageAccount("<your Storage Account Name>")
.addNewAdvancedImage()
    .withReferenceImage("Canonical", "UbuntuServer", "16.04-LTS", "latest")
    .withInitScript("sudo add-apt-repository ppa:openjdk-r/ppa -y \n" +
                    "sudo apt-get -y update \n" +
                    "sudo apt-get install openjdk-8-jre openjdk-8-jre-headless openjdk-8-jdk -y")
.endAdvancedImage()
.withAdminCredential("<your admin credential ID>")
.build()
def myCloud = new AzureVMCloudBuilder()
.withCloudName("myAzure")
.withAzureCredentialsId("<your azure credential ID>")
.withNewResourceGroupName("<your Resource Group Name>")
.addToTemplates(firstTemplate)
.addNewTemplate()
    .withName("second-template")
    .withLabels("windows")
    .withLocation("Southeast Asia")
    .withVirtualMachineSize("Standard_DS2_v2")
    .withNewStorageAccount("<your Storage Account Name>")
    .addNewAdvancedImage()
        .withReferenceImage("MicrosoftWindowsServer", "WindowsServer", "2016-Datacenter", "latest")
    .endAdvancedImage()
    .withAdminCredential("<your admin credential ID>")
.endTemplate()
.build()
Jenkins.getInstance().clouds.add(myCloud)
```

## Inherit existing template

```groovy
//inherit existing template
import com.microsoft.azure.vmagent.builders.*
import com.microsoft.azure.vmagent.*
AzureVMAgentTemplate baseTemplate = new AzureVMTemplateBuilder()
.withLocation("Southeast Asia")
.withVirtualMachineSize("Standard_DS2_v2")
.withStorageAccountType("Premium_LRS")
.withNewStorageAccount("<your Storage Account Name>")
    .addNewAdvancedImage()
         .withReferenceImage("Canonical", "UbuntuServer", "16.04-LTS", "latest")
    .endAdvancedImage()
    .withAdminCredential("<your admin credential ID>")
.build()
def myCloud = new AzureVMCloudBuilder()
.withCloudName("myAzure")
.withAzureCredentialsId("<your azure credential ID>")
.withNewResourceGroupName("<your Resource Group Name>")
.addNewTemplateLike(baseTemplate)
    .withName("inherit")
    .withLabels("inherit")
    .addNewAdvancedImageLike(baseTemplate.getAdvancedImageInside())
        .withInitScript("sudo add-apt-repository ppa:openjdk-r/ppa -y \n" +
                        "sudo apt-get -y update \n" +
                        "sudo apt-get install openjdk-8-jre openjdk-8-jre-headless openjdk-8-jdk -y")
    .endAdvancedImage()
.endTemplate()
.build()
Jenkins.getInstance().clouds.add(myCloud)
```

This sample only contains a few arguments of builder, please find all the arguments in folder [builders](src/main/java/com/microsoft/azure/vmagent/builders).