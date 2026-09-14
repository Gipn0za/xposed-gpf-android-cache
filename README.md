---

# GBF Cache LSPosed

一个专为 **SkyLeap浏览器** 用的LSPosed 模块。通过本地缓存《碧蓝航线/碧蓝幻想 (GBF)》等游戏静态资源（图片、音频、JS、CSS），显著减少重复网络请求，提升页面加载速度。

基于 **libxposed API 102（Modern API）** 开发，同时支持 Root 环境（LSPosed）与无 Root 环境（NPatch / LSPatch 内嵌）。

---

## 目录

* [核心特性](https://www.google.com/search?q=%23%E6%A0%B8%E5%BF%83%E7%89%B9%E6%80%A7)
* [虚拟管理页](https://www.google.com/search?q=%23%E8%99%9A%E6%8B%9F%E7%AE%A1%E7%90%86%E9%A1%B5)
* [缓存目录结构](https://www.google.com/search?q=%23%E7%BC%93%E5%AD%98%E7%9B%AE%E5%BD%95%E7%BB%93%E6%9E%84)
* [热更新处理机制](https://www.google.com/search?q=%23%E7%83%AD%E6%9B%B4%E6%96%B0%E5%A4%84%E7%90%86%E6%9C%BA%E5%88%B6)
* [安装与部署](https://www.google.com/search?q=%23%E5%AE%89%E8%A3%85%E4%B8%8E%E9%83%A8%E7%BD%B2)
* [日志说明](https://www.google.com/search?q=%23%E6%97%A5%E5%BF%97%E8%AF%B4%E6%98%8E)
* [调试指南](https://www.google.com/search?q=%23%E8%B0%83%E8%AF%95%E6%8C%87%E5%8D%97)
* [编译指南](https://www.google.com/search?q=%23%E7%BC%96%E8%AF%91%E6%8C%87%E5%8D%97)
* [兼容性说明](https://www.google.com/search?q=%23%E5%85%BC%E5%AE%B9%E6%80%A7%E8%AF%B4%E6%98%8E)

---

## 核心特性

* **自动拦截与缓存**：拦截 WebView 的 `shouldInterceptRequest` 方法。命中缓存则直接返回本地文件，未命中则走网络传输并异步下载补齐缓存。
* **精准资源覆盖**：
* **图片**：`.png`, `.jpg`, `.jpeg`, `.gif`, `.webp`, `.avif`, `.bmp`, `.svg`
* **音频**：`.mp3`, `.ogg`, `.oga`, `.wav`, `.m4a`, `.aac`
* **视频**：`.mp4`, `.webm`, `.m4v`
* **脚本/样式**：`.js`, `.css`


* **多 CDN 支持**：内置 GBF 常用 Akamai CDN 域名，以及主站 `gbf.game.mbga.jp`（仅拦截 `/assets/` 目录下的静态资源，避免误缓存接口 API）。
* **热更新智能整理**：自动拦截 `modified_list.txt` 并比对版本号，仅精准清除发生变更的图片/音频资源，不误删附带版本号的 JS/CSS。
* **内置可视化管理页**：直接在游戏内浏览器访问虚拟域名即可查看缓存状态或进行分级清理。
* **灵活分级清理**：
* 智能整理（基于 `modified_list.txt`）
* 按时间清理（7 天 / 30 天 / 90 天前）
* 按类型清理（图片 / 音频 / 视频 / JS / CSS）
* 按目录清理（`img_low`, `img`, `sound` 等）
* 一键全部清空


* **无 Root 友好**：默认使用应用私有目录 `/data/data/<包名>/files/gbf-cache`，不依赖外部存储权限，不受 Android 分区存储（Scoped Storage）限制。

---

## 虚拟管理页

在 SkyLeap 地址栏输入以下虚拟地址即可直接访问控制台（所有请求均在本地被模块拦截并渲染 HTML）：

| 虚拟 URL | 功能说明 |
| --- | --- |
| `https://gbf-cache.local/status` | 查看当前缓存状态（总量、文件数、按来源/类型/目录/时间分布） |
| `https://gbf-cache.local/clear` | 访问分级清理菜单 |
| `https://gbf-cache.local/clear?action=sync` | 手动触发智能整理 |
| `https://gbf-cache.local/` | 查看使用帮助 |

---

## 缓存目录结构

缓存文件保存在应用的内部私有目录中：

```
/data/data/<SkyLeap包名>/files/gbf-cache/<host>/<URL路径>

```

**示例路径：**

```
/data/data/com.dena.skyleap/files/gbf-cache/
├── prd-game-a-granbluefantasy.akamaized.net/
│   └── assets/
│       └── img_low/
│           └── sp/
│               └── cjs/
│                   └── npc_3040028000_03.png
└── gbf.game.mbga.jp/
    └── assets/
        └── ...

```

> **私有目录特性：** > 1. 无 Root 用户无需担心权限问题，普通文件管理器不可见。
> 2. 随 SkyLeap 卸载自动清除，无垃圾残留。
> 3. 不需要申请额外的动态存储权限。

---

## 热更新处理机制

GBF 通过 `modified_list.txt` 记录资源更新动态，模块的处理逻辑如下：

1. **拦截请求**：拦截 `modified_list.txt` 请求，该请求本身不缓存，透传至网络。
2. **后台比对**：模块在后台异步下载一份最新的 `modified_list.txt`。
3. **版本校验**：读取文件首行的版本号，并与本地保存的 `.last_version` 进行比对。
* **版本一致**：无操作，结束流程。
* **版本变更**：逐行遍历变更列表，**精准删除本地对应的图片与音频缓存**。


4. **失效更新**：JS/CSS 文件因 URL 内带版本号会自动失效，无需额外删除。
5. **保存记录**：同步更新 `.last_version` 文件。

> **已知限制：**
> * `modified_list.txt` 是基于某个基线的增量变更列表。如果跨越了多个版本更新，可能仅会触发“最后一跳”的变更清理。建议每次大修维护后登录一次游戏，确保模块及时完成同步。
> * JS/CSS 依赖 URL 内自带的版本参数失效，不参与 `modified_list.txt` 的增量删改逻辑。
> 
> 

---

## 安装与部署

### 方式一：LSPosed 环境（需要 Root）

1. 编译模块产生 APK。
2. 在设备上安装该 APK。
3. 打开 **LSPosed 管理器** 启用模块，将作用域勾选为：`com.dena.skyleap`。
4. 强制停止并重启 SkyLeap。

### 方式二：NPatch / LSPatch 环境（免 Root）

1. 编译模块产生 APK。
2. 使用 **NPatch** 或 **LSPatch** 的便携模式，将模块 APK 与 SkyLeap 客户端打入同一个安装包。（*注：打包时若遇到签名问题，请尝试将“破解签名校验”选项设为 `None*`）。
3. 卸载原版 SkyLeap 客户端，安装打包后的新客户端。

---

## 日志说明

可通过 **LSPosed 管理器 → 日志** 或执行命令 `adb logcat -s GBFCache` 查看输出日志。

### 关键控制台输出

* **模块加载与初始化：**
```text
========================================
target process loaded: com.dena.skyleap
mode = LibXposed API 102
========================================
GBFCache: setupCacheRoot, SDK=34
GBFCache: getFilesDir = /data/data/com.dena.skyleap/files
GBFCache: cache root ready = /data/data/com.dena.skyleap/files/gbf-cache
hooked WebViewClient.shouldInterceptRequest(WebResourceRequest)
hooked WebViewClient.shouldInterceptRequest(String)

```


* **检测到热更新：**
```text
GBFCache: version changed: <旧版本> -> <新版本>
GBFCache: sync done, checked=<n> deleted=<n>

```



> **高频静默说明：** 为降低性能损耗及避免频繁刷屏，每次资源的拦截命中/未命中、后台下载细节以及清空过程默认**不打印日志**。

---

## 调试指南

如果需要排查拦截与缓存是否成功生效，可以在 `InterceptHook.intercept` 逻辑中临时注入调试日志：

```java
WebResourceResponse response = tryLocalResponse(url);
if (response != null) {
    xlog("HIT  " + url);
    return response;
}
xlog("MISS " + url);
scheduleDownload(url);
return chain.proceed();

```

同时将 `downloadAndCache` 的异常捕获改为输出具体堆栈：

```java
} catch (Throwable t) {
    xlog("DL FAIL " + url, t);
}

```

*调试完毕后请记得移除上述日志代码，以免影响生产环境性能。*

---

## 编译指南

### 编译依赖

* **Android Studio**（建议 Hedgehog 及以上）
* **Android SDK Platform**: 36
* **Gradle**: 8.0+
* **libxposed API**: 102 (`compileOnly`)

### 项目配置

#### `build.gradle`

```groovy
android {
    namespace 'com.frinsecta.gbfcache'
    compileSdk 36

    defaultConfig {
        applicationId 'com.frinsecta.gbfcache'
        minSdk 26
        targetSdk 36
        versionCode 1
        versionName '1.0'
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}

```

*注：`minSdk` 设置为 26 是因为 libxposed API 102 使用了 Android 8.0 (API 26) 才引入的 Executable 类型 Hook 目标。*

#### `AndroidManifest.xml`

API 102 无需在清单文件配置 `<meta-data>` 标签：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="GBF Cache LSPosed"
        android:description="@string/app_description"
        android:allowBackup="false"
        android:supportsRtl="true" />
</manifest>

```

#### `META-INF/xposed/` 配置文件

配置位于 `app/src/main/resources/META-INF/xposed/` 路径下：

* **`java_init.list`** (入口类配置，替代旧版 `assets/xposed_init`):
```text
com.frinsecta.gbfcache.GBFCacheHook

```


* **`scope.list`** (目标作用域包名):
```text
com.dena.skyleap

```


* **`module.prop`** (API 102 必需模块元数据):
```properties
minApiVersion=102
targetApiVersion=102
name=GBF Cache LSPosed
version=1.0
versionCode=1
author=Frinsecta
description=GBF 静态资源本地缓存

```



#### `proguard-rules.pro`

若项目开启混淆 (`minifyEnabled true`)，请加入以下规则保留入口类：

```proguard
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

```

---

## 兼容性说明

| 项目 | 说明 |
| --- | --- |
| **目标应用** | SkyLeap（包名 `com.dena.skyleap`） |
| **多开/共存版** | 支持（包名匹配 `com.dena` 即可生效） |
| **系统版本** | Android 8.0+（minSdk 26） |
| **框架支持** | LSPosed (Modern API 102) / NPatch / LSPatch |
| **Root 要求** | 非必需（支持免 Root 打包集成） |
