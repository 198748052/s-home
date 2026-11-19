# 云端 Key 服务（Go 单二进制 + 网页管理）

**只做一件事：把接口 Key 发给 App。** 接口地址和字段解析都写死在 App 里，
所以服务端没有「配置下发」，只有四个 Key + 一个管理页。

- 单二进制（7 MB，静态链接），服务器上不用装 Go
- 网页里填 Key、看端口、改管理员口令、轮换 App 令牌、点一下测试语音 Key
- App 拉 Key 带 ETag，没变化就 304；缓存**永不过期**，拉不到继续用旧的
- 同步时 App 把 Key 直接写进设置页那几个输入框，所以「本地存的就是当前值」

## 目录内容

| 文件 | 作用 |
|---|---|
| `config-server.go` | 服务本体（`net/http`，零第三方依赖） |
| `admin.html` | 管理页（登录 + 填 Key + 服务设置），编译进二进制 |
| `config.default.json` | 内置默认（四个 Key 为空），首次启动时生成 `config.json` |
| `config-server_test.go` | 服务端测试（`go test ./...`） |
| `install.sh.in` / `make-installer.ps1` | 一键安装脚本模板与打包脚本 |
| `build.sh` / `build.ps1` | 交叉编译 linux/amd64 + arm64 |
| `dist/install-amd64.sh` | **一键安装脚本**（自带二进制） |
| `dist/tianqi-config-linux-amd64` | 编译好的二进制 |
| `dist/SHA256SUMS.txt` | 校验和 |
| `DEPLOY.md` | 部署与排错 |

> `dist/` 不进 git；改了源码用 `build.ps1` 重编，再 `make-installer.ps1` 重新打包。

## 接口

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/keys` | `X-Config-Token` | App 拉 Key，带 `ETag`，命中 `If-None-Match` 返回 304（`/api/config` 是旧版别名） |
| GET | `/api/health` | `X-Config-Token` | 版本号、端口 |
| GET | `/admin` | 页面自带登录 | 管理页 |
| GET | `/api/admin/state` | 可选 | 是否已设置口令 / 是否已登录 / 端口 / 域名 |
| POST | `/api/admin/setup` | 设置码（`X-Setup-Token`）或本机 | 首次设置管理员口令 |
| POST | `/api/admin/login` / `logout` | — | 登录 / 退出（会话 Cookie） |
| GET/PUT | `/api/admin/config` | 会话或 Basic | 读 / 保存 Key（version 自增、写 `.bak`） |
| POST | `/api/admin/settings` | 会话或 Basic | 端口、对外域名、管理员口令、App 令牌 |
| POST | `/api/admin/restart` | 会话或 Basic | 重启（退出进程，systemd 拉起） |
| POST | `/api/admin/test-tts` | 会话或 Basic | 用当前语音 Key 真打一次 MiMo，看通不通 |

Key 的数据结构（`config.json`）：

```json
{
  "schema": 2,
  "version": 1,
  "updatedAt": "",
  "calendar": { "key": "" },
  "weather": { "host": "", "key": "" },
  "tts": { "key": "" }
}
```

## 部署（一条命令）

```powershell
scp dist\install-amd64.sh root@服务器IP:/tmp/
```

```bash
sudo sh /tmp/install-amd64.sh          # 装到 /root/data/tianqi，端口 8787
```

浏览器打开 `http://服务器IP:8787/admin`：先设管理员口令，然后填四个 Key、点「保存 Key」。
完整步骤与排错见 **[DEPLOY.md](DEPLOY.md)**。

## App 侧要配的两个值

`app/src/main/java/com/local/tianqi/ConfigDefaults.java`：

```java
static final String KEYS_URL = "https://tq.example.com/api/keys";
static final String KEYS_TOKEN = "<管理页里的 App 访问令牌>";
```

也可以在 App 设置页「云端 Key」里直接填地址和令牌，不用改代码。

## 开发

```bash
go vet ./...
go test ./...      # 15 个用例：首次设置/登录/会话签名、Key 读写与版本自增、
                   # ETag/304、默认值补齐、语音 Key 自检（成功/失败/没填）
go run . -config config.json -data /tmp/tianqi-dev -port 8787
```

## 想换接口或换厂商怎么办

接口地址和字段解析在 App 里写死（`ConfigDefaults` + `WeatherService` / `LunarService` / `TtsClient`），
换厂商需要改 App 并发版——这是为了把服务端和客户端都做简单而做的取舍。
服务端只需要跟着改 Key。
