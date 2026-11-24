# 部署到服务器

服务器：国内 Linux 云主机（Debian/Ubuntu/CentOS 都行）。
本机：Windows。**服务器上不用装 Go，本机也不用装任何东西。**

装完只暴露一个端口，域名、Key、登录口令全在网页里配，反代你自己做。

服务端只下发 **Key**（万年历 / 和风天气 / 语音合成），接口地址和解析都写死在 App 里。

App 侧的行为：每次打开先用本地 Key 直接请求数据，同时后台再拉一次 Key；
拉取失败就一直用旧的（**缓存永不过期**），只有 Key 真的变了才触发重新取数——所以拉 Key 不会拖慢首页。
拉到的 Key 会**直接写进设置页那几个输入框**，本地存的就是当前值。

---

## 1. 上传（x86_64 服务器用 amd64）

```powershell
scp D:\develops\deepseek\home\s-home-main\server\dist\install-amd64.sh root@服务器IP:/tmp/
```

> 先确认架构：`uname -m` → `x86_64` 用 `install-amd64.sh`，`aarch64` 用 `install-arm64.sh`。

## 2. 安装

```bash
sudo sh /tmp/install-amd64.sh
```

可选参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `--port 8787` | 8787 | 首次启动的端口（之后在网页里也能改） |
| `--dir /root/data/tianqi` | `/root/data/tianqi` | 安装目录 |

脚本做的事：解出二进制并校验 SHA-256 → 建目录 → 写默认 `config.json`（已存在不动）→
装 systemd（`User=root`，`/root` 下所以要 `ProtectHome=false`）→ 启动 → 自检管理页 →
打印访问地址。**可重复执行**，升级只换二进制。

## 3. 打开网页设置

浏览器访问 `http://服务器IP:8787/admin`：

1. 第一次进去 → 先填「设置码」再设管理员用户名和口令。设置码在启动日志里：

   ```bash
   sudo journalctl -u tianqi-config | grep 首次设置
   ```

   从服务器本机打开本页（或 `ssh -L 8787:127.0.0.1:8787 root@服务器IP` 走隧道）时，设置码可以留空。
   首次设置只接受「本机」或「带设置码」的请求，别人抢不走；设置完成后设置码自动失效。
2. 进去后可以：
   - 填和风天气 Host / Key、聚合数据 Key、语音合成 Key
   - 换语音厂商（MiMo / OpenAI 兼容 / 自定义接口）
   - 点「测试语音合成」确认 Key 通不通
   - 改端口（改完服务自动重启，用新端口重开本页）
   - 填对外域名（页面会显示 App 要用的 Key 接口地址）
   - 查看「设备管理」：有几台设备对接过、最近一次上报时间
   - 改管理员口令
3. 点「保存配置」→ 服务端把 `version` 加一，App 下次同步就能感知

## 4. App 侧地址

`app/src/main/java/com/local/tianqi/ConfigDefaults.java`：

```java
static final String KEYS_URL = "http://home.topsygf.cn";
```

App 每次打开会自动拉取一次；拉取失败时继续用上次缓存的 Key。服务端不再校验 App 令牌，
打开 App 后会在「设备管理」里出现这台设备。

```powershell
cd D:\develops\deepseek\home\s-home-main
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
.\gradlew.bat assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

装好后 App 设置页会显示「配置来源：云端 v1 · 同步于 …」。

## 5. 反代（你自己来，示例）

服务只监听 `0.0.0.0:8787`，前面挂什么由你定。nginx 示例：

```nginx
server {
    listen 443 ssl http2;
    server_name tq.example.com;
    # 证书省略，certbot 会补

    location /api/ {
        proxy_pass http://127.0.0.1:8787;
        proxy_set_header Host $host;
        proxy_read_timeout 60s;          # 测试语音合成要等厂商推理
    }

    # 管理页建议只允许自己的 IP
    location = /admin {
        allow 1.2.3.4;
        deny all;
        proxy_pass http://127.0.0.1:8787;
        proxy_set_header Host $host;
        proxy_read_timeout 60s;
    }

    location / { return 404; }
}
```

配好域名后，回到管理页把「对外地址」填成 `https://tq.example.com`，
页面就会显示 App 该用的 Key 接口地址。

---

# 在线更新（GitHub Releases）

构建在 GitHub Actions 的 runner 上完成，服务器不装 Go、不编译：

1. 本地打完 tag 推送：`git tag v3.3.0 && git push origin v3.3.0`。
2. `.github/workflows/release.yml` 自动交叉编译 `linux/amd64` + `linux/arm64`，
   注入版本号，生成 `SHA256SUMS.txt`，发布到 GitHub Releases。
3. 打开管理页「在线更新」，填更新仓库 `owner/repo`，保存后点「检查更新」。
4. 有新版本时点「立即更新」：下载同架构资产 → 校验 SHA-256 → 备份旧二进制 →
   原子替换 → 退出进程，systemd 按 `Restart=always` 拉起新版本。

注意：

- 更新源只认管理页里配置的仓库，接口仅管理员可用；缺少 `SHA256SUMS.txt`
  或校验不通过会直接拒绝，旧二进制不受影响。
- 国内云主机直连 GitHub 常超时。可在「GitHub API 地址」填镜像，或把发布产物
  同步到自己的对象存储/镜像站后使用对应 API 地址。
- 回滚：把安装目录里的 `tianqi-config.bak` 覆盖回 `tianqi-config`，再
  `sudo systemctl restart tianqi-config`。
- 也可以在管理页看当前版本；`/api/health` 返回 `serverVersion`。

---

# 日常操作

| 要做什么 | 怎么做 |
|---|---|
| 换 Key / 换厂商 / 改音色语速 | 管理页改完点「保存配置」 |
| 改端口 / 改域名 / 改口令 | 管理页「服务设置」→ 保存 |
| 立刻让 App 生效 | App 设置页点「立即同步配置」 |
| 升级服务端 | 管理页「在线更新」检查并更新；或重跑 `sudo sh /tmp/install-amd64.sh`（配置和口令不动） |
| 配置改坏了 | `/root/data/tianqi/config.json.bak` 是上一版 |
| 忘了管理口令 | 停服务，删 `/root/data/tianqi/server.json` 里的 `adminHash` 和 `adminSalt` 两行，重启后重新设置（**会保留端口**；重新设置同样需要日志里的设置码） |
| 看日志 | `sudo journalctl -u tianqi-config -f` |
| 重启服务 | `sudo systemctl restart tianqi-config` |

> 配置的默认值与 App 内置的一致（聚合万年历 + 和风天气 + MiMo）。
> 旧版本升级上来时，`config.json` 里缺的字段（比如新增的接口解析映射）会在读取时自动补齐，
> 你已有的 Key 和手填值不会被覆盖；在管理页点一次「保存配置」就会把补齐后的完整配置落盘。

数据都在 `/root/data/tianqi/`：

| 文件 | 内容 |
|---|---|
| `config.json` | 下发给 App 的配置（Key、厂商、音色…） |
| `config.json.bak` | 上一版配置 |
| `server.json` | 端口、对外域名、管理员口令哈希（权限 600） |
| `devices.json` | 对接设备列表（设备 ID、名称、版本、最近上报时间） |
| `tianqi-config` | 二进制 |

---

# 常见问题

| 现象 | 原因与处理 |
|---|---|
| 脚本报「二进制校验失败」 | 上传被截断，重新 scp |
| 脚本报「请换 install-xxx.sh」 | 架构不匹配，`uname -m` 看一眼 |
| 打开 `/admin` 打不开 | 云厂商安全组没放行端口；`sudo systemctl status tianqi-config` 看服务 |
| 打开 `/admin` 又要求设置口令 | `server.json` 里 `adminHash` 为空（删过或没设置成功），重新设置即可；这次同样要填启动日志里的设置码 |
| 管理页一直转圈 / 加载失败 | 看 `journalctl -u tianqi-config -n 30`；多半是 `config.json` 不是合法 JSON |
| 改端口后页面打不开 | 服务已用新端口重启，用 `http://IP:新端口/admin` 打开 |
| 合成自检 401 | 语音厂商 Key 错，或 `tts.provider` 与接口不匹配 |
| 合成自检 200 但没 audioBytes | `audioField` 路径不对（MiMo 用 `choices.0.message.audio.data`；OpenAI 兼容接口留空） |

---

# 安全清单

- [ ] 装完立刻打开 `/admin` 设置口令（首次设置要日志里的设置码，别人抢不走，但别拖）
- [ ] 口令够长；能用反代限 IP 就限 `/admin`
- [ ] 上 HTTPS（反代里配证书），否则口令和 Key 在链路上是明文
- [ ] `server.json` 权限 600（脚本已设）
- [ ] 给和风天气 / 语音厂商的 Key 开用量告警
