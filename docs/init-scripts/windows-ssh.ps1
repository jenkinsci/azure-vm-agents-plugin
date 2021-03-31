Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072; iex ((New-Object System.Net.WebClient).DownloadString('https://chocolatey.org/install.ps1'))

choco install -y adoptopenjdk11

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
