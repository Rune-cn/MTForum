# 代码地图

包根 `io.rune.mt.forum`。

```
app/src/main/java/io/rune/mt/forum/
├── data/
│   ├── forum/                        论坛数据层
│   │   ├── model/
│   │   │   └── ForumSmilies.kt       表情目录（3 组 257 个，生成自站点 JS）
│   │   ├── network/
│   │   │   ├── AcwSignature.kt       acw_sc__v2 签名算法
│   │   │   ├── AcwInterceptor.kt     OkHttp 拦截器：检测挑战页 → 签名 → 重放
│   │   │   ├── ForumCookieJar.kt     内存 Cookie（= 会话，按 host 下发）
│   │   │   ├── ForumUrls.kt          URL 绝对化 + 占位/非导航 scheme 过滤 + CDN→站点兜底
│   │   │   ├── ForumHttpClient.kt    GET/POST，强制移动 UA，切 IO 调度器
│   │   │   └── ForumHtmlParser.kt    jsoup 解析：版块/列表/帖子/楼层/分页/缩略图
│   │   └── repository/
│   │       ├── ForumRepositoryImpl.kt   仓库实现（每方法 withContext(IO)）
│   │       ├── ForumDraftServiceImpl.kt 服务器草稿（`save=1` 存、`save` 空发表、`delete=1` 删）
│   │       ├── ForumDraftRepositoryImpl.kt  本机草稿兜底（DataStore `forum_drafts`，上限 20 条）
│   │       ├── ForumSessionStore.kt     DataStore 持久化 Cookie
│   │       └── ForumPasswordAuthenticator.kt  账号密码登录
│   ├── settings/                     设置项仓库
│   └── updater/                      更新检查（指向本仓库 Releases）
├── domain/
│   ├── forum/
│   │   ├── model/                    Forum / ForumThread / ForumPost / ForumUser / ForumSession / ForumDraft
│   │   └── repository/               ForumRepository.kt / ForumDraftRepository.kt（含 ForumDraftService）
│   ├── settings/                     偏好模型与用例
│   └── updater/
├── di/
│   ├── ForumModule.kt                论坛 Koin 模块
│   ├── ViewModelModule.kt            所有 VM 注册
│   └── init/app_modules.kt           模块汇总
├── core/
│   ├── image/ForumImageLoader.kt     Coil 配置（CDN：UA+Referer；站点附件：会话 Cookie+acw；coil-gif；
│   │                                  组件全显式注册 + serviceLoaderEnabled(false)，debug/release 一致）
│   ├── crash/ · i18n/ · device/ · env/ · reflection/
├── ui/
│   ├── navigation/
│   │   ├── Routes.kt                 Main / About / Theme / Login / ForumList / ForumThread
│   │   │                             / PrivateConversation / NewThread / Favorites / Drafts
│   │   └── AppNavContainer.kt        NavDisplay + entry 注册
│   ├── page/
│   │   ├── forum/                    论坛页面层
│   │   │   ├── ForumHomePage.kt      版块宫格 + Top-10 热度榜
│   │   │   ├── ForumListPage.kt      版块列表 + 标签 + 缩略图（顶栏「发帖」入口）
│   │   │   ├── ForumThreadPage.kt    楼层 + 悬浮回复 + 楼层内引用 + 图片放大
│   │   │   ├── ForumGuidePage.kt     导读（全量 + 分页）
│   │   │   ├── ForumMessagesPage.kt  私信会话列表
│   │   │   ├── ForumConversationPage.kt  私信会话（气泡 + 回复框）
│   │   │   ├── ForumMessagesViewModel.kt 私信列表/会话状态
│   │   │   ├── NewThreadPage.kt      发新帖 / 编辑站点草稿（标题/正文/图片附件/保存草稿）
│   │   │   ├── NewThreadViewModel.kt 发帖表单、站点草稿保存、发表与删除
│   │   │   ├── ForumDraftsPage.kt    草稿列表（`filter=save`，下拉刷新/翻页/删除）
│   │   │   ├── ForumDraftsViewModel.kt 草稿列表状态与删除
│   │   │   ├── ForumImageViewer.kt   全屏图片查看（双指缩放/拖动）
│   │   │   ├── ForumHtmlText.kt      正文渲染组件 + 块模型（段落/标题/代码/引用/列表/表格/附件）
│   │   │   ├── ForumBodyParser.kt    正文 HTML → 块列表（dom 选择器只在此处；代码块不拍平）
│   │   │   ├── ForumAvatar.kt        统一头像组件
│   │   │   ├── ForumSignInCard.kt    设置页「每日签到」卡片（排名/连续/累计 + 签到按钮）
│   │   │   ├── ForumSignInViewModel.kt 签到页状态 + 签到结果（CDATA 文案）
│   │   │   ├── ForumViewModel.kt     VM + 各页面 State
│   │   │   ├── ReplyComposer.kt      回复框（引用 + 表情）
│   │   │   ├── SmileyPicker.kt       表情选择器
│   │   │   ├── ForumGuidePage.kt     导读五视图（最新/热门/精华/回复/抢沙发）
│   │   │   └── ThreadActions.kt      顶栏溢出按钮 + 页面根部 OS4 玻璃菜单（收藏/顶/关注/复制/分享）＋返回关菜单
│   │   ├── login/                    登录页 / VM / 状态
│   │   ├── main/forum/               论坛 VM 与 State（旧位置，保留兼容）
│   │   ├── miuix/
│   │   │   ├── settings/             设置页（账号区块、主题入口、关于）
│   │   │   └── widgets/              共享组件（页面脚手架、顶栏、弹窗等）
│   │   └── theme/ · library/ · animation/
└── App.kt
```

## 数据流

```
ForumHttpClient（IO 线程）
   ↓ HTML
ForumHtmlParser（jsoup）
   ↓ 领域模型
ForumRepositoryImpl ── ForumCookieJar / ForumSessionStore
   ↓ StateFlow
ForumViewModel
   ↓
Compose 页面（Miuix 组件，OS3/OS4 自适应）
```

## 关键约束

1. **契约层单点定义**：`domain/forum/**` + `data/forum/repository/ForumRepositoryImpl.kt`。UI 只消费 `ForumRepository`。
2. **反爬不可绕过**：所有请求必须经 `ForumHttpClient`（挂着 `AcwInterceptor`）。
3. **DOM 选择器集中**：只在 `ForumHtmlParser`，改动必须有测试样本支撑。
4. **线程**：阻塞调用必须在 `Dispatchers.IO`（`MainThreadSafetyTest` 守卫）。
5. **Miuix 双风格**：页面用 `MiuixPageScaffold` + `rememberMiuixPageChrome()`；
   顶栏图标用 `MiuixTopBarIconButton`；弹窗按 `AppTheme.useMiuixOs4` 分支。
6. **OS4 `GlassPopup` 是内联 overlay**：必须组合在页面根 `BoxScope(fillMaxSize)`，
   放在顶栏/单元格里会被 host 钳制而错位（见 HANDOVER §12.1）。
7. **图片两条路**：CDN 静态图裸请求即可；站点 `mod=image`/`mod=attachment` 必须走带
   `ForumCookieJar` + `AcwInterceptor` 的图片客户端；URL 统一经 `ForumUrls` 规范化。
8. **无模拟器的 UI 验证**：Robolectric + Compose 截图（`ThreadActionsPopupLayoutTest`、
   `ForumImageScreenshotTest`、`Os4PredictiveBackScreenshotTest`）；截图前需
   `LocalSquircleEnabled provides false` 绕开 AGSL。
9. **预测式返回只有一条实现**：OS4 卡片动效在 `MiuixOs4NavTransition.kt`，由
   `appNavTransition(useMiuixOs4=…)` 选择；`NavDisplayEffects`（圆角/压暗）由 `AppNavContainer` 统一给。
   entry 内**不得**注册常驻 `BackHandler`/`PredictiveBackHandler`（会抢走 NavDisplay 的手势驱动）。
   唯一例外：菜单展开时的 `ThreadActionsBackHandler`（miuix `PredictiveBackHandler`，组合在
   `ThreadActionsGlassMenu` 之后，`enabled = expanded`）。
10. **首页榜单与导读都是站点数据源**：热帖排行解析 `forum.php` 的 `comiis_mh_txtlist_phb`
   （站点顺序，不重排）；导读走 `ForumUrls.guidePath(view, page)`，五个 view 共用列表解析器。
11. **深链只有一个入口**：`ForumDeepLink.forumDeepLinkFrom(Uri)` + `ForumDeepLinkState`
   一次性 hand-off；Manifest filter 与 `onNewIntent` 不另写解析。
12. **会员页必须先判登录**：搜索（`search.php`）、提醒（`do=notice`）、个人资料（`do=profile`）、
   收藏（`do=favorite`）都只对登录态可见，匿名会 302 到登录页。仓库层用
   `ForumLoginRequiredException`（或 `isLoginRequired()`）抛/判，UI 显示登录卡片，不发“空结果”。
13. **搜索结果行的 fid 来自行内链接**：`parseThreadRow(li, fid = null)` 从每行自己的
   `forum-<fid>-1.html` 取 fid；版块列表则沿用请求的 fid。搜索 POST 必须先取 `formhash`，
   再按 `searchid` + `kw` 翻页。
14. **版块宫格必须是全量**：`getHome()` 一次调用内并发取 `forum.php`（宫格 + 热帖榜）与
   `forum.php?forumlist=1`（全量、含分组），`parseForums` 合并三处并按 fid 去重、
   **保持站点顺序**；`em` 数字是「今日 N」，记 `Forum.todayCount`。
15. **主题分类走 `filter=typeid`**：`ForumThreadPage.categories` 由
   `#forumlist_time_li` 解析（首项「全部」= `forum-<fid>-1.html`），
   `getThreads(fid, page, typeId)` 拼接筛选；UI 用 `TabRow`，无分类的版块不显示。
16. **置顶公告没有 byline**：站点公告条只输出标题。不要用空作者渲染问号头像：
   `ForumViewModel.enrichNoticeAuthors()` 按 tid 补抓线程页 OP，失败时卡片显示置顶图钉。
17. **OS4 菜单玻璃采样页面内容层，绝不能用页面 backdrop**：`ForumThreadPage` 把
   `chrome.backdrop`（`rememberMiuixPageChrome`，录制在页面根 `Box` 内的内容子树）传给
   `ThreadActionsGlassMenu`，经 `threadMenuBackdrop(chrome, page)` 决策（`SafeMode.blurDisabled`
   时传 `null`，走 opaque 兜底）；内联 `GlassPopup` 组合在该录制子树**之外**，故不成环。
   `pageBackdrop` 由 app 级 host 录制在整棵导航树外，而 `GlassPopup` 就在同一棵树里 →
   采样它就是 RenderNode 自环（hwui 渲染线程 `computeTransformImpl` 栈溢出，HANDOVER §11 / §15.8）。
   改这里要一起验证预测返回（组合顺序不变）。
18. **私信/输入框的 IME inset 不要叠加**：用
   `WindowInsets.navigationBars.union(WindowInsets.ime)` 一次性取较大底边距，
   并把 composer 实测高度加进列表底部 padding。
