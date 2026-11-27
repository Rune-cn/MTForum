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
   **底部面板同理**：OS4 → `GlassBottomSheet`（内联，渲染进根 `Miuix Scaffold` 的 dialog 层，
   因此宿主 `MiuixComponentHost` 必须在；`backdrop` 传页面内容层或 `null`），
   OS3 → `WindowBottomSheet`（真实窗口）。**回复面板与签到面板共用同一个 sheet 实现**：
   统一走 `ui/page/miuix/widgets/MiuixBottomSheet`（标题头 + 关闭动作 + 相同内边距/圆角/手势，
   风格分支只在该助手里），别各写一套。实例见 `ForumSignInSheet`、`ReplyComposer`、
   `AttachmentChoiceSheet`。
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
    两者都是登录账号自己的论坛数据，放在这里最直觉）；**提醒不进顶栏**——系统提醒 / 坛友互动 /
    我的帖子三个分类在底栏「消息」页里用 `TabRow` 与「私信」并列（`ForumMessagesPage`），首页顶栏
    只留「搜索放大镜 / 发帖 / 签到」三个动作。
    **每日签到从首页顶栏最右侧的 `Tasks` 图标进**（`ForumSignInSheet` 底部面板，OS4 玻璃/OS3
    窗口；面板内容 `ForumSignInCard`，游客仍是登录卡片），签到**不进**设置页；
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

    **行内链接必须可点**：`<a href>` 不能只上色，必须挂 `LinkAnnotation.Url`。注意
    `normalize()`/引用截断会重建 AnnotatedString，**重建时必须重映射 link 注解**，否则链接
    静默退回不可点（正文段落全走 `normalize()`，漏一次就全站失效）。

    **span/链接偏移必须按真实变换重映射，不能只减一个 delta**：`normalize()` 折叠模板空白时，
    内部的换行+缩进、连续空格都会让**后面所有** offset 左移。只补 `leadingTrim` 会把
    `link`/颜色/下划线画到相邻字符上（真机反馈「有些 bbcode 蓝色解析错位置了」）。重建时边折叠
    边记 `raw → new` 的索引映射，用映射重算 `spanStyles`/`paragraphStyles`/`LinkAnnotation`
    的 start/end；任何新的“先改写文本再重建 AnnotatedString”路径同理。

    **纯图片的列表是相册，不是 bullet list**：`ul.comiis_img_list`（以及任何“只有图片、没有
    文字”的 `ul`/`ol`）要把每个 `<img>` 按文档顺序发成 `Picture` 块，绝不能走
    `BulletList`——`collectListItems` 只收文字，`ul` 又是块标签，图片会整块消失（真机反馈
    「图片完全不显示」，样本 `album_images_177269.html`）。文字+图片混排的列表保留文字行，
    图片按文档顺序**跟在列表之后**；只有图片的 `li` 不产生空 bullet。真实列表仍是 `BulletList`。

    **表格列宽必须按内容加权，禁止等宽**：`<table>` 不能给每个单元格同一个 weight——5 列表格里
    只要有一列是长散文，均分就会把短列压到一字一行、URL 断行（真机反馈「表格方面显示不行」，
    样本 `rich_156696.html`，见 HANDOVER §3.5）。列宽由 `ForumHtmlText.kt` 的
    `tableColumnWeights()` 按每列最长单元格字数算（短列有下限、长列取剩余、全列皆长则回退等
    weight），整表过宽时在卡片内 `horizontalScroll`；表头靠「整行 `<strong>`」识别，单元格
    `<div align>`/`<td align>`/`text-align` 要生效（文本需 `fillMaxWidth()`）。

    **附件必须给「下载 or 浏览器」选择**：附件卡的点击只能弹选择面板
    （`AttachmentChoiceSheet`），不能直接把 `mod=attachment` 丢给浏览器（Cookie 门禁，403 或
    下载到 acw 挑战页）。下载走 `ForumAttachmentDownloader`（复用 `ForumHttpClient.client`
    的 Cookie+acw、流式写 `cacheDir`、可取消、失败要有原因），**禁止**绕过鉴权或静默无响应。
    文件名可来自他人帖子，落盘前必须 `sanitiseAttachmentFileName()`。

    **付费附件必须走 attachcredit 握手**：卡片的 `p.attach_size` 有 `下载积分: 金币 -N` 时，
    `mod=attachment` 首跳只会 302 到 `action=attachcredit` 的「提示信息」页，**不会**直接给文件。
    该页要识别为握手页（URL 含 `action=attachcredit`，或正文含 `attachcredit` /
    `点击此链接进行跳转`），提取**唯一**的继续链接（`aid` 哈希会重新生成、并多一个 `ck` 令牌，
    `&amp;` 要还原成 `&` 且按原字符串请求），**只再跳一次**；第二跳仍是 HTML 就按失败处理，
    绝不循环。**绝不能把这张握手页当成 acw 挑战页**（那会报「challenge page」并把付费附件判死）；
    也不要自己再写第二套 acw 求解器。命中 `金币不足/抱歉/无法下载/请先登录/权限` 等失败通知时，
    把服务端文案原样作为错误抛出，不要盲目重试。付费价格要在用户点「下载」**之前**显示
    （`Attachment.costCoins` → `forum_attach_cost_hint`），不得自动确认付费下载。

26. **发帖编辑器的图片、板块与三条硬约束**：图片选择用 `OpenMultipleDocuments` + 持久 URI，
    经 `ComposerImagePrep` 缩放/重编码到站点 1MB 限制内（仍超则**拒绝并给原因**，绝不静默丢弃
    ——「添加图片没有用」就是这么来的）；上传走站点自身的 multipart `Filedata`，**没有**独立
    图床 API。换板块必须重新取表单（`formhash`/`posttime` 按表单签发）并让提交 URL 用新 `fid`；
    草稿绑定原板块，编辑草稿时板块选择器禁用。表情/图片等动作统一用 `Arrangement.spacedBy`
    定距，编辑器文案一律走三语资源。

27. **草稿由站点保存（服务器草稿），本机只做离线兜底**：发帖/回复表单里的隐藏域
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
    - **拉到数据就显示，绝不用会话标志挡列表**：草稿页按 `ForumDraftsPhase`（`Rows / Loading /
      LoginRequired / Error / Empty`）渲染，`drafts.isNotEmpty()` 永远排第一。cookie 恢复是**异步**的
      （`App.restoreForumSession`），首帧 `session.isLoggedIn=false` 不代表用户没登录；把
      `!session.isLoggedIn` 放在数据分支前面，就会出现「站点有草稿、App 一片空白」。
    - **空 ≠ 错误 ≠ 需要登录**：三者必须长得不一样——加载中 / 已加载但 0 条（「草稿箱是空的」）/
      失败（站点原文 + 重试）/ 站点回登录表单（登录卡片）。网络或 HTTP 失败**绝不能**渲染成
      「没有草稿」。
    - **登录后要重问**：`loaded` 只说明"这次请求拿到了结果"。上次结果是 `loginRequired` 而会话
      已经登录（用户从登录卡片登录回来）时，`loadIfNeeded()` 必须再取一次，否则登录卡片永远粘住。
      页面的 `LaunchedEffect` 要 key 在 `session.isLoggedIn` 上，而不是 `Unit`。
    - **诊断日志**：`ForumDraftService` 每次加载打一行 `url / http / finalUrl / bytes / rows /
      parsed`（不含 Cookie）。`rows` 是 `li.forumlist_li` 原始行数、`parsed` 是解析出的行数，
      用来区分「站点没有草稿」和「选择器失配」；`finalUrl` 便于发现被 302 回登录页。
    - **草稿只属于发帖，回复没有草稿**：回复框（`ReplyComposer`）**不得**出现「保存草稿」/
      「恢复草稿」/「丢弃草稿」，`ReplyState` 也不再持有 `pendingDraft`/`draftSaved`/`draftError`/
      `isDraftBusy`。关闭回复框就是丢弃输入，`dismissReply()` 不写任何本机副本。
      `ForumDraftRepository`/`ForumDraftService` 的 reply 方法保留在数据层（历史与测试），但
      生产路径**不再调用**——机主要求「帖子和评论要分开」。
28. **签到只有一条 GET，结果在 CDATA 里**：`plugin.php?id=k_misign:sign&operation=qiandao&format=text&formhash=…`
    返回 `<?xml version="1.0"?>\r\n<root><![CDATA[已签到]]></root>`；`formhash` 只能从签到页
    `k_misign-sign.html` 的**退出链接**取（`parseFormhash` 的 `formhash=` 兜底）。
    `已签到` 是「今天已签」，**不是错误**；其余非错误文案按成功原样展示（服务器文字即结果），
    权限/登录/限流文案必须判失败。接口不返回数字——签到后的排名/连续/累计只能**重新取页**，
    不要自己 +1。游客按规则 18 走登录卡片。解析在 `ForumHtmlParser.parseSignInState` /
    `parseSignInOutcome`，CDATA 抽取只此一处（见 REVERSE_SPEC §22）。
29. **顶栏只能采样页面内容层**：`chrome.backdrop` 由 `MiuixPageChrome.contentBackdropModifier`
    录在滚动容器上（不含顶栏）。`MiuixTopAppBar` 三个分支都只读
    `barBackdrop(backdrop, pageBackdrop)`，宿主层 `MiuixComponentScope.pageBackdrop` 会被拒绝。
    不要为了“加模糊”把 `pageBackdrop` 传给顶栏——那是 §23 同一个自环（HANDOVER §17.2）。
30. **长列表要带滚动条**：`MiuixPageScaffold` 已内置 `MiuixPageScrollBar`；自带 `Scaffold` 的页面
    （帖子/版块/提醒/收藏）把 `MiuixPageScrollBar` 作为列表**兄弟**放在同一个 `Box` 里，传列表自己的
    `contentPadding` 与 `chrome.scrollBehavior`（下拉刷新不受影响，示例 `PullToRefreshPage.kt` 同形）。
31. **分组卡片用 `MiuixGroupedCards`**：需要把连续行画成一张 Miuix 卡片时用
    `miuixGroupedItems` / `MiuixCardSegment`（圆角取 `CardDefaults.CornerRadius`），
    不要各自 `clip(RoundedCornerShape(...))` 手写。单个卡片直接用 `Card` + `CardDefaults`。
32. **解析器只出“能渲染的信息”**：站点模板里纯 JS 的动作行（如 `a.profile_ewmbox` 的 二维码
    浮层）在 `ForumHtmlParser` 就丢弃并写明原因（HANDOVER §17.1）；不要留到 UI 端靠“空值”判断，
    否则会连带吞掉真正空值的字段。

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
