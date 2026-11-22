# 交接文档

> 最后更新：2026-11-22 · 对应 commit `0721ee2`
> 仓库：https://github.com/Rune-cn/MTForum

## 1. 一句话

`bbs.binmt.cc` 的第三方 Android 客户端。包名 `io.rune.mt.forum`，应用名 `MT论坛`。
可浏览版块与帖子、账号登录、回复与引用、论坛表情、OS3/OS4 双主题。

## 2. 架构

```
OkHttp + AcwInterceptor          反爬：挑战页 → 签名 → 重放
   ↓ HTML
ForumHtmlParser (jsoup)          版块/列表/帖子/楼层/分页/缩略图
   ↓ 领域模型
ForumRepositoryImpl              每个方法都包 withContext(IO)
   ├─ ForumCookieJar             内存会话
   └─ ForumSessionStore          DataStore 持久化
   ↓ StateFlow
ForumViewModel                   首页/列表/帖子/导读/回复
   ↓
Compose + Miuix 页面
```

图片走 `core/image/ForumImageLoader.kt`（独立 OkHttp，带 UA + Referer）。
正文渲染走 `ui/page/forum/ForumBodyParser.kt`（BBCode → HTML → AnnotatedString）。

### 数据流要点

- **会话**：Discuz 用 Cookie 认证，`cQWy_2132_auth` 是核心。启动时 `restoreSession()` 用
  `lastcheckfeed` 里的 uid 探测 `span.user_tit` 是否存在来校验。
- **分页**：列表页 `forum.php?mod=forumdisplay&fid=X&page=N`；
  帖子页 `thread-{tid}-{page}-1.html`；导读 `forum.php?mod=guide&view=newthread&page=N`。

## 3. 目录

| 路径 | 内容 |
|---|---|
| `data/forum/network/` | 反爬、Cookie、HTTP、HTML 解析 |
| `data/forum/model/` | 表情目录（生成自站点 JS） |
| `data/forum/repository/` | 仓库实现、会话持久化、密码登录 |
| `domain/forum/` | 领域模型与仓库接口 |
| `ui/page/forum/` | 首页/列表/帖子/导读/消息、正文解析、表情选择、回复框 |
| `ui/page/login/` | 登录页与 VM |
| `ui/page/miuix/` | 设置、主题、关于、共享组件 |
| `core/` | 崩溃、i18n、图片加载、设备信息 |

## 4. 改动约定（铁律）

1. **不要绕过 `ForumHttpClient`**：只有它挂了 `AcwInterceptor`，直连 OkHttp 只会拿到 JS 挑战页。
2. **suspend 不等于切线程**：任何阻塞调用（`execute()`、jsoup 解析）必须包
   `withContext(Dispatchers.IO)`。`MainThreadSafetyTest` 会守住这条。
3. **DOM 选择器只在 `ForumHtmlParser` 里**；改动必须同步更新
   `app/src/test/resources/forum/` 的样本与断言。
4. **字符串三语同步**：`values/`(en) + `values-zh-rCN` + `values-zh-rTW`。
   删键必须三处一起删，否则 `lintVital` 报 `ExtraTranslation`。
5. **头像统一用 `ForumAvatar`**：不要各自判断 `noavatar` 来换字母——列表和帖子内曾经因此表现不一致。
6. **顶栏图标用 `MiuixTopBarIconButton`**：它按 `AppTheme.useMiuixOs4` 分支玻璃/普通样式；
   直接用 `IconButton` 会在 OS4 下样式错。
7. **弹窗按风格分支**：OS4 用 `GlassPopup`/`GlassIconDropdownMenu`，OS3 用 `WindowListPopup`。
8. **不要条件化 `MiuixComponentHost`**：`SubcomposeLayout` 会在同帧崩溃
   （`Could not resolve state for movable content`）。
9. **禁止日志打印 Cookie 明文**，只可打 uid / isLoggedIn。
10. **提交**：Conventional Commits，主体 ≤72 字符，不加 AI 署名。

## 5. 构建与验证

```bash
bash tools/build.sh                               # :app:assembleStableDebug
bash tools/build.sh :app:testStableDebugUnitTest   # 42 tests
bash tools/build.sh :app:assembleStableRelease
bash tools/build.sh :app:lintVitalStableRelease
```

环境与踩坑记录见 [BUILD_ENV.md](BUILD_ENV.md)。

## 6. 测试覆盖

| 测试 | 覆盖 |
|---|---|
| `AcwSignatureTest` | 反爬签名（与 JS 参考实现交叉验证） |
| `ForumParserTest` | 版块索引、帖子列表、楼层、导读 |
| `ForumLoginFormTest` | 登录表单字段、失败响应（HTTP 200 + 文本） |
| `ForumRichTextTest` | 粗体/斜体/链接/颜色/引用/表情/图片 |
| `ForumCardRenderTest` | 帖子卡片 Compose 真实渲染 |
| `MainThreadSafetyTest` | 静态守卫：阻塞调用必须切 IO |

## 7. 已知限制

1. **未在真机运行过**。本机无法启动模拟器（沙箱禁止嵌套虚拟化），
   UI 用 Robolectric 验证「元素存在且在视口内」，但验证不了间距美感、滚动流畅度、
   玻璃模糊实际效果、图片加载的真实网络表现。
2. **消息页是占位**。私信在 `home.php?mod=space&do=pm`，属独立 Discuz 模块，尚未逆向。
3. **发新帖未实现**。`action=newthread` 字段已清楚，但图片上传（multipart）与验证码未处理。
4. **收藏/关注/赞未接线**。接口已知（见 REVERSE_SPEC §7.4），UI 只有复制链接与系统分享。
5. **热度排行是客户端计算**（回复×2 + 浏览/10），不是站点官方榜单。
6. **会话过期无自动重登**，表现为只读。

## 8. 下一步建议

优先级从高到低：

1. 真机跑一遍，确认 UI 观感（这是当前最大的未知）
2. 接私信模块（消息页）
3. 发新帖（含图片上传）
4. 收藏/关注/赞接线
5. 会话过期自动重登

## 9. 快速排障

| 现象 | 排查方向 |
|---|---|
| 页面空白 | `AcwInterceptor` 是否拿到签名；抓 `ForumHttpClient` 日志 |
| 帖子分页无反应 | URL 是否为 `thread-{tid}-{page}-1.html`（页码在第二段） |
| 登录成功却提示密码错 | `cookieUid()` 是否读的 `lastcheckfeed`（不是 `lip`） |
| 头像/图片不显示 | `ForumImageLoader` 是否带 UA + Referer |
| `NetworkOnMainThreadException` | 阻塞调用是否漏了 `withContext(IO)` |
