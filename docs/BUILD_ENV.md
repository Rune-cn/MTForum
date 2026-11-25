# 构建与测试环境

记录本机工具链的搭建方式与踩过的坑，便于换机器时快速复现。

## 一键构建

```bash
cd /c/Users/Administrator/Documents/1
bash tools/build.sh                               # :app:assembleStableDebug
bash tools/build.sh :app:testStableDebugUnitTest
bash tools/build.sh :app:assembleStableRelease
bash tools/build.sh :app:lintVitalStableRelease
```

`tools/build.sh` 已注入全部环境变量（JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME /
TEMP / 代理 / git PATH），无需手动设置。

## 组件

| 组件 | 位置 | 版本 |
|---|---|---|
| JDK | `tools/jdk-25.0.5.1+2` | Temurin 25.0.5.1+2 |
| Android SDK | `tools/android-sdk` | platform 37.2、build-tools 36.0.0 + 37.0.0、platform-tools |
| Gradle | `tools/gradle-home` | 9.8.1（wrapper 下载） |
| Git | `tools/cmd/git.exe` | 2.51.0 |
| 代理 | `127.0.0.1:7897` | JAVA_TOOL_OPTIONS 注入 |

## 为让这台机器能构建所做的适配

1. **miuix 依赖**：`gradle/libs.versions.toml` 用 `0.9.5-beta03`，从 Maven Central 拉
   （不需要 GitHub Packages 凭据）。
2. **`COMPILE_SDK_MINOR = 2`**：beta03 要求 API 37.2。
3. **删除 `gradle/gradle-daemon-jvm.properties`**：它强制下载 Microsoft JDK，本机拿不到。
4. **删除 foojay-resolver 插件**：它会尝试自动下载 JDK。
5. **`gradle.properties` 指定 `org.gradle.java.installations.paths`** 指向本地 JDK。
6. **`platforms/android-37.2/package.xml` 手工构造**：官方 zip 里不带这个文件。
7. **`ANDROID_USER_HOME` 指向工作区**：避开 `~/.android` 的沙箱写限制。
8. **`kotlin.daemon.jvmargs` 与 `JAVA_TOOL_OPTIONS` 把 `java.io.tmpdir` 指到工作区**：
   否则 Kotlin 客户端标记文件写入 `%LOCALAPPDATA%\kotlin` 会被拒，表现为间歇性
   `AccessDeniedException` 构建失败。

## 沙箱权限相关的坑（都已在脚本里解决）

本环境的进程写 `~`（`C:\Users\Administrator`）会被拒。表现与解法：

| 现象 | 解法 |
|---|---|
| `~/.gradle` 建 wrapper 锁失败 | `GRADLE_USER_HOME` 指到 `tools/gradle-home` |
| `~/.android` keystore 锁失败 | `ANDROID_USER_HOME` 指到 `tools/android-home` |
| `~/.local/kotlin` daemon 标记失败 | `java.io.tmpdir` 指到 `tools/tmp` |
| Robolectric 下载锁失败 | Gradle `testOptions` 里把 `user.home` 指到 `tools/robo-home` |

## UI 测试（Robolectric）

`ForumCardRenderTest` 在 JVM 上真实渲染 Compose 组件并查询语义树，
是本环境唯一可行的 UI 验证手段。

```bash
bash tools/build.sh :app:testStableDebugUnitTest --tests "io.rune.mt.forum.ForumCardRenderTest"
```

三个必要配置：

1. `testOptions.unitTests.all { it.systemProperty("user.home", …) }` —— 沙箱允许的下载目录
2. `TestApp` —— 空 `Application`，跳过 `App.onCreate` 的
   HiddenApiBypass / MonetCompat（JVM 环境不支持）
3. **断言写英文**：Robolectric 的 `stringResource` 用默认资源（`values/`），不是中文包

## 模拟器：本机不可用（重要）

尝试过但失败，结论是**这台机器无法运行 Android 模拟器**：

1. 下载 emulator 37.4.1 + API 35 / API 30 系统镜像（合计约 3 GB）
2. 手工创建 AVD（`avdmanager` 依赖 sdkmanager 索引，本机不可用）
3. 安装 AEHD 硬件加速驱动（`aehd-windows_v2.2.zip`，`silent_install.bat` 安装成功，
   `emulator-check accel` 报 **"AEHD is installed and usable"**）
4. 启动后 qemu 进程存活但 **CPU 时间始终 <2 秒、内存 140 MB**，永不进入 boot 阶段

根因是沙箱禁止嵌套虚拟化。因此 UI 观感（间距、滚动、玻璃效果）只能靠真机确认。

AVD 与系统镜像仍在磁盘上，如需清理：

```powershell
Remove-Item C:\Users\Administrator\Documents\1\tools\android-home\avd -Recurse -Force
Remove-Item C:\Users\Administrator\Documents\1\tools\android-sdk\system-images -Recurse -Force
sc.exe stop aehd; sc.exe delete aehd   # 卸载硬件加速驱动
```

## 发布（GitHub Actions）

`.github/workflows/manual-stable-release.yml`，**只能手动触发**：

| 输入 | 说明 |
|---|---|
| `version_tag` | 必填，`^\d+\.\d+(\.\d+)?$` |
| `version_code` | 可选正整数；留空用 git commit count |
| `draft` | 默认 true |

流程：构建 → `aapt2` 校验版本号 → `apksigner` 拒绝 debug 签名 → 上传 APK 到草稿 Release
→ 无论成败删除 keystore。

**必需 Secrets**：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

⚠️ `versionCode` 来自 git commit count，所以 `actions/checkout` 必须 `fetch-depth: 0`。
本仓库只有 1 个 commit，发布时要显式传 `version_code`。

⚠️ 查找产物目录用 `find -ipath "*/stable/release"`：flavor 是大写 `Stable`、
build type 是小写 `release`，大小写敏感。
