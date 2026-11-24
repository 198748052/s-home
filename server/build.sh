#!/usr/bin/env bash
# 交叉编译给 Linux 服务器用的静态二进制（服务器上不用装 Go）。
# 在 Windows 上用 Git Bash / WSL 跑，或在装有 Go 的机器上跑。
#
# 版本号默认取 git 最近的 tag（如 v3.3.0），也可显式传入：
#   VERSION=v3.3.0 ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${VERSION:-$(git describe --tags --always --dirty 2>/dev/null || echo dev)}"
echo "version: $VERSION"

for target in amd64 arm64; do
    echo "building linux/$target ..."
    GOOS=linux GOARCH="$target" CGO_ENABLED=0 go build \
        -trimpath -ldflags "-s -w -X main.version=$VERSION" \
        -o "tianqi-config-linux-$target" .
done

ls -lh tianqi-config-linux-*
echo
echo "上传：scp tianqi-config-linux-amd64 root@server:/srv/tianqi/tianqi-config"
