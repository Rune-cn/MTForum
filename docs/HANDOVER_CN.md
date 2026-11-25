# 交接文档

> MT论坛 客户端 · 包名 `io.rune.mt.forum` · 仓库 https://github.com/Rune-cn/MTForum
> 最后更新 2026-11-22 · 约 183 个 Kotlin 文件 / 17.8k 行 · 42 个测试

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
| 版块首页 + Top-10 热度榜 | 发新帖（含图片上传、验证码） |
| 导读（全量 + 分页） | 私信（消息页是占位） |
| 版块列表（标签、缩略图、分页） | 收藏 / 关注 / 赞 的 UI |
| 帖子详情（楼层、楼主区分） | 会话过期自动重登 |
| 回复 + 引用上一楼 | 帖子内的图片点击放大 |
| 论坛表情（3 组 257 个） | 深色模式下部分页面微调 |
| 账号密码登录 + 会话持久化 | |
| 退出二次确认 | |
| OS3 / OS4 双主题 | |

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

### 3.7 图片

`core/image/ForumImageLoader.kt` 配置 Coil，带移动 UA + 站点 Referer——
CDN（`cdn.binmt.cc` / `icdn.binmt.cc` / `oos.binmt.cc`）会拒绝裸请求。

**不复用** `ForumHttpClient`：那个带 acw 拦截器和会话 Cookie，图片是公开资源。

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
bash tools/build.sh :app:testStableDebugUnitTest    # 42 个测试，必跑
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
2. **消息页是占位**。私信在 `home.php?mod=space&do=pm`，属独立 Discuz 模块，尚未逆向。
3. **发新帖未实现**。`action=newthread` 字段已清楚
   （`formhash`/`posttime`/`topicsubmit`/`subject`/`message`），
   但图片上传（multipart）与验证码未处理。
4. **收藏/关注/赞未接线**。接口已知（见 REVERSE_SPEC §7.4），UI 只有复制链接与系统分享。
5. **热度排行是客户端算的**：`回复数×2 + 浏览数/10`，不是站点官方榜单。
6. **会话过期无自动重登**，表现为只读（能看帖，不能回帖）。

---

## 8. 下一步优先级

1. **真机验证**（最高优先）。装机后重点看：版块宫格间距、帖子卡片留白、
   表情选择器手感、OS4 玻璃效果、图片加载。
2. **私信模块**（消息页空着，用户能直接感知）
3. **发新帖**（含图片上传）
4. **收藏/关注/赞** 接线
5. **会话过期自动重登**
6. 帖子内图片点击放大

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
