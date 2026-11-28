# App 内置 MCP 服务（论坛帖子 / 回复，只读）

App 内跑一个只绑定回环地址的 **MCP（Model Context Protocol）服务器**，把
[`ForumRepository`](../app/src/main/java/io/rune/mt/forum/domain/forum/repository/ForumRepository.kt)
里已有的「版块 / 主题列表 / 帖子楼层 / 搜索」暴露给本机的 AI 客户端。它是只读的：
**没有发帖、没有私信、没有登录、没有收藏/签到/改设置**。

开关在 **设置 → MCP 服务**：开关、端口（默认 `8765`）、完整端点 URL（带复制）、
以及当前暴露的工具清单都在那一页；开关与端口写进既有的 app-settings DataStore
（`mcp_server_enabled` / `mcp_server_port`），不是第二套存储。开关打开后
`App.onCreate` 里启动的 `McpServerController` 会从设置流里对账，改端口即时重绑，无需重启 App。

## 端点与传输（Streamable HTTP 子集）

| 项 | 值 |
|---|---|
| 端点 | `http://127.0.0.1:8765/mcp`（端口可改） |
| 协议 | JSON-RPC 2.0，MCP **2025-06-18** |
| 方法 | 仅 `POST /mcp`（`POST /` 作为宽容别名） |
| 响应 | `application/json; charset=utf-8`，一条请求一条响应 |
| `GET /mcp` | `405 Method Not Allowed` + `Allow: POST`（**不实现 SSE**） |
| 其它路径 | `404` |
| `initialize` | 响应头带 `Mcp-Session-Id`；客户端回传时接受并忽略（服务端不保存会话） |
| 通知 | `notifications/initialized`（以及任意无 `id` 的帧）→ `202` + 空 body |
| 请求体上限 | 64 KB（超出 `413`，解析前拒绝） |
| 头部上限 | 16 KB（超出 `413`） |
| 限流 | 120 请求/分钟（超出 `429`）；只读接口，防误循环 |

完整实现：

- JSON-RPC 分发：`data/mcp/McpJsonRpcDispatcher.kt`（纯函数，无 socket，suspend 调仓库）
- HTTP 层：`data/mcp/McpHttpServer.kt`（`ServerSocket`，无新依赖）
- 生命周期：`data/mcp/McpServerControllerImpl.kt`（观察设置流，启停/重绑 socket）
- 工具元数据：`domain/mcp/McpToolCatalog.kt`（设置页与 `tools/list` 共用同一份）
- 正文纯文本化：`ui/page/forum/ForumMcpPostFormatter.kt` 复用 `ForumBodyParser`
  （`plainText` + 附件块），不在数据层另写一套 HTML 解析（规则 25）

## JSON-RPC 交互示例

### 1. `initialize`

请求：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}
```

响应（头含 `Mcp-Session-Id: <uuid>`，可忽略）：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-06-18",
    "capabilities": {"tools": {"listChanged": false}},
    "serverInfo": {"name": "MT论坛 MCP", "version": "26.11.28"},
    "instructions": "Read-only MT论坛 access: boards, thread lists, thread floors (plain text + attachments) and in-forum search. No posting, private messages, login or writes."
  }
}
```

### 2. `notifications/initialized`

请求（无 `id`）：

```json
{"jsonrpc":"2.0","method":"notifications/initialized"}
```

响应：`HTTP/1.1 202 Accepted`，空 body。

### 3. `tools/list`

请求：

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

响应（节选一个工具）：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "get_thread",
        "title": "Get thread",
        "description": "One thread page: title, forum, and every floor with plain-text body and attachments.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "tid": {"type": "integer", "description": "Thread id (tid) from list_threads or search_threads.", "minimum": 1},
            "page": {"type": "integer", "description": "1-based page number; the site's own pager decides the page count.", "minimum": 1, "default": 1}
          },
          "required": ["tid"],
          "additionalProperties": false
        }
      }
    ]
  }
}
```

### 4. `tools/call`

请求：

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_thread","arguments":{"tid":166223,"page":1}}}
```

响应（`text` 是 JSON 字符串；这里格式化展示）：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{"type": "text", "text": "{\"tid\":166223,\"title\":\"…\",\"forum\":\"ANDROID 逆向\",\"page\":1,\"totalPages\":4,\"count\":10,\"floors\":[{\"floor\":0,\"author\":\"…\",\"time\":\"…\",\"isOp\":true,\"body\":\"正文纯文本…\",\"attachments\":[{\"name\":\"patch.zip\",\"url\":\"https://bbs.binmt.cc/forum.php?mod=attachment&aid=…\"}]}]}"}],
    "isError": false
  }
}
```

工具内部失败（仓库异常、未登录搜索）是 **tool 结果**，不是 JSON-RPC 错误：

```json
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"search_threads requires a signed-in session: …"}],"isError":true}}
```

协议级错误用 JSON-RPC `error`：

| 场景 | code |
|---|---|
| 请求体不是合法 JSON | `-32700` |
| 不是单个 JSON 对象 / `jsonrpc` 不是 `"2.0"` / 缺 `method` | `-32600` |
| 未知 method、未知工具 | `-32601` |
| 参数缺失、类型错误、`fid`/`tid`/`page` 越界 | `-32602` |

## 工具（全部只读，全部走 `ForumRepository`）

| 工具 | 参数 | 返回 |
|---|---|---|
| `list_boards` | 无 | `{count, forums:[{fid,name,description?,todayThreads?}]}`（`getHome()`，站点顺序） |
| `list_threads` | `fid` 必填 int，`page` int 默认 1 | `{fid,page,totalPages,count,threads:[{tid,title,author,replies,time?,pinned?,digest?}]}`（`getThreads`，站点顺序，不重排、不客户端截断行数） |
| `get_thread` | `tid` 必填 int，`page` int 默认 1 | `{tid,title,forum?,page,totalPages,count,floors:[{floor,author,time?,isOp,body,truncated?,attachments?}]}`（`getThreadDetail` + `ForumBodyParser.plainText` + 附件块） |
| `search_threads` | `q` 必填 string，`page` int 默认 1 | `{query,page,totalPages,count,threads:[…]}`（站点搜索；未登录直接返回 `isError:true` 的说明，不发请求） |

约定：

- `floor` 沿用 App 内部编号：`0` = 楼主，回复从 `1` 连续编号（跨页按站点每页 10 楼推导）。
- `body` 是 **纯文本**（`ForumBodyParser.plainText`），不是 HTML；附件单独放在
  `attachments:[{name,url,costCoins?}]`，付费附件的 `costCoins` 只是站点标价提示。
- 单楼正文最多 4,000 字符、单条工具结果最多 48,000 字符；被截断时正文带
  `…(truncated)` 后缀 / 楼层带 `"truncated": true`。列表工具**不截断行**，只在超出
  预算时收紧标题/描述字段，保证仍是合法 JSON 且不丢站点返回的行（规则 16）。
- 附件 URL 是站点原始绝对地址；真正下载仍受站点 Cookie/权限限制，MCP 只提供名字与 URL。

## 安全边界

- **只绑回环**：`127.0.0.1`（`McpEndpoint.HOST`），绝不 `0.0.0.0`；局域网 / 模拟器宿主机都访问不到。
- **无鉴权**：这是本机开发/自用接口，没有 token。同一台设备上的任何应用都能调用；
  设置页把这一点写在「安全」卡片里。不用时请关掉开关。
- **只读**：工具目录只有上面 4 个查询，服务端没有任何 POST/写接口；仓库的写方法
  （发帖、私信、登录、收藏、签到）**没有**被 MCP 调用路径引用（`McpFakeRepository`
  里这些方法会直接让测试失败，防止将来误接）。
- **日志**：只记 method/path/status/字节数；不记录请求体、Cookie 或帖子内容（规则 10）。
- **资源上限**：64 KB 请求体、16 KB 头、120 请求/分钟、4,000 字符/楼、48,000 字符/结果。
- **无 TLS**：回环连接不加密（系统不提供回环证书信任），这与「仅本机」的威胁模型一致。

## 把 MCP 客户端指过来

支持 **Streamable HTTP** 的客户端直接填端点即可：

```json
{
  "mcpServers": {
    "mtforum": {
      "type": "http",
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

只支持 stdio 的客户端（如部分桌面版）可以经
[`mcp-remote`](https://www.npmjs.com/package/mcp-remote) 桥接（需本机安装 Node；这只是
本地回环转发，不会访问 `bbs.binmt.cc`）：

```json
{
  "mcpServers": {
    "mtforum": {
      "command": "npx",
      "args": ["mcp-remote", "http://127.0.0.1:8765/mcp"]
    }
  }
}
```

手机上的 MCP 客户端必须与 App 在同一台设备（回环地址只在本机有效）；本 App 不提供局域网监听。

## 测试（全部 fixture / fake，无真实站点请求）

- `McpJsonRpcDispatcherTest`（21 例）：`initialize` / `initialized` 202 / `tools/list`
  schema / 四个工具 happy path / 未知工具 `-32601` / 缺参、错类型、越界 `-32602` /
  仓库异常与未登录搜索 → `isError:true` / 解析错误 `-32700` / 未知 method / 非对象帧。
- `McpHttpServerTest`（10 例）：`127.0.0.1:0` 真实 socket 往返（`initialize` + `tools/list`
  同一 keep-alive 连接）、`tools/call` 纯文本楼层、通知 202、`GET /mcp` 405、
  未知路径 404、>64 KB 请求体 413、关闭后端口拒绝连接、限流 429。
- `McpSettingsStoreTest`（6 例）：默认关 + 8765；开关/端口跨仓库实例持久化；
  关闭持久化；特权端口与越界端口的规范化。
- `McpSettingsPageTest`（5 例，Robolectric）：设置页渲染端点/开关/工具清单/安全说明；
  打开开关写入设置；改端口写入设置；运行/停止状态文案；设置页入口的源码契约
  （`Route.Mcp` + `mcp_server_title`）。

## 已知边界

- 不做 SSE（服务端 → 客户端流式推送），因此只实现请求/响应式工具；`GET /mcp` 明确 405。
- 不实现 MCP 会话（`Mcp-Session-Id` 只发不收状态）、resources、prompts、采样。
- 搜索依赖 App 内已登录的 Cookie；游客调用得到可读的 `isError`，不伪装成空结果。
- 附件只给元数据，不代下载（下载仍走 App 内的 `ForumAttachmentDownloader`，含 Cookie/acw）。