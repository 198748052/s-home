# 在 Windows 上交叉编译给 Linux 服务器用的静态二进制（服务器不用装 Go）。
#   powershell -ExecutionPolicy Bypass -File build.ps1
#
# 需要本机装 Go（https://golang.google.cn/dl/ 下载 windows-amd64 的 zip 解压即可）。
param(
    [string]$GoBin = "go"
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$env:CGO_ENABLED = "0"
foreach ($arch in @("amd64", "arm64")) {
    $env:GOOS = "linux"
    $env:GOARCH = $arch
    $out = "dist/tianqi-config-linux-$arch"
    New-Item -ItemType Directory -Force -Path dist | Out-Null
    Write-Host "building linux/$arch ..."
    & $GoBin build -trimpath -ldflags "-s -w" -o $out .
    if ($LASTEXITCODE -ne 0) { throw "build failed for linux/$arch" }
}

Remove-Item Env:\GOOS, Env:\GOARCH, Env:\CGO_ENABLED -ErrorAction SilentlyContinue
Get-ChildItem dist | Select-Object Name, @{n = "MB"; e = { [math]::Round($_.Length / 1MB, 1) } }
Write-Host ""
Write-Host "上传：scp dist/tianqi-config-linux-amd64 root@你的服务器:/srv/tianqi/tianqi-config"
