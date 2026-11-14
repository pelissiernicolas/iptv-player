# Compile IPTV Player pour Windows et prépare l'archive distribuable
# (iptv-player.exe + libmpv-2.dll). Usage :
#   .\scripts\package.ps1             # architecture de ce PC
#   .\scripts\package.ps1 -Arch x64   # ou arm64
param([ValidateSet("x64", "arm64")] [string]$Arch = $(if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "arm64" } else { "x64" }))

$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

$target = if ($Arch -eq "arm64") { "aarch64-pc-windows-msvc" } else { "x86_64-pc-windows-msvc" }
$mpvArch = if ($Arch -eq "arm64") { "aarch64" } else { "x86_64" }

rustup target add $target | Out-Null
cargo build --release --locked --target $target
if ($LASTEXITCODE) { throw "échec de la compilation" }

# libmpv : builds publiques https://github.com/shinchiro/mpv-winbuild-cmake (téléchargée une fois).
$mpvDir = "third_party/mpv/$Arch"
if (-not (Test-Path "$mpvDir/libmpv-2.dll")) {
    New-Item -ItemType Directory -Force $mpvDir | Out-Null
    $release = Invoke-RestMethod "https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest"
    $asset = $release.assets | Where-Object { $_.name -match "^mpv-dev-$mpvArch-\d+-git-[0-9a-f]+\.7z$" } | Select-Object -First 1
    if (-not $asset) { throw "archive libmpv $mpvArch introuvable" }
    Write-Host "Téléchargement de $($asset.name)…"
    Invoke-WebRequest $asset.browser_download_url -OutFile "$mpvDir/mpv.7z"
    tar.exe -xf "$mpvDir/mpv.7z" -C $mpvDir libmpv-2.dll
    Remove-Item "$mpvDir/mpv.7z"
}

$version = (cargo metadata --no-deps --format-version 1 | ConvertFrom-Json).packages[0].version
$out = "target/package-$Arch"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out | Out-Null
Copy-Item "target/$target/release/iptv-player.exe", "$mpvDir/libmpv-2.dll" $out
$zip = "target/IPTV-Player-$version-windows-$Arch.zip"
Compress-Archive -Path "$out/*" -DestinationPath $zip -Force
Write-Host "Archive prête : $zip"
