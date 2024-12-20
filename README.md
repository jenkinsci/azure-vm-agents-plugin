# Azure VM Agents Plugin

A Jenkins Plugin to create Jenkins agents in Azure Virtual Machines (via Azure ARM template).

Supports creating Windows and Linux agents.

## How to Install

You can install/update this plugin in the Jenkins update center (Manage Jenkins -> Plugins -> Available plugins, search Azure VM Agents).

## Prerequisites

To use this plugin to create VM agents, first you need to have an Azure Service Principal in your Jenkins instance.

1. Create an Azure Service Principal through [Azure CLI](https://docs.microsoft.com/en-us/cli/azure/create-an-azure-service-principal-azure-cli?toc=%2fazure%2fazure-resource-manager%2ftoc.json) or [Azure portal](https://docs.microsoft.com/en-us/azure/azure-resource-manager/resource-group-create-service-principal-portal).
   * Managed identity is also supported, you can create a managed identity and assign it to the Jenkins controller.
2. Open Jenkins dashboard, go to Credentials, add a new Azure Service Principal with the credential information you just created.

## Configure the Plugin

### Add a New Azure VM Agents Cloud
1. Click Manage Jenkins -> Clouds -> click "New cloud".
2. Provide a name for the cloud
3. Select type "Azure VM Agents"
4. Click "Create"
5. Select an existing credential from the Azure credentials dropdown or add a new "Azure Service Principal" credential
6. Either select create new resource group or Use existing
7. Click on “Verify configuration” to make sure that the profile configuration is done correctly.
8. Save and continue with the template configuration (See instructions below).

### Add a New Azure VM Agent Template
1. Click "Agent templates" in the side panel
2. Click "Add an agent template"
3. Provide a name for your new template.
4. Click "Create"
5. Provide one or more meaningful labels, e.g. "Windows" or "Linux". Label is used by job to determine which agents will be selected to run the job, so please make sure you give it a meaningful label.
6. Specify Admin Credentials - This needs to be either an "SSH Username with private key" or "Username with password" credential
7. Select the desired region, and Virtual machine size from dropdown list.
8. Select the Storage Account Type
9. Specify the Azure Storage account name or select an existing Storage account name.
   If you choose to create a new one but leave the name blank the plugin will generate a name for you.
10. Select the disk type between Managed Disk (recommended) or Unmanaged Disk.
11. Select the retention strategy
    * Idle Retention Strategy. You can specify the retention time in minutes. This defines the number of minutes Jenkins can wait before automatically deleting an idle agent. Specify 0 if you do not want idle agents to be deleted automatically.
    * Pool Retention Strategy. This retention strategy help you to maintain amount of agents in a specific number. You can specify the retention time in hour and the pool size.
    * Once Retention Strategy. This retention strategy make sure to use one agent only once.

    Retention time define the time before automatically deleting since the agent created. And the pool size define the agent pool size you want to maintain.
    If you change your cloud name, template name or most of the parameters (e.g. Region, Image), we will delete the existing agents at once and provision the new one according to your new template.
    But if you only change your Retention Time or Pool Size, we will only scale in, scale out or do nothing for you.
12. Select a usage option:
    * If "Use this node as much as possible" is selected, then Jenkins may run any job on the agent as long as it is available.
    * If "Only build jobs with label expressions matching this node" is selected,
      Jenkins will only build a project on this node when that project is restricted to certain nodes using a label expression, and that expression matches this node's name and/or labels.
      This allows an agent to be reserved for certain kinds of jobs.
13. Select a pre-existing network security group in the resource group for the template, this needs to have SSH (22) allowed from the controller to the agent
14. Select a built-in image, you can choose between a Windows Server or Ubuntu version. You can also choose to install some tools on the agent, including Git, Maven and Docker (Java is always installed).
15. Click Verify Template to make sure all your configurations are correct, then Save.

### Run Jenkins Jobs on Azure VM Agents
After you configured an Azure VM agent template, when you run a new Jenkins job, Jenkins will automatically provision a new Azure VM only if there is no executor available.

A more common scenario is you want to restrict some jobs to always be running on a particular VM agent instead of Jenkins controller. To achieve that:
1. Open your Jenkins project, under General, check "Restrict where this project can be run".
2. In Label Expression, fill in the label you assigned to your VM template.
3. Save and run the job, you'll see your job is running on the VM agent even if Jenkins controller is free.

For how to select agent in pipeline, refer to this [doc](https://jenkins.io/doc/book/pipeline/syntax/#agent).

## Use a Custom VM Image
The built-in image only has a clean Windows or Ubuntu OS and some tools like Git and Maven installed, in some cases, you may want to have more customization on the image. To use a custom image:
1. In Image Configuration, select "Use Advanced Image Configurations".
2. Choose between two possible alternatives:
   * Use a custom user image (provide image URL and OS type - note, your custom image has to be available into the same storage account in which you are going to create agent nodes);
   * Using any marketplace image by specifying an image reference (provide image reference by publisher, offer, sku and version). You can get the publisher, offer and sku by looking at the ARM template of that image.
3. For the launch method, select SSH or JNLP.
   * For Windows agents, if the launch method is SSH then check Pre-Install SSH in Windows Agent or image needs to be custom-prepared with an SSH server pre-installed.

   We recommend to use SSH rather than connecting it to the controller, it's simpler to setup and get much clearer logs.
   > When using the Inbound agent launch option, ensure the following:
   > * Jenkins URL (Manage Jenkins -> Configure System -> Jenkins Location)
   > * The URL needs to be reachable by the Azure agent, so make sure to configure any relevant firewall rules accordingly.
   > * TCP port for Inbound agents (Manage Jenkins -> Configure Global Security -> Enable security -> TCP port for inbound agents).
   > * The TCP port needs to be reachable from the Azure agent launched with connecting to the controller. It is recommended to use a fixed port so that any necessary firewall exceptions can be made.
   >
   > If the Jenkins controller is running on Azure, then open an endpoint for "TCP port for Inbound agents" and,
   > in case of Windows, add the necessary firewall rules inside virtual machine (Run -> firewall.cpl).

4. For the Initialization Script, you can provide a script that will be executed after the VM is provisioned. This allows to install any app/tool you need on the agent. You need to at least install Java if the image does not have Java pre-installed.
   We have provided sample scripts. See more details in the help for 'Initialization script'

   For more details about how to prepare custom images, refer to the below links:
   * [Capture Windows Image](http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-capture-image-windows-server/)
   * [Capture Linux Image](http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-linux-capture-image/)

   Init script should finish in 20 minutes (this time can be configured in Deployment Timeout setting of Azure Profile Configuration). It's not recommended to run complex init script, if the init script is expected to take a long time to complete, it is recommended to use a custom-prepared image.

## Advanced Configurations
If you choose Use Advanced Image Configurations, you can click on Advanced button where you can find more Virtual Machine configurations:
1. Virtual Network Name, Virtual Network Resource Group Name and Subnet name: by default the VM does not belong to any virtual network, you can provide one if you want the VM to be in a virtual network for network security. Please be noted the virtual network must exist.
2. Make VM agent IP private: by default the plugin will create a public IP for the VM so it's public accessible on internet. Check this option if you don't want the public IP to be created.
   > Make VM agent IP private can make the VM more secure, but if you configured to use SSH to launch agent, Jenkins controller needs to be able to access the VM. So in this case you need to also specify virtual network and subnet name so the agent and Jenkins master are in the same subnet.

3. Network Security Group Name: add the VM to a network security group.
4. JVM Options: specify JVM options.
5. Number of Executors: specify the number concurrent builds that a VM agent can run at the same time.
6. Disable template: disable this template temporarily.

## Configure VM Template using configuration-as-code plugin

This plugin can be fully configured by the [Jenkins Configuration as Code plugin](https://github.com/jenkinsci/configuration-as-code-plugin).

Configure the plugin normally first and then [export the configuration](https://github.com/jenkinsci/configuration-as-code-plugin/blob/master/docs/features/configExport.md) and adjust it to your needs.

## Configure VM Template using Groovy Script

It is recommended that you use the `configuration-as-code` plugin for automating the plugin configuration. If you can't do that for some reason then you can use groovy script

See [Configure the plugin with Groovy](docs/configure-with-groovy.md) for more details.

## Permissions

The required permissions depend on which features of the plugin you are using.

The simplest permission to deploy will be 'Contributor' on the resource group that the Virtual Machines are being deployed to.

### Roles required by feature

- **Deploying Virtual Machines**:
  - [Virtual Machine Contributor](https://learn.microsoft.com/en-us/azure/role-based-access-control/built-in-roles/compute#virtual-machine-contributor) to deploy the Virtual Machine
    - On the resource group
  - [Network Contributor](https://learn.microsoft.com/en-us/azure/role-based-access-control/built-in-roles/networking#network-contributor) to deploy the Virtual Network and Public IP Address
    - On the resource group if deploying new resources, or on the Virtual Network if using an existing network and no Public IP addresses
- **Uploading file to storage account** - Used by Windows agents and Inbound Agents
  - [Reader and Data Access](https://learn.microsoft.com/en-us/azure/role-based-access-control/built-in-roles/storage#storage-account-contributor) if using Storage account keys (default)
    - On storage account resource
  - [Storage Blob Data Contributor](https://learn.microsoft.com/en-us/azure/role-based-access-control/built-in-roles/storage#storage-blob-data-contributor) if using Azure RBAC
    - On storage account resource
- **Managed Identity**: Used when a managed identity is configured for the template and required when using storage RBAC
  - [Managed Identity Operator](https://learn.microsoft.com/en-us/azure/role-based-access-control/built-in-roles/identity#managed-identity-operator)
    - On the Managed Identity resource
- **Azure Compute Gallery**: 
  - [Reader](https://learn.microsoft.com/en-us/azure/role-based-access-control/built-in-roles/general#reader)
    - On the VM image definition

## Troubleshooting

### Deployment validation failure

If you can't tell from validation why a deployment does not work you can enable additional logging.

The plugin creates a Jenkins logger called `Azure VM Agent (Auto)`, update that to `FINE` level and re-run the agent provisioning.
The ARM template which is used to deploy resources will now show up.

More information on Jenkins logs can be found in the Jenkins documentation for [Viewing logs](https://www.jenkins.io/doc/book/system-administration/viewing-logs/).

### Jenkins times out connecting to the agent when using a Public IP address

Ensure you've configured a Network Security Group on the subnet that Jenkins is using or selected one in the advanced configuration of the VM template.
