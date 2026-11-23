# Creates the Self Watch release signing key, then backs it up.
# keytool asks for the passwords itself; nothing is saved here.
$ErrorActionPreference = 'Stop'
$keytool = 'C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\keytool.exe'
$project = Split-Path $PSScriptRoot -Parent
$keyDir  = Join-Path $project 'keys'
$backup  = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'Keys\SelfWatch'
$jks     = Join-Path $keyDir 'selfwatch-release.jks'

if (Test-Path $jks) { Write-Host "Key already exists: $jks  (not overwriting)"; exit 1 }
New-Item -ItemType Directory -Force $keyDir, $backup | Out-Null

Write-Host "`nCreating Self Watch release key. Enter a strong password when asked." -ForegroundColor Cyan
& $keytool -genkeypair -v -keystore $jks -storetype PKCS12 -alias selfwatch `
  -keyalg RSA -keysize 4096 -validity 10950 -dname 'CN=Self Watch, O=Self Watch'
if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }

Copy-Item $jks $backup
@"
SELF WATCH RELEASE SIGNING KEY  (app.selfwatch)
Created: $(Get-Date -Format yyyy-MM-dd)

File:  selfwatch-release.jks  (PKCS12, RSA 4096, valid 30 years)
Alias: selfwatch
Password: store it in your password manager as "Self Watch release key".

Signs every Self Watch release (GitHub, Google Play upload key, F-Droid reproducible
builds). If it is lost, existing installs cannot be updated. Keep this copy off GitHub.
Working copy: $jks
"@ | Set-Content (Join-Path $backup 'README.txt')

Write-Host "`nDone." -ForegroundColor Green
Write-Host "Key:    $jks"
Write-Host "Backup: $backup"
& $keytool -list -v -keystore $jks -alias selfwatch
