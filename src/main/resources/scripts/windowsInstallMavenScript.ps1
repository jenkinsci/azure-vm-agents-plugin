# Install Maven

choco install -y maven

$env:M2_HOME = [System.Environment]::GetEnvironmentVariable("M2_HOME","Machine")
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

mvn --version
