
## 10. 导读接口（修正）

```
❌ forum.php?mod=guide&view=hot    → 302 到门户首页，页面里没有任何帖子列表
✅ forum.php?mod=guide&view=newthread   → 「导读-最新发表」，50 行帖子
✅ forum.php?mod=guide&view=digest      → 「导读-最新精华」
```

`view=newthread` / `digest` 的 DOM 与版块列表**完全一致**（`li.forumlist_li`），
可以直接复用 `parseThreadPage()`，只需把 `fid` 传 0。

早期实现复用了 `view=hot` 且用列表页选择器，导致导读 tab 一直是空的。

## 11. 线程约束（重要）

`ForumHttpClient` 的 `getHtml` / `postForm` **必须在 `Dispatchers.IO` 上执行**。
`suspend` 修饰符不会自动切换调度器——早期版本只有 `suspend` 而没有 `withContext`，
主页与导读在 Main 调度器上调用即抛 `NetworkOnMainThreadException`。

jsoup 解析同样不便宜（帖子页约 60KB、数百节点），因此 `ForumRepositoryImpl`
的每个公开方法都用 `withContext(Dispatchers.IO)` 包住「取页 + 解析」。

`MainThreadSafetyTest` 是静态回归测试：若有人去掉 `withContext`，它会失败。

---

## 12. 私信（PM）— 2026-11-26 实测

站点私信走 `home.php?mod=space&do=pm`（独立于论坛模块），所有页面均可用移动 UA 直接解析。

### 12.1 会话列表

```
GET home.php?mod=space&do=pm                     # 默认=私人消息
GET home.php?mod=space&do=pm&filter=privatepm    # 私人消息
GET home.php?mod=space&do=pm&filter=announcepm   # 公共消息
```

每个会话一行：

```html
<a href="home.php?mod=space&amp;do=pm&amp;subop=view&amp;touid={uid}" class="b_b">
  <img src=".../noavatar_middle.gif">        <!-- 头像 -->
  <h2><span class="f_d">{日期}</span>{对方用户名}</h2>
  <p class="f_c">{最后一条消息摘要}</p>
</a>
```

选择器：`a[href*="subop=view"][href*="touid="]`；uid 从 href，日期取 `span.f_d`，姓名取 `h2`
去掉日期后的文本，摘要取 `p.f_c`。

### 12.2 会话详情

```
GET home.php?mod=space&do=pm&subop=view&touid={uid}
```

- 容器 `div.comiis_msgbox`，按文档顺序含日期分隔 `div.comiis_msg_date` 与消息条。
- 消息条：对方 `div.comiis_friend_msg`，自己 `div.comiis_self_msg`。
  正文 `div.msg_mes`（HTML），时间 `div.msg_time`，头像 `img.msg_avt`（`.z`=对方 `.y`=自己）。

### 12.3 发送

```
POST home.php?mod=spacecp&ac=pm&op=send&pmid={pmid}&daterange=2&pmsubmit=yes&mobile=2
字段：formhash, touid, message
```

`pmid` 从会话页 `form#pmform` 的 action 里取（无 pmid 时也可直接以 touid 发新会话）。

---

## 13. 收藏 — 两步（GET 出表单 → POST 提交）

**加收藏**：

```
1) GET  home.php?mod=spacecp&ac=favorite&type=thread&id={tid}&handlekey=favorite_thread   # 返回表单页
2) POST home.php?mod=spacecp&ac=favorite&type=thread&id={tid}&spaceuid=&mobile=2
   字段：formhash, favoritesubmit=true, handlekey=favorite_add, description=（理由，可空）, referer={urlencode(帖子URL)}
```

**删收藏**（`favid` 从收藏列表删除链接取）：

```
1) GET  home.php?mod=spacecp&ac=favorite&op=delete&favid={favid}&type=all
2) POST home.php?mod=spacecp&ac=favorite&op=delete&favid={favid}&type=all&mobile=2
   字段：formhash, deletesubmit=true, handlekey=comiis, referer={urlencode(收藏页)}
```

**收藏列表**：`GET home.php?mod=space&do=favorite&view=me&type=all|thread|forum`，
行选择器 `li.mysclist_li`，标题 `a[href*="thread-"]`，favid 在 `a[href*="op=delete"]`。

---

## 14. 关注 — 一步 GET

```
加关注：GET home.php?mod=spacecp&ac=follow&op=add&hash={formhash}&fuid={uid}&mobile=2
取消：  GET home.php?mod=spacecp&ac=follow&op=del&hash={formhash}&fuid={uid}&mobile=2
```

响应是「提示信息」页并 1 秒后 JS 跳转，无 JSON。判断成败读页面标题/提示文本即可
（成功=提示信息页；未登录会跳登录页）。

---

## 15. 顶 / 推荐（赞）— 一步 GET（不可撤销）

```
GET forum.php?mod=misc&action=recommend&handlekey=recommend_add&do=add&tid={tid}&hash={formhash}
```

赞数展示来自 `misc.php?op=recommend&tid={tid}&mod=faq`。因不可撤销，未做线上实测。

---

## 16. 发新帖 — multipart POST

```
POST forum.php?mod=post&action=newthread&fid={fid}&extra=&topicsubmit=yes&mobile=2
Content-Type: multipart/form-data
字段：formhash, posttime, subject, message, htmlon=0, usesig=1, allownoticeauthor=1, save=（空）
附件：Filedata（file，可多个；大小限制 1MB，图片 accept=image/*）
```

- `formhash` / `posttime` 来自发帖表单隐藏域（`forum.php?mod=post&action=newthread&fid={fid}`）。
- 当前该账号/版块发帖表单**无验证码字段**；若站点对某些用户开启验证码，会多出 `seccode` 等，
  解析器 detect 到则不允许提交并提示。
- 附件也可先走 `forum.php?mod=misc&action=postattach` 上传拿 aid，但移动表单本身是 multipart，
  直接把 `Filedata` 一并提交即可（待真机/线上验证，本次未发真实帖以免污染版面）。

### 16.1 `save` 字段 = 「保存草稿」按钮（真·服务器草稿）— 2026-11-27 复测

移动表单里的保存草稿按钮：

```html
<input type="hidden" id="postsave" name="save" value="" />
<button type="button" id="postsubmit" class="comiis_btn formdialog bg_a f_f" value="true"
        onclick="document.getElementById('postsave').value=1;$('postsubmit').click();"><em>保存草稿</em></button>
```

它没有独立接口：只是把 `save=1` 加到同一个 multipart POST 上。**`save=1` 就是真草稿**：

```
POST forum.php?mod=post&action=newthread&fid={fid}&extra=&topicsubmit=yes&mobile=2
Content-Type: multipart/form-data
字段：formhash, posttime, subject, message, htmlon=0, usesig=1, allownoticeauthor=1, save=1
附件：Filedata（可多个）
```

服务端确实建出一个 `thread-<tid>`，但它的可见性与普通主题不同：

| 观测点 | 结果 |
|---|---|
| `forum-<fid>-1.html`（版面列表） | **不含**该 tid（草稿不进版面） |
| 匿名 GET 该草稿的线程页 | 空/拒绝页（只有作者可见） |
| 作者本人 GET 线程页 | 正常渲染，并带 `<span class="top_jg bg_a f_f">草稿</span>` 徽标 |
| 草稿列表 | **列出**该 tid |
| `save` 为空（或缺省）的同一 POST | **发表**，草稿变成公开主题 |

上一轮的「`save=1` 会直接发布」结论**是错的**：那次实测把 `save` 漏掉了（只发了
`topicsubmit=yes`），所以走的是发表分支。判据就是 `save` 字段的有无。

#### 草稿列表

```
GET forum.php?mod=guide&view=my&type=thread&filter=save&fid=0&mobile=2
GET forum.php?mod=guide&view=my&type=thread&filter=save&fid=0        （桌面版，UA 门控）
```

行与导读/版块列表**同构**（`li.forumlist_li`）：

| 字段 | 选择器 |
|---|---|
| tid | `div.mmlist_li_box h2 a` → `thread-<tid>-…` |
| 标题 | 同上（去掉徽标） |
| 摘要 | `div.list_body a` |
| fid / 版块名 | `div.comiis_xznalist_bk a` → `forum-<fid>-1.html` |
| 相对时间 | `div.forumlist_li_time span`（如 `5&nbsp;分钟前`） |

桌面版行是 `tbody#normalthread_<tid>`。行里**没有 pid**，打开发帖页才拿得到。

#### 草稿编辑页（发表 / 再存 / 删除）

```
GET forum.php?mod=post&action=edit&fid={fid}&tid={tid}&pid={pid}&page=1&mobile=2
```

`form#postform` 的隐藏域（**必须整份回放**，只手搓最小字段集会被拒绝或静默忽略）：

```
formhash, posttime, delete(=0), fid, tid, pid, page, editsubmit(=yes),
subject, message, htmlon(=0), usesig(=1), save(=空)
```

三个动作都是「同一表单改一个字段」：

| 动作 | 改的字段 |
|---|---|
| 发表（把草稿变成公开主题） | `save` 空 / `delete=0` |
| 保存草稿（继续留作草稿） | `save=1` |
| 删除 | `delete=1`（页面里的 `comiis_delthread()` 就是这么做的） |

删除不可逆；发表不可逆（无法退回草稿）。两者在客户端都必须走显式二次确认。

#### 回复草稿

回复表单（`form#fastpostform`）带同样的隐藏 `postsave`/`save` 对，所以回复也能存草稿：
把 `save=1` 加到 `replysubmit=yes` 的那个 POST 上即可。

**但回复草稿的「列表」地址仍未证实**：2026-11-27 第二次抓取的
`forum.php?mod=guide&view=my&type=thread&filter=save&fid=0&mobile=2` 只列主题草稿，
页面里**没有**任何指向 `type=reply` 的 tab / 链接（`view=my&type=reply` 一次都没出现），
仓库里也没有回复草稿列表的抓包。所以客户端**不**猜测第二个列表、也不拼 `type=reply`
地址：先用真机日志（`ForumDraftService` 的 `rows` / `parsed`）确认这个列表里到底有没有行，
确认没有再去抓一张回复草稿列表页，补选择器与 §16.1 的表格。

#### 客户端结论

草稿由站点保存，客户端通过 `ForumDraftService` 读写；本机 `ForumDraftRepository` 只在
站点保存失败或提前离开页面时兜底。**不要**再把「用 `save=1` 存草稿」当成禁忌。

---

## 17. 会话过期自动重登

Discuz 纯 Cookie，`cQWy_2132_auth` 失效后访问写接口会被 302 到登录页并返回 200。
自动重登必须持有账号密码：仅在用户显式开启「记住密码并自动重登」时，才把凭据加密保存到
设备（Keystore 派生的 AES-GCM，见 `ForumCredentialStore`）；未开启则不做任何保存。
写操作（回帖/发帖/收藏/关注）收到「未登录」信号时，若凭据可用则重登一次并重试。


---

## 18. 搜索 — POST 铸造 searchid（登录必需，2026-11-26 实测）

```
GET  search.php?mod=forum&mobile=2
       -> <input type="hidden" name="formhash" value="16385279">
POST search.php?mod=forum&mobile=2        (application/x-www-form-urlencoded)
       formhash=16385279&srchtxt=<关键词>&searchsubmit=yes&mod=forum&mobile=2&srchfid=all
       -> 302 search.php?mod=forum&searchid=1591&orderby=lastpost&ascdesc=desc
                &searchsubmit=yes&kw=<urlencoded 关键词>&mobile=2
GET  上一步 URL（body 已是第 1 页，10 行）
       + &page=2  翻页；searchid 与 kw 必须保留，不能只换 searchid
```

- **游客不可搜索**：`search.php` 302 到 `member.php?mod=logging`，或返回
  「抱歉，您所在的用户组(游客)无法进行此操作」。客户端先判登录再发请求。
- 结果行与版块列表同 DOM：`li.forumlist_li`；标题在 `div.mmlist_li_box h2 a`（关键词被
  `<strong><font>` 包裹），板块链接 `a[href*=forum-<fid>-1.html]` 是每行自己的 fid 来源；
  作者 `a.top_user`、头像 `img.top_tximg`、时间 `div.forumlist_li_time span.f_d`、
  摘要 `div.list_body a`、统计 `span.comiis_tm`、分页 `div.pg`。
- 短时间重复搜索会被站点限流（返回提示文本），客户端原样展示，不自行重试。
- 样本：`app/src/test/resources/forum/search_results.html` / `search_results_p2.html`。

---

## 19. 提醒（home.php?mod=space&do=notice，登录必需）

```
GET home.php?mod=space&do=notice&mobile=2                # 聚合入口页
GET home.php?mod=space&do=notice&view=system&mobile=2      # 系统提醒
GET home.php?mod=space&do=notice&view=interactive&mobile=2 # 坛友互动（回复我的）
GET home.php?mod=space&do=notice&view=mypost&mobile=2      # 我的帖子
GET home.php?mod=space&do=notice&view=app&mobile=2         # 应用提醒
```

- 聚合入口页：`div.comiis_userlist ul li a.kmdbt`（我的消息 / 我的粉丝 / 我的帖子 /
  坛友互动 / 系统提醒 / 应用提醒）。**不是**列表页。
- 列表页行：`div.comiis_notices_box > div.comiis_notice_list > ul > li.b_b.bg_f.cl`；
  头像 `a.notice_img img`；正文 `div.ntc_body`（首个 `a[href*=space-uid-]` = 作者，
  目标 `a[target=_blank]` 的 href 含 `ptid=<tid>` 与 `pid=`）；时间 `h2.f_d`
  （内含屏蔽图标 `&#xe67f;`，取文本时剔除私有区字符）。
- 空态：`div.comiis_notip` + 「暂时没有提醒内容」（system/interactive/app 常为空）。
- `view=mypost` / `view=interactive` 还有 `type=` 二级 tab（帖子/点评/活动/悬赏/商品/提到我的；
  打招呼/好友/留言/评论/挺你/分享），在 `#comiis_sub` 里，不在列表容器内。
- **未读标记**：真实 13 行（2026-11-26）全部 `li.b_b.bg_f.cl`，没有任何未读 class，
  未读态无法从 HTML 判断（客户端恒 false）。
- 样本：`notices_mypost.html` / `notices_system.html` / `notices_interactive.html`。

---

## 20. 个人资料（do=profile）

```
GET home.php?mod=space&uid={uid}&mobile=2                 # 只是广播壳，资料走 AJAX，解析不到
GET home.php?mod=space&uid={uid}&do=profile&mobile=2       # 真实资料页
GET home.php?mod=space&uid={uid}&do=thread&view=me&from=space&mobile=2  # 最近发表
```

- 头部：昵称 `div.comiis_space_info h2`、头像 `div.user_img img`、等级 `span.kmlevs.kmlv`、
  头衔 `span.kmlev`；头部一行 `N 人气 | N 关注 | N 粉丝`。
- 统计：`div.comiis_space_profileico li a span`（帖子/回复/好友/粉丝/人气）。
- 积分：`div.comiis_space_profilejf li`（`span.f_0` 数值 + 尾部标签）。
- 资料行：`div.comiis_space_profile li div.profile_rs` + `span` 标签
  （用户ID/生日/性别/在线时间/注册时间/最后访问）；签名 `div.profile_face`
  （站点默认「这家伙很懒…」应过滤）；关注态 `a#followmod`（`op=add` / `op=del`，本人页没有）。
- 最近发表与版块列表同 DOM（`li.forumlist_li`），复用 `parseThreadPage(html, fid = 0, 1)`。
- 匿名访问 302 登录页；样本 `space_profile.html` / `space_profile_own.html` / `space_threads.html`。
- 收藏页的 `formhash` 只出现在退出链接 `member.php?mod=logging&action=logout&formhash=...`
  （HTML 转义成 `&amp;formhash=`），所以 `parseFormhash` 需要 `formhash=` 兜底（见 §13）。

---

## 21. 正文渲染（2026-11-27 实测 + 样本）

### 21.1 核心事实

帖子以 **BBCode 存储**，Comiis 移动模板在服务端渲染成 **HTML**；客户端拿到 HTML，正文渲染
即忠实还原这段 HTML。实测单个 BBCode 标签的输出：

| 发送的 BBCode | 模板输出 HTML |
|---|---|
| `[b]` `[i]` `[u]` `[s]` | `<strong>` `<i>` `<u>` `<strike>` |
| `[color=#ff0000]` / `[color=blue]` | `<font color="#ff0000">` / `<font color="blue">` |
| `[size=5]` | **服务端丢弃**，文本变无样式 |
| `[align=center]` | `<div align="center">…</div>` |
| `[quote]`（普通回复） | 纯文本 + `<br>`，无 blockquote |
| `[quote]`（引用回复） | `div.comiis_quote > blockquote`；作者行 `<font color="#999999">作者 发表于 …</font>` |
| `[code]` | `div.comiis_blockcode > .b_l > ol > li`（每 `<li>` 一行，可含 `<br>`/`&nbsp;`） |
| `[url=…]text[/url]` | `<a href="…" target="_blank">text</a>` |
| `[list][*]` | `<ul><li>` |
| `[table][tr][td]` | `<table><tr><td>…` |
| `[hr]` | `<hr class="l" />` |
| 表情 | `<img src="…/static/image/smiley/…" smilieid="…">` |
| 附件 | `div.comiis_attach`：`a[href*=mod=attachment]` + `p.attach_tit > span.f_ok`（名）+ `p.attach_size`（大小/下载次数） |
| `[align=center]`（块级） | `<div align="center">…</div>`，内部可含列表/表格/图片 |
| `[table]` 首行做表头 | `<tr><td><div align="center"><strong>标题</strong></div></td>…`（**无 `<th>`**，靠整行加粗） |
| `colspan` | `<td colspan="2">`（Discuz `[td=2]` 或富文本表格）；`rowspan` 在 `[table]` 里不出现 |
| 引用被服务端截断 | `blockquote` 体末尾追加字面量 ` ...`（ASCII 三点） |

模板自身还输出两类注释块：`div.comiis_modact`（「本主题由 X 于 … 加入精华」）与
`i.pstatus`（「本帖最后由 X 于 … 编辑」）。正文图片 `span.comiis_postimg img` 的真实地址在
`comiis_loadimages`（`src` 是 `none.png` 占位）。

### 21.2 DOM 选择器

- 正文容器：`div.comiis_postli` → `div.comiis_messages`（`ForumHtmlParser.parsePost`）。
- 代码块：`div.comiis_blockcode > div.b_l > ol > li`，行内 `<br>` 为额外换行，`&nbsp;` 为缩进。
- 标题：`font[size="6|5|4|2"]`（常包 `strong`）。
- 引用：`div.comiis_quote > blockquote`，作者取含「发表于」的 `font[color=#999999]`。
- 附件：`div.comiis_attach`；文件名 `span.f_ok`，说明 `p.attach_size`，链接 `a[href*=mod=attachment]`。
- 分隔线：`hr.l`。
- 表格：`table/tr/th|td`；表头 = 真实 `<th>`/`<thead>` **或**「首行每格整体加粗」；`colspan` 参与列宽。
- 块级对齐：`div[align]` / `p[align]`（`[align=…]` 的服务端输出）。
- 行内链接：`a[href]` → `LinkAnnotation.Url`；`javascript:`/`file:`/`data:` 不产生注解。
- 附件文件类型角标：`div.comiis_attach img[src*=static/image/filetype]`（**是图标，不是正文图片**）。

上述选择器的解析/归块逻辑集中在 `ui/page/forum/ForumBodyParser.kt`（不进 `ForumHtmlParser`，
因为它是对已解析正文的二次渲染，不是页面抓取）。

### 21.3 样本

- `app/src/test/resources/forum/rich_166223.html` —— 社区 BBCode⇄HTML 研究帖，正文含
  39 个 `comiis_blockcode`、`font size=6/5/4` 标题、`hr.l`、`comiis_quote`、`comiis_attach`、
  `pstatus`、`comiis_modact`，是正文渲染的主回归样本。
- `app/src/test/resources/forum/rich_bbcode_reply.html` —— 一个回复里几乎放全了行内标签：
  `strong/i/u/strike`、`font color`、`align=center`、`comiis_blockcode`、`a`、`ul`、`table`、
  `hr`、表情。
- `app/src/test/resources/forum/sample_bbcode_thread.html` —— 与 `rich_166223.html` 同帖的
  社区研究样本（`mobile=2` 变体），正文外链、引用、代码块、附件齐备。
- `app/src/test/resources/forum/sample_bbcode_reply_page2.html` —— 同 `rich_bbcode_reply.html`
  的第 2 页变体，钉住「一个回复放全标签」。
- `app/src/test/resources/forum/sample_attach_thread.html` —— 同帖的附件变体，
  `div.comiis_attach` 里是真实 `.mtp` 附件 + `static/image/filetype/unknown.gif` 角标。
- `app/src/test/resources/forum/rich_156696.html` —— 网盘对比帖：**首行整行加粗当表头**的
  五列表格（`<td><div align="center"><strong>`），块级对齐的唯一真实证据。

**诚实说明**：上述真实样本的 `blockquote` 内都没有 `<img>`；引用内图片的修复由合成用例
（按站点真实 `<img>` 属性形状）支撑，不是某条 fixture 直接证明的。

---

## 22. 每日签到（k_misign 插件）— 2026-11-27 实测

首页「每日签到」卡片 → `k_misign-sign.html`（插件 `k_misign`）。签到页是**整页卡片**，
不是 JSON 接口，也不走已下线的 `api/mobile`。

### 22.1 页面

```
GET k_misign-sign.html        # 即 https://bbs.binmt.cc/k_misign-sign.html（+?mobile=2 输出相同）
```

DOM（Comiis 移动模板，用户块与个人资料头部同构）：

| 字段 | 选择器 | 样例 |
|---|---|---|
| 头像 | `div.comiis_space_info div.user_img img[src]` | `.../avatar/000/15/34/66_avatar_middle.jpg` |
| 昵称 | `div.comiis_space_info h2.fyy` | `Rune` |
| 签到等级 | `div.comiis_space_info p.fyy.comiis_tm` | `签到等级 Lv.8` |
| 三项统计 | `div.comiis_qdinfo ul li` | 见下 |
| 签到按钮 | `div.comiis_btnbox a#signresult` | `<a id="signresult" onClick="ajaxsign();…">签到</a>` |
| 补签入口 | `div.comiis_btnbox a[href*="mid=k_misign:k_misign_bq"]` | `home.php?mod=magic&mid=k_misign:k_misign_bq&bid=…` |
| 排行榜容器 | `div#ranklist` | 由 `ajaxlist('today')` AJAX 填充（**客户端未实现**） |

统计行的形状是「标签在 `span.comiis_tm`，值是该 `li` 去掉标签后的自身文本」：

```html
<li class="f_f"><span class="comiis_tm">今日排名</span>1033</li>
<li class="f_f"><span class="comiis_tm">连续签到</span>3天</li>
<li class="f_f"><span class="comiis_tm">累计签到</span>272天</li>
```

- 未签到时 `今日排名` 的值是 `0`（模板占位），签到后才变成真实名次。
- `连续签到` / `累计签到` 带「天」单位，客户端只取数字。

**已签到的判定**：签到后模板把按钮换成没有 id 的
`<a href="javascript:;" class="comiis_btn bg_b f_c">已签到</a>`。两种信号都认：
`a#signresult` 不存在，或 `div.comiis_btnbox` 里出现 `已签到`。

### 22.2 签到接口（整个 API 就这一个 GET）

```
GET plugin.php?id=k_misign:sign&operation=qiandao&format=text&formhash={formhash}
```

`formhash` 就是签到页自己的会话哈希——该页**没有** `<input name=formhash>`，只有退出链接
`member.php?mod=logging&action=logout&formhash=16385279`（HTML 里是 `&amp;formhash=`），
所以复用 `parseFormhash` 的 `formhash=` 兜底（同 §13 / §20）。

响应既不是 HTML 也不是 JSON，而是全站 ajax 端点通用的 **XML 包 CDATA**（含 `\r\n`）：

```
<?xml version="1.0" encoding="utf-8"?>\r\n<root><![CDATA[已签到]]></root>
```

CDATA 里的文本就是给用户看的结果。**实测到的两种文案**：

| 文案 | 含义 | 客户端映射 |
|---|---|---|
| `已签到` | 今天已经签过，本次没有新增签到 | `ForumSignInOutcome.AlreadySigned` |
| （成功文案未观测到：测试账号当天已签到） | 签到成功 | `ForumSignInOutcome.Success`（非错误、非「已签到」一律按成功，文案原样展示） |

失败文案（权限 / 登录 / 限流，例如「抱歉，您所在的用户组(游客)无法进行此操作」
「…间隔少于…」）必须判成 `Failure`，**不能**当成功。

- 游客访问签到页会被 302 到登录页（`ForumHtmlParser.isLoginRequired`），客户端抛
  `ForumLoginRequiredException`，UI 显示登录卡片（同 §18 的会员页规则）。
- 签到成功后站点 JS 直接 `window.location.reload()`，所以「新名次 / 连续 / 累计」只能靠
  **重新取页**获得，接口本身不返回数字。
- 样本：`app/src/test/resources/forum/signin_misign.html`（签到前：排名 0 / 连续 2 天 /
  累计 271 天）与 `signin_misign_after.html`（签到后：1033 / 3 天 / 272 天，按钮已是「已签到」）。
- 代码落点：`ForumHtmlParser.parseSignInState/parseSignInOutcome/parseCdataMessage`、
  `ForumRepository.getSignInState/signIn`、`ForumSignInViewModel` + `ForumSignInCard`
  （设置页账号区块下方的签到卡片）。

## 23. 付费附件下载（attachcredit 两跳）— 2026-11-27 实测

**触发条件**：附件卡的 `p.attach_size` 里有 `下载积分: 金币 -1`（数字是扣减额，绝对值即价格）。
免费附件没有这句，第一跳就是文件。

**实测链路**（目标帖 `thread-177248-1-1.html`，OP 附件 `mt-tts-plugin.txt`，62.54 KB，扣 1 金币）：

1. `GET https://bbs.binmt.cc/forum.php?mod=attachment&aid=Mzg1Mzc2fDY1ZDE3ZTE3fDE3OTU3NTQ1NzB8MTUzNDY2fDE3NzI0OA%3D%3D`
   **不返回文件**，302 到：
   `https://bbs.binmt.cc/forum.php?mod=misc&action=attachcredit&aid=385376&formhash=<formhash>&mobile=2`
   （302 里的 `aid` 是**纯数字 385376**，不是哈希）。
2. 该页是 Discuz 的「提示信息」页，`div#messagetext` 文案：
   `您的 金币 -1 ，现在将开始下载“mt-tts-plugin.txt”`
   页内**恰好一个**继续链接，文本 `点击此链接进行跳转`：
   `https://bbs.binmt.cc/forum.php?mod=attachment&aid=Mzg1Mzc2fGRkNmNhODk2fDE3OTU3NTQ3Nzh8MTUzNDY2fDE3NzI0OA%3D%3D&ck=452985a0&mobile=2`
   **关键**：继续链接的 `aid` 哈希被**重新生成**（与第 1 步不同），并多出 `ck=452985a0` 令牌；
   HTML 源里写作 `&amp;`。解析后必须把实体还原成 `&`，并按**原字符串**请求——重新编码会破坏
   哈希/令牌。
3. `GET` 继续链接 → `200`、`60687` 字节，随后 302 到对象存储：
   `https://oos.binmt.cc/forum/202611/25/082913dsim5c95lzx1x1dd.txt?OSSAccessKeyId=…&Expires=…&Signature=…`

**客户端判定（`ForumHtmlParser`）**：

| 首跳响应 | 判定 | 动作 |
|---|---|---|
| 最终 URL 含 `action=attachcredit`，或正文含 `attachcredit` / `点击此链接进行跳转` | 握手页 | 取继续链接，**只再跳一次** |
| `div#messagetext` 等提示框命中 `金币不足/抱歉/无法下载/请先登录/权限/不存在/已失效/购买/余额不足` | 失败通知 | 把服务端文案直接作为错误抛出，**不重试** |
| 正文含 `acw_sc__v2` / `var arg1=` | acw 挑战页 | 交给既有 `AcwInterceptor`；这里只报失败，**不加第二套求解器** |
| 其余 HTML | 未知页面 | `IOException("HTML page instead of a file…")` |

**防环**：握手最多一跳。第二跳（继续链接）若再次返回 HTML，一律按失败处理，绝不继续跟，
避免无限循环或重复扣金币。免费附件不经过本流程（一次请求直达文件）。

**UI**：付费提示必须出现在**用户选择「下载」之前**——附件卡行与选择面板都会显示
「下载将扣除 N 金币」（`forum_attach_cost_hint`），下载仍只在用户点了「下载到本机」后开始，
绝不自动确认。

**代码落点**：`ForumAttachmentDownloader.followAttachCredit`（握手 + 第二跳 HTML 直接失败）、
`ForumHtmlParser.isAttachCreditPage / attachCreditContinueUrl / isAcwChallenge /
isAttachmentFailureNotice / extractNotice`、`ForumBodyParser` 的 `ATTACH_COST`
（`下载积分: 金币 -1` → `ForumBodyBlock.Attachment.costCoins = 1`）。

**样本**：
- `app/src/test/resources/forum/attach_paid_thread.html` —— 目标帖 OP 的附件块
  （`div.comiis_attach` + `p.attach_size` 含 `下载积分: 金币 -1`，测试解析出 `costCoins = 1`）。
- `app/src/test/resources/forum/attach_paid_credit.html` —— 第 2 步的提示信息页
  （`div#messagetext` + 唯一的 `点击此链接进行跳转` 继续链接，含 `&amp;` 与 `ck`）。
- 下载器测试只把该页的站内 host 换成本机回环地址，其余字段（aid 哈希 / `ck` / `&amp;`）保持
  原样，所以全程无外网。

