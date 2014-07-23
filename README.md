azure-slave-plugin
==================

Jenkins Plugin to create Azure slaves

Supports creating 

a) Windows slave on Azure Cloud using SSH and JNLP
   - For windows images to launch via SSH, the image needs to be preconfigured with ssh.  
   For preparing custom windows image, refer to instructions @ http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-capture-image-windows-server/   
 
b) Linux slaves on Azure Cloud using SSH
   For preparing custom linux image, refer to instructions @ http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-linux-capture-image/
 
-------------------------------------------------------------------------------------------------------------------------------------------------------------------

I.	How to install azure slave plugin
	a.	Within the Jenkins dashboard, click Manage Jenkins.
	b.	In the Manage Jenkins page, click Manage Plugins.
	c.	Click the Available tab.
	d.	In the "Cluster Management and Distributed Build" section, check Azure Slave plugin.
	e.	Click either Install without restart or Download now and install after restart.
	f.	Restart Jenkins if necessary.

II.	Configure plugin: Azure profile configuration
	a.	Within the Jenkins dashboard, click Manage Jenkins --> Configure System --> Scroll to the bottom of the page and find section with 
		Combo box "Add new cloud" --> click on it and select "Microsoft Azure"
	b.	Enter subscription ID and management cert from publish settings. If you don’t have publish settings, click on help 
	    and follow the directions to download publish settings.
	c.	Click on "Verify configuration" to make sure that profile configuration is done correctly.
	d.	Save and continue with template configuration (See instructions below)
	
III. Configure plugin : Template configuration
	a.	Click on Add option to add a template. Template defines azure slave configuration like VM size, Region, Retention time.
	b.	For template name, provide valid DNS name. Jenkins will create a cloud service with same name if one does not already exists.	
	c.	For description, provide any text which defines template configuration. This field is not used for slave provisioning. 
	d.	For label, give any valid string. E.g. windows or linux. Label defined in template can be used during job configuration.
	e.	Select any one of the Region from combo box.
	f.	Select desired VM size.
	g.	Specify Storage Account name. Please note that storage account location and region should match. 
	    Alternatively you can leave it blank so that Jenkins will create storage account if needed.
	h.	Specify retention time in minutes. Retention time defines number of minutes Jenkins can wait before deleting idle slave.
		Specify 0 if you do not want slave to be deleted
	i.	Select any one of the option from usage.
		If "Utilize this node as much as possible" is selected , then Jenkins may run any job on the slave if it is available.
		If "Leave this node for tied jobs only" is selected, Jenkins will only build a project(or Job) 
		on this node when that project specifically was tied to that node.
		
		This allows a slave to be reserved for certain kinds of jobs.
	j.	For Image Family or ID , enter Image family name or image ID. 
			For Image family, Just enter first character with proper case and auto complete suggestions will pop up.
			For image ID, enter the name of the image. Note that image ID’s are not auto populated , exact name needs 
			to be entered.

		Typically image family is used for platform images and image ID’s are used for custom prepared images.
	k.	For launch method , select SSH or JNLP.
			Linux slaves can be launched using SSH only. 
			Windows slaves can be launched using SSH or JNLP. For Windows slaves if launch method is SSH 
			then image needs to be custom prepared with SSH server pre-installed.

			When using JNLP launch, ensure following
			1)	Jenkins URL (Manage Jenkins --> configure system --> Jenkins Location) 
					URL needs to be reachable by azure slave, so make sure to configure firewall rules accordingly. 
			2)	TCP port for JNLP slave agents (Manage Jenkins --> configure global security --> Enable security --> TCP port for JNLP slaves). 
					TCP port needs to be reachable from azure slave launched using JNLP. It is suggested to use fixed port so that 
					necessary firewall exceptions can be made. 

			If Jenkins master is running on Azure then open endpoint for "TCP port for JNLP slave agents" and in case of 
            windows add necessary firewall (Run --> firewall.cpl) rules inside virtual machine. 
	l.	For Init script, provide script to install at a minimum java runtime if image does not have java pre-installed.
 
		It is suggested to prepare custom prepared images with necessary software’s pre-installed if init script is going to take long time.
		For details about how to prepare custom images , Refer below links
		http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-capture-image-windows-server/
		http://azure.microsoft.com/en-us/documentation/articles/virtual-machines-linux-capture-image/
	m.	Specify user name and password as per the rules specified in help.
	n.	Make sure to validate template configuration by clicking on the link "Verify Template". 
-----------------------------------------------------------------------------------------------------------------------------------------------------------------

   
