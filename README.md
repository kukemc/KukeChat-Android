# KukeChat Android

KukeChat 的原生安卓客户端（Kotlin + Jetpack Compose）。

- 后端：[KukeChat-Server](https://github.com/kukemc/KukeChat-Server)
- 社区扩展 / 桌面端：[KukeChat-Client](https://github.com/kukemc/KukeChat-Client)

## 构建

需要 JDK 17 和 Android SDK 35。服务器地址不写在代码里，构建前在仓库根的 `local.properties` 里配好（缺了构建会直接失败）：

```properties
sdk.dir=<Android SDK 路径>
kukechat.apiBaseUrl=https://<你的后端>
kukechat.wsUrl=wss://<你的后端>/ws
# 可选：邀请链接的网页地址（同服务端 INVITE_BASE_URL），不配只认 kukechat://invite/<token>
kukechat.inviteBaseUrl=https://<你的域名>/invite
```

```bash
./gradlew :app:assembleDebug     # 开发版，包名带 .dev，可以和正式版同时装
./gradlew :app:assembleRelease   # 正式版
```

正式版签名读 `../KukeChat/ops/android-release/keystore.properties`，或用 `-Pkukechat.signing=<keystore.properties 路径>` 指定；找不到时打出的正式包不签名。

## 许可

[MIT](LICENSE)
