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

`tools/build.sh` 已注入全部环境变量（JAVA_HOME / ANDROID_HOME / ANDROID_USER_HOME /
GRADLE_USER_HOME / TEMP / 代理 / git PATH / `java.io.tmpdir`），无需手动设置。

> 并行开发多个 `git worktree` 时注意：`tools/build.sh` / `build.ps1` 把 `cd` 写死到
> `Documents/1/MTForum`，worktree 里要用同一套变量手动跑 wrapper：
>
> ```powershell
> $env:JAVA_HOME='C:\Users\Administrator\Documents\1\tools\jdk-25.0.5.1+2'
> $env:ANDROID_HOME='C:\Users\Administrator\Documents\1\tools\android-sdk'
> $env:ANDROID_USER_HOME='C:\Users\Administrator\Documents\1\tools\android-home'
> $env:GRADLE_USER_HOME='C:\Users\Administrator\Documents\1\tools\gradle-home'
> $env:TEMP='C:\Users\Administrator\Documents\1\tools\tmp'; $env:TMP=$env:TEMP
> $env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=C:/Users/Administrator/Documents/1/tools/tmp'
> .\gradlew.bat :app:compileStableDebugKotlin --console=plain
> ```

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

## JVM 截图验证（无模拟器时「看」UI，2026-11-26 新增）

除了语义树断言，还可以把真实组件渲染成 PNG 直接看：

```bash
bash tools/build.sh :app:testStableDebugUnitTest --tests "io.rune.mt.forum.ThreadActionsPopupLayoutTest"
bash tools/build.sh :app:testStableDebugUnitTest --tests "io.rune.mt.forum.ForumImageScreenshotTest"
bash tools/build.sh :app:testStableDebugUnitTest --tests "io.rune.mt.forum.Os4PredictiveBackScreenshotTest"
```

- `ThreadActionsPopupLayoutTest`：822×1782（`w411dp-h891dp-xhdpi`）下打开 OS4 玻璃菜单，
  断言 host=整页、面板右缘对齐 anchor 右缘，截图落 `app/build/ui-screenshots/os4-thread-actions-menu.png`。
- `ForumImageScreenshotTest`：起一个本地 `com.sun.net.httpserver` 提供 GIF 字节，
  渲染真实 `ForumThreadCard` + `ForumHtmlText`，落盘 `app/build/ui-screenshots/`。
  用内存缓存条目数断言「图确实解码成功」。
- `Os4PredictiveBackScreenshotTest`：用真实 `miuixOs4NavTransition` + 假 `NavTransitionScope`
  渲出手势 p=0.45 的一帧（含 scrim/卡片的绘制顺序），像素级校验后落
  `app/build/ui-screenshots/os4-predictive-back.png`。
- 三张样例图已入库：[screenshots/os4-thread-actions-menu.png](screenshots/os4-thread-actions-menu.png)、
  [screenshots/jvm-image-render.png](screenshots/jvm-image-render.png)、
  [screenshots/os4-predictive-back.png](screenshots/os4-predictive-back.png)。

### 坑：miuix squircle 在 Robolectric 下编译失败

miuix 的 squircle 用 AGSL `RuntimeShader`，Robolectric 自带 Skia 报
`IllegalArgumentException: error: 8: 'color' is not a valid layout qualifier`，
栈在 `SquircleShaderBrush`/`RuntimeShader`。绕法：截图测试的 `setContent` 里包一层

```kotlin
CompositionLocalProvider(LocalSquircleEnabled provides false) { AppTheme(...) { ... } }
```

关掉后 squircle 退化为 `RoundedCornerShape`，**布局测量不受影响**，只是截图圆角观感略有差异。
截取用 `activity.window.decorView.draw(Canvas(bitmap))` 或 `onRoot().captureToImage()` 均可，
必须 `@GraphicsMode(GraphicsMode.Mode.NATIVE)`。

## 模拟器：本机不可用（重要）

结论：**这台机器无法运行 Android 模拟器**，相关资产已删除。

当初的尝试过程（留档，便于换机器时参考）：

1. 下载 emulator 37.4.1 + API 35 / API 30 系统镜像（合计约 3 GB）
2. 手工创建 AVD（`avdmanager` 依赖 sdkmanager 索引，本机不可用）
3. 安装 AEHD 硬件加速驱动（`aehd-windows_v2.2.zip`，`silent_install.bat` 安装成功，`emulator-check accel` 报 **"AEHD is installed and usable"**）
4. 启动后 qemu 进程存活但 **CPU 时间始终 <2 秒、内存 140 MB**，永不进入 boot 阶段

根因是沙箱禁止嵌套虚拟化。因此 UI 观感（间距、滚动、玻璃效果）只能靠真机确认。

---

### 如需重新尝试模拟器

```powershell
# 1. 模拟器二进制
Invoke-WebRequest 'https://dl.google.com/android/repository/emulator-windows_x64-16916650.zip' -OutFile emulator.zip
Expand-Archive emulator.zip -DestinationPath $env:ANDROID_HOME -Force
# 2. 系统镜像（需先在 sys-img2-4.xml 里查实际文件名）
Invoke-WebRequest 'https://dl.google.com/android/repository/sys-img/google_apis/x86_64-35_r09.zip' -OutFile sysimg.zip
# 3. 硬件加速驱动
Invoke-WebRequest 'https://dl.google.com/android/repository/aehd-windows_v2.2.zip' -OutFile aehd.zip
Expand-Archive aehd.zip -DestinationPath aehd -Force; .\aehd\silent_install.bat
```

前提是宿主允许嵌套虚拟化——本机不允许，`emulator-check accel` 会报 usable 但 qemu 不执行。

---

## 已清理的资产（2026-11-22）

模拟器路线失败后，相关资产已全部删除，回收约 **22.9 GB**：

| 项目 | 大小 | 说明 |
|---|---|---|
| `android-sdk/system-images`（API 30 / 35） | 6.9 GB | 模拟器系统镜像 |
| `android-sdk/emulator` | 1.1 GB | 模拟器二进制 |
| `android-home/.android` | 10.9 GB | 尝试启动时产生的 AVD 用户数据 |
| `android-home/avd` | 90 MB | AVD 定义 |
| `tools/emu` | 3.8 GB | 下载的 zip 与 miuix sources jar |
| `tools/pgit.7z.exe`、`tools/dl`、`assets/*.zip` | 0.83 GB | 已解压的安装包 |

AEHD 驱动已卸载：`sc stop aehd` → `sc delete aehd` → 删除
`C:\Windows\System32\drivers\aehd.sys`。

### 刻意保留

| 项目 | 原因 |
|---|---|
| `android-home/debug.keystore` | 构建签名。删了会让已安装的 APK 无法覆盖升级 |
| `android-sdk/{platforms,build-tools,platform-tools,cmdline-tools}` | 构建必需 |
| `tools/{jdk-25…,gradle-home,git}` | 构建必需 |
| `assets/rev`（2 MB） | 逆向证据：acw 脚本、真实页面样本、表情 JS |
| `tools/repo.xml`（0.4 MB） | Android SDK 各组件的确切下载 URL 记录 |

清理后已验证：`testStableDebugUnitTest` 与 `assembleStableDebug` 均 BUILD SUCCESSFUL。
