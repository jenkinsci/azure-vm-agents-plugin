Set-ExecutionPolicy Unrestricted
$jenkinsServerUrl = $args[0]
$vmName = $args[1]
$secret = $args[2]

$jenkinsSlaveJarUrl = $jenkinsServerUrl + "jnlpJars/slave.jar"
$jnlpUrl=$jenkinsServerUrl + 'computer/' + $vmName + '/slave-agent.jnlp'

$baseDir = 'c:\azurecsdir'
$JDKUrl = 'http://azure.azulsystems.com/zulu/zulu1.7.0_51-7.3.0.4-win64.zip?jenkins'
$destinationJDKZipPath = $baseDir + '\zuluJDK.zip'
$destinationSlaveJarPath = $baseDir + '\slave.jar'
$javaExe = $baseDir + '\zulu1.7.0_51-7.3.0.4-win64\bin\java.exe'

# Function to get path of script file
function Get-ScriptPath
{
	return $MyInvocation.ScriptName;
}

# Checking if this is first time script is getting executed, if yes then downloading JDK
If(-not((Test-Path $destinationJDKZipPath)))
{
	md -Path $baseDir -Force
	$wc = New-Object System.Net.WebClient
	$wc.DownloadFile($JDKUrl, $destinationJDKZipPath)
	
	$shell_app = new-object -com shell.application
	$zip_file = $shell_app.namespace($destinationJDKZipPath)
	$javaInstallDir = $shell_app.namespace($baseDir)
	$javaInstallDir.Copyhere($zip_file.items())
	
	$wc = New-Object System.Net.WebClient
	$wc.DownloadFile($jenkinsSlaveJarUrl, $destinationSlaveJarPath)
	
	$scriptPath = Get-ScriptPath
	$content = 'powershell.exe -ExecutionPolicy Unrestricted -file' + ' '+ $scriptPath + ' '+ $jenkinsServerUrl + ' ' + $vmName + ' ' + $secret
	$commandFile = $baseDir + '\slaveagenttask.cmd'
	$content | Out-File $commandFile -Encoding ASCII -Append
	schtasks /create /tn "Jenkins slave agent" /ru "SYSTEM" /sc onstart /rl HIGHEST /delay 0000:30 /tr $commandFile /f
}

# Launching jenkins slave agent					  
$process = New-Object System.Diagnostics.Process;
$process.StartInfo.FileName = $javaExe;
If($secret)
{
	$process.StartInfo.Arguments = "-jar $destinationSlaveJarPath -secret $secret -jnlpUrl $jnlpUrl"
} 
else 
{
	$process.StartInfo.Arguments = "-jar $destinationSlaveJarPath -jnlpUrl $jnlpUrl"
}
$process.StartInfo.RedirectStandardError = $true;
$process.StartInfo.RedirectStandardOutput = $true;
$process.StartInfo.UseShellExecute = $false;
$process.StartInfo.CreateNoWindow = $true; 

$process.StartInfo;
$process.Start();
 
Write-Host 'Done Init Script.';
