# MT论坛 客户端

**bbs.binmt.cc** 的第三方 Android 客户端。Jetpack Compose + Miuix，原生 Kotlin。

## 功能

| 模块 | 说明 |
|---|---|
| 论坛首页 | 版块宫格、按热度排序的 Top-10 榜单 |
| 导读 | 全量帖子列表，滚动分页加载 |
| 版块列表 | 置顶/推荐/有图/热门标签、缩略图、作者头像、分页 |
| 帖子详情 | 楼层（楼主/回复区分）、正文富文本渲染、楼层内引用回复 |
| 回复 | 右下角悬浮按钮、引用上一楼、论坛表情选择器（3 组 257 个） |
| 登录 | 账号密码登录、会话持久化、退出二次确认 |
| 私信 | 会话列表、会话气泡、收发消息（`home.php?mod=space&do=pm`） |
| 收藏 / 关注 / 顶 | 帖子页溢出菜单一键收藏、关注楼主、推荐本帖 |
| 发新帖 | 标题 + 正文 + 表情 + 图片附件（multipart `Filedata`，1MB/张） |
| 图片查看 | 帖子内图片点击全屏，双指缩放、拖动、双击复位 |
| 主题 | OS3 / OS4 双风格、动态取色、模糊与玻璃效果 |

## 技术要点

### 反爬（acw_sc__v2）

站点对无签名请求返回 4 KB JS 挑战页。客户端在 OkHttp 拦截器里检测挑战页、计算签名并重放请求。
算法实现在 `data/forum/network/AcwSignature.kt`，逆向记录见 [docs/REVERSE_SPEC.md](docs/REVERSE_SPEC.md)。

### 数据获取

站点是 Discuz! X + Comiis 模板，官方移动 API 已下线（502），全部走 HTML 解析（jsoup）。
选择器集中在 `data/forum/network/ForumHtmlParser.kt`，有真实页面样本作为回归测试。

### 正文渲染

站点输出 HTML（不是 BBCode）。渲染用 Compose `AnnotatedString`：`strong`/`i`/`u`/`s`/`a`/`font color`
映射为 `SpanStyle`，`blockquote` 单独成块，`[#xxx]` 表情 token 解析成图片。无需 WebView。

### 线程

所有网络与解析都在 `Dispatchers.IO` 上执行，`MainThreadSafetyTest` 是静态回归守卫。

## 构建

```bash
bash tools/build.sh                              # debug APK
bash tools/build.sh :app:testStableDebugUnitTest  # 47 tests
bash tools/build.sh :app:assembleStableRelease
bash tools/build.sh :app:lintVitalStableRelease
```

环境要求见 [docs/BUILD_ENV.md](docs/BUILD_ENV.md)（JDK 25、Android SDK 37.2、Gradle 9.8.1）。

## 验证

```
:app:testStableDebugUnitTest    47 tests, 0 failures
:app:assembleStableRelease      BUILD SUCCESSFUL  (2.3 MB)
:app:lintVitalStableRelease     BUILD SUCCESSFUL
```

测试覆盖：acw 签名（与 JS 参考实现交叉验证）、版块/列表/帖子/楼层解析、导读解析、
登录表单与失败响应、正文富文本、帖子卡片 Compose 真实渲染、主线程安全、
私信会话/收藏/发帖表单解析。

## 文档

- [docs/HANDOVER_CN.md](docs/HANDOVER_CN.md) — 交接文档（架构、约定、已知问题）
- [docs/REVERSE_SPEC.md](docs/REVERSE_SPEC.md) — 站点逆向规格
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 代码地图
- [docs/BUILD_ENV.md](docs/BUILD_ENV.md) — 构建与测试环境
- [AGENTS.md](AGENTS.md) — 面向 AI 代理的开发约定

## 许可

GPL-3.0（见 [LICENSE](LICENSE)）。
