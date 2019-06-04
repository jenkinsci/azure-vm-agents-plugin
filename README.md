# Azure VM Agents Plugin

A Jenkins Plugin to create Jenkins agents in Azure Virtual Machines (via Azure ARM template).

Supported features:

1. Windows Agents on Azure Cloud using SSH and JNLP
   * For Windows images to launch via SSH, the image needs to be preconfigured with SSH.
   * For preparing custom windows image, refer to [Azure documentation](http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-capture-image-windows-server/)
2. Linux Agents on Azure Cloud using SSH
   * For preparing custom linux image, refer to [Azure documentation]( http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-linux-capture-image/)

## How to Install

You can install/update this plugin in Jenkins update center (Manage Jenkins -> Manage Plugins, search Azure VM Agents Plugin).

You can also manually install the plugin if you want to try the latest feature before it's officially released.
To manually install the plugin:

1. Clone the repo and build:
   ```
   mvn package
   ```
2. Open your Jenkins dashboard, go to Manage Jenkins -> Manage Plugins.
3. Go to Advanced tab, under Upload Plugin section, click Choose File.
4. Select `azure-vm-agents.hpi` in `target` folder of your repo, click Upload.
5. Restart your Jenkins instance after install is completed.

## Prerequisites

To use this plugin to create VM agents, first you need to have an Azure Service Principal in your Jenkins instance.

1. Create an Azure Service Principal through [Azure CLI](https://docs.microsoft.com/en-us/cli/azure/create-an-azure-service-principal-azure-cli?toc=%2fazure%2fazure-resource-manager%2ftoc.json) or [Azure portal](https://docs.microsoft.com/en-us/azure/azure-resource-manager/resource-group-create-service-principal-portal).
2. Open Jenkins dashboard, go to Credentials, add a new Microsoft Azure Service Principal with the credential information you just created.

## Configure the Plugin

### Add a New Azure VM Agents Cloud
1. Within the Jenkins dashboard, click Manage Jenkins -> Configure System -> Scroll to the bottom of the page
   and find the section with the dropdown "Add a new cloud" -> click on it and select "Microsoft Azure VM Agents"
2. Provide a name for the cloud (plugin will generate one for you if you leave it empty, but it's recommended to give it a meaningful name).
3. Select an existing account from the Azure Credentials dropdown or add new "Microsoft Azure Service Principal" credentials in the Credentials Management page by filling out the Subscription ID, Client ID, Client Secret and the OAuth 2.0 Token Endpoint.
4. Click on “Verify configuration” to make sure that the profile configuration is done correctly.
5. Save and continue with the template configuration (See instructions below).

### Add a New Azure VM Agent Template
1. Click the "Add" in "Add Azure Virtual Machine Template" to add a template. A template is used to define an Azure VM Agent configuration, like its VM size, region, or retention time.
2. Provide meaningful name and description for your new template.
3. Provide one or more meaningful labels, e.g. "Windows" or "Linux". Label is used by job to determine which agents will be selected to run the job, so please make sure you give it a meaningful label.
4. Select the desired region, and VM size from dropdown list.
5. Select the Storage Account Type, either Standard_LRS or Premium_LRS. Note that some VM size only supports Standard_LRS.
6. Specify the Azure Storage account name or select an existing Storage account name for storing VM's OS disk.
   If you choose to create a new one but leave the name blank the plugin will generate a name for you.
7. Select the disk type between Managed Disk (recommended) or Unmanaged Disk.
8. Select the retention strategy
   * Idle Retention Strategy. You can specify the retention time in minutes. This defines the number of minutes Jenkins can wait before automatically deleting an idle agent. Specify 0 if you do not want idle agents to be deleted automatically.
   * Pool Retention Strategy. This retention strategy help you to maintain amount of agents in a specific number. You can specify the retention time in hour and the pool size.
   * Once Retention Strategy. This retention strategy make sure to use one agent only once. 
   
   Retention time define the time of hour before automatically deleting since the agent created. And the pool size define the agent pool size you want to maintain.
   If you change your cloud name, template name or most of parameters (e.g. Region, Image), we will delete the existing agents at once and provision the new one according to your new template.
   But if you only change your Retention Time or Pool Size, we will only scale in, scale out or do nothing for you.
9. Select a usage option:
   * If "Utilize this node as much as possible" is selected, then Jenkins may run any job on the agent as long as it is available.
   * If "Only build jobs with label expressions matching this node" is selected,
     Jenkins will only build a project on this node when that project is restricted to certain nodes using a label expression, and that expression matches this node's name and/or labels.
     This allows an agent to be reserved for certain kinds of jobs.
10. Select a built-in image, you can choose between Windows Server 2016 and Ubuntu 16.04 LTS. You can also choose to install some tools on the agent, including Git, Maven and Docker (JDK is always installed).
11. Specify Admin Credentials (a username/password credentials), this is the username and password if you want to log into the agent VM.
12. Click Verify Template to make sure all your configurations are correct, then Save.

### Run Jenkins Jobs on Azure VM Agents
After you configured an Azure VM agent template, when you run a new Jenkins job, Jenkins will automatically provision a new Azure VM only if there is no executor available.

A more common scenario is you want to restrict some jobs to always be running on a particular VM agent instead of Jenkins master. To achieve that:
1. Open your Jenkins project, under General, check "Restrict where this project can be run".
2. In Label Expression, fill in the label you assigned to your VM template.
3. Save and run the job, you'll see your job is running on the VM agent even if Jenkins master is free.

For how to select agent in pipeline, refer to this [doc](https://jenkins.io/doc/book/pipeline/syntax/#agent).

## Use a Custom VM Image
The built-in image only has a clean Windows or Ubuntu OS and some tools like Git and Maven installed, in some cases, you may want to have more customization on the image. To use a custom image:
1. In Image Configuration, select "Use Advanced Image Configurations".
2. Choose between two possible alternatives:
   * Use a custom user image (provide image URL and OS type - note, your custom image has to be available into the same storage account in which you are going to create agent nodes);
   * Using any marketplace image by specifying an image reference (provide image reference by publisher, offer, sku and version). You can get the publisher, offer and sku by looking at the ARM template of that image.
3. For the launch method, select SSH or JNLP.
   * Linux agents can be launched only using SSH.
   * Windows agents can be launched using SSH or JNLP. For Windows agents, if the launch method is SSH then check Pre-Install SSH in Windows Slave or image needs to be custom-prepared with an SSH server pre-installed.

   We recommend to use SSH rather than JNLP, for you need less init codes and get much clearer logs.
   > When using the JNLP launch option, ensure the following:
   > * Jenkins URL (Manage Jenkins -> Configure System -> Jenkins Location)
   > * The URL needs to be reachable by the Azure agent, so make sure to configure any relevant firewall rules accordingly.
   > * TCP port for JNLP agent agents (Manage Jenkins -> Configure Global Security -> Enable security -> TCP port for JNLP agents).
   > * The TCP port needs to be reachable from the Azure agent launched using JNLP. It is recommended to use a fixed port so that any necessary firewall exceptions can be made.
   > 
   > If the Jenkins master is running on Azure, then open an endpoint for "TCP port for JNLP agent agents" and,
   > in case of Windows, add the necessary firewall rules inside virtual machine (Run -> firewall.cpl).

4. For the Initialization Script, you can provide a script that will be executed after the VM is provisioned. This allows to install any app/tool you need on the agent. Please be noted you need to at least install JRE if the image does not have Java pre-installed.
   We prepared a sample script for Linux via SSH, Windows via SSH and Windows via JNLP. Please find details in help button.

   If you hit the [storage scalability limits](https://docs.microsoft.com/en-us/azure/storage/storage-scalability-targets) for your custom images on the storage account where the VHD resides, you should consider using the agent's [temporary storage](https://blogs.msdn.microsoft.com/mast/2013/12/06/understanding-the-temporary-drive-on-windows-azure-virtual-machines) or copy your custom image in multiple storage accounts and use multiple VM templates with the same label within the same agent cloud.

   For more details about how to prepare custom images, refer to the below links:
   * [Capture Windows Image](http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-capture-image-windows-server/)
   * [Capture Linux Image](http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-linux-capture-image/)

   Init script should finish in 20 minutes (this time can be configured in Deployment Timeout setting of Azure Profile Configuration). It's not recommended to run complex init script, if the init script is expected to take a long time to complete, it is recommended to use a custom-prepared image.

## Advanced Configurations
If you choose Use Advanced Image Configurations, you can click on Advanced button where you can find more VM configurations:
1. Virtual Network Name, Virtual Network Resource Group Name and Subnet name: by default the VM does not belong to any virtual network, you can provide one if you want the VM to be in a virtual network for network security. Please be noted the virtual network must exist.
2. Make VM agent IP private: by default the plugin will create a public IP for the VM so it's public accessible on internet. Check this option if you don't want the public IP to be created.
   > Make VM agent IP private can make the VM more secure, but if you configured to use SSH to launch agent, Jenkins master needs to be able to access the VM. So in this case you need to also specify virtual network and subnet name so the agent and Jenkins master are in the same subnet.

3. Network Security Group Name: add the VM to a network security group.
4. JVM Options: specify JVM options.
5. Number of Executors: specify the number concurrent builds that a VM agent can run at the same time.
6. Disable template: disable this template temporarily.

## Configure VM Template using configuration-as-code plugin

This plugin can be fully configured by the [Jenkins Configuration as Code plugin](https://github.com/jenkinsci/configuration-as-code-plugin).

Configure the plugin using the UI initially and then export the configuration and tweak to your needs

Note: Until credentials support is merged into the credentials plugin you will need to also install the `configuration-as-code-support` plugin

## Configure VM Template using Groovy Script

It is recommended that you use the `configuration-as-code` plugin for automating the plugin configuration. If you can't do that for some reason then you can use groovy script

Here is a sample groovy script that creates a new Azure cloud and VM template. You can run it in Manage Jenkins -> Script Console.

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
    .endBuiltInImage()
    .withAdminCredential("<your admin credential ID>")
.endTemplate()
.build()
Jenkins.getInstance().clouds.add(myCloud)
```
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