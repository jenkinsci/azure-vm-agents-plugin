# Install Agent jar and connect as an Inbound agent
# Jenkins plugin will dynamically pass the server name and vm name.
# If your jenkins server is configured for security , make sure to edit command for how agent executes
$jenkinsserverurl = $args[0]
$vmname = $args[1]
$secret = $args[2]

# Downloading jenkins agent jar
Write-Output "Downloading jenkins agent jar "
$slaveSource = $jenkinsserverurl + "jnlpJars/agent.jar"
$destSource = "C:\agent.jar"
$wc = New-Object System.Net.WebClient
$wc.DownloadFile($slaveSource, $destSource)

# execute agent
Write-Output "Executing agent process "
$java="java"
$jar="-jar"
$jnlpUrl="-jnlpUrl"
$secretFlag="-secret"
$serverURL=$jenkinsserverurl+"computer/" + $vmname + '/jenkins-agent.jnlp'
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
