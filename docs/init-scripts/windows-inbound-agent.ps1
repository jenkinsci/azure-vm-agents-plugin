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
$configArgs = "-url `"${jenkinsserverurl}`" -workDir C:\jenkins\workDir -webSocket -name `"${vmname}`""
if ($secret) {
    $configArgs += " -secret `"$secret`""
}
(Get-Content $configFile).replace('@JAVA@', $configExec) | Set-Content $configFile
(Get-Content $configFile).replace('@ARGS@', $configArgs) | Set-Content $configFile
(Get-Content $configFile).replace('@SLAVE_JAR_URL', $agentSource) | Set-Content $configFile

# Install the service
& $wrapperExec install
$svcName = ([xml](Get-Content $configFile)).service.id

# Optional: run the service as a domain or local account (Base64 args 4 and 5, passed by the plugin
# when "Run inbound agent service as" credentials are configured)
if ($args.Count -ge 5) {
    $svcUser = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($args[3]))
    $svcPass = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($args[4]))
    $startName = $svcUser

    # Local accounts: a bare 'user', '.\user' or 'COMPUTERNAME\user'
    $localName = $null
    if ($svcUser -match '^(?<domain>[^\\]+)\\(?<user>.+)$') {
        if ($Matches.domain -eq '.' -or $Matches.domain -ieq $env:COMPUTERNAME) { $localName = $Matches.user }
    } elseif ($svcUser -notmatch '@') {
        $localName = $svcUser
    }
    if ($localName) {
        if (-not (Get-LocalUser -Name $localName -ErrorAction SilentlyContinue)) {
            $securePass = ConvertTo-SecureString $svcPass -AsPlainText -Force
            New-LocalUser -Name $localName -Password $securePass -PasswordNeverExpires -AccountNeverExpires `
                -Description 'Jenkins inbound agent service account' | Out-Null
            Add-LocalGroupMember -SID 'S-1-5-32-545' -Member $localName  # BUILTIN\Users
        }
        # icacls and the SID lookup need COMPUTERNAME\user; the service needs .\user
        $svcUser = "$env:COMPUTERNAME\$localName"
        $startName = ".\$localName"
    }

    icacls C:\jenkins /grant "${svcUser}:(OI)(CI)M" | Out-Null

    # Optional arg 6: the template's configured agent workspace (Base64)
    if ($args.Count -ge 6) {
        $workspace = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($args[5]))
        New-Item -ItemType Directory -Force -Path $workspace | Out-Null
        icacls $workspace /grant "${svcUser}:(OI)(CI)M" | Out-Null
    }

    # Grant "Log on as a service" (Win32_Service.Change does not do this)
    $sid = (New-Object Security.Principal.NTAccount($svcUser)).Translate([Security.Principal.SecurityIdentifier]).Value
    $inf = "$env:TEMP\svcright.inf"
    secedit /export /cfg $inf /areas USER_RIGHTS | Out-Null
    $content = Get-Content $inf
    if ($content -match '^SeServiceLogonRight') {
        $content = $content -replace '^(SeServiceLogonRight = .*)$', "`$1,*$sid"
    } else {
        $content = $content -replace '^\[Privilege Rights\]$', "[Privilege Rights]`r`nSeServiceLogonRight = *$sid"
    }
    $content | Set-Content $inf -Encoding Unicode
    secedit /configure /db "$env:TEMP\svcright.sdb" /cfg $inf /areas USER_RIGHTS | Out-Null
    Remove-Item $inf, "$env:TEMP\svcright.sdb" -ErrorAction SilentlyContinue

    $svc = Get-CimInstance Win32_Service -Filter "Name='$svcName'"
    $r = Invoke-CimMethod -InputObject $svc -MethodName Change -Arguments @{ StartName = $startName; StartPassword = $svcPass }
    if ($r.ReturnValue -ne 0) { throw "Failed to set service account: $($r.ReturnValue)" }
    sc.exe config $svcName start= delayed-auto | Out-Null
}

# This can fail before the domain-join reboot; delayed-auto starts the service afterwards
Start-Service $svcName -ErrorAction SilentlyContinue
