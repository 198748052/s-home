# 天气（s-home）

个人用的 Android 天气 App，首页兼做智能家居入口。配套一个只负责下发接口 Key 的 Go 云端服务。

- **App**：农历大字 + 实时天气 + 逐小时/每日预报，点区域语音播报
- **服务端**：单二进制 + 网页管理，只下发 4 个 Key，接口地址和字段解析全部写死在 App 里

## 功能

| 模块 | 说明 |
|---|---|
| 首页 | 农历大字（含节气/宜忌）、实时天气、逐小时预报、每日预报卡片、下拉刷新 |
| 语音播报 | 点农历卡片念农历、点逐小时区域念今天、点每日预报念明天；小米 MiMo TTS，4 种音色、3 档语速；没填 Key 或合成失败自动退回系统 TTS 并 Toast 说明 |
| 定位 | 系统定位取经纬度，失败回退上次定位位置；也支持城市名查询（仅限中国） |
| 设置页 | 天气/万年历/语音 Key、云端 Key 地址、音色与语速、试听、接口自检 |
| 云端 Key 下发 | 每次打开 App 自动回源拉取一次；缓存永不过期，拉不到继续用上次的 Key；服务端按设备信息统计对接数量 |
| 图标 | 和风天气官方图标字体（`qweather-icons.ttf`），随包内置 |

## 目录结构

```
s-home/
├── app/                          Android App（Java）
│   └── src/
│       ├── main/java/com/local/tianqi/
│       │   ├── MainActivity.java        首页：农历 + 天气 + 语音播报
│       │   ├── SettingsActivity.java    设置页
│       │   ├── WeatherService.java      和风天气接口与解析
│       │   ├── LunarService.java        万年历接口与解析
│       │   ├── TtsClient.java           小米 MiMo TTS 协议
│       │   ├── VoiceAnnouncer.java      合成 / 缓存 / 播放 / 系统语音兜底
│       │   ├── KeysStore.java           云端 Key 拉取、缓存、ETag
│       │   ├── CloudKeys.java           拉取结果模型
│       │   ├── DeviceInfo.java          上报给服务端的设备 ID / 名称 / 版本
│       │   ├── ConfigDefaults.java      写死的接口地址与协议参数
│       │   └── ...                      定位、时间/文案拼接、图标映射、下拉刷新等
│       ├── main/res/                    布局、颜色、字符串、图标字体
│       └── test/java/com/local/tianqi/  Robolectric 单元测试（97 个）
├── server/                       Go 云端 Key 服务（零第三方依赖）
│   ├── config-server.go          HTTP 服务本体
│   ├── devices.go                对接设备统计与持久化
│   ├── admin.html                管理页（编译进二进制）
│   ├── config.default.json       内置默认配置
│   ├── install.sh.in             一键安装脚本模板
│   ├── build.sh / build.ps1      交叉编译 linux/amd64 + arm64
│   ├── make-installer.ps1        打包自带二进制的安装脚本
│   ├── DEPLOY.md                 部署与排错
│   └── README.md                 接口清单与开发说明
├── gradle/                       Gradle Wrapper 8.7
├── build.gradle                  根构建脚本（AGP 8.5.2）
└── settings.gradle               rootProject.name = "TianQi"
```

## 数据来源

| 数据 | 接口 |
|---|---|
| 农历 / 节气 / 宜忌 | 聚合数据 `v.juhe.cn/calendar/day` |
| 实时、逐小时、每日天气 | 和风天气 v1（`/weather/v1/current|hourly|daily`） |
| 语音合成 | 小米 MiMo TTS（`mimo-v2.5-tts`，非流式 wav） |

Key 可以在设置页手填，也可以由 `server/` 下发。

## 构建与测试

环境：JDK 17、Android SDK（`local.properties` 指向 SDK 目录，不入库）。

```bash
# 编译 Debug APK
./gradlew assembleDebug

# 跑单元测试（Robolectric，97 个用例）
./gradlew testDebugUnitTest
```

Windows 下用 `gradlew.bat`。

| 项 | 值 |
|---|---|
| applicationId | `com.local.tianqi` |
| minSdk / targetSdk / compileSdk | 24 / 34 / 34 |
| versionCode / versionName | 27 / 3.4 |
| Gradle / AGP | 8.7 / 8.5.2 |
| 运行时第三方依赖 | 无（仅 JUnit + Robolectric 用于测试） |

## 云端 Key 服务

服务端只做一件事：把 4 个 Key 发给 App，并记录对接设备。接口地址和字段解析写死在 App 的
`ConfigDefaults` / `WeatherService` / `LunarService` / `TtsClient` 里，服务端没有配置下发、
没有字段映射、没有厂商适配。

```bash
# 编译（交叉编译 linux/amd64 + arm64）
cd server && ./build.sh

# 本地跑
go run . -config config.json -data /tmp/tianqi-dev -port 8787

# 测试
go vet ./... && go test ./...
```

部署到服务器后打开 `http://服务器IP:8787/admin`：先设管理员口令，再填四个 Key。
完整步骤见 [server/DEPLOY.md](server/DEPLOY.md)，接口清单见 [server/README.md](server/README.md)。

## App 侧配置

默认从 `app/src/main/java/com/local/tianqi/ConfigDefaults.java` 读取：

```java
static final String KEYS_URL = "http://home.topsygf.cn";  // 打开 App 自动拉取
```

App 每次打开都会自动请求一次 `/api/keys`：拉取成功就写进本地 Key，失败就继续用上次缓存的 Key，不需要手动点击同步。请求会带上设备 ID、名称和 App 版本，服务端管理页可以看到有多少台设备对接过。

## 设计取舍

- **接口写死**：换天气/语音厂商要改 App 并发版，换来的是服务端极简、出错面小
- **Key 缓存永不过期**：拉不到就用旧的，避免云端不可用导致 App 打不开数据
- **明文 HTTP 允许**（`usesCleartextTraffic`）：自建服务常见 `http://IP:8787`
- **签名用 debug 配置**：个人自用，不做正式发布签名

## 说明

个人自用项目，未附开源许可证。
