# 把 dist/ 里的 Linux 二进制打成一个自带安装逻辑的脚本：
#   powershell -ExecutionPolicy Bypass -File make-installer.ps1
# 产物：dist/install-amd64.sh、dist/install-arm64.sh
# 服务器上只需要：sudo sh install-amd64.sh --domain 你的域名
param(
    [string]$Arch = "amd64"
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$bin = Join-Path "dist" "tianqi-config-linux-$Arch"
if (-not (Test-Path $bin)) {
    throw "找不到 $bin，先编译：bash build.sh 或 powershell -File build.ps1"
}

$templatePath = Join-Path $PWD "install.sh.in"
# 必须显式按 UTF-8 读：Windows PowerShell 5.1 的 Get-Content 会按 ANSI 解码，中文会变乱码，
# 还会把紧跟中文的引号吃掉，生成出语法错误的脚本。
$template = [IO.File]::ReadAllText($templatePath, [Text.Encoding]::UTF8) -replace "`r`n", "`n"
if ($template -notmatch '__PAYLOAD_BELOW__') {
    throw "install.sh.in 里缺少 __PAYLOAD_BELOW__ 标记"
}

$bytes = [IO.File]::ReadAllBytes((Resolve-Path $bin))
$sha = (Get-FileHash $bin -Algorithm SHA256).Hash.ToLower()

# 每行 100 字符，方便文本编辑器打开；base64 -d 会忽略换行
$b64 = [Convert]::ToBase64String($bytes)
$lines = for ($i = 0; $i -lt $b64.Length; $i += 100) {
    $b64.Substring($i, [Math]::Min(100, $b64.Length - $i))
}

$script = $template.Replace('__ARCH__', $Arch).Replace('__SHA256__', $sha)
$script = $script + ($lines -join "`n") + "`n"

$out = Join-Path "dist" "install-$Arch.sh"
[IO.File]::WriteAllText((Join-Path $PWD $out), $script, (New-Object Text.UTF8Encoding($false)))

$sizeMb = [math]::Round((Get-Item $out).Length / 1MB, 1)
Write-Host "生成 $out（$sizeMb MB，二进制 sha256=$sha）"
