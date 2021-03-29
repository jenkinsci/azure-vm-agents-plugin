# Install Git

$git_url = "https://api.github.com/repos/git-for-windows/git/releases/latest"

$asset = Invoke-RestMethod -Method Get -Uri $git_url | % assets | where name -like "*64-bit.exe"
$destination = "C:\$($asset.name)"
Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $destination

$proc = Start-Process -FilePath $destination -ArgumentList "/VERYSILENT" -Wait -PassThru
$proc.WaitForExit()
$Env:Path += ";C:\Program Files\Git\cmd;C:\Program Files\Git\usr\bin"
#Disable git credential manager, get more details in https://support.cloudbees.com/hc/en-us/articles/221046888-Build-Hang-or-Fail-with-Git-for-Windows
git config --system --unset credential.helper

git --version
