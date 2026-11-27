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
15. **预测式返回只有一条实现路径**：页面动效在
    `ui/animation/predictiveback/MiuixOs4NavTransition.kt`，`appNavTransition(…, useMiuixOs4=…)`
    选择，圆角/压暗在 `AppNavContainer` 的 `NavDisplayEffects` 统一给。**entry 内禁止**注册
    常驻 `BackHandler`/`PredictiveBackHandler`（会抢走 NavDisplay 的手势驱动，表现为没有跟手预览）；
    唯一例外是菜单展开时的 `ThreadActionsBackHandler`：必须是 miuix
    `PredictiveBackHandler(enabled = expanded)`，组合在 `ThreadActionsGlassMenu` **之后**
    （最近组合的 enabled 处理器优先），进度写进 `ThreadActionsState.dismissProgress` 预览关闭。
16. **首页榜单 / 导读必须用站点数据**：热帖排行解析 `forum.php` 的
    `div.comiis_mh_txtlist_phb`（文档顺序 + `em` 名次 + `a[title]`），**禁止客户端重排**；
    导读走 `ForumUrls.guidePath(view, page)`（`newthread|hot|digest|new|sofa`），不要手拼 URL。
17. **深链只有一个入口**：外部 URL 一律经 `ui/navigation/ForumDeepLink.kt` 的
    `forumDeepLinkFrom(Uri)` 解析；Manifest 的 intent-filter 与 Activity 的 `onNewIntent`
    都只调用它，不要再写第二套 intent 解析。
18. **会员页先判登录**：搜索 / 提醒 / 个人资料 / 收藏 / 草稿只对登录态可见（匿名 302 登录页）。
    仓库抛 `ForumLoginRequiredException` 或先用 `ForumHtmlParser.isLoginRequired()` 判，
    UI 显示登录卡片 + 跳 `Route.Login`，**不要**把登录页解析成“空结果”。
    **设置页「论坛」分组只留「我的收藏」+「草稿」两行**（`Route.Favorites` / `Route.Drafts`—
    两者都是登录账号自己的论坛数据，放在这里最直觉）；消息提醒从首页顶栏铃铛进，
    我的主页从账号卡片点头像/昵称进（`AccountSection` 的 `onOpenProfile`），不要再往设置页
    的「论坛」分组加回 Notices / UserProfile 行。发帖页顶栏**不另设**草稿入口（避免重复）。
19. **搜索是 POST 流程**：`search.php?mod=forum&mobile=2` 先取 `formhash`，POST
    `srchtxt+searchsubmit=yes` 得 `searchid`，翻页必须保留 `searchid` 与 `kw`；
    结果行 fid 取自每行 `forum-<fid>-1.html`（`parseThreadRow(li, fid = null)`）。
20. **版块宫格必须全量**：`getHome()` 同时取 `forum.php` 与 `forum.php?forumlist=1`，
    `parseForums` 合并宫格/全量/快捷发帖三处并按 fid 去重、保持站点顺序；
    `em` 数字是「今日 N」（`Forum.todayCount`），不是主题数。
21. **主题分类走 `filter=typeid`**：`#forumlist_time_li` 解析成 `ForumCategory`，
    `getThreads(fid, page, typeId)` 拼接筛选，UI 用 `TabRow`，无分类版块不显示。
22. **置顶公告的 byline 要补**：公告条只有标题，`ForumViewModel.enrichNoticeAuthors()`
    按 tid 补抓线程页 OP；未解析到时卡片显示置顶图钉，**不要**渲染空作者问号头像。
23. **OS4 菜单玻璃采样页面内容层，绝不能用页面 backdrop**：`ForumThreadPage` 把
    `chrome.backdrop`（录制在页面根 `Box` 内的内容子树）传给 `ThreadActionsGlassMenu`，
    经 `threadMenuBackdrop(chrome, page)` 决策（`SafeMode.blurDisabled` 时传 `null`，走
    opaque 兜底）；内联 `GlassPopup` 在该录制子树**之外**，不会成环。`pageBackdrop` 的录制
    范围包含内联 `GlassPopup` 自身 → RenderNode 自环 → Android 16 渲染线程栈溢出
    （`computeTransformImpl`；见 HANDOVER §11 / §15.8）。改这里要一起验证预测返回
    （组合顺序不变）。
24. **IME inset 不要叠加**：用 `navigationBars.union(ime)` 一次性取较大底边距；
    输入框高度要计入列表底部 padding。
25. **正文渲染只在 `ForumBodyParser`/`ForumHtmlText`**：帖子正文从 HTML 到块的逻辑就放这两处
    （正文 DOM 选择器不留散到别处；`ForumHtmlParser` 只负责页面抓取与楼层切分）。**代码块
    `div.comiis_blockcode` 绝不能被拍平成段落**——每个 `<li>` 是一行、行内还可能有 `<br>`/
    `&nbsp;` 缩进，必须整块取出行并保持等宽与对齐；标题（`font[size]`）、引用、表格、附件、
    `hr`、`i.pstatus`、`div.comiis_modact` 同理各自成块。真实正文是 HTML，切勿对其跑 BBCode
    正则转换（代码里合法含 `[b]` 等字面量）。
<<<<<<< HEAD
26. **草稿由站点保存（服务器草稿），本机只做离线兜底**：发帖/回复表单里的隐藏域
    `<input type="hidden" id="postsave" name="save" value="">` 就是「保存草稿」——那个按钮只是
    `document.getElementById('postsave').value=1` 后提交**同一个表单**。
    - **`save=1` = 存草稿**：服务端会建一个真实的 `thread-<tid>`，但它**不进版面列表**
      （`forum-<fid>-1.html` 里没有）、**匿名看不到**、只有**作者本人**可见，并出现在草稿列表里；
      它的帖子页带 `<span class="top_jg bg_a f_f">草稿</span>` 徽标。
    - **`save` 为空（或缺省）= 发表**，这才把草稿变成公开主题——**必须**放在显式二次确认后面，
      绝不能做成「离开页面」的副作用。
    - 草稿列表：`forum.php?mod=guide&view=my&type=thread&filter=save&fid=0&mobile=2`
      （桌面版去掉 `mobile=2`）。行与导读/版块列表同构（`li.forumlist_li`），标题取
      `div.mmlist_li_box h2 a`、摘要取 `div.list_body a`、版块取 `div.comiis_xznalist_bk a`、
      时间取 `div.forumlist_li_time span`。行里**没有 pid**，要打开发帖页才拿得到。
    - 草稿编辑页：`forum.php?mod=post&action=edit&fid=<fid>&tid=<tid>&pid=<pid>&page=1&mobile=2`。
      删除 = `delete=1`，发表 = `save` 空，保存草稿 = `save=1`。**必须整份回放该表单的隐藏域**
      （`formhash/posttime/delete/fid/tid/pid/page/editsubmit/subject/message/htmlon/usesig/save`），
      只手搓最小字段集会被服务端拒绝或静默忽略。
    - 领域/数据层：`ForumDraftService` + `ForumDraftServiceImpl`（`getDrafts` /
      `saveNewThreadDraft` / `saveReplyDraft` / `loadDraft` / `publishDraft` / `deleteDraft` /
      `updateDraft`），列表解析在 `ForumHtmlParser.parseDraftPage`，编辑表单在
      `parseDraftEdit`。本机 `ForumDraftRepository`（DataStore `forum_drafts`，上限 20 条）
      **只在站点保存失败或提前离开页面时**写入，是离线兜底，不再是主存储。
27. **签到只有一条 GET，结果在 CDATA 里**：`plugin.php?id=k_misign:sign&operation=qiandao&format=text&formhash=…`
    返回 `<?xml version="1.0"?>\r\n<root><![CDATA[已签到]]></root>`；`formhash` 只能从签到页
    `k_misign-sign.html` 的**退出链接**取（`parseFormhash` 的 `formhash=` 兜底）。
    `已签到` 是「今天已签」，**不是错误**；其余非错误文案按成功原样展示（服务器文字即结果），
    权限/登录/限流文案必须判失败。接口不返回数字——签到后的排名/连续/累计只能**重新取页**，
    不要自己 +1。游客按规则 18 走登录卡片。解析在 `ForumHtmlParser.parseSignInState` /
    `parseSignInOutcome`，CDATA 抽取只此一处（见 REVERSE_SPEC §22）。
28. **顶栏只能采样页面内容层**：`chrome.backdrop` 由 `MiuixPageChrome.contentBackdropModifier`
    录在滚动容器上（不含顶栏）。`MiuixTopAppBar` 三个分支都只读
    `barBackdrop(backdrop, pageBackdrop)`，宿主层 `MiuixComponentScope.pageBackdrop` 会被拒绝。
    不要为了“加模糊”把 `pageBackdrop` 传给顶栏——那是 §23 同一个自环（HANDOVER §17.2）。
29. **长列表要带滚动条**：`MiuixPageScaffold` 已内置 `MiuixPageScrollBar`；自带 `Scaffold` 的页面
    （帖子/版块/提醒/收藏）把 `MiuixPageScrollBar` 作为列表**兄弟**放在同一个 `Box` 里，传列表自己的
    `contentPadding` 与 `chrome.scrollBehavior`（下拉刷新不受影响，示例 `PullToRefreshPage.kt` 同形）。
30. **分组卡片用 `MiuixGroupedCards`**：需要把连续行画成一张 Miuix 卡片时用
    `miuixGroupedItems` / `MiuixCardSegment`（圆角取 `CardDefaults.CornerRadius`），
    不要各自 `clip(RoundedCornerShape(...))` 手写。单个卡片直接用 `Card` + `CardDefaults`。
31. **解析器只出“能渲染的信息”**：站点模板里纯 JS 的动作行（如 `a.profile_ewmbox` 的 二维码
    浮层）在 `ForumHtmlParser` 就丢弃并写明原因（HANDOVER §17.1）；不要留到 UI 端靠“空值”判断，
    否则会连带吞掉真正空值的字段。
=======

    **行内链接必须可点**：`<a href>` 不能只上色，必须挂 `LinkAnnotation.Url`。注意
    `normalize()`/引用截断会重建 AnnotatedString，**重建时必须重映射 link 注解**，否则链接
    静默退回不可点（正文段落全走 `normalize()`，漏一次就全站失效）。

    **附件必须给「下载 or 浏览器」选择**：附件卡的点击只能弹选择面板
    （`AttachmentChoiceSheet`），不能直接把 `mod=attachment` 丢给浏览器（Cookie 门禁，403 或
    下载到 acw 挑战页）。下载走 `ForumAttachmentDownloader`（复用 `ForumHttpClient.client`
    的 Cookie+acw、流式写 `cacheDir`、可取消、失败要有原因），**禁止**绕过鉴权或静默无响应。
    文件名可来自他人帖子，落盘前必须 `sanitiseAttachmentFileName()`。
26. **草稿只能是本地草稿**：站点发帖表单的 `save=1` **不是**保存草稿——2026-11-27 实测
    `save=1` + `topicsubmit=yes` 会直接发布主题，去掉 `topicsubmit` 也不会落任何草稿
    （`home.php?mod=space&do=thread&view=me&type=draft` 等地址均为 0 条）。发帖/回复草稿一律
    存本机（`ForumDraftRepository`，DataStore `forum_drafts`，上限 20 条，按 `savedAt` 淘汰），
    **禁止**再向站点发送 `save=1` 冒充草稿。
>>>>>>> feat/bbcode2

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
