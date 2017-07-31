# Azure VM Agents Plugin Changelog

## Version 0.4.7, 2017-08-01
* Built-in image support. Now besides manually fill in image reference and init script, you can select from two built-in images, Windows Server 2016 and Ubuntu 16.04 LTS.
* Auto tool installation on VM agents. If you're using built-in image, you can choose to install Git, Maven or Docker.
* SSH support for Windows agent. You can now use SSH to launch Windows agent.
* Support managed disk for VM agent.
* Allow user to specify the name for the cloud. This fixes the issue that one cloud will be ignored if you have two with same subscription ID and resource group name.
* Various minor bug fixes.

## Version 0.4.6, 2017-06-20
* Add LogRecorder for Azure VM Agent plugin to make it easier for troubleshooting
* Fix an issue that Jenkins crashes in some cases when CloudStatistics is enabled
* Improve VM template creation, you're now able to select from existing resource groups and storage accounts.

## Version 0.4.5.1 Beta, 2017-06-09
* Fixed a backward compatibility issue that storage type becomes empty when reading a configuration created from an older version. ([JENKINS-44750](https://issues.jenkins-ci.org/browse/JENKINS-44750))

## Version 0.4.5 Beta, 2017-06-02
* Added the option to specify different resource group for virtual network. ([JENKINS-43909](https://issues.jenkins-ci.org/browse/JENKINS-43909))
* Support multiple cloud profiles with the same subscription ID ([JENKINS-43704](https://issues.jenkins-ci.org/browse/JENKINS-43704))
* Support premium storage account for VM template ([JENKINS-43097](https://issues.jenkins-ci.org/browse/JENKINS-43097))
* Support Cloud Statistics Plugin ([JENKINS-42799](https://issues.jenkins-ci.org/browse/JENKINS-42799))

## Version 0.4.4 Beta, 2017-04-12
* Added the option to deploy VM Agents without a public IP. ([JENKINS-40620](https://issues.jenkins-ci.org/browse/JENKINS-40620))
* Added the ability to attach a public IP for an already deployed agent. The user need to go to the Nodes management page and configure the desired Azure VM Agent.
* Added the option to attach an existing Azure Network Security Group to the provisioned agents
* Fixed the 'Usage' parameter. ([JENKINS-42037](https://issues.jenkins-ci.org/browse/JENKINS-42037))
* Fixed a Null Pointer Exception while trying to fill the VM Size dropdown. ([JENKINS-42853](https://issues.jenkins-ci.org/browse/JENKINS-42853))
* The agent password verification now allows more special characters. ([JENKINS-43243](https://issues.jenkins-ci.org/browse/JENKINS-43243))

## Version 0.4.3 Beta, 2017-03-03
* The plugin now depends on the Azure Credentials plugin
* Existing credentials are now working when upgrading the plugin ([JENKINS-42479](https://issues.jenkins-ci.org/browse/JENKINS-42479))
* Fixed an issue where deployments in some existing Azure Resource Groups might have failed
* The plugin now removes the leftover empty containers after a custom-image agent was deleted.

## Version 0.4.2 Beta, 2017-01-16
* Extend support to all Azure regions and available VM sizes ([JENKINS-40488](https://issues.jenkins-ci.org/browse/JENKINS-40488))
* Fixed an edge case where provisioned VMs were not removed after they were used ([JENKINS-41330](https://issues.jenkins-ci.org/browse/JENKINS-41330))
* The fix will ensure that newly created resources are properly disposed, but it won't disposed of any resources deployed with the plugin before and including version 0.4.1
* Updated the 'Max Jenkins Agents Limit' label in the cloud configuration page to reflect the quota on the number of agents the plugin is allowed to deploy in a resource group ([JENKINS-41568](https://issues.jenkins-ci.org/browse/JENKINS-41568))
* Other minor fixes and improvements

## Version 0.4.1 Beta, 2016-12-13
* Verify that the storage account name is valid during template verification ([JENKINS-40289](https://issues.jenkins-ci.org/browse/JENKINS-40289))
* The auto-generated storage account name is unique across Azure ([JENKINS-40288](https://issues.jenkins-ci.org/browse/JENKINS-40288))
* The SSH session is re-established after the init script runs ([JENKINS-40291](https://issues.jenkins-ci.org/browse/JENKINS-40291))
* Other minor fixes and improvements

## Version 0.4.0 Beta, 2016-12-06
* Initial release
