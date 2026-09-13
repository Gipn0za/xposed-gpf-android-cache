一个面向《碧蓝幻想》SkyLeap 客户端的 Xposed 模块，
把游戏静态资源（图片、音频、JS、CSS）缓存到本地，
减少重复下载、加快加载、节省流量。

支持 **无 root** 使用（通过 NPatch / LSPatch 内置进客户端），
也支持有 root 的 LSPosed 环境。

---

## 功能

- **自动缓存**：拦截 WebView 的 `shouldInterceptRequest`，
  把命中缓存的请求直接返回本地文件，未命中的走网络并异步下载缓存。
- **精准缓存范围**：
  - 图片：`.png` / `.jpg` / `.jpeg` / `.gif` / `.webp` / `.avif` / `.bmp` / `.svg`
  - 音频：`.mp3` / `.ogg` / `.oga` / `.wav` / `.m4a` / `.aac`
  - 视频：`.mp4` / `.webm` / `.m4v`
  - 脚本：`.js` / `.css`
- **多 CDN 支持**：内置 GBF 常用 Akamai CDN 域名，
  以及主站 `gbf.game.mbga.jp`（只缓存 `/assets/` 下的资源，避免误缓存 API）。
- **热更新自动整理**：
  拦截 `modified_list.txt`，对比版本号，
  只删除本次维护后发生变化的图片/音频资源，不清 JS/CSS。
- **可视化管理页**：在游戏内浏览器访问虚拟域名即可查看状态、清理缓存。
- **分级清理**：
  - 智能整理（基于 `modified_list.txt`）
  - 按时间清理（7 天 / 30 天 / 90 天前）
  - 按类型清理（图片 / 音频 / 视频 / JS / CSS）
  - 按目录清理（`img_low` / `img` / `sound` 等）
  - 全部清空
- **无 root 可用**：使用 app 内部私有目录
  `/data/data/<包名>/files/gbf-cache`，
  不依赖外部存储权限，不受分区存储限制。

---

## 虚拟管理页

在游戏内浏览器地址栏输入以下地址即可访问：

| 地址 | 说明 |
|---|---|
| `https://gbf-cache.local/status` | 查看缓存状态（总量、文件数、按来源/类型/目录/时间分布） |
| `https://gbf-cache.local/clear` | 清理缓存（分级菜单） |
| `https://gbf-cache.local/clear?action=sync` | 手动触发智能整理 |
| `https://gbf-cache.local/` | 帮助页 |

这些域名**不是真实存在的**，
所有请求都会被模块在 `shouldInterceptRequest` 里拦截并返回本地生成的 HTML 页面。

---

## 缓存位置

缓存目录：`/data/data/<SkyLeap 包名>/files/gbf-cache/<host>/<URL 路径>`

例如：

```
/data/data/com.dena.skyleap/files/gbf-cache/
  prd-game-a-granbluefantasy.akamaized.net/
    assets/
      img_low/
        sp/
          cjs/
            npc_3040028000_03.png
  gbf.game.mbga.jp/
    assets/
      ...
```

**这是 app 内部私有目录**：
- 无 root 用户通过文件管理器看不到。
- 随 app 卸载自动清理。
- 不需要任何存储权限。

---

## 热更新处理

GBF 的热更新通过 `modified_list.txt` 通知客户端哪些资源发生了变化。

模块的处理流程：

1. 拦截 `modified_list.txt` 请求，不缓存，让游戏自己走网络。
2. 异步下载同一份 `modified_list.txt`。
3. 读取第一行版本号，和本地记录的 `.last_version` 对比。
4. 版本号**不变**：什么都不做。
5. 版本号**变化**：遍历列表中每行的路径，
   只删除本地对应的**图片/音频**缓存；
   JS/CSS 因为路径里带版本号，会自动失效，不需要手动删。
6. 更新 `.last_version`。

可以在 `/clear` 或 `/status` 页面手动触发一次整理。

### 已知限制

- `modified_list.txt` 是**相对某个基线的变更列表**，
  如果你跨越多个版本，可能只删除"最后一跳"的变化。
  建议在维护更新后进游戏一次，让模块及时整理。
- JS/CSS 依赖 URL 里的版本号做失效，不参与 `modified_list.txt` 的处理。

---

## 安装

### 方式一：LSPosed（需要 root）

1. 编译模块 APK。
2. 安装。
3. 在 LSPosed 管理器中启用模块，勾选作用域：`com.dena.skyleap`。
4. 重启 SkyLeap。

### 方式二：NPatch / LSPatch（无需 root）

1. 编译模块 APK。
2. 使用 NPatch 或 LSPatch 的便携模式，把模块和 SkyLeap 客户端一起打包。
3. 卸载原版客户端，安装打包后的 APK。

> 打包后签名会变，需要先卸载原版再安装。
> 如果 SkyLeap 使用本地登录态，可能需要重新登录。

---

## 日志

模块只输出少量启动日志，方便确认加载状态：

```
GBFCache: target loaded
GBFCache: pkg     = com.dena.skyleap
GBFCache: process = com.dena.skyleap
GBFCache: cache root ready = /data/data/com.dena.skyleap/files/gbf-cache
```

缓存读写、热更新整理等操作默认**不打日志**，
避免刷屏，也不会影响游戏性能。

---

## 编译

### 依赖

- Android Studio
- Gradle 7.0+
- Xposed API 82（`compileOnly`）

### build.gradle

```groovy
android {
    namespace 'com.frinsecta.gbfcache'
    compileSdk 33

    defaultConfig {
        applicationId 'com.frinsecta.gbfcache'
        minSdk 23
        targetSdk 28
        versionCode 1
        versionName '1.0'
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    compileOnly files('libs/api-82.jar')
}
```

### AndroidManifest.xml

```xml
<application>
    <meta-data android:name="xposedmodule" android:value="true" />
    <meta-data android:name="xposeddescription" android:value="GBF 静态资源本地缓存" />
    <meta-data android:name="xposedminversion" android:value="82" />
</application>
```

### assets/xposed_init

```
com.frinsecta.gbfcache.GBFCacheHook
```

---

## 兼容性

| 项目 | 说明 |
|---|---|
| 目标应用 | SkyLeap（包名 `com.dena.skyleap`） |
| 多开/共存版 | 支持，包名包含 `com.dena` 即生效 |
| Android 版本 | 6.0+（`minSdk 23`） |
| 框架 | LSPosed / EdXposed / NPatch / LSPatch |
| root | 不需要 |

---

## 注意事项

- 模块只缓存**静态资源**，不会缓存任何 API 响应。
- 主站 `gbf.game.mbga.jp` 只缓存 `/assets/` 下的资源，
  `/rest/`、`/od/`、`/resultmulti/`、`/multiraid/` 等接口一律放行。
- WebSocket 连接不经过模块。
- 缓存目录默认随 app 卸载清理。
- 当前没有缓存容量上限，请定期通过管理页清理，或等待后续版本加入自动清理。

---

