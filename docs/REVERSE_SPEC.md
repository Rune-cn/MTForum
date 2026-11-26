
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

---

## 17. 会话过期自动重登

Discuz 纯 Cookie，`cQWy_2132_auth` 失效后访问写接口会被 302 到登录页并返回 200。
自动重登必须持有账号密码：仅在用户显式开启「记住密码并自动重登」时，才把凭据加密保存到
设备（Keystore 派生的 AES-GCM，见 `ForumCredentialStore`）；未开启则不做任何保存。
写操作（回帖/发帖/收藏/关注）收到「未登录」信号时，若凭据可用则重登一次并重试。
