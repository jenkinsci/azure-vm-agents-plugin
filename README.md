# azure-slave-plugin


Jenkins Plugin to create Azure slaves.

Supports creating 

1. Windows slave on Azure Cloud using SSH and JNLP
  * For windows images to launch via SSH, the image needs to be preconfigured with ssh.  
   For preparing custom windows image, refer to [Azure documentation](http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-capture-image-windows-server/)
2. Linux slaves on Azure Cloud using SSH
  * For preparing custom linux image, refer to [Azure documentation]( http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-linux-capture-image/)

## How to install the Azure Slave plugin
1. Within the Jenkins dashboard, click Manage Jenkins.
2. In the Manage Jenkins page, click Manage Plugins.
3. Click the Available tab.
4. In the "Cluster Management and Distributed Build" section, select the Azure Slave plugin.
5. Click either “Install without restart” or “Download now and install after restart”.
6. Restart Jenkins if necessary.

## Configure the plugin : Azure profile configuration
1. Within the Jenkins dashboard, click Manage Jenkins --> Configure System --> Scroll to the bottom of the page 
   and find the section with the dropdown "Add new cloud" --> click on it and select "Microsoft Azure"
2. Enter the subscription ID and the management certificate from your publish settings file. 
   If you don’t have a publish settings file, click on the help button and follow the directions to 
   download the publish settings file.
3. Click on “Verify configuration” to make sure that the profile configuration is done correctly.
4. Save and continue with the template configuration (See instructions below)

## Configure the plugin : Template configuration.
1. Click on the "Add" option to add a template. A template is used to define an Azure slave configuration, like 
   its VM  size, its region, or its retention time.
2. For the template name, provide a valid DNS name. Jenkins will create a cloud service with same name if one 
   does not already exists.
3. For the description, provide any remarks you wish about this template configuration. This field is not 
   used for slave provisioning.
4. For the label, provide any valid string. E.g. “windows” or “linux”. The label defined in a template can be
   used during a job configuration.
5. Select the desired region from the combo box.
6. Select the desired VM size.
7. Specify the Azure Storage account name. Please note that the storage account and cloud service should use the 
   same region. Alternatively you can leave it blank to let Jenkins create a storage account automatically if needed.
8. Specify the retention time in minutes. This defines the number of minutes Jenkins can wait before automatically 
   deleting an idle slave. Specify 0 if you do not want idle slaves to be deleted automatically.
9. Select a usage option:
  * If "Utilize this node as much as possible" is selected, then Jenkins may run any job on the slave as long as it 
    is available.
  * If "Leave this node for tied jobs only" is selected, Jenkins will only build a project (or job) on this node 
    when that project specifically was tied to that node.This allows a slave to be reserved for certain kinds of jobs.
10. For the Image Family or ID , enter either an available image family name or a specific image ID.
  * If you want to specify an image family, then just enter the first character with the proper case to see an
    automatically generated list of available families. Jenkins will use the latest image within the selected family.
  * If you want to specify a specific image ID, enter the name of the image. Note that since image ID’s are not auto   
    populated, the exact name needs to be entered manually. Also, if you are referring to an image using an image ID from   
    the public Azure image gallery rather than your own account, note that such public images with specific IDs are 
    available in Azure only for a limited time as they eventually get deprecated in favor of newer images in the same 
    family. For this reason, it is recommended that you use the image family to refer to public platform images, and image 
    ID’s for your own custom-prepared images.
11. For the launch method, select SSH or JNLP.
  * Linux slaves can be launched using SSH only.
  * Windows slaves can be launched using SSH or JNLP. For Windows slaves, if the launch method is SSH then 
    image needs to be custom-prepared with an SSH server pre-installed.<br>
      

  When using the JNLP launch option, ensure the following:
  * Jenkins URL (Manage Jenkins --> configure system --> Jenkins Location) 
    * The URL needs to be reachable by the Azure slave, so make sure to configure any relevant                                      firewall rules accordingly.
  * TCP port for JNLP slave agents (Manage Jenkins --> configure global security --> Enable security --> TCP port for JNLP slaves).
    * The TCP port needs to be reachable from the Azure slave launched using JNLP. It is recommended to use a fixed port so         that any necessary firewall exceptions can be made.
    
      If the Jenkins master is running on Azure, then open an endpoint for "TCP port for JNLP slave agents" and, in case of 
      Windows, add the necessary firewall rules inside virtual machine (Run --> firewall.cpl).
12. For the Init script, provide a script to install at least a Java runtime if the image does not have Java   
      pre-installed.

      For the JNLP launch method, the init script must be in PowerShell.
      If the init script is expected to take a long time to execute, it is recommended to prepare custom images with the            necessary software pre-installed.<br>
     
      For more details about how to prepare custom images, refer to the below links:
      * [Capture Windows Image](http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-capture-image-windows-server/)
      * [Capture Linux Image](http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-linux-capture-image/)
      
13. Specify a user name and a password as per the rules explained in the help text.
14. Make sure to validate the template configuration by clicking on the link “Verify Template”. This will connect 
      to your Azure account to verify the correctness of the supplied information.

## Template Configuration for Ubuntu images.
1. Configure an Azure profile and Template as per the above instructions.
2. If the init script is expected to take a long time to complete, it is recommended to use a custom-prepared Ubuntu 
   image that has the required software pre-installed, including a Java runtime 
3. For platform images, you may specify an Init script as below to install Java, Git and Ant:

```
      #Install Java
      sudo apt-get -y update
      sudo apt-get install -y openjdk-7-jdk
      sudo apt-get -y update --fix-missing
      sudo apt-get install -y openjdk-7-jdk
      
      # Install Git
      sudo apt-get install -y git
      
      #Install Ant
      sudo apt-get install -y ant
      sudo apt-get -y update --fix-missing
      sudo apt-get install -y ant
```

## Template configuration for Windows images with launch method JNLP.
1. Make sure to follow the instructions specified above for JNLP.
2. If the Jenkins master does not have a security configuration, leave the Init script blank for the default 
   script to execute on the slave.
3. If the Jenkins master has a security configuration, then refer to the script at    
   https://gist.github.com/snallami/5aa9ea2c57836a3b3635 and modify the script with the proper 
   Jenkins credentials.

   At a minimum, the script needs to be modified with the Jenkins user name and API token.
   To get the API token, click on your username --> configure --> show api token<br>

   The below statement in the script needs to be modified:
   $credentails="username:apitoken"
   
## Create a Jenkins job that runs on a Linux slave node on Azure
1. In the Jenkins dashboard, click New Item/Job.
2. Enter a name for the task/Job you are creating.
3. For the project type, select Freestyle project and click OK.
4. In the task configuration page, select Restrict where this project can be run.
5. In the Label Expression field, enter label given during template configuration.
6. In the Build section, click Add build step and select Execute shell.
7. In the text area that appears, paste the following script.
 
 ````
  # Clone from git repo
  currentDir="$PWD"
  if [ -e sample ]; then
    cd sample
    git pull origin master
  else
    git clone https://github.com/snallami/sample.git
  fi
 
 # change directory to project
 cd $currentDir/sample/ACSFilter
 
 #Execute build task
 ant
 ````
8. Save Job and click on Build now.
9. Jenkins will create a slave node on Azure cloud using the template created in the previous section and 
   execute the script you specified in the build step for this task.
10. Logs are available @ Manage Jenkins --> System logs --> All Jenkins logs.
11. Once the node is provisined in Azure, which typically takes about 5 to 7 minutes, node gets added to Jenkins.


 
