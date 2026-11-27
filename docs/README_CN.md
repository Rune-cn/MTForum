# MT论坛 客户端

**bbs.binmt.cc** 的第三方 Android 客户端。Jetpack Compose + Miuix，原生 Kotlin。

## 功能

| 模块 | 说明 |
|---|---|
| 论坛首页 | 版块宫格、站点原生「热帖排行」Top-10（与论坛主页同序） |
| 搜索 | 论坛内关键词搜索（登录后使用），结果分页，收藏/评论等入口保持一致 |
| 提醒 | 系统提醒 / 坛友互动 / 我的帖子，点行进对应帖子 |
| 个人主页 | 头像、等级、积分、统计与最近发表（`do=profile`） |
| 我的收藏 | 收藏列表 + 取消收藏（登录后） |
| 版块与分类 | 首页宫格全量版块（12 个）+ 版块主题分类筛选 |
| 帖子页 | 楼层卡片重做、正文可选取文字、显示所属版块、回复框重做 |
| 导读 | 最新 / 热门 / 精华 / 回复 / 抢沙发 五个视图，各自分页 |
| 版块列表 | 置顶/推荐/有图/热门标签、缩略图、作者头像、分页 |
| 帖子详情 | 楼层（楼主/回复区分）、正文富文本渲染（代码块/标题/引用/表格/附件/表情）、楼层内引用回复 |
| 回复 | 右下角悬浮按钮、引用上一楼、论坛表情选择器（3 组 257 个）、本地草稿（保存/恢复/丢弃） |
| 发帖 | 标题+正文（BBCode/表情/图片）、本机草稿（站点不支持草稿保存，见交接 §16） |
| 私信 | 会话列表、会话气泡、收发消息 |
| 收藏 / 关注 / 顶 | 帖子页溢出菜单：收藏、关注楼主、推荐本帖，以及复制链接、在浏览器打开、系统分享 |
| 深链 | `thread-*.html` / `forum-*.html` / `forum.php?mod=` / `mtforum://` 直接在应用内打开 |
| 发新帖 | 标题 + 正文 + 表情 + 图片附件（1MB/张）、**本地草稿**（站点草稿不可用，见 `docs/REVERSE_SPEC.md` §16.1） |
| 图片查看 | 帖子内图片点击全屏，双指缩放、拖动 |
| 主题 | OS3 / OS4 双风格、动态取色、模糊与玻璃效果、HyperOS 风格预测式返回 |
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
行结构与等宽排版（可复制）；`div.comiis_quote` 带作者页眉；`ul/ol`、`table/tr/td`、附件卡片
`div.comiis_attach`、`hr`、`i.pstatus`、`div.comiis_modact` 各有专属块；`[#xxx]` 表情 token
解析成图片。

### 线程

所有网络与解析都在 `Dispatchers.IO` 上执行，`MainThreadSafetyTest` 是静态回归守卫。

## 构建

```bash
bash tools/build.sh                              # debug APK
bash tools/build.sh :app:testStableDebugUnitTest  # 201 tests
bash tools/build.sh :app:assembleStableRelease
bash tools/build.sh :app:lintVitalStableRelease
```

环境要求见 [docs/BUILD_ENV.md](docs/BUILD_ENV.md)（JDK 25、Android SDK 37.2、Gradle 9.8.1）。

## 验证

```
:app:testStableDebugUnitTest    201 tests, 0 failures
:app:assembleStableRelease      BUILD SUCCESSFUL  (2.3 MB)
:app:lintVitalStableRelease     BUILD SUCCESSFUL
```

测试覆盖：acw 签名（与 JS 参考实现交叉验证）、版块/列表/帖子/楼层解析、导读解析、
登录表单与失败响应、正文富文本、帖子卡片 Compose 真实渲染、主线程安全、
私信会话/收藏/发帖表单解析、图片 URL 规范化/解码器路由/会话 Cookie 隔离、
OS4 玻璃菜单像素级锚定。

无模拟器时可直接「看」UI（Robolectric 截图，产物在 `app/build/ui-screenshots/`）：
`ThreadActionsPopupLayoutTest`、`ForumImageScreenshotTest`、`ForumRichBodyScreenshotTest`，
样例见 [screenshots/](screenshots/)。

## 文档

- [docs/HANDOVER_CN.md](docs/HANDOVER_CN.md) — 交接文档（架构、约定、已知问题）
- [docs/REVERSE_SPEC.md](docs/REVERSE_SPEC.md) — 站点逆向规格
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 代码地图
- [docs/BUILD_ENV.md](docs/BUILD_ENV.md) — 构建与测试环境
- [docs/screenshots/](screenshots/) — JVM 截图样例（OS4 菜单 / 图片渲染）
- [AGENTS.md](AGENTS.md) — 面向 AI 代理的开发约定

## 许可

GPL-3.0（见 [LICENSE](LICENSE)）。
