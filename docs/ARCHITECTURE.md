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
│   │   │   ├── ForumCookieJar.kt     内存 Cookie（= 会话）
│   │   │   ├── ForumHttpClient.kt    GET/POST，强制移动 UA，切 IO 调度器
│   │   │   └── ForumHtmlParser.kt    jsoup 解析：版块/列表/帖子/楼层/分页/缩略图
│   │   └── repository/
│   │       ├── ForumRepositoryImpl.kt   仓库实现（每方法 withContext(IO)）
│   │       ├── ForumSessionStore.kt     DataStore 持久化 Cookie
│   │       └── ForumPasswordAuthenticator.kt  账号密码登录
│   ├── settings/                     设置项仓库
│   └── updater/                      更新检查（指向本仓库 Releases）
├── domain/
│   ├── forum/
│   │   ├── model/                    Forum / ForumThread / ForumPost / ForumUser / ForumSession
│   │   └── repository/ForumRepository.kt
│   ├── settings/                     偏好模型与用例
│   └── updater/
├── di/
│   ├── ForumModule.kt                论坛 Koin 模块
│   ├── ViewModelModule.kt            所有 VM 注册
│   └── init/app_modules.kt           模块汇总
├── core/
│   ├── image/ForumImageLoader.kt     Coil 配置（UA + Referer）
│   ├── crash/ · i18n/ · device/ · env/ · reflection/
├── ui/
│   ├── navigation/
│   │   ├── Routes.kt                 Main / About / Theme / Login / ForumList / ForumThread
│   │   │                             / PrivateConversation / NewThread
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
│   │   │   ├── NewThreadPage.kt      发新帖（标题/正文/图片附件）
│   │   │   ├── NewThreadViewModel.kt 发帖表单与提交
│   │   │   ├── ForumImageViewer.kt   全屏图片查看（双指缩放/拖动）
│   │   │   ├── ForumHtmlText.kt      正文渲染组件
│   │   │   ├── ForumBodyParser.kt    HTML/BBCode → AnnotatedString 块
│   │   │   ├── ForumAvatar.kt        统一头像组件
│   │   │   ├── ForumViewModel.kt     VM + 各页面 State
│   │   │   ├── ReplyComposer.kt      回复框（引用 + 表情）
│   │   │   ├── SmileyPicker.kt       表情选择器
│   │   │   └── ThreadActions.kt      顶栏溢出菜单（收藏/顶/关注/复制/分享，OS3/OS4 双风格）
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
