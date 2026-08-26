# Cross-compile the viewer for the Linux container. The SPA is embedded by go:embed, so
# there is no frontend build step - the whole app is one static binary.
# Usage:  pwsh scripts/build.ps1 [-Version v1.0.0] [-Arch amd64]
param(
    [string]$Version = "dev",
    [string]$Arch = "amd64"
)
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot

$commit = (& git -C $root rev-parse --short HEAD 2>$null)
if (-not $commit) { $commit = "none" }
$ldflags = "-s -w -X main.buildVersion=$Version -X main.buildCommit=$commit"

$saved = @{ GOOS = $env:GOOS; GOARCH = $env:GOARCH; CGO_ENABLED = $env:CGO_ENABLED }
try {
    $env:GOOS = "linux"; $env:GOARCH = $Arch; $env:CGO_ENABLED = "0"
    New-Item -ItemType Directory -Force "$root/dist" | Out-Null
    & go build -trimpath -ldflags $ldflags -o "$root/dist/fct-viewer" $root
    if ($LASTEXITCODE -ne 0) { throw "go build failed" }
} finally {
    # Leave the shell as we found it, or a later native `go test` silently cross-compiles.
    $env:GOOS = $saved.GOOS; $env:GOARCH = $saved.GOARCH; $env:CGO_ENABLED = $saved.CGO_ENABLED
}

$size = "{0:N1} MB" -f ((Get-Item "$root/dist/fct-viewer").Length / 1MB)
Write-Host "built $root/dist/fct-viewer  linux/$Arch  $Version / $commit  ($size)"
