# Install Slaves jar and connect via JNLP
# Jenkins plugin will dynamically pass the server name and vm name.
# If your jenkins server is configured for security , make sure to edit command for how slave executes
$jenkinsserverurl = $args[0]
$vmname = $args[1]
$secret = $args[2]

# Downloading jenkins slaves jar
Write-Output "Downloading jenkins slave jar "
$slaveSource = $jenkinsserverurl + "jnlpJars/slave.jar"
$destSource = "C:\slave.jar"
$wc = New-Object System.Net.WebClient
$wc.DownloadFile($slaveSource, $destSource)

# execute agent
Write-Output "Executing agent process "
$java="java"
$jar="-jar"
$jnlpUrl="-jnlpUrl"
$secretFlag="-secret"
$serverURL=$jenkinsserverurl+"computer/" + $vmname + "/slave-agent.jnlp"
while ($true) {
  try {
    # Launch
    & $java -jar $destSource $secretFlag  $secret $jnlpUrl $serverURL -noReconnect
  }
  catch [System.Exception] {
    Write-Output $_.Exception.ToString()
  }
  Start-Sleep 10
}