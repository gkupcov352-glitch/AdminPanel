# Build AdminPanel for every 1.21.x Minecraft version.
# Each jar is compiled against that version's own spigot-api, so any future
# API removal in a specific version is caught at compile time.
#
# Usage:  powershell -ExecutionPolicy Bypass -File build-all.ps1

$ErrorActionPreference = 'Continue'
$root = 'C:\Users\gkupc\OneDrive\Documents\Проекты\Плагины\Admin panel'
$dist = Join-Path $root 'dist'
$log  = Join-Path $root 'build-all.log'

New-Item -ItemType Directory -Force $dist | Out-Null
"BUILD START $(Get-Date -Format o)" | Out-File $log -Encoding utf8

# 1.21.2 was never released as a Paper build (Mojang shipped 1.21.3 immediately
# after it), but the Spigot API for it exists, so it is built anyway.
$versions = @('1.21','1.21.1','1.21.2','1.21.3','1.21.4','1.21.5',
              '1.21.6','1.21.7','1.21.8','1.21.9','1.21.10','1.21.11')

Set-Location $root
$ok = @(); $bad = @()

foreach ($v in $versions) {
    $jar     = "AdminPanel-1.4-$v.jar"
    $srcJar  = Join-Path $root "target\$jar"
    if (Test-Path $srcJar) { Remove-Item $srcJar -Force }

    $out = & mvn -B clean package "-Dmc.version=$v" '-Dmc.apiversion=1.21' 2>&1 | Out-String

    if ($LASTEXITCODE -eq 0 -and (Test-Path $srcJar)) {
        Copy-Item $srcJar (Join-Path $dist $jar) -Force
        $size = [math]::Round((Get-Item $srcJar).Length / 1KB, 1)
        "OK    $v  ($jar, ${size} KB)" | Out-File $log -Append -Encoding utf8
        $ok += $v
    } else {
        "FAIL  $v" | Out-File $log -Append -Encoding utf8
        ($out -split "`n" | Select-String -Pattern 'ERROR|BUILD FAILURE' |
            Select-Object -First 15) | Out-File $log -Append -Encoding utf8
        $bad += $v
    }
}

"SUMMARY: ok=$($ok.Count) [$($ok -join ',')] fail=$($bad.Count) [$($bad -join ',')]" |
    Out-File $log -Append -Encoding utf8
'DONE' | Out-File $log -Append -Encoding utf8
