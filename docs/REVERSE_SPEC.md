
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
