Set-ExecutionPolicy Unrestricted

$source = "https://github.com/PowerShell/Win32-OpenSSH/releases/download/v0.0.16.0/OpenSSH-Win64.zip"
$destination = "C:\OpenSSH-Win64.zip"
$webClient = New-Object System.Net.WebClient
$webClient.DownloadFile($source, $destination)

$shell_app=new-object -com shell.application
$zip_file = $shell_app.namespace($destination)
$dir='C:\Program Files\OpenSSH-Win64'
mkdir $dir
$destination = $shell_app.namespace('C:\Program Files')
$destination.Copyhere($zip_file.items(), 0x14)
[System.Environment]::SetEnvironmentVariable("PATH", $Env:Path + ";${dir}", "Machine")

Set-Location $dir

.\install-sshd.ps1
.\ssh-keygen.exe -A
.\FixHostFilePermissions.ps1 -Confirm:$false

Start-Service ssh-agent
Start-Service sshd

New-NetFirewallRule -Protocol TCP -LocalPort 22 -Direction Inbound -Action Allow -DisplayName SSH
Set-Service sshd -StartupType Automatic
Set-Service ssh-agent -StartupType Automatic
