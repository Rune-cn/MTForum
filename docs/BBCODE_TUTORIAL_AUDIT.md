# BBCode 教程（thread 161119）标签覆盖审计

本表以论坛自带教程 **《【论坛教程】mt论坛语法总汇（bbcode）》**（thread 161119，样本
`app/src/test/resources/forum/bbcode_tutorial_161119.html`）为唯一依据：教程的「使用示例」
写 BBCode，「➽渲染效果」写服务端产出的 HTML。客户端渲染的是**服务端 HTML**，所以「支持」
指 `ForumBodyParser` + `ForumHtmlText` 能否还原该 HTML 形态；草稿/预览另走
`ForumBodyParser.bbcodeToHtml()`（只处理客户端自己发出的纯 BBCode）。

审计方法：逐条从教程样本里取出对应 HTML 片段（`jsoup` 选择器），对照渲染器源码分支。
下表「修复前」为审计时的真实状态，「修复后」为本轮结果，「证据」是钉住它的测试/样本。

## 一、基础格式类

| 标签 | 教程渲染 HTML | 修复前 | 修复后 | 证据 / 落点 |
|---|---|---|---|---|
| `[b]` | `<strong>` | 支持 | 支持 | `inlineStyle` → `FontWeight.Bold` |
| `[i]` | `<i>` | 支持 | 支持 | `inlineStyle` → `FontStyle.Italic` |
| `[u]` | `<u>` | 支持 | 支持 | `inlineStyle` → `Underline` |
| `[color=red]` | `<font color="red">` | 支持 | 支持 | `fontSpanStyle` / `parseHexColor` |
| `[size=5]` | `<font size="5">` | 部分（6/5/4/2 只当标题；`font` 包块时被拍平成标题） | 支持 | `isHeadingFont` 现在要求无块级子元素，`[size]` 包列表/表格时保留结构；`ForumBbcodeTutorialTest.size on a block element…` |
| `[font=微软雅黑]` | `<font face="微软雅黑">` | 部分（文字保留，字族丢弃） | 部分（诚实缺口） | Compose 只有通用字族；不伪造。`fontSpanStyle` 不含 face |
| `[s]` | `<strike>` | 支持 | 支持 | `inlineStyle` → `LineThrough` |
| `[backcolor=green]` | `<font style="background-color:green">` | **缺失**（只读 `color` 属性，背景被丢） | 支持 | `fontSpanStyle` 解析 `style` 的 `background-color`；`ForumBbcodeTutorialTest.backcolor renders a background span` |

## 二、多媒体与嵌入类

| 标签 | 教程渲染 HTML | 修复前 | 修复后 | 证据 / 落点 |
|---|---|---|---|---|
| `[img]` | `<img src>` | 支持 | 支持 | `handleImage` + `imageUrl` |
| `[audio]` | `div.media` + 「如果无法播放…」`<a>` 兜底链接 | 部分（渲染成兜底链接纯文本） | 支持（诚实媒体卡 + URL） | `mediaUrlOf`/`emitMedia`；`ForumBbcodeTutorialTest.audio tag…` |
| `[media=x,500,375]` | `ignore_js_op > script` 注入 `<iframe src=…>` | **缺失**（`script`/`iframe` 被跳过，整块消失） | 支持（诚实媒体卡；B 站重写为观看页） | `mediaUrlOf` 扫脚本 `src=`；`ForumBbcodeTutorialTest.media tag…` |
| `[flash]` | 同 `[media]`（iframe/embed/object，教程未演示） | **缺失** | 支持（诚实媒体卡） | 同一 `mediaUrlOf` 覆盖 `iframe/video/audio/embed/object` |
| `[attach]` | `div.comiis_attach`（块级卡片） | 支持 | 支持 | `emitAttachment` |
| `[attachimg]` | `span.comiis_postimg > img` | 支持 | 支持 | `handleImage` 走懒加载属性 |

## 三、结构布局类

| 标签 | 教程渲染 HTML | 修复前 | 修复后 | 证据 / 落点 |
|---|---|---|---|---|
| `[quote]` | `div.comiis_quote > blockquote` | 支持（作者行 + 嵌套结构） | 支持 | `emitQuote` |
| `[hr]` | `<hr class="l">` | 支持 | 支持 | `ForumBodyBlock.Divider` |
| `[list]` | `<ul><li>` | 支持（圆点） | 支持 | `emitList` |
| `[list=1]` | **`<ul type="1" class="litype_1">`**（不是 `<ol>`） | **部分**（当无序，显示圆点） | 支持（`1.`/`2.`…） | `listMarkerStyle` 读 `type`/`litype_*`；`ForumBbcodeTutorialTest.numbered list…` |
| `[list=a]` | `<ul type="a" class="litype_2">` | **部分**（圆点） | 支持（`a.`/`b.`…） | `markerFor` ALPHA；`ForumBbcodeTutorialTest.alpha list…` |
| 嵌套「有序套无序」 | `<ul><li>…<ul type="1">…` | 部分（内层有序也变圆点） | 支持（内层按自身 `type` 编号） | `ForumBbcodeTutorialTest.nested ordered list…` |
| `[align=center]` | `<div align="center">` | 支持（块级容器） | 支持 | `emitAligned` |

## 四、特殊功能类

| 标签 | 教程渲染 HTML | 修复前 | 修复后 | 证据 / 落点 |
|---|---|---|---|---|
| `[hide]` | 服务端门禁（内容不下发） | 支持（草稿显示占位、不泄露） | 支持 | `bbcodeToHtml` 映射为「此内容已隐藏」 |
| `[url=…]text[/url]` | `<a href target=_blank>` | 支持（可点） | 支持 | `LinkAnnotation.Url` |
| `[code]` | `div.comiis_blockcode > .b_l > ol > li` | 支持（逐行等宽） | 支持 | `emitDiscuzCodeBlock` |

## 五、其他可用标签

| 标签 | 教程渲染 HTML | 修复前 | 修复后 | 证据 / 落点 |
|---|---|---|---|---|
| `[free]` | `div.comiis_quote > blockquote`（「免费内容」框） | 支持（渲染为引用） | 支持 | `emitQuote`；`ForumBbcodeTutorialTest.free content…` |
| `[email=…]` | `<a href="mailto:…">` | **部分**（上色但点不动：`ForumUrls.absolute` 把 `mailto:` 判空） | 支持（可点） | 新增 `ForumUrls.navigable()`；`ForumBbcodeTutorialTest.email tag…` |
| `[qq]123[/qq]` | `<a href="…wpa.qq.com…"><img qq_big.gif></a>` | **部分**（渲染成一张孤立 QQ 图标图） | 支持（可点「QQ 123…」） | `emitQqLink`；`ForumBbcodeTutorialTest.qq tag…` |

## 六、教程未演示 / 无真实样本的标签（诚实缺口）

| 标签 | 说明 | 现状 |
|---|---|---|
| `[md]` | Discuz 的 Markdown 标记；服务端转成 HTML。教程未出现 | 真实正文已是 HTML → 天然渲染；**草稿/预览**里只剥标记、不跑 Markdown。等真实样本 |
| `[rate]` | 评分提示块 | 教程未演示，无真实正文样本；草稿链剥标记 |
| `[postbg]` | 帖子背景图/色 | 教程未演示；背景属模板样式，正文渲染不承载 |
| `[groupid]` | 群组徽标 | 教程未演示，无真实正文样本；草稿链剥标记 |
| `[font=…]` 字族 | 自定义字体 | Compose 无对应字体资产，只能保留文字（部分） |

> 结论：教程列出的 20 个语法点里，基础格式 6/8 完全支持（`font` 字族、`backcolor` 见上）、
> 多媒体 6/6（`audio`/`media`/`flash` 改为诚实卡）、结构 8/8、特殊 3/3、其他 3/3；
> 仅剩 `[font=]` 字族为「文本在、字族丢」的部分项，以及教程未演示的 `[md]/[rate]/[postbg]/[groupid]`
> 作为诚实缺口记录。

## 自动化证据

- `ForumBbcodeTutorialTest`（12 例）：从教程样本里取出真实 HTML 片段逐条断言，
  合成用例（嵌套列表、`[size]` 包块）引用教程自身示例 markup。
- 变更的既有契约：`ForumRichTextTest.attachment and media markers degrade to readable text`
  从「media 退化成纯文本 URL」改为「media 成为携带 URL 的诚实媒体卡」。
