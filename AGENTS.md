# AGENTS.md

面向 AI 编码代理的仓库指引。

## 用途

本文件定义在 **MT论坛 客户端**（`io.rune.mt.forum`，bbs.binmt.cc 第三方客户端）上工作时
必须遵守的规则：改动放哪里、哪些约束不能破坏、怎么自证完成。

窄任务做最小改动；大功能或跨文件重构先列计划再动手。

## 关键事实

- 包名 `io.rune.mt.forum`；应用名 `MT论坛`；主题 `Theme.MTForum`
- 只有 `:app` 与 `:baselineprofile` 两个 Gradle 模块
- 构建插件统一 `mtforum.*`（**catalog key 名仍是 `miuixexample-*`，勿改**）
- UI 只用 **Miuix** 家族，OS3/OS4 双风格由 `AppTheme.useMiuixOs4` 切换
- 语言包仅 `values/`(en) + `values-zh-rCN` + `values-zh-rTW`
- 应用始终可写文件日志（不随构建类型门控）

## 项目布局

- `data/forum/` — 反爬、Cookie、HTTP（含 multipart）、HTML 解析、表情目录、仓库
- `domain/forum/` — 领域模型与仓库接口（含 `ForumMessaging.kt`：私信/收藏/发帖模型）
- `ui/page/forum/` — 首页/列表/帖子/导读/私信/发帖、正文渲染、回复、表情选择、图片查看
- `ui/page/login/` — 登录
- `ui/page/miuix/` — 设置、主题、关于、共享组件
- `docs/` — 交接、逆向规格（§12–§17 是私信/收藏/关注/顶/发帖/重登）、架构、构建环境

## 构建与验证

```bash
bash tools/build.sh                               # 冒烟构建
bash tools/build.sh :app:testStableDebugUnitTest   # 必跑
bash tools/build.sh :app:lintVitalStableRelease    # 改字符串后必跑
```

- 改 Kotlin/XML 后**必须**跑 `:app:compileStableDebugKotlin`
- 改解析器后**必须**跑单元测试（改解析器须同步 `app/src/test/resources/forum/` 样本）
- **看 UI 不用真机/模拟器**（本机跑不了模拟器）：用 Robolectric 截图测试
  `ThreadActionsPopupLayoutTest`（OS4 菜单锚定）与 `ForumImageScreenshotTest`（卡片/正文图片渲染），
  产物落 `app/build/ui-screenshots/`；截图前要 `LocalSquircleEnabled provides false`，
  否则 miuix squircle 的 AGSL 在 Robolectric 下编译失败
- **Kotlin 文件头用 `//` 而非 `#`**：`# SPDX-License-Identifier:` 会被当成非法令牌，整个文件编译失败
- **不要用 PowerShell 的 `Set-Content -Encoding UTF8` 改写含中文的 Kotlin/XML**：
   本机 PowerShell 5 会写成 GBK/加 BOM 并破坏全角标点，请用编辑器工具改
- **如实报告**跑了什么、结果如何

## 铁律（易踩坑）

1. **不许绕过 `ForumHttpClient`**：只有它挂 `AcwInterceptor`，直连 OkHttp 只会拿到 JS 挑战页。
2. **suspend 不等于切线程**：阻塞调用（`execute()`、jsoup）必须包 `withContext(Dispatchers.IO)`。
   违反会被 `MainThreadSafetyTest` 抓出，并会在真机上抛 `NetworkOnMainThreadException`。
3. **DOM 选择器只在 `ForumHtmlParser`**；改动须同步更新
   `app/src/test/resources/forum/` 样本与断言。
4. **文档同步铁律**：每次代码修改都要同步更新对应 MD
   （`docs/HANDOVER_CN.md`、`docs/ARCHITECTURE.md`、本文件、`README.md`、`docs/README_CN.md`）。
5. **字符串三语同步**：删键必须三处一起删，否则 `lintVital` 报 `ExtraTranslation` 致命错误。
6. **头像用 `ForumAvatar`**：不要各自判断 `noavatar` 换字母（列表和帖子内曾因此不一致）。
7. **顶栏图标用 `MiuixTopBarIconButton`**：它按风格分支，直接 `IconButton` 在 OS4 下样式错。
8. **弹窗按风格分支**：OS4 → `GlassPopup` / `GlassIconDropdownMenu`；OS3 → `WindowListPopup`。
9. **不要条件化 `MiuixComponentHost`**：`SubcomposeLayout` 同帧销毁会崩溃。
10. **禁止日志打印 Cookie 明文**，只可打 uid / isLoggedIn。
11. **提交**：Conventional Commits；主体 ≤72 字符；不加 AI 署名或 Co-authored-by。
12. **OS4 `GlassPopup` 必须放页面根 `BoxScope`**：它是内联 overlay（`fillMaxSize` 遮罩 +
    按窗口坐标 place 面板），放进顶栏 actions/小容器会被 host 钳制 → 真机表现为「菜单错位」，
    且全屏遮罩失效。状态用 `ThreadActionsState` 提升，菜单组件放根 Box。
13. **图片必须走 `buildForumImageLoader` + `ForumUrls`**：站点图床/附件
    （`forum.php?mod=image`、`mod=attachment`）匿名请求只会拿到 200 + acw 挑战页（text/html），
    必须复用 `ForumCookieJar` + `AcwInterceptor`；URL 统一 `ForumUrls.absolute()` 绝对化，
    `javascript:`/`file:`/`data:` 一律当无图处理，不要直接把原始 `src` 属性丢给 Coil。
    **正文图片的懒加载属性优先**：`comiis_loadimages → file → zoomfile → data-src` 才是真实地址，
    `src` 往往是 `none.png`/`imageloading.gif` 占位。
14. **Cookie 只按 host 下发**：`ForumCookieJar.loadForRequest` 用 `Cookie.matches(url)` 过滤，
    禁止把 `cQWy_2132_auth` 发给 `cdn/icdn/oos` 等 CDN。
15. **OS4 预测式返回只有一条实现路径**：动效在
    `ui/animation/predictiveback/MiuixOs4NavTransition.kt`，`appNavTransition(…, useMiuixOs4=…)`
    选择，圆角/压暗在 `AppNavContainer` 的 `NavDisplayEffects` 统一给。**entry 内禁止**注册
    `BackHandler`/`NavigationBackHandler`（会抢走 NavDisplay 的手势驱动，表现为没有跟手预览）；
    唯一例外是菜单展开时关菜单的 `ThreadActionsBackHandler`（`enabled = expanded`）。
16. **首页榜单 / 导读必须用站点数据**：热帖排行解析 `forum.php` 的
    `div.comiis_mh_txtlist_phb`（文档顺序 + `em` 名次 + `a[title]`），**禁止客户端重排**；
    导读走 `ForumUrls.guidePath(view, page)`（`newthread|hot|digest|new|sofa`），不要手拼 URL。

## Miuix 双风格（OS3 / OS4）

- **唯一开关**：`ThemeState.useMiuixOs4` → `AppTheme.useMiuixOs4`（CompositionLocal）。
  **不要**在每个 Composable 上新增 `useOs4` 传参。
- **平台降级**：OS4 依赖 render effect，仅 Android 13+ 且 `isRenderEffectSupported()` 时生效；
  否则整体降级为 OS3。任何读取处都要带同一守卫。
- **首选共享组件**：页面用 `MiuixPageScaffold` + `rememberMiuixPageChrome()`；
  顶栏 `MiuixTopAppBar` + `MiuixTopBarIconButton`；返回 `MiuixBackButton`；
  底部导航 `MiuixGlassNavigationBar`（OS4）/ `FloatingBottomBar`（OS3）。
- **OS4 专属视觉**：`size`、`Glass*` 来自 `miuix-material`/`miuix-glass`；
  OS3 下不得泄漏玻璃组件，反之亦然。

## 测试环境说明

Robolectric 的 `stringResource` 用默认资源（英文），UI 测试断言要写英文串。
`TestApp` 跳过 `App.onCreate` 的 HiddenApiBypass / MonetCompat。

## 交接格式

任务收尾汇报：**改了哪些文件 / 行为变化 / 验证（命令+结果）/ 备注（风险、未验证项）**。
