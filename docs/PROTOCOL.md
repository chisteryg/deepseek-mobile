# 手机桥接协议（v1）

桌面插件 `@local/dsh-mobile-bridge` 在 `0.0.0.0:4318` 提供一组 HTTP + SSE 接口，供 Android 客户端在同一局域网内读取并驱动 DSH 会话。

- 传输：明文 HTTP（局域网），SSE 走 `text/event-stream`
- 鉴权：`Authorization: Bearer <device-token>`（SSE 也接受 `?token=`）
- 配对：6 位配对码换取设备令牌
- 协议版本：`version = 1`，与客户端耦合，改动需同步两侧

## 配对

`GET /pair` 返回一个极简 HTML 页：显示服务地址、当前配对码（仅 loopback 可见）和会话数。

`POST /api/pair`

```json
{ "code": "123456", "name": "Pixel 8" }
```

```json
{ "token": "…", "host": "192.168.1.5", "port": 4318, "version": 1, "name": "desktop-host" }
```

- 配对码 10 分钟有效，最多 12 次尝试，成功后立即轮换
- 令牌首次启动生成并持久化在 `$DSH_HOME/mobile-bridge.json`

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | `{ ok, name, version, sessions, running, time }` |
| GET | `/api/sessions` | `{ sessions: SessionSummary[] }` |
| GET | `/api/sessions/<id>` | `{ session, state }` |
| GET | `/api/sessions/<id>/stream` | SSE：`state` / `snapshot` / `event` / `closed` / `failure` |
| POST | `/api/sessions/<id>/prompt` | `{ "text": "…", "mode": "queue" \| "steer" }` |
| POST | `/api/sessions/<id>/cancel` | 停止当前回合 |
| GET | `/api/models` | 模型目录（provider、模型、推理档位） |
| POST | `/api/sessions/<id>/model` | `{ provider, model, reasoningEffort? }` |
| GET | `/api/interactions` | 待处理审批 `{ items: [...] }` |
| POST | `/api/interactions/<id>` | `{ "decision": "allow" \| "deny" }` |

### SessionSummary

```json
{
  "id": "session-…",
  "title": "安卓版客户端方案",
  "running": true,
  "pending": ["approval"],
  "updatedAt": 1763500000000,
  "cwd": "C:\\work\\project",
  "agentAvailable": true,
  "blank": false
}
```

### 会话状态

```json
{ "cursor": 451, "active": true, "running": false, "queue": [], "pendingInput": [] }
```

`pendingInput` 取值 `approval` / `question` / `other`。

## SSE 帧

连接后依次收到：

```
event: state
data: {"cursor":451,"running":false,"pendingInput":[]}

event: snapshot
data: {"header":{…},"cursor":451,"records":[{"type":"event","event":{…}}],"hasMore":false,"projections":{…}}

event: event
data: {"type":"user/message","seq":460,"time":…,"data":{…}}
```

- `snapshot`：打开时的历史，服务端已按 `maxMessages` 截断；`records[].event` 是持久化事件
- `event`：之后的新事件
- `state`：会话状态变化（节流推送）
- `closed`：follow 结束；客户端可重连并从 `snapshot` 恢复
- `failure`：服务端错误描述

客户端只保留 `user/message`、`assistant/message`、`tool/call`、`tool/result`、`turn/end` 等对话相关事件，其余视为日志噪声。

## 模型目录

`GET /api/models` 直接返回 DSH `sessionController.modelCatalog()` 的结果：

```json
{
  "default": { "provider": "deepseek-official", "model": "deepseek-flash", "reasoningEffort": "high" },
  "routableProviders": ["deepseek-official"],
  "groups": [
    {
      "id": "deepseek-official",
      "name": "DeepSeek",
      "models": [
        {
          "id": "deepseek-flash",
          "name": "DeepSeek-V4.1-Flash",
          "reasoning": {
            "efforts": [
              { "id": "off", "name": "Off" },
              { "id": "low", "name": "Low" },
              { "id": "high", "name": "High" },
              { "id": "max", "name": "Max" }
            ]
          }
        }
      ]
    }
  ],
  "failures": []
}
```

## 审批接管

插件在 `approval/request` 瀑布里注册应答者，规则：

- 手机最近 60 秒内访问过桥接时才接管
- 接管后把请求放进 `/api/interactions`，等待手机回答，最长 90 秒
- 超时或手机未连接时调用 `next()`，交回桌面端原有应答者，桌面体验不变

## 错误

统一 `{ "error": "…" }` 配合 HTTP 状态码：`401` 未配对/令牌错误、`404` 路径不存在、`410` 配对码过期、`429` 尝试过多、`500` 服务端异常。

## 事件压缩

为减小手机端流量，快照与实时事件只保留对话相关类型，并对超长文本做截断（默认 2000 字符），`assistant/message` 的 `stream` 字段被丢弃。
