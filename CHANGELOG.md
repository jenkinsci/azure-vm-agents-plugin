# Azure VM Agents Plugin Changelog

## version 1.5.2 2021-01-05
* Fix compatibility with Jenkins core > 2.266

## version 1.5.1 2020-10-26
* Update maintainer

## version 1.5.0 2020-05-13
* Allow configuring path to java
* Fix deadlock bug [JENKINS-54776](https://issues.jenkins-ci.org/browse/JENKINS-54776)

## version 1.4.1 2020-03-31
* Fix agents getting deleted on reboot when using JCasC 

## version 1.4.0 2020-02-04
* Add termination script support
* Add retry logic for connecting agents with SSH
* Use GitHub as a source of plugin documentation

## version 1.3.0 2019-11-22
* Add descriptor visibility filter
* Support OS ephemeral disk

## version 1.2.2 2019-09-18
* Revert images to previous API version

## version 1.2.1 2019-09-12
* Fix invalid template error for custom image

## version 1.2.0 2019-09-04
* Enable adding custom tags for created resources
* Support purchase plan for custom image
* Fix possible NullPointer exception for template
* Bump arm template version

## version 1.1.1 2019-07-02
* Fix UAMI for reference image id template

## version 1.1.0 2019-06-18
* Add more location options like UK
* Add support for user assigned managed identity

## version 1.0.1 2019-05-22
* Fix deserialize losing configuration issue

## version 1.0.0 2019-05-22

* Bump Jenkins baseline to 2.60.3
* Add IMDS credential type
* Support configuration as code
* Support incrementalified plugin
* Add management tags for plugin created resource group and storage account
* Fix not support different templates in different regions

## Version 0.9.0 2019-03-11

* Support Image Gallery as an option for agents
* Fix configuration with error pages [JENKINS-55935](https://issues.jenkins-ci.org/browse/JENKINS-55935)

## Version 0.8.1 2019-03-04

* Fix multiple security issues

## Version 0.8.0 2019-01-28
* Add Availability Set support [JENKINS-40635](https://issues.jenkins-ci.org/browse/JENKINS-40635)
* Remove periodic jobs' log from Jenkins default log level [JENKINS-51282](https://issues.jenkins-ci.org/browse/JENKINS-51282)
* Specify OS disk size for agents [JENKINS-51528](https://issues.jenkins-ci.org/browse/JENKINS-51528)
* Fix customer image version fixed as latest issue

## Version 0.7.5 2018-11-28
* Fix storage account name already exists issue [JENKINS-54885](https://issues.jenkins-ci.org/browse/JENKINS-54885)
* Use https when communicating with storage account [JENKINS-52967](https://issues.jenkins-ci.org/browse/JENKINS-52967)

## Version 0.7.4 2018-10-17
* Support MSI for agents [JENKINS-53945](https://issues.jenkins-ci.org/browse/JENKINS-53945)

## Version 0.7.3 2018-08-06
* Fixed Jackson version conflicts caused by Azure client runtime lib updates [JENKINS-52838](https://issues.jenkins-ci.org/browse/JENKINS-52838)

## Version 0.7.2.1 2018-08-01
* Fixed an issue that built-in images may fail to provision

## Version 0.7.2 2018-07-25
* Fix missing plan information for some images in reference image configuration [JENKINS-52407](https://issues.jenkins-ci.org/browse/JENKINS-52407)

## Version 0.7.1, 2018-05-23
* Update Provision retry strategy in order to prevent hitting Azure request rate limit 
* Update validate logic to avoid bad template by accidentally issue
* Change the way of generating name of VMs to resolve the same name conflict
* Remove sleep while shutting down VMs in OnceRetentionStrategy

## Version 0.7.0, 2018-04-09
* Support custom managed disk [JENKINS-48076](https://issues.jenkins-ci.org/browse/JENKINS-48076)
* Add more checks in Template Name, Admin/Password [JENKINS-49150](https://issues.jenkins-ci.org/browse/JENKINS-49150)
* Add sync logic to prevent issues when using only shutdown [JENKINS-49021](https://issues.jenkins-ci.org/browse/JENKINS-49021)
* Clean unused warning logs [JENKINS-48901](https://issues.jenkins-ci.org/browse/JENKINS-48901)
* Add Cloud Statistics cleaner [JENKINS-48345](https://issues.jenkins-ci.org/browse/JENKINS-48345)
* Add blank choice for storage account to reduce UI issues related to existing storage account [JENKINS-48253](https://issues.jenkins-ci.org/browse/JENKINS-48253)
* Check whether reuse existing storage account [JENKINS-47923](https://issues.jenkins-ci.org/browse/JENKINS-47923)
* Show detail error message when the template cannot be deployed. [JENKINS-46337](https://issues.jenkins-ci.org/browse/JENKINS-46337)
* Resolve vm delete issue if related cloud have been deleted. [JENKINS-49473](https://issues.jenkins-ci.org/browse/JENKINS-49473)

## Version 0.6.3, 2018-03-08
* Fix SSH initialization failed in Windows Server 2012

## Version 0.6.2, 2018-02-24
* Use Tls1.2 to fix the bug in SSH initialization
* Support Windows Server 1709 as SSH slave

## Version 0.6.1, 2018-02-09
* Fix location verification on non-global clouds

## Version 0.6.0, 2018-01-02
* Use deallocation instead of powerOff when configured shutdown only
* Add built-in windows image with docker 
* Redesign verification task to improve performance
* Fix launching issues when using performance limited vms

## Version 0.5.0, 2017-11-29
* Add support for the Managed Service Identity (MSI) as credential
* Clean init scripts after the deployment
* Fix some minor bugs

## Version 0.4.8, 2017-11-07
* Add Cloud and Template builder with fluent interface
* Add pool retention strategy and once retention strategy
* Fix bugs and improve performance
* Maven version in built-in image update to 3.5.2
* Add Third Party Notice

## Version 0.4.7.1, 2017-08-10
* Fixed an issue that built-in init script cannot run correctly under Linux.

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
