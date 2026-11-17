#!/usr/bin/env bash
# 交叉编译给 Linux 服务器用的静态二进制（服务器上不用装 Go）。
# 在 Windows 上用 Git Bash / WSL 跑，或在装有 Go 的机器上跑。
set -euo pipefail
cd "$(dirname "$0")"

for target in amd64 arm64; do
    echo "building linux/$target ..."
    GOOS=linux GOARCH="$target" CGO_ENABLED=0 go build \
        -trimpath -ldflags "-s -w" \
        -o "tianqi-config-linux-$target" .
done

ls -lh tianqi-config-linux-*
echo
echo "上传：scp tianqi-config-linux-amd64 root@server:/srv/tianqi/tianqi-config"
