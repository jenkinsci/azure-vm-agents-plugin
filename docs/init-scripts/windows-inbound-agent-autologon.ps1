# Sample Auto-logon script for "Enable auto-logon (Windows Only)".
# The plugin runs this script at first logon in the admin user's desktop session and passes the
# Jenkins URL, agent name and secret as arguments. Java must already be installed on the image.
# The script registers a scheduled task so the agent starts again in the desktop session after a reboot.
$jenkinsserverurl = $args[0]
$vmname = $args[1]
$secret = $args[2]

$destSource = "C:\agent.jar"
$launcher = "$env:LOCALAPPDATA\jenkins-agent.ps1"

# Write a launcher script that downloads agent.jar and keeps the agent connected
@"
while (`$true) {
  try {
    (New-Object System.Net.WebClient).DownloadFile("${jenkinsserverurl}jnlpJars/agent.jar", "$destSource")
    & java -jar "$destSource" -url "$jenkinsserverurl" -secret "$secret" -name "$vmname" -webSocket -workDir "C:\jenkins"
  }
  catch [System.Exception] {
    Write-Output `$_.Exception.ToString()
  }
  Start-Sleep 10
}
"@ | Set-Content -Path $launcher

# Start the launcher at every logon of this user, in their desktop session
$user = "$env:USERDOMAIN\$env:USERNAME"
$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Minimized -File `"$launcher`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries
Register-ScheduledTask -TaskName "Jenkins Agent" -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force

# Start the agent now for this first logon
Start-ScheduledTask -TaskName "Jenkins Agent"
