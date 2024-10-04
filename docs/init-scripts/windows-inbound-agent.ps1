# choco 2.x requires .net 4.8 which is not installed by default and requires a reboot
$env:chocolateyVersion = '1.4.0'
Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072; iex ((New-Object System.Net.WebClient).DownloadString('https://chocolatey.org/install.ps1'))

choco install -y temurin17

$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

java -version

# Install Maven

choco install -y maven

$env:M2_HOME = [System.Environment]::GetEnvironmentVariable("M2_HOME","Machine")
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

mvn --version

# Install Git

choco install -y git

$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

#Disable git credential manager, get more details in https://support.cloudbees.com/hc/en-us/articles/221046888-Build-Hang-or-Fail-with-Git-for-Windows
git config --system --unset credential.helper

git --version

# Setup inbound agent

$jenkinsserverurl = $args[0]
$vmname = $args[1]
$secret = $args[2]

# Download the service wrapper
$wrapperExec = "c:\jenkins\jenkins-agent.exe"
$configFile = "c:\jenkins\jenkins-agent.xml"
$agentSource = $jenkinsserverurl + "jnlpJars/agent.jar"

mkdir C:\jenkins
$wc = New-Object System.Net.WebClient
$wc.DownloadFile("https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW.NET461.exe", $wrapperExec)
$wc.DownloadFile("https://raw.githubusercontent.com/Azure/jenkins/master/agents_scripts/jenkins-slave.exe.config", "c:\jenkins\jenkins-agent.exe.config")
$wc.DownloadFile("https://raw.githubusercontent.com/Azure/jenkins/master/agents_scripts/jenkins-slave.xml", $configFile)

# Prepare config
Write-Output "Executing agent process "
$configExec = "java"
$configArgs = "-jnlpUrl `"${jenkinsserverurl}/computer/${vmname}/jenkins-agent.jnlp`" -workDir C:\jenkins\workDir"
if ($secret) {
    $configArgs += " -secret `"$secret`""
}
(Get-Content $configFile).replace('@JAVA@', $configExec) | Set-Content $configFile
(Get-Content $configFile).replace('@ARGS@', $configArgs) | Set-Content $configFile
(Get-Content $configFile).replace('@SLAVE_JAR_URL', $agentSource) | Set-Content $configFile

# Install the service
& $wrapperExec install
& $wrapperExec start
