Set-ExecutionPolicy Unrestricted
$jenkinsServerUrl = $args[0]
$vmName = $args[1]
$secret = $args[2]

$baseDir = 'C:\Jenkins'
mkdir $baseDir
# Download the JDK
$source = "http://download.oracle.com/otn-pub/java/jdk/7u79-b15/jdk-7u79-windows-x64.exe"
$destination = "$baseDir\jdk.exe"
$client = new-object System.Net.WebClient 
$cookie = "oraclelicense=accept-securebackup-cookie"
$client.Headers.Add([System.Net.HttpRequestHeader]::Cookie, $cookie) 
$client.downloadFile([string]$source, [string]$destination)

# Execute the unattended install
$jdkInstallDir=$baseDir + '\jdk\'
$jreInstallDir=$baseDir + '\jre\'
C:\Jenkins\jdk.exe /s INSTALLDIR=$jdkInstallDir /INSTALLDIRPUBJRE=$jdkInstallDir

$javaExe=$jdkInstallDir + '\bin\java.exe'
$jenkinsSlaveJarUrl = $jenkinsServerUrl + "jnlpJars/slave.jar"
$destinationSlaveJarPath = $baseDir + '\slave.jar'

# Download the jar file
$client = new-object System.Net.WebClient
$client.DownloadFile($jenkinsSlaveJarUrl, $destinationSlaveJarPath)

# Calculate the jnlpURL
$jnlpUrl = $jenkinsServerUrl + 'computer/' + $vmName + '/slave-agent.jnlp'

while ($true) {
    try {
        # Launch
        & $javaExe -jar $destinationSlaveJarPath -secret $secret -jnlpUrl $jnlpUrl -noReconnect
    }
    catch [System.Exception] {
        Write-Output $_.Exception.ToString()
    }
    sleep 10
}