# Install Maven
$source = "https://archive.apache.org/dist/maven/maven-3/3.5.2/binaries/apache-maven-3.5.2-bin.zip"
$destination = "C:\maven.zip"
$webClient = New-Object System.Net.WebClient
$webClient.DownloadFile($source, $destination)

$shell_app=new-object -com shell.application
$zip_file = $shell_app.namespace($destination)
mkdir 'C:\Program Files\apache-maven-3.5.2'
$destination = $shell_app.namespace('C:\Program Files')
$destination.Copyhere($zip_file.items(), 0x14)

[System.Environment]::SetEnvironmentVariable("PATH", $Env:Path + ";C:\Program Files\apache-maven-3.5.2\bin", "Machine")
$Env:Path += ";C:\Program Files\apache-maven-3.5.2\bin"