# MT论坛 客户端

**bbs.binmt.cc** 的第三方 Android 客户端。Jetpack Compose + Miuix，原生 Kotlin。

## 功能

| 模块 | 说明 |
|---|---|
| 论坛首页 | 版块宫格、站点原生「热帖排行」Top-10（与论坛主页同序）、可分页的「最新主题」帖子流 |
| 搜索 | 论坛内关键词搜索（登录后使用）：**入口只有首页顶栏放大镜**，覆盖层带最近搜索（本地保存、可清除、一键复搜），结果分页 |
| 提醒 | 系统提醒 / 坛友互动 / 我的帖子，点行进对应帖子 |
| 个人主页 | 头像、等级、积分、统计与最近发表（`do=profile`） |
| 我的收藏 | 收藏列表 + 取消收藏（登录后） |
| 每日签到 | 首页右上角入口（登录后）：今日排名 / 连续签到 / 累计签到，已签到置灰，服务器文案原样提示 |
| 版块与分类 | 首页宫格全量版块（12 个）+ 版块主题分类筛选 |
| 帖子页 | 楼层卡片重做、正文可选取文字、显示所属版块、回复面板改为与签到同款底部面板 |
| 首页顶栏 | 搜索放大镜（Miuix 搜索覆盖层 + 最近搜索）/ 发帖（先选板块）/ 每日签到 |
| 消息 | 私信 + 系统提醒 / 回复我的 / 我的帖子 四个页签（标签按内容定宽，窄屏横向滚动而不是省略号） |
| 导读 | 最新 / 热门 / 精华 / 回复 / 抢沙发 五个视图，各自分页 |
| 版块列表 | 置顶/推荐/有图/热门标签、缩略图、作者头像、分页 |
| 帖子详情 | 楼层（楼主/回复区分）、正文富文本渲染（代码块/标题/引用/表格/附件/表情/块级对齐/表头与合并列/可点击链接）、**附件可选择「下载到本机 / 浏览器打开 / 复制链接」**（付费附件先提示「下载将扣除 N 金币」，下载走 attachcredit 两跳）、楼层内引用回复 |
| 回复 | 右下角悬浮按钮、引用上一楼、论坛表情选择器（3 组 257 个）、与签到同款的底部面板（OS4 玻璃 / OS3 窗口），关闭即丢弃（回复无草稿） |
| 发帖 | 标题+正文（BBCode/表情/图片）、服务器草稿（站点自带草稿，见交接 §16） |
| 私信 | 会话列表、会话气泡、收发消息；正文支持 **BBCode**（b/i/u/引用/链接工具栏 + 预览），`添加图片` 走 MT 图床插入 `[img]` |
| 收藏 / 关注 / 顶 | 帖子页溢出菜单：收藏、关注楼主、推荐本帖，以及复制链接、在浏览器打开、系统分享 |
| 深链 | `thread-*.html` / `forum-*.html` / `forum.php?mod=` / **`forum.php` 与 `mtforum://home` 回首页「论坛」页签** / `mtforum://` 直接在应用内打开 |
| 发新帖 | 标题 + 正文 + 表情 + 图片（**MT 图床** `img.binmt.cc`：原图直传并插入 `[img]…[/img]`，默认；或站点附件 1MB/张）、**服务器草稿**（`save=1`；列表/编辑/发表/删除，见 `docs/REVERSE_SPEC.md` §16.1 与 §24） |
| 图片查看 | 帖子内图片点击全屏，双指缩放、拖动 |
| 主题 | OS3 / OS4 双风格、动态取色、模糊与玻璃效果、HyperOS 风格预测式返回；主题模式用示例应用的「浅色/深色预览卡片 + 跟随系统开关」 |
| MCP 服务 | 设置里开关的本机回环 MCP 服务器（`POST /mcp`，JSON-RPC 2.0）：向 AI 客户端提供**只读**的版块 / 主题列表 / 帖子楼层（纯文本 + 附件）/ 搜索；无鉴权、无任何写操作，见 [MCP.md](MCP.md) |
| 界面 | 对齐 Miuix 官方示例应用：玻璃顶栏采样页面内容层、长列表滚动条、分组卡片圆角（见交接 §17） |

## 技术要点

### 反爬（acw_sc__v2）

站点对无签名请求返回 4 KB JS 挑战页。客户端在 OkHttp 拦截器里检测挑战页、计算签名并重放请求。
算法实现在 `data/forum/network/AcwSignature.kt`，逆向记录见 [docs/REVERSE_SPEC.md](docs/REVERSE_SPEC.md)。

### 数据获取

站点是 Discuz! X + Comiis 模板，官方移动 API 已下线（502），全部走 HTML 解析（jsoup）。
选择器集中在 `data/forum/network/ForumHtmlParser.kt`，有真实页面样本作为回归测试。

### 正文渲染

站点把帖子存成 BBCode、由 Comiis 模板在服务端渲染成 HTML，所以客户端拿到的是 HTML，任务是
忠实还原它。渲染用 Compose `AnnotatedString`，无需 WebView：行内 `strong`/`i`/`u`/`s`/`a`/
`font color` 映射为 `SpanStyle`；`font[size]` 变成标题层级；`div.comiis_blockcode` 整块保留
行结构与等宽排版（可复制）；`div.comiis_quote` 带作者页眉并保留体内列表/表格/图片；`ul/ol`（不限层数）、`table/tr/th|td`（表头 + `colspan`）、块级 `[align]` 容器、可点击 `<a>`、附件卡片（含下载选择；付费附件显示「下载将扣除 N 金币」并走 attachcredit 握手）
`div.comiis_attach`、`hr`、`i.pstatus`、`div.comiis_modact` 各有专属块；`[#xxx]` 表情 token
解析成图片。

### 线程

所有网络与解析都在 `Dispatchers.IO` 上执行，`MainThreadSafetyTest` 是静态回归守卫。

## 构建

```bash
bash tools/build.sh                              # debug APK
bash tools/build.sh :app:testStableDebugUnitTest  # 481 tests
bash tools/build.sh :app:assembleStableRelease
bash tools/build.sh :app:lintVitalStableRelease
```

环境要求见 [docs/BUILD_ENV.md](docs/BUILD_ENV.md)（JDK 25、Android SDK 37.2、Gradle 9.8.1）。

## 验证

```
:app:testStableDebugUnitTest    481 tests, 0 failures
:app:assembleStableRelease      BUILD SUCCESSFUL  (2.3 MB)
:app:lintVitalStableRelease     BUILD SUCCESSFUL
```

测试覆盖：acw 签名（与 JS 参考实现交叉验证）、版块/列表/帖子/楼层解析、导读解析、
登录表单与失败响应、正文富文本、帖子卡片 Compose 真实渲染、主线程安全、
私信会话/收藏/发帖表单解析、图片 URL 规范化/解码器路由/会话 Cookie 隔离、
MT 图床两步上传（令牌 → 上传、419 刷新重试、`status:false`、坏 JSON，全部对本机回环 server）、
两个编辑器的图床插入（光标位置 / 失败不丢字）、OS4 玻璃菜单像素级锚定、
应用内 MCP 服务（JSON-RPC 分发、回环 socket 往返/405/413/429、设置持久化与设置页渲染）。

无模拟器时可直接「看」UI（Robolectric 截图，产物在 `app/build/ui-screenshots/`）：
`ThreadActionsPopupLayoutTest`、`ForumImageScreenshotTest`、`ForumRichBodyScreenshotTest`，
样例见 [screenshots/](screenshots/)。

## 文档

- [docs/HANDOVER_CN.md](docs/HANDOVER_CN.md) — 交接文档（架构、约定、已知问题）
- [docs/REVERSE_SPEC.md](docs/REVERSE_SPEC.md) — 站点逆向规格
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 代码地图
- [docs/MCP.md](MCP.md) — 应用内 MCP 服务（端点、JSON-RPC、工具、安全边界）
- [docs/BUILD_ENV.md](docs/BUILD_ENV.md) — 构建与测试环境
- [docs/screenshots/](screenshots/) — JVM 截图样例（OS4 菜单 / 图片渲染）
- [AGENTS.md](AGENTS.md) — 面向 AI 代理的开发约定

## 许可

GPL-3.0（见 [LICENSE](LICENSE)）。

## 赞助

感谢您对此项目的支持喵！如果此项目对您有帮助，可以通过微信赞赏码进行赞赏喵！

<img src="sponsor/wechat-sponsor-qr.png" alt="微信赞赏码" width="240" />

赞赏完全自愿，不影响任何功能——所有功能都是免费的。
