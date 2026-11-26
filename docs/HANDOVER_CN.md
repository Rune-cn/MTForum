# 交接文档

> MT论坛 客户端 · 包名 `io.rune.mt.forum` · 仓库 https://github.com/Rune-cn/MTForum
> 最后更新 2026-11-26 · 约 209 个 Kotlin 文件 / 18k 行 · 84 个测试（17 个测试类）

本文件是接手这个项目的第一入口。读完这一份就能动工；
需要站点细节时再看 [REVERSE_SPEC.md](REVERSE_SPEC.md)，需要找文件时看 [ARCHITECTURE.md](ARCHITECTURE.md)。

---

## 1. 这个项目是什么

`bbs.binmt.cc`（MT论坛）的第三方 Android 客户端。

**为什么不用官方 API**：站点是 Discuz! X + Comiis 移动模板，官方移动 API
（`api/mobile/index.php`）已经下线，全线返回 502。所以所有数据靠解析 HTML 获得。

**为什么不用 WebView**：正文渲染只需要文本、图片、少量行内样式，
用 `AnnotatedString` 原生渲染比套浏览器引擎轻得多，也能跟随主题色。

### 已实现 / 未实现

| 已实现 | 未实现 |
|---|---|
| 版块首页 + Top-10 热度榜 | 会话过期自动重登（见 §12） |
| 导读（全量 + 分页） | 图片上传的进度/多图压缩 |
| 版块列表（标签、缩略图、分页） | 编辑/删除自己的帖子 |
| 帖子详情（楼层、楼主区分） | 点赞列表、@ 提醒 |
| 回复 + 引用上一楼 | 深色模式下部分页面微调 |
| 论坛表情（3 组 257 个） | |
| 账号密码登录 + 会话持久化 | |
| 退出二次确认 | |
| OS3 / OS4 双主题 | |
| **私信（会话列表 + 会话 + 收发）** | |
| **收藏（帖子页一键收藏 + 列表）** | |
| **关注楼主 / 顶（推荐）** | |
| **发新帖（含图片附件上传）** | |
| **帖子内图片点击放大（可缩放）** | |

> 本轮（2026-11-26）新增的 5 个功能对应 REVERSE_SPEC §12–§16，全部按线上实测接口实现。

---

## 2. 架构

```
                   ┌─────────────────────────────┐
   bbs.binmt.cc ──▶│ ForumHttpClient             │  OkHttp
                   │  └ AcwInterceptor           │  挑战页 → 签名 → 重放
                   │  └ Dispatchers.IO           │  阻塞调用切线程
                   └──────────┬──────────────────┘
                              │ HTML
                   ┌──────────▼──────────────────┐
                   │ ForumHtmlParser (jsoup)     │  版块/列表/帖子/楼层/分页/缩略图
                   └──────────┬──────────────────┘
                              │ 领域模型
                   ┌──────────▼──────────────────┐
                   │ ForumRepositoryImpl         │
                   │  ├ ForumCookieJar           │  内存会话
                   │  ├ ForumSessionStore        │  DataStore 持久化
                   │  └ ForumPasswordAuthenticator│  账号密码登录
                   └──────────┬──────────────────┘
                              │ StateFlow
                   ┌──────────▼──────────────────┐
                   │ ForumViewModel              │  首页/列表/帖子/导读/回复
                   └──────────┬──────────────────┘
                              │ Compose State
                   ┌──────────▼──────────────────┐
                   │ Miuix 页面（OS3/OS4 自适应）│
                   └─────────────────────────────┘
```

### 三个独立关注点

1. **反爬**（`AcwSignature` + `AcwInterceptor`）：透明地解决 JS 挑战，上层无感。
2. **解析**（`ForumHtmlParser`）：站点 DOM 的唯一知识来源。
3. **渲染**（`ForumBodyParser` + `ForumHtmlText`）：把 HTML/BBCode 变成 Compose。

任何一层出问题，先定位是哪一层：抓 `ForumHttpClient` 的日志看拿到的是不是真实页面。

---

## 3. 关键机制

### 3.1 反爬 acw_sc__v2

无签名的请求会得到 4 KB 的 JS 挑战页（含 `arg1='<hex>'`），不是内容。
签名算法：

```
acw_sc__v2 = hexXor(unsbox(arg1), KEY)
KEY = "3000176000856006061501533003690027800375"
```

`AcwInterceptor` 检测到挑战页 → 算签名 → 设 Cookie → 重放（最多 3 次）。
实测 2 次即可拿到真实页面。

### 3.2 会话

Discuz 纯 Cookie 认证，核心是 `cQWy_2132_auth`。

**取 uid 的坑**：只能从 `cQWy_2132_lastcheckfeed`（格式 `<uid>|<ts>`）取。

| Cookie | 内容 | 能取 uid 吗 |
|---|---|---|
| `*_lastcheckfeed` | `153466\|1795300445` | ✅ |
| `*_lip` | `222.84.222.7%2C1795299217` | ❌ 是客户端 IP |
| `*_ulastactivity` | `a3e7EBiJGs7...` | ❌ 不透明哈希 |
| `*_comiis_coloruid_153466` | `7s` | ⚠️ uid 在 **cookie 名**里 |

早期用 `lip` 取 uid → 永远 null → 会话判定失败 → **登录明明成功却报「密码错误」**。

**会话失效必须显式判断**：过期 Cookie 访问 `home.php?mod=space&uid=` 会 302 到登录页并返回
**200**。早期用 `title.substringBefore("的广播")` 兜底，而「登录 - MT论坛」不含该串时
`substringBefore` 原样返回整串（非空）→ 未登录被误判为已登录。
现在要求 `span.user_tit` 真实存在。

### 3.3 登录

两步握手（已实测通过）：

```
GET  member.php?mod=logging&action=login          → formhash + loginhash
POST member.php?mod=logging&action=login&loginsubmit=yes&loginhash=…&inajax=1
     字段：formhash, referer=https://bbs.binmt.cc/./, fastloginfield=username,
           cookietime, username, password
```

- 成功：返回 `window.location.href='…'` + 下发 `cQWy_2132_auth`
- **失败也是 HTTP 200**，靠文本判别：`<p>登录失败，您还可以尝试 4 次…</p>`

`loginhash` 实测非强制（带与不带都能成功），客户端保留它因为那是表单原样参数。
失败时把服务端原话（含剩余次数）直接回显给用户。

### 3.4 分页（踩过两次坑）

| 页面 | URL | 备注 |
|---|---|---|
| 版块列表 | `forum.php?mod=forumdisplay&fid={fid}&page={n}` | |
| 帖子 | `thread-{tid}-{page}-1.html` | **页码在第二段** |
| 导读 | `forum.php?mod=guide&view=newthread&page={n}` | |

⚠️ `thread-{tid}-1-{page}.html` **不报错，静默返回第 1 页**——曾是「加载下一页没反应」的真因。

⚠️ `forum.php?mod=guide&view=hot` 会 302 到门户首页（没有帖子列表）。
真正的导读是 `view=newthread` / `view=digest`，DOM 与版块列表一致，可复用同一个解析器。

### 3.5 正文渲染

站点输出的是 **HTML 不是 BBCode**（实测多个帖子，标签为
`blockquote/br/div/font/i/img/strong/table/td/tr`）。

但客户端发送回复时用的是 BBCode（`[quote]`/`[b]`/`[#呵呵]`），所以解析器双向兼容：

```
原文 ──(bbcodeToHtml)──▶ HTML ──(jsoup)──▶ 块列表 ──▶ Compose
                                               ├ Paragraph(AnnotatedString)
                                               ├ Picture(url)
                                               ├ EmojiRow(urls)
                                               └ Quote(AnnotatedString)
```

行内样式映射：

| HTML | SpanStyle |
|---|---|
| `strong`/`b` | `FontWeight.Bold` |
| `em`/`i` | `FontStyle.Italic` |
| `u`/`ins` | `Underline` |
| `s`/`del` | `LineThrough` |
| `a` | `primary` 色 + 下划线 |
| `font color` | 解析 hex / 少量颜色名 |

颜色解析**没有**用 `android.graphics.Color.parseColor`——它在 JVM 单测里是 stub 会抛异常，
被 `runCatching` 吞掉后样式静默消失。已改为自己解析。

### 3.6 表情

目录来自 CDN 文件 `https://cdn.binmt.cc/data/cache/common_smilies_var.js`：

```js
smilies_type['_12'] = ['默认', 'qq']                                  // 分组：[标签, 目录]
smilies_array[12][1] = [['1240','[呵呵]','qq001.gif','20','20','50']] // [id, BBCode, 文件名]
```

已生成成 Kotlin 常量表 `data/forum/model/ForumSmilies.kt`（3 组 257 个）。
选表情时插入 `[#呵呵]`，服务端会渲染成图片；本地预览也解析同一 token。

**注意**：code 不全局唯一（`[#呵呵]` 在滑稽组和 QQ 组都有），`byCode` 保留首个。

### 3.7 图片（会话感知，2026-11-26 重写）

图片分两类，不能一概而论「公开资源」：

| 类别 | 例子 | 需要什么 |
|---|---|---|
| CDN 静态图 | `cdn.binmt.cc/uc_server/data/avatar/…jpg`、`icdn.binmt.cc/…gif`、`oos.binmt.cc/image/…jpg` | 只要 UA + Referer；**不需要** Cookie |
| 站点代发/附件 | `bbs.binmt.cc/forum.php?mod=image&aid=…`、`mod=attachment` | **必须**带登录会话 Cookie，且要过 acw 挑战 |

`core/image/ForumImageLoader.kt` 里 `buildForumImageHttpClient(cookieJar)` 用**独立的**
OkHttpClient：共用 `ForumCookieJar` + `AcwInterceptor`（由 `App.onCreate` 在 `startKoin`
之后懒解析注入），但**不复用** `ForumHttpClient`（避免带上写路径拦截器和 `forum.php` Referer）。
站点 host 之外的 CDN 不会命中 acw 拦截器（`AcwInterceptor` 对非 `bbs.binmt.cc` 直接放行，
否则每张图都要 `peekBody(64KB)`），Cookie 也只按 host 下发。

URL 规范化统一走 `data/forum/network/ForumUrls.kt`：`//cdn…`、`/data/attachment/…`、
`data/attachment/…` 都转绝对地址；`javascript:`/`file:`/`data:` 返回 null（模板里的图片按钮）。
正文图片加载失败会做一次「CDN → `bbs.binmt.cc` 同路径」重试（Discuz 资源树两边都有），
仍失败则 `Timber.w("picture failed: %s", url)`（只记 URL，不记 Cookie）。

GIF 由 `coil-gif`（API 28+ 用 `AnimatedImageDecoder`，以下用 `GifDecoder`）处理：没有它时
Coil 走 `BitmapFactoryDecoder` 只出首帧，首帧透明的 GIF 看起来就是空白——这是「部分图/头像
像没加载」的次要原因之一，不是主因。

### 3.8 线程模型

`suspend` **不会**自动切线程。所有阻塞调用（`execute()`、jsoup 解析）必须包
`withContext(Dispatchers.IO)`：

- `ForumHttpClient.getHtml/postForm` 内部切 IO
- `ForumRepositoryImpl` 每个公开方法再包一层（覆盖解析开销）

漏掉就会在真机上抛 `NetworkOnMainThreadException`（曾导致首页和导读直接崩）。
`MainThreadSafetyTest` 是静态守卫。

---

## 4. 目录与职责

| 路径 | 文件数 | 职责 |
|---|---|---|
| `data/forum/network/` | 5 | 反爬、Cookie、HTTP、解析 |
| `data/forum/model/` | 1 | 表情目录 |
| `data/forum/repository/` | 3 | 仓库实现、会话持久化、密码登录 |
| `domain/forum/` | 8 | 领域模型 + 仓库接口 |
| `ui/page/forum/` | 14 | 论坛全部页面与组件 |
| `ui/page/login/` | 4 | 登录 |
| `ui/page/miuix/` | 26 | 设置、主题、关于、共享组件 |
| `core/` | 14 | 崩溃、i18n、图片加载、设备信息 |

完整目录树见 [ARCHITECTURE.md](ARCHITECTURE.md)。

---

## 5. 开发约定（违反会出问题）

1. **不要绕过 `ForumHttpClient`**。只有它挂 `AcwInterceptor`，直连 OkHttp 只会拿到 JS 挑战页。
2. **阻塞调用必须 `withContext(Dispatchers.IO)`**。`suspend` 不切线程。
3. **DOM 选择器只在 `ForumHtmlParser`**。改选择器必须同步更新
   `app/src/test/resources/forum/` 的样本与断言。
4. **字符串三语同步**：`values/`(en) + `values-zh-rCN` + `values-zh-rTW`。
   删键必须三处一起删，否则 `lintVital` 报 `ExtraTranslation` 致命错误。
5. **头像统一用 `ForumAvatar`**。不要各自判断 `noavatar` 换字母——
   卡片和帖子内曾因此表现不一致（卡片把 6/10 的头像换成了字母）。
6. **顶栏图标用 `MiuixTopBarIconButton`**。它按 `AppTheme.useMiuixOs4` 分支玻璃/普通样式；
   直接 `IconButton` 会在 OS4 下样式错。
7. **弹窗必须按风格分支**：
   - OS4 → `GlassPopup`（需要**真实的 `anchorBounds`**）+ `GlassPopupItem`
   - OS3 → `WindowListPopup` + `ContextMenuPositionProvider` + `Align.TopEnd`
   `Rect.Zero` 会让弹窗开在窗口左上角；`Align.Start` 会让右边缘按钮的菜单溢出。
8. **图片必须给高度约束**。`AsyncImage` + `fillMaxWidth()` 而没有高度时，
   尺寸未知的图在加载完成前高度为 0，Coil 就没机会显示。
9. **不要条件化 `MiuixComponentHost`**。它的 `Scaffold` 用 `SubcomposeLayout`，
   条件移除会在同帧销毁子组合并崩溃（`Could not resolve state for movable content`）。
10. **禁止日志打印 Cookie 明文**，只可打 uid / isLoggedIn。
11. **提交**：Conventional Commits，主体 ≤72 字符，不加 AI 署名。

---

## 6. 构建与测试

```bash
bash tools/build.sh                                # debug 冒烟
bash tools/build.sh :app:testStableDebugUnitTest    # 84 个测试，必跑
bash tools/build.sh :app:assembleStableRelease
bash tools/build.sh :app:lintVitalStableRelease     # 改字符串/资源后必跑
```

工具链与踩坑细节见 [BUILD_ENV.md](BUILD_ENV.md)（JDK 25 / SDK 37.2 / Gradle 9.8.1 / miuix 0.9.5-beta03）。

### 测试覆盖

| 测试 | 覆盖什么 |
|---|---|
| `AcwSignatureTest` | 反爬签名，与 JS 参考实现交叉验证 |
| `ForumParserTest` | 版块索引、帖子列表、楼层、导读 |
| `ForumLoginFormTest` | 登录表单字段、失败响应（200 + 文本） |
| `ForumRichTextTest` | 粗体/斜体/链接/颜色/引用/表情/图片/BBcode |
| `MainThreadSafetyTest` | 静态守卫：阻塞调用必须切 IO |
| `ForumCardRenderTest` | 帖子卡片 **Compose 真实渲染**（Robolectric） |
| `ForumMessagingParserTest` | 私信会话列表/会话详情、收藏、发新帖表单 |
| `ThreadActionsPopupLayoutTest` | OS4 玻璃菜单**像素级锚定**（右缘对齐/不出窗/宿主=整页）+ 截图 |
| `ForumImageUrlTest` | 头像/缩略图 URL 绝对化、占位图过滤、`javascript:` 不拼接 |
| `ForumImageDecoderTest` | GIF→gif 解码器、JPEG→BitmapFactory 的路由断言 |
| `ForumImageSessionTest` | 图片客户端带会话 jar + acw、Cookie 不跨 host |
| `AcwImageFetchTest` | acw 只对 html/xhtml 探测；`image/*` 只取一次、不重放 |
| `ForumBodyImageUrlTest` | 正文图懒加载属性优先于 `src` 占位；`javascript:` 不产生图片块 |
| `ForumImageScreenshotTest` | **JVM 截图**：真实卡片 + 正文渲染、失败占位块落盘 PNG |

### 无模拟器时怎么「跑」UI（2026-11-26 新增）

本机跑不了模拟器，用 Robolectric 把真实组件组合出来、**截成 PNG 直接看**：

```bash
bash tools/build.sh :app:testStableDebugUnitTest \
  --tests "io.rune.mt.forum.ThreadActionsPopupLayoutTest"   # OS4 菜单截图
bash tools/build.sh :app:testStableDebugUnitTest \
  --tests "io.rune.mt.forum.ForumImageScreenshotTest"       # 卡片+正文图片截图
```

产物在 `app/build/ui-screenshots/`（`ForumImageScreenshotTest`）与
`artifacts/`（菜单测试）；仓库里存了两张样例：[docs/screenshots/os4-thread-actions-menu.png](screenshots/os4-thread-actions-menu.png)、
[docs/screenshots/jvm-image-render.png](screenshots/jvm-image-render.png)。

⚠️ miuix 的 squircle 用了 AGSL RuntimeShader，Robolectric 自带 Skia 编译不了
（`'color' is not a valid layout qualifier`）。截图测试里用
`CompositionLocalProvider(LocalSquircleEnabled provides false)` 绕开——只影响截图圆角观感，
不影响布局测量。

### 加新页面的检查清单

1. 在 `ui/navigation/Routes.kt` 加 `@Serializable data class/object`
2. 在 `AppNavContainer.kt` 注册 `entry<Route.X>`
3. 页面用 `MiuixPageScaffold` + `rememberMiuixPageChrome()`
4. VM 加到 `di/ViewModelModule.kt`
5. 字符串三语同步
6. 跑测试 + `lintVital`

---

## 7. 已知限制

1. **未在真机运行过**（最重要的一条）。本机无法启动 Android 模拟器
   （沙箱禁止嵌套虚拟化：AEHD 驱动可装且 `emulator-check accel` 报 usable，
   但 qemu 客户机 CPU 时间始终 <2s、永不 boot）。
   UI 用 Robolectric 验证「元素存在且在视口内」，但**验证不了**：
   - 间距与排版的实际观感
   - 滚动流畅度
   - 玻璃模糊（OS4）的真实效果
   - 图片在真实网络下的加载表现
2. **发帖的图片附件未做线上实测**：接口按页面表单实现（multipart `Filedata`，1MB 限制），
   为避免往版面灌测试帖，只验证到「表单解析 + 字段正确」，真实提交需真机确认。
3. **顶（推荐）未做线上实测**：接口不可撤销，实测会给帖子永久 +1，故只实现不实测。
4. **点赞数是客户端算的**：`回复数×2 + 浏览数/10`，不是站点官方榜单。
5. **会话过期无自动重登**：表现为只读（能看帖，不能回帖）。
   自动重登需要保存密码，方案见 REVERSE_SPEC §17，尚未实现（当前版本不落盘任何凭据）。
6. **发帖遇验证码会直接拒绝**：站点当前对这几个版块未启用验证码，
   一旦启用，解析器会检测到 `needsCaptcha` 并在 UI 上提示，而不是盲提交。

---

## 8. 下一步优先级

1. **真机验证**（最高优先）。装机后重点看：版块宫格间距、帖子卡片留白、
   表情选择器手感、OS4 玻璃效果、图片加载，以及本轮新增的私信/发帖页。
2. **发帖真实提交 + 图片上传**（接口已就绪，缺真机验证）
3. **会话过期自动重登**（需先决定是否落盘凭据，见 REVERSE_SPEC §17）
4. **收藏列表页**（仓库已有 `getFavorites`，UI 入口未挂到设置页）
5. 编辑/删除自己的帖子
6. 深色模式下部分页面微调

---

## 9. 快速排障

| 现象 | 排查方向 |
|---|---|
| 页面空白 / 只有几行脚本 | `AcwInterceptor` 是否拿到签名；打印响应长度（4321 = 挑战页） |
| 帖子「加载下一页」无反应 | URL 是否 `thread-{tid}-{page}-1.html`（页码在第二段） |
| 导读空白 | 是否用了 `view=hot`（应 `view=newthread`） |
| 登录成功却提示密码错 | `cookieUid()` 是否读 `lastcheckfeed`（不是 `lip`） |
| 未登录被当成已登录 | `getUserProfile` 是否校验了 `span.user_tit` |
| 头像/图片不显示 | `ForumImageLoader` 是否带 UA + Referer；图片容器是否给了高度 |
| 站点图床/附件图空白（`mod=image`、`mod=attachment`） | 图片客户端是否拿到 `ForumCookieJar` + `AcwInterceptor`；匿名请求会拿到 4321B 的 acw 挑战页 |
| 个别头像显示成字母 | URL 是否为 `pic/none.png` 占位或 `javascript:;`；`ForumUrls.absolute` 是否返回 null |
| OS4 菜单错位 / 遮罩只盖住按钮 | `GlassPopup` 必须组合在页面根 `BoxScope(fillMaxSize)`，不能放在顶栏 actions 单元格里 |
| 弹窗开在左上角 | `anchorBounds` 是不是 `Rect.Zero`（没测量） |
| 右上角菜单溢出屏幕 | OS3 是否用 `ContextMenuPositionProvider` + `Align.TopEnd` |
| `NetworkOnMainThreadException` | 阻塞调用是否漏了 `withContext(IO)` |
| `ExtraTranslation` 构建失败 | 三语 strings 键不一致 |

---

## 10. 站点速查

| 用途 | 地址 |
|---|---|
| 论坛首页 | `https://bbs.binmt.cc/forum.php` |
| 版块 | `forum.php?mod=forumdisplay&fid={fid}` |
| 帖子 | `thread-{tid}-{page}-1.html` |
| 导读 | `forum.php?mod=guide&view=newthread` |
| 个人空间 | `home.php?mod=space&uid={uid}` |
| 登录 | `member.php?mod=logging&action=login` |
| 回帖 | `forum.php?mod=post&action=reply&fid=&tid=` |
| 发帖 | `forum.php?mod=post&action=newthread&fid=` |
| 表情目录 | `https://cdn.binmt.cc/data/cache/common_smilies_var.js` |

已知版块 fid：44 综合交流、41 逆向交流、42 编程开发、50 休闲灌水、39 玩机交流、
38 建议反馈、40 求助问答、2 版本发布。

---

## 11. 真机崩溃记录：hwui 栈溢出（2026-11-25）

### 现象

```
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR)
Cause: stack pointer is close to top of stack; likely stack overflow.
name: RenderThread
#00..#512  libhwui.so (computeTransformImpl(DirtyStack const*, Matrix4*)+0/+36)
```

512 帧全部落在 `computeTransformImpl` 的递归里，说明 **RenderNode 层级异常深**（或有环）。

### 结论

崩溃发生在**渲染线程**，与解析、网络、Compose 重组逻辑无关。
当时生效的渲染特性里，唯一会构造 `LayerBackdrop` 并用 `RenderEffect` 回采场景的是
**blur 链路**：

```
rememberMiuixBlurBackdrop(enableBlur)      ← 记录整个子树到 LayerBackdrop
  └ MiuixPageScaffold: Modifier.layerBackdrop(backdrop)  ← 内容录进图层
  └ MiuixTopAppBar: Modifier.textureBlur(backdrop = …)    ← 再从该图层采样
```

容器（pager）和每个页面各自建一层 backdrop，嵌套录制会把同一棵子树反复展开成
RenderNode，刚好就是 `DirtyStack` 链过深的表现。

### 处理

1. **blur 默认关闭**。注意要改两处，只改 `ThemeState` 的默认值**无效**——
   `AppSettingsRepositoryImpl` 里写的是 `prefs[UI_USE_BLUR] ?: true`，那个才是实际默认。
2. **SafeMode 崩溃回环保护**（`core/safemode/SafeMode.kt`）：
   上次运行留下 crash dump 时，本次会话强制不建 backdrop，并把标记写进
   `SharedPreferences`（同步，早于 DataStore 流）。用户在主题设置里重新打开 blur 会清除标记。
   没有这层保护的话，一旦开着 blur 启动即崩，设置被持久化，会形成**永远进不去设置**的死循环。
3. OS4 弹窗（`ThreadActions`）的 `backdrop` 传 `null`：从窗口级浮层采样页面图层是另一种
   制造环的方式。

### 仍未证实

上述是针对渲染线程崩溃的**最可能解释**，但本机跑不了模拟器，**没有实机复现验证**。

如果关掉 blur 后仍然崩溃，说明还有别的 RenderNode 环，排查顺序建议：

1. 逐个页面二分（先只留首页，再加导读/列表/帖子）
2. 关掉 `beyondViewportPageCount = 1`（相邻页面同时组合，各自持有 backdrop）
3. 检查 `MiuixPageScaffold` 的 `layerBackdrop` 与顶栏 `textureBlur` 的层级关系：
   顶栏必须**在图层外**、与之同级，不能落在被录制的子树里
4. 用 `adb shell dumpsys gfxinfo` / `Layout Inspector` 看 RenderNode 深度

### 复现所需信息

再次遇到时请提供：**崩溃前停在哪个页面、做了什么操作**（启动即崩 / 进帖子崩 / 开关 blur 时崩）。
这三者指向完全不同的 RenderNode 路径。

---

## 12. 真机反馈修复：OS4 菜单错位 + 图床/附件图空白（2026-11-26）

两个 bug 都来自真机反馈，本机无模拟器/无设备，因此用 **JVM + Robolectric 截图/像素断言**定位并
验证（见 §6「无模拟器时怎么跑 UI」）。两项修复都有可复现的失败→通过证据，但**都没有真机复核**。

### 12.1 OS4 帖子页右上角三点菜单展开错位

**根因**：`GlassPopup` 不是 `Popup` 窗口，而是**内联 overlay**（`Box(fillMaxSize)` 遮罩 +
`Layout` 用窗口坐标 place 面板，`placeGlassPopup` 把面板钳进 `intersect(window, host)`）。
它被组合在顶栏 `actions` 槽的 `Box` 里 → host 只有按钮单元格大小：
面板被钳到错误一端、遮罩只盖住按钮，而 `Box(fillMaxSize)` 作为 Row 兄弟又把 actions 行撑高，
`onGloballyPositioned` 把跳变后的按钮坐标回写给 `anchorBounds`，形成反馈，最终就是所见「错位」。

**修复**：状态与 anchorBounds 提升成 `ThreadActionsState`（`rememberThreadActionsState()`）；
`ThreadActions` 只画按钮并上报 `boundsInWindow()`；新增 `BoxScope.ThreadActionsGlassMenu()`
在 `ForumThreadPage` 的页面根 `Box(fillMaxSize)` 里组合（OS3 的 `WindowListPopup` 仍留在按钮旁，
保持原锚点）。miuix KDoc 的原话就是 "Place it in the page's root BoxScope"。

**证据**（822×1782 px，真实顶栏 + 真实 ThreadActions）：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| popup host 尺寸 | 798×1782（= actions 行宽） | 822×1782（= 整页） |
| anchor 坐标 | 展开瞬间跳到 (0, −839) | 稳定 (710, 8, 798, 96) |
| 面板矩形 | right=424（跑到左边） | (398, 8, 798, 480)：right=anchor.right，top=anchor.top |

回归测试 `ThreadActionsPopupLayoutTest`（4 个，含一条**反证测试**：把同一批组件放回旧嵌套断言
host≠页面尺寸）与截图 [screenshots/os4-thread-actions-menu.png](screenshots/os4-thread-actions-menu.png)。

### 12.2 站点图床/附件图片加载不出（真机反馈「MT 自带图床的图片不显示」）

**根因**：图片客户端此前刻意「不复用会话」——只带 UA + Referer。CDN 静态图确实不需要登录，
但 Discuz 的附件/图床代理是 **`bbs.binmt.cc/forum.php?mod=image&aid=…` / `mod=attachment`**，
匿名请求返回的是 **200 + 4321B 的 acw 挑战页（text/html）**，Coil 把它当图片解码必然失败。
（对照实测：`cdn/icdn/oos` 三类 CDN 直连 200 `image/jpeg|gif`，带不带 Referer 都一样，所以不是防盗链。）

**修复**：
1. `buildForumImageHttpClient(cookieJar)`：独立 OkHttpClient，共用 `ForumCookieJar` +
   `AcwInterceptor`；`App.onCreate` 在 `startKoin { … }` 之后**立即**取 `ForumCookieJar`（eager，
   没有 `runCatching` 静默降级）再 `SingletonImageLoader.setSafe`，任何图片请求都不会抢跑。
   `AcwInterceptor` 先按 host 放行（非 `bbs.binmt.cc` 不探测），再按 **Content-Type** 只对
   `text/html`/`xhtml` `peekBody(64KB)` 并重放；`image/*` 不缓冲不重放（线上真机链路实测：
   带 acw 后 `mod=image` 返回 `image/gif`）。
2. `ForumCookieJar.loadForRequest` 改为按 `Cookie.matches(url)` 过滤——会话 Cookie 不再泄漏给
   CDN；`AcwInterceptor` 对非 `bbs.binmt.cc` 直接放行（不再对每张图 `peekBody(64KB)`）。
3. 新增 `ForumUrls.absolute()/imageFallback()`：URL 绝对化（`//`、`/`、裸相对路径）与
   `javascript:/file:/data:` 过滤；正文图片失败时 CDN → `bbs.binmt.cc` 同路径重试一次，
   最终失败记 `Timber.w("picture failed: %s")`（只记 URL）。
4. `coil-gif` + 显式注册解码器：GIF 动图；没有它时首帧透明的 GIF 看着就是空白（次要原因）。
5. 占位属性（`comiis_loadimages`/`src=none.png`）不会被当成图片下发（测试锁定）。
6. **懒加载属性优先**：正文 `imageUrl()` 现在按 `comiis_loadimages → file → zoomfile → data-src`
   取真实地址，只有 `src` 不是占位（`none.png`/`imageloading.gif`/`file:`/`data:`/`javascript:`）时才用
   `src`——图床/附件图在正文里是懒加载的，之前读 `src` 只拿到 1px 透明占位。
7. **失败可见**：正文图彻底失败后在占位块中央显示 `⚠ <host>`（并用 `Timber.w` 记 URL），
   用户截图即可指出是哪个域/哪类 URL 失败，无需 adb 或登录。

**测试**：`ForumImageUrlTest` / `ForumImageDecoderTest` / `ForumImageSessionTest` /
`ForumImageScreenshotTest` 覆盖；失败→通过样例：相对头像 `""` → 绝对 URL；缩略图
`data/attachment/...` → `https://cdn...`；`javascript:;` 不再拼成站点 URL。

**仍未验证**：`mod=image` 在**真实登录会话**下是否返回图片字节，本环境无凭据，只能在装配层
（jar 按 host 过滤 / client 含 acw / fallback 映射）证明；需要真机登录后看带附件帖。

### 12.3 已发布 Release

- `26.11`（versionCode 12）：首个修复版，release 签名（**不是 debug 签名**）。
- `26.11.1`（versionCode 13）：在 26.11 上再加固——`AcwInterceptor` 按 Content-Type 门控
  （`image/*` 不探测不重放）、图片 loader 在 `startKoin` 后 eager 注入、Coil ServiceLoader 关闭
  （组件表 debug/release 一致），并新增 `AcwImageFetchTest` 与失败占位截图测试。
- 签名密钥**不**入库：本机 `C:\Users\Administrator\Documents\1\secrets\mtforum-release.jks`
  （密码在同目录 `mtforum-release.properties`）；GitHub 侧用 `KEYSTORE_BASE64` 等 4 个 Secrets。
  该文件丢失后无法再签发可升级包，务必备份。
