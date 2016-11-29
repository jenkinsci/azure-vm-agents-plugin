# azure-vm-agents


Jenkins Plugin to create Azure VM agents (on Azure ARM).

Supports creating

1. Windows Agents on Azure Cloud using SSH and JNLP
  * For windows images to launch via SSH, the image needs to be preconfigured with ssh.
   For preparing custom windows image, refer to [Azure documentation](http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-capture-image-windows-server/)
2. Linux Agents on Azure Cloud using SSH
  * For preparing custom linux image, refer to [Azure documentation]( http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-linux-capture-image/)

## Pre-requirements
Register and authorize your client application.

Retrieve and use Client ID and Client Secret to be sent to Azure AD during authentication.

Refer to
  * [Adding, Updating, and Removing an Application](https://msdn.microsoft.com/en-us/library/azure/dn132599.aspx)
  * [Register a client app](https://msdn.microsoft.com/en-us/dn877542.asp)

## How to install the Azure VM Agents plugin
1. Within the Jenkins dashboard, click Manage Jenkins.
2. In the Manage Jenkins page, click Manage Plugins.
3. Click the Available tab.
4. In the "Cluster Management and Distributed Build" section, select the Azure VM Agents plugin.
5. Click either “Install without restart” or “Download now and install after restart”.
6. Restart Jenkins if necessary.

## Configure the plugin : Azure profile configuration
1. Within the Jenkins dashboard, click Manage Jenkins --> Configure System --> Scroll to the bottom of the page
   and find the section with the dropdown "Add new cloud" --> click on it and select "Microsoft Azure VM Agents"
2. Select an existing account from the Azure Credentials drop down or add new "Microsoft Azure VM Agents" credentials in the Credentials Management page by filling out the Subscription ID, Client ID, Client Secret and the OAuth 2.0 Token Endpoint.
3. Click on “Verify configuration” to make sure that the profile configuration is done correctly.
4. Save and continue with the template configuration (See instructions below)

## Configure the plugin : Template configuration.
1. Click on the "Add" option to add a template. A template is used to define an Azure VM Agent configuration, like
   its VM  size, its region, or its retention time.
2. Provide a name for your new template. This field is not used for agent provisioning.
3. For the description, provide any remarks you wish about this template configuration. This field is not
   used for agent provisioning.
4. For the label, provide any valid string. E.g. “windows” or “linux”. The label defined in a template can be
   used during a job configuration.
5. Select the desired region from the combo box.
6. Select the desired VM size.
7. Specify the Azure Storage account name. Alternatively you can leave it blank to let Jenkins create a storage
   account by using the default name "jenkinsarmst".
8. Specify the retention time in minutes. This defines the number of minutes Jenkins can wait before automatically
   deleting an idle agent. Specify 0 if you do not want idle agents to be deleted automatically.
9. Select a usage option:
  * If "Utilize this node as much as possible" is selected, then Jenkins may run any job on the agent as long as it
    is available.
  * If "Leave this node for tied jobs only" is selected, Jenkins will only build a project (or job) on this node
    when that project specifically was tied to that node. This allows a agent to be reserved for certain kinds of jobs.
10. Specify your Image Family. Choose between two possible alternatives:
  * use a custom user image (provide image URL and os type - note, your custom image has to be available into the same storage account in which you are going to create agent nodes);
  * give an image reference (provide image reference by publisher, offer, sku and version).
11. For the launch method, select SSH or JNLP.
  * Linux agents can be launched using SSH or JNLP, though no startup script is available through JNLP.
  * Windows agents can be launched using SSH or JNLP. For Windows agents, if the launch method is SSH then
    image needs to be custom-prepared with an SSH server pre-installed.<br>


  When using the JNLP launch option, ensure the following:
  * Jenkins URL (Manage Jenkins --> configure system --> Jenkins Location)
    * The URL needs to be reachable by the Azure agent, so make sure to configure any relevant                                      firewall rules accordingly.
  * TCP port for JNLP agent agents (Manage Jenkins --> configure global security --> Enable security --> TCP port for JNLP agents).
    * The TCP port needs to be reachable from the Azure agent launched using JNLP. It is recommended to use a fixed port so         that any necessary firewall exceptions can be made.

      If the Jenkins master is running on Azure, then open an endpoint for "TCP port for JNLP agent agents" and, in case of
      Windows, add the necessary firewall rules inside virtual machine (Run --> firewall.cpl).
12. For the Init script, provide a script to install at least a Java runtime if the image does not have Java
      pre-installed.

    For the Windows JNLP launch method, the init script must be in PowerShell.
        Automatically passed to this script is:
            First argument - Jenkins server URL
            Second argument - VMName
            Third argument - JNLP secret, required if the server has security enabled.
    You need to install Java, download the agent jar file from: '[server url]jnlpJars/slave.jar'.
    The server url should already have a trailing slash.  Then execute the following to connect:
    `java.exe -jar [agent jar location] [-secret [client secret if required]] [server url]computer/[vm name]/agent-agent.jnlp`

    Example script
    ```
    Set-ExecutionPolicy Unrestricted
    $jenkinsServerUrl = $args[0]
    $vmName = $args[1]
    $secret = $args[2]

    $baseDir = 'C:\Jenkins'
    mkdir $baseDir
    # Download the JDK
    $source = "http://download.oracle.com/otn-pub/java/jdk/7u79-b15/jdk-7u79-windows-x64.exe"
    $destination = "$baseDir\jdk.exe"
    $client = new-object System.Net.WebClient
    $cookie = "oraclelicense=accept-securebackup-cookie"
    $client.Headers.Add([System.Net.HttpRequestHeader]::Cookie, $cookie)
    $client.downloadFile([string]$source, [string]$destination)

    # Execute the unattended install
    $jdkInstallDir=$baseDir + '\jdk\'
    $jreInstallDir=$baseDir + '\jre\'
    C:\Jenkins\jdk.exe /s INSTALLDIR=$jdkInstallDir /INSTALLDIRPUBJRE=$jdkInstallDir

    $javaExe=$jdkInstallDir + '\bin\java.exe'
    $jenkinsSlaveJarUrl = $jenkinsServerUrl + "jnlpJars/slave.jar"
    $destinationSlaveJarPath = $baseDir + '\slave.jar'

    # Download the jar file
    $client = new-object System.Net.WebClient
    $client.DownloadFile($jenkinsSlaveJarUrl, $destinationSlaveJarPath)

    # Calculate the jnlpURL
    $jnlpUrl = $jenkinsServerUrl + 'computer/' + $vmName + '/slave-agent.jnlp'

    while ($true) {
        try {
            # Launch
            & $javaExe -jar $destinationSlaveJarPath -secret $secret -jnlpUrl $jnlpUrl -noReconnect
        }
        catch [System.Exception] {
            Write-Output $_.Exception.ToString()
        }
        sleep 10
    }
    ```

      If you hit the [storage scalability limits](https://docs.microsoft.com/en-us/azure/storage/storage-scalability-targets) for your custom images on one storage account you should consider using the agent [temporary storage](https://blogs.msdn.microsoft.com/mast/2013/12/06/understanding-the-temporary-drive-on-windows-azure-virtual-machines) or creating multiple storage accounts and spread your agents across multiple templates with the same label.

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
3. For platform images, you may specify an Init script as below to install Java (may vary based on OS):

```
      #Install Java
      sudo apt-get -y update
      sudo apt-get install -y openjdk-8-jre
```

## Template configuration for Windows images with launch method JNLP.
1. Make sure to follow the instructions specified above for JNLP.
2. If the Jenkins master does not have a security configuration, leave the Init script blank for the default
   script to execute on the agent.
3. If the Jenkins master has a security configuration, then refer to the script at
   https://gist.github.com/snallami/5aa9ea2c57836a3b3635 and modify the script with the proper
   Jenkins credentials.

   At a minimum, the script needs to be modified with the Jenkins user name and API token.
   To get the API token, click on your username --> configure --> show api token<br>

   The below statement in the script needs to be modified:
   $credentails="username:apitoken"

## Create a Jenkins job that runs on a Linux agent node on Azure
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
9. Jenkins will create a agent node on Azure cloud using the template created in the previous section and
   execute the script you specified in the build step for this task.
10. Logs are available @ Manage Jenkins --> System logs --> All Jenkins logs.
11. Once the node is provisined in Azure, which typically takes about 5 to 7 minutes, node gets added to Jenkins.



