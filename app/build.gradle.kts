import java.util.Properties
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 服务器地址不写进代码（同 KukeChat-Client 的约定）：从 local.properties 或同名 Gradle 属性读，缺了就构建失败
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun requiredConfig(name: String): String {
    val value = (findProperty(name) as String?) ?: localProps.getProperty(name)
    require(!value.isNullOrBlank()) { "缺少配置 $name：写进 local.properties 或用 -P$name=… 传入" }
    return value.trim().trimEnd('/')
}

android {
    namespace = "ink.kuke.chat"
    compileSdk = 35

    defaultConfig {
        // 正式版沿用现有 App 的包名，老用户可以覆盖安装升级（要用同一把签名密钥）
        applicationId = "ink.kuke.chat"
        minSdk = 26
        targetSdk = 35
        versionCode = 203
        versionName = "2.0.3"
        buildConfigField("String", "API_BASE_URL", "\"${requiredConfig("kukechat.apiBaseUrl")}\"")
        buildConfigField("String", "WS_URL", "\"${requiredConfig("kukechat.wsUrl")}\"")
        // 邀请链接深链接（服务端发 {INVITE_BASE_URL}?invite=<token>）：在 local.properties 配 kukechat.inviteBaseUrl（同服务端 INVITE_BASE_URL）才认网页链接；
        // 没配时网页链接的过滤器用一个不存在的域名占位（不抢 API 域名下的其他链接），只认 kukechat://invite/<token>
        val inviteBase = ((findProperty("kukechat.inviteBaseUrl") as String?) ?: localProps.getProperty("kukechat.inviteBaseUrl"))?.trim()?.takeIf { it.isNotEmpty() }
        val inviteUri = inviteBase?.let { URI(it) }
        manifestPlaceholders["inviteHost"] = inviteUri?.host ?: "invite.invalid"
        manifestPlaceholders["invitePathPattern"] = inviteUri?.path.orEmpty().trimEnd('/').let { if (it.isEmpty()) ".*" else "$it.*" }
    }

    // 正式版签名：密钥放在私有仓库 ../KukeChat/ops/android-release/（不进 git，丢了就无法再给已装用户覆盖升级）。
    // 也可以用 -Pkukechat.signing=<keystore.properties 的路径> 指定别处；找不到时正式包不签名（打不出可安装的包）
    val signingProps = ((findProperty("kukechat.signing") as String?)?.let { file(it) } ?: rootProject.file("../KukeChat/ops/android-release/keystore.properties"))
        .takeIf { it.isFile }
        ?.let { f -> Properties().apply { f.inputStream().use { stream -> load(stream) } } to f.parentFile }
    signingConfigs {
        if (signingProps != null) {
            create("release") {
                val (props, dir) = signingProps
                storeFile = File(dir, props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 开发版和现有 App 能同时装在一台手机上
            applicationIdSuffix = (findProperty("kukechat.appIdSuffix") as String?) ?: ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // 先不混淆：R8 混淆后 kotlinx.serialization / 反射相关代码要专门配规则并在真机上验证，2.0.0 先求稳
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    // 底栏毛玻璃：取背后的页面内容做模糊（Android 12+ 真模糊，更早的系统退回半透明底色）
    implementation(libs.haze)
    // 群文件里的视频：应用内流式播放（ExoPlayer + 自带的播放控制条）
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    testImplementation(libs.junit)
}
