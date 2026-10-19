<div align="center">

<img src=".github/assets/icon.png" alt="KukeChat" width="120">

# KukeChat Android

**为创作社区而做的原生安卓客户端**

KukeChat 的安卓版，用 Kotlin 与 Jetpack Compose 从头写成，和其他客户端连同一个后端。

<p align="center">
<img src=".github/assets/icons/smartphone.svg" width="16" height="16" align="absmiddle">&nbsp;原生 Kotlin + Compose&emsp;&emsp;
<img src=".github/assets/icons/radio.svg" width="16" height="16" align="absmiddle">&nbsp;后台常驻实时连接&emsp;&emsp;
<img src=".github/assets/icons/link.svg" width="16" height="16" align="absmiddle">&nbsp;与桌面端账号互通&emsp;&emsp;
<img src=".github/assets/icons/download.svg" width="16" height="16" align="absmiddle">&nbsp;应用内更新
</p>

[![License](https://img.shields.io/badge/License-MIT-111111?style=flat-square&logo=opensourceinitiative&logoColor=white)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-111111?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.12-111111?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Material 3](https://img.shields.io/badge/Material-3-111111?style=flat-square&logo=materialdesign&logoColor=white)](https://m3.material.io)
[![SDK](https://img.shields.io/badge/minSdk%20%2F%20targetSdk-26%20%2F%2035-111111?style=flat-square&logo=android&logoColor=white)](app/build.gradle.kts)

**简体中文** · [English](README.en.md)

[关于](#关于) · [功能](#功能) · [技术栈](#技术栈) · [构建](#构建) · [项目结构](#项目结构) · [下载](#下载) · [相关仓库](#相关仓库) · [贡献](#贡献)

</div>

---

## 关于

KukeChat 是给 Scratch / CCW 创作者用的聊天应用。社区扩展和桌面端来自 [KukeChat-Client](https://github.com/kukemc/KukeChat-Client)，这个仓库是手机上的那一端。

之所以单独做一个原生应用，是因为聊天软件在手机上要做的事远不止画界面：常驻后台收消息、系统通知、按住说话、从别的应用分享进来、桌面图标长按直达会话、预测性返回。这些用原生写更稳，也更省电。

它和其他客户端连的是同一个 [KukeChat-Server](https://github.com/kukemc/KukeChat-Server)，账号、会话和消息完全互通。接口与交互尽量和电脑版保持一致，图标也由同一套 lucide 线稿生成。

> [!NOTE]
> 代码里不写任何服务器地址。后端地址在构建时从 `local.properties` 读取，缺了构建会直接失败，见[构建](#构建)。

## 功能

下面列的都是代码里已经实现的功能。

| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;分&#8288;类&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; | 内容 |
| --- | --- |
| <img src=".github/assets/icons/message-circle.svg" width="16" height="16" align="absmiddle">&nbsp;聊天 | 私聊与群聊；文字、图片（一次选多张，发送前预览）、文件；引用回复（滑动即回复）、转发、表情回应（长按查看谁回应了）、撤回；消息发出即上屏，失败可点击重发；CCW 作品和 B 站视频链接显示为卡片；投票；会话内搜索，跳转到某条消息时居中高亮 |
| <img src=".github/assets/icons/mic.svg" width="16" height="16" align="absmiddle">&nbsp;语音 | 按住说话；播放时实时显示进度，可拖动；长按「转文字」 |
| <img src=".github/assets/icons/smile.svg" width="16" height="16" align="absmiddle">&nbsp;表情 | 收藏表情，支持 GIF、动态 WebP、APNG 动图；表情管理页可拖动排序、批量删除、移到最前；群表情（管理员可排序、重命名、批量添加） |
| <img src=".github/assets/icons/at-sign.svg" width="16" height="16" align="absmiddle">&nbsp;提醒 | 「有人@我」胶囊，点一下逐条跳到 @ 我和 @ 全体成员的消息；聊天里有未领红包时提示并直达 |
| <img src=".github/assets/icons/gift.svg" width="16" height="16" align="absmiddle">&nbsp;红包与礼物 | 普通红包、接龙红包、专属红包，红包记录；送礼物和装扮；商店（气泡皮肤、头像框） |
| <img src=".github/assets/icons/users.svg" width="16" height="16" align="absmiddle">&nbsp;群 | 群资料与管理；群公告与确认统计；群文件（文件夹、搜索、下载进度、视频在线播放）；群精华、群签到、群任务；群升级 Pro / Pro+ / MAX |
| <img src=".github/assets/icons/bookmark.svg" width="16" height="16" align="absmiddle">&nbsp;收藏 | 跨会话收藏消息，按全部 / 私聊 / 群聊筛选 |
| <img src=".github/assets/icons/newspaper.svg" width="16" height="16" align="absmiddle">&nbsp;社区 | 动态（好友动态、广场、话题、发布）；社区主页；组队中心；机器人广场；账号等级与排行榜；好友亲密度与徽章、火花；生日庆祝 |
| <img src=".github/assets/icons/shield-check.svg" width="16" height="16" align="absmiddle">&nbsp;风纪 | 风纪委员工作台，只展示服务端给出的匿名案件快照 |
| <img src=".github/assets/icons/smartphone.svg" width="16" height="16" align="absmiddle">&nbsp;系统集成 | 常驻前台服务保持实时连接，被系统停掉后由 WorkManager 补拉；从其他应用分享文字、图片、视频、文件进来，支持直接分享快捷方式；邀请深链接 `kukechat://invite/<token>`；应用内更新，后端发版推送后自动检查，下载完校验 sha256；预测性返回；底栏与功能入口可自定义排序 |

## 技术栈

<p align="center">
<img src="https://skillicons.dev/icons?i=kotlin,java,gradle,androidstudio&theme=dark" height="40" alt="Kotlin, Java, Gradle, Android Studio">
</p>

| 层 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.1.0，JVM 17 |
| 界面 | Jetpack Compose（BOM 2024.12.01）、Material 3 |
| 导航 | Navigation Compose 2.8，`@Serializable` 类型安全路由 |
| 网络 | OkHttp 4.12（REST 与 WebSocket） |
| 序列化 | kotlinx.serialization 1.7 |
| 异步 | Kotlin Coroutines 1.9 |
| 图片 | Coil 2.7（含 GIF 解码） |
| 媒体 | Media3 ExoPlayer 1.5（语音、群文件视频） |
| 存储 | DataStore Preferences，加上消息与页面的本地缓存 |
| 后台 | 前台服务（`remoteMessaging`）+ WorkManager 2.10 |
| 视觉 | Haze 1.1（底栏毛玻璃） |
| 构建 | Android Gradle Plugin 8.7.3，Gradle Version Catalog |

依赖注入用手写的 `AppGraph` 容器，没有引入 Hilt。整个应用只有一条 WebSocket，由后台服务持有，界面只订阅事件。

## 构建

### 环境要求

- JDK 17
- Android SDK 35

### 配置

在仓库根目录新建 `local.properties`（已被 `.gitignore` 排除）。这些键也可以用同名 Gradle 属性 `-P<键>=<值>` 传入。

```properties
sdk.dir=<Android SDK 路径>
kukechat.apiBaseUrl=https://chat.example.com
kukechat.wsUrl=wss://chat.example.com/ws
# 可选：邀请链接的网页地址
kukechat.inviteBaseUrl=https://chat.example.com/invite
```

| 键 | 必填 | 说明 |
| --- | :---: | --- |
| `sdk.dir` | 是 | Android SDK 路径 |
| `kukechat.apiBaseUrl` | 是 | 后端根地址，带不带 `/api/v1` 都可以 |
| `kukechat.wsUrl` | 是 | 实时消息 WebSocket 地址 |
| `kukechat.inviteBaseUrl` | 否 | 与服务端 `INVITE_BASE_URL` 一致。配置后应用能直接打开网页邀请链接；不配时只识别 `kukechat://invite/<token>` |

### 编译

```bash
./gradlew :app:assembleDebug        # 开发版
./gradlew :app:assembleRelease      # 正式版
./gradlew :app:testDebugUnitTest    # 单元测试
```

开发版的包名带 `.dev` 后缀，版本名带 `-dev`，可以和正式版装在同一台手机上。需要别的后缀时用 `-Pkukechat.appIdSuffix=<后缀>`。

### 正式版签名

正式版读取一份你自己的 `keystore.properties`（包含 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`），用 `-Pkukechat.signing=<keystore.properties 路径>` 指定。找不到签名配置时，打出来的正式包不签名，无法直接安装。

```bash
./gradlew :app:assembleRelease -Pkukechat.signing=/path/to/keystore.properties
```

> [!WARNING]
> 同一个包名的每个版本都要用同一把密钥签名。换了密钥，系统会拒绝覆盖安装，已装用户也就收不到应用内更新。

## 项目结构

```text
app/src/main/java/ink/kuke/chat/
  MainActivity.kt      入口，处理邀请链接与通知跳转
  KukeApp.kt           Application
  core/
    AppGraph.kt        手写的依赖容器
    net/               HTTP 客户端、客户端证明、设备标识
    realtime/          WebSocket：心跳、断线重连、事件分发
    data/              会话仓库、消息与页面缓存
    model/             数据模型
    session/           登录状态
  service/             常驻服务、开机自启、补拉、通知
  feature/
    chat/              会话列表、聊天页、输入框、语音、表情、红包、投票
    group/             群资料与管理、群表情
    groupfiles/        群文件、下载、视频播放
    groupannouncement/ 群公告
    groupplus/         群升级、群精华
    feed/              动态
    jury/              风纪
    share/             系统分享与直接分享快捷方式
    settings/          设置、应用内更新
    …                  contacts、bots、shop、gifts、level、tasks、teamup 等
  ui/
    shell/             主框架、路由、底栏、预测性返回
    components/        通用组件
    icons/             图标（由 tools/build-icons.mjs 生成）
    theme/             主题
docs/release-notes/    各版本面向用户的更新说明
tools/build-icons.mjs  从 KukeChat-Client 的 lucide 线稿生成 Compose 图标
```

## 下载

官方安装包在 [KukeChat 下载页](https://chat-api.kuke.ink/download)。装好之后，新版本会在应用内提示并直接更新。

## 相关仓库

| 仓库 | 说明 |
| --- | --- |
| <img src=".github/assets/icons/server.svg" width="16" height="16" align="absmiddle">&nbsp;[KukeChat-Server](https://github.com/kukemc/KukeChat-Server) | 后端：REST API、WebSocket、数据库 |
| <img src=".github/assets/icons/monitor.svg" width="16" height="16" align="absmiddle">&nbsp;[KukeChat-Client](https://github.com/kukemc/KukeChat-Client) | 社区扩展与桌面端（Windows，macOS 测试中） |
| <img src=".github/assets/icons/smartphone.svg" width="16" height="16" align="absmiddle">&nbsp;KukeChat-Android | 原生安卓客户端（本仓库） |

## 贡献

欢迎提交 Issue 和 Pull Request。请沿用现有的代码风格和目录划分；改动涉及接口时，以 KukeChat-Server 的接口契约为准。

不要在代码里写死任何服务器地址，部署相关的配置一律走 `local.properties` 或 Gradle 属性。

## License

[MIT](LICENSE)

---

<div align="center">
<sub>MIT © 2026 kukemc</sub>
</div>
