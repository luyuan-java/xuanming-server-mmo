# Robot 端客户端契约：robot_smoke 里程碑（登录 → 建角 → 进游戏 → NotifyEnterScene → ListSkills → 离开/断开）

> 来源快照：mmorpg commit `766cb037c`。下文所有路径都相对快照根目录 `baseline/`，引用写成 `文件:行号`。
> 范围：只写 **Go 机器人（robot/）实际发什么、读什么、怎么判成功失败**，以及它用到的 Java gateway HTTP 形状。服务端内部实现只在影响客户端可见行为时才提。UE 客户端不在本文范围内。

---

## 0. 冒烟主链路总览

`robot.exe -c etc/robot_smoke.yaml` 的 `mode: "stress"` 会走 `main.go` 的通用 stress 分支（`robot/main.go:208-248`），每个机器人执行一次 `runRobot → runRobotOnce`（`robot/main.go:252-482`）：

```
[HTTP] (zone_id==0 时才调) GET  {gateway_addr}/api/server-list
[HTTP] POST {gateway_addr}/api/assign-gate            → gate_ip / gate_port / token_payload / token_signature
[TCP ] 连接 gate_ip:gate_port（后台异步拨号），sleep 500ms
[TCP ] 首帧 ClientTokenVerifyRequest（裸帧，不包 ClientRequest） → 等 ClientTokenVerifyResponse（无超时）
[TCP ] ClientRequest{message_id=48 Login}             → MessageContent{message_id=48, LoginResponse}        15s
[TCP ] (players 为空时) ClientRequest{14 CreatePlayer} → MessageContent{14, CreatePlayerResponse}           15s
[TCP ] ClientRequest{26 EnterGame}                     → MessageContent{26, EnterGameResponse}              15s
       stats: login_ok++ enter_ok++；日志 "entered game"
[TCP ] 服务端推送 MessageContent{79 NotifyEnterScene, EnterSceneS2C}（可以早于 26 到达）                    60s
       日志 "notify enter scene"
[TCP ] ClientRequest{77 ListSkills}                    → MessageContent{77, ListSkillsResponse}             5s（超时只打 Warn）
[TCP ] AI 循环：每 3s 发 84 ReleaseSkill（85%）或 63 EnterScene（15%），不等响应
... 一直跑到 SIGINT/SIGTERM ...
[TCP ] ClientRequest{17 LeaveGame} → ClientRequest{58 Disconnect} → 立即关 TCP（都不等响应）
```

---

## 1. 配置：让机器人指向另一个 gateway，并设置密码、认证方式、账号前缀和数量

### 1.1 冒烟配置原文（`robot/etc/robot_smoke.yaml:3-20`）

| 行 | 字段 | 值 | 作用 |
|---|---|---|---|
| 3 | `gateway_addr` | `"http://127.0.0.1:8081"` | **唯一的服务端入口**。机器人拿它直接拼 `gateway_addr + "/api/assign-gate"`（`robot/http_assign_gate.go:45`），所以不能带结尾 `/`。gate 的 TCP 地址不能配置，完全取自 assign-gate 的 JSON 响应 |
| 4 | `zone_id` | `1` | 0 表示先调 `/api/server-list` 自动选区（`robot/main.go:514-520`） |
| 5 | `robot_count` | `3` | 并发机器人数 |
| 6 | `account_fmt` | `"robot_%04d"` | 用 `fmt.Sprintf(AccountFmt, i)` 生成账号，**i 从 1 开始**：`robot_0001..robot_0003`（`robot/main.go:223-224`）。代码里不存在 `robot_0000` |
| 8 | `password` | `"<robot 冒烟配置里的开发口令>"` | 所有机器人共用。注释说明它等于 login 进程的环境变量 `LOGIN_DEV_PASSWORD_SHARED_SECRET`（现服务端的 DevPasswordAuth 用共享密钥加账号前缀白名单 `robot_`/`dev_`，见 `go/login/etc/login.yaml:326-338`）。机器人**从不注册账号**，服务端必须在首次登录时自动建账号 |
| 9 | `auth_type` | `"password"` | 见 1.3 |
| 10 | `skill_ids` | `[]` | 为空时从技能表自动取（`robot/config/config.go:579-584`）。在 stress 路径里没有实际作用，AI 用的是 ListSkills 返回的技能（见 §5） |
| 11 | `table_dir` | `"../generated/tables"` | **机器人本地前置条件**：`table.LoadTables` 任何一张表读不到都会 `log.Fatalf`，退出码 1（`go/shared/generated/table/all_table.go:14+`）。所以机器人必须在 `robot/` 目录下运行 |
| 12 | `action_interval` | `3` | AI 动作间隔（秒） |
| 13 | `report_interval` | `5` | 统计行打印间隔（秒） |
| 14 | `mode` | `"stress"` | `""` 或 `"stress"` 才走本文描述的路径（`robot/main.go:79-206` 前面的分支都是其他模式） |
| 15 | `profile` | `"stress"` | AI 权重，见 §5 |
| 16-20 | `llm.enabled` | `false` | 不启用 |

### 1.2 其他相关配置字段（`robot/config/config.go`）

- 字段定义在 `config.go:15-24`、`71-73`（`auth_type`、`satoken_addr`）、`87`（`use_http_login`）、`91-92`（`profile`、`custom_weights`）。
- 缺省值（`config.go:433-446`）：`robot_count=1`、`account_fmt="robot_%04d"`、`password="123456"`、`action_interval=3`、`report_interval=5`、`table_dir="../generated/tables"`。
- 校验规则（`config.go:475-492`）：`gateway_addr` 非空、`robot_count>0`、`account_fmt` 非空、`mode` 合法；`auth_type=="satoken"` 时必须填 `satoken_addr`。
- 配置文件路径由命令行 `-c` 指定，缺省是 `etc/robot.yaml`（`robot/main.go:52`）。
- **Java 首个里程碑的实用建议**：`custom_weights: {idle: 1}` 可以让 AI 完全不发 84/63（`config.go:458-468` 中 `custom_weights` 的优先级高于 `profile`；动作名是 `idle/move/cast_skill/switch_scene/chat`，见 `robot/logic/ai/action.go:16-22`）。

### 1.3 auth_type 的实际路由（`robot/main.go:763-792`）

1. 如果本机器人有缓存的 access_token（只有**同一机器人上一次登录成功后又重试**时才有），并且 `expire==0 || now < expire-60`，先走 `auth_type="access_token"`（`robot/login.go:415-473`）。失败后 `recon_fb++`，**同时 `login_fail++`**，然后在**同一条 TCP 连接**上回落到主认证。
2. `use_http_login: true` 时先走 HTTP `/api/login`（见 §2.4）。失败会 `login_fail++` 再回落。
3. `auth_type == "satoken"` 时：`GET {satoken_addr}/auth/dev-login?account=<acc>`，期望 `{"ok":true,"token_value":"..."}`（`robot/login.go:290-321`），然后发 `LoginRequest{account, auth_type:"satoken", auth_token}`。
4. **其他任何值（包括 `"password"`、`""`）** 都走 `loginAndEnterLocal`（`robot/main.go:701-758`）。**TCP 上的 `LoginRequest` 只填 `account` 和 `password`，`auth_type` 保持空串**（`robot/main.go:705`）。所以服务端必须把 `auth_type==""` 当作密码认证。

---

## 2. HTTP 契约（Java gateway_node）

公共约定：
- Spring 端口 8081（`java/gateway_node/src/main/resources/application.yaml:2-3`）。Jackson 全局 `SNAKE_CASE`（`application.yaml:8-9`）。
- 业务错误**一律返回 HTTP 200，错误放在 body 的 `code` 里**（`AssignGateService.java:183-186` 注释写明"恒 HTTP 200 + body.code"）。
- 机器人对 assign-gate、queue-status、login、refresh-token **只接受 HTTP 200**，非 200 一律当成错误（`robot/http_assign_gate.go:82-84`、`robot/http_login.go:80-82`、`118-120`）。
- 机器人用 Go `encoding/json` 解析。未知字段会被忽略；键名匹配不区分大小写，但**下划线必须一致**，所以 `gateIp` 这种驼峰写法匹配不上。类型必须对得上，比如 `"gate_port":"10000"` 这种字符串会导致解析失败并进入重试。
- **`[]byte` 字段必须是标准 Base64（带 `=` 填充，标准字母表）的 JSON 字符串**。Go 解码 `[]byte` 用 `base64.StdEncoding`，Jackson 默认把 `byte[]` 输出为同样格式（`AssignGateResponse.java:23-24`）。如果改成 hex 或原始字符串，机器人会解码失败。

### 2.1 `POST /api/assign-gate`（机器人主路径）

**调用链**：`resolveGateAddrLocal`（`robot/main.go:502`）→ `resolveGateViaHTTPLocal`（`513-538`）→ `assignGateHTTPLocal`（`591-696`）→ `httpAssignGate`/`postJSON`（`robot/http_assign_gate.go:44-90`）。
`robot/gate.go` 里的 `resolveGateAddr`/`assignGateHTTP` 是**没有调用方的死代码**，不要参照它。

**请求**（`robot/http_assign_gate.go:15-20`，`robot/main.go:599-602`）：
- `Content-Type: application/json`，HTTP 客户端是 `sharedHTTPClient`（`robot/http_client.go:37-53`），单次超时 5s。
- `account`、`device_id`、`queue_token` 都带 `omitempty`，机器人传的 `Account` 是空串，所以首次请求体**正好是**：
  ```json
  {"zone_id":1}
  ```
- 排队重入时会带上 `"queue_token":"..."`（`robot/main.go:649-651`）。
- Java 端 DTO 字段：`zone_id`(int)、`account`、`device_id`、`queue_token`（`AssignGateRequest.java:9-29`）。

**响应**：机器人按以下结构解析（`robot/http_assign_gate.go:25-41`）。

| JSON 键 | Go 类型 | 机器人是否使用 |
|---|---|---|
| `code` | int | 是，决定分支 |
| `gate_ip` | string | 是（`code=0` 时必须非空） |
| `gate_port` | int | 是（`code=0` 时必须非 0） |
| `token_payload` | []byte（base64） | 是，原样作为 `ClientTokenVerifyRequest.payload` |
| `token_signature` | []byte（base64） | 是，原样作为 `.signature` |
| `token_deadline` | int64 | 解析但不使用 |
| `error` | string | 只拼进错误信息 |
| `retry_after_ms` | int64 | `code=100` 时作为轮询间隔（≤0 时按 2s） |
| `queue_pos` | int64 | 不使用 |
| `queue_source` | string | `"login"` 走 queue-status 轮询；其他值重新调 assign-gate |
| `queue_token` | string | 排队令牌 |
| `queue_rank` | int64 | 只进统计 |
| `queue_total` | int64 | 不使用 |

**Java 现状**：`AssignGateResponse.java:5-40` 带 `@JsonInclude(NON_NULL)`，基本类型字段 `code`、`gate_port`、`token_deadline` 永远会输出。示例：

```json
// 准入
{"code":0,"gate_ip":"127.0.0.1","gate_port":10000,"token_payload":"CAMQARi...","token_signature":"NmY0...","token_deadline":1760000000}
// 失败
{"code":500,"gate_port":0,"token_deadline":0,"error":"login_unavailable"}
```

Java 端可能返回的 code：
- `0`：准入。
- `100`：排队。`queue_source` 为 `"ratelimit"` 或 `"login"`（`AssignGateResponse.java:42-69`）。
- `410`：排队令牌过期（`AssignGateService.java:310-315`）。
- `429`：限流（`:71-76`）。
- `404` `zone_not_found`、`503` `zone_maintenance`/`zone_closed`/`zone_not_open`（`:78-96`、`AssignGateService.java:175-200`）。
- `500`：`login_unavailable`/`internal_error`/`admitted_without_endpoint` 等（`AssignGateService.java:100-114`、`354-363`）。

**机器人的处理**（`robot/main.go:613-695`）：
- `0`：`gate_ip==""` 或 `gate_port==0` 时报错；否则返回 `"%s:%d"` 地址和 token。
- `100`：等待 `retry_after_ms`。`queue_source=="login"` 且有 token 时轮询 `/api/queue-status`，否则重新 POST assign-gate。总预算 5 分钟（`:595`）。
- `410`：清掉 `queue_token`，重新 assign-gate。
- `429` 及其他：返回错误。

外层最多重试 30 次，退避 2s、3s、4.5s…，每次乘 1.5，到 ≥10s 后不再增长（`robot/main.go:522-536`）。30 次都失败时 `login_fail++`（`robot/main.go:333-337`）。

**冒烟时**：限流总开关默认关闭（`application.yaml:38` `enabled: false`），正常应当直接拿到 `code=0`。**Java 只需实现 `code=0` 这一种形状就能跑通冒烟**，其他 code 保持同样语义即可。

### 2.2 `POST /api/queue-status`（只在 code=100 且 queue_source="login" 时调用）

- 请求体由 Go map 序列化，键按字母序：`{"queue_token":"<tok>","zone_id":1}`（`robot/http_assign_gate.go:56-59`）。
- 响应形状与 assign-gate 完全相同（`QueueStatusController.java:38-41`、`QueueStatusRequest.java:19-27`）。

### 2.3 `GET /api/server-list`（只在配置 `zone_id: 0` 时调用；冒烟配置为 1，不会调用）

- 使用 `http.DefaultClient`，超时 5s，**不检查 HTTP 状态码**（`robot/main.go:540-577`）。
- 只读 `zones[].zone_id`(uint32) 和 `zones[].recommended`(bool)。取第一个 `recommended=true` 的区，没有就取 `zones[0]`。
- 出错或返回空列表时，用 `zone_id=0` 继续调 assign-gate（`robot/main.go:516-519`）。
- Java 现状：`{"zones":[{"zone_id":1,"name":..,"status":..,"load_level":..,"maintenance_msg":..,"open_time":..,"is_new":false,"recommended":true}]}`（`ServerListResponse.java:5-13`、`ZoneInfoDto.java:6-44`，`is_new` 通过 `@JsonProperty` 显式命名）。

### 2.4 `POST /api/login`（只在 `use_http_login: true` 时调用；冒烟配置未开启）

- 调用时机是在 **TCP 连上并完成 token verify 之后**，在 `loginAndEnterWithAuth` 内部（`robot/main.go:780-787`、`807-853`）。这与 `config.go:77-84` 注释里描述的顺序不一致，以代码为准。
- 请求体（`robot/http_login.go:28-35`）：`{"zone_id":1,"account":"robot_0001","password":"...","auth_type":"password"}`。`password`、`auth_type`、`auth_token`、`device_id` 带 omitempty。超时 10s（`robot/main.go:826`）。
- 响应只读这些字段（`robot/http_login.go:17-26`）：`code`、`message`、`retry_after_ms`、`queue_pos`、`access_token`、`refresh_token`、`access_token_expire`、`refresh_token_expire`。**`players` 被忽略**。
- 判定：`code=0` 且 `access_token` 非空时，在 TCP 上用 `auth_type="access_token"` 做 Login/建角/EnterGame（`robot/main.go:852`）。`100/101/429` 及其他 code 都记 `login_fail++`，然后回落到 TCP 密码登录（`:831-843`）。
- Java DTO：`LoginRequest.java:14-52`、`LoginResponse.java:22-106`（players 形如 `[{player_id,name,level}]`）。

### 2.5 `POST /api/refresh-token`（会话期间的后台任务，可能触发）

- `runTokenRefresher`（`robot/login.go:199-263`）每 30s 检查一次。只有同时满足以下条件才会发请求：`refresh_token` 非空、`access_token_expire != 0`、剩余时间 ≤10 分钟、距上次刷新 ≥60s。
- `gateway_addr` 是必填项，所以**永远走 HTTP 分支**，TCP 127 分支实际不可达（`login.go:232`）。
- 请求体 `{"refresh_token":"..."}`，超时 5s（`robot/http_login.go:94-126`）。响应读 `code`，`code=0` 时写回 4 个 token 字段。结果只影响 `refresh_ok`/`refresh_fail`。
- **Java 冒烟建议**：`LoginResponse.access_token_expire` 设为远大于 10 分钟（例如现网语义的 2h，见 `proto/login/login.proto:38`）；或者直接置 0，这样根本不会触发刷新。

---

## 3. TCP 帧格式与收发机制（机器人侧实现）

### 3.1 编解码器（vendored `github.com/luyuancpp/muduoclient` v0.0.14）

代码位置：`robot/vendor/github.com/luyuancpp/muduoclient/muduo/codec.go`。

**帧布局（两个方向相同，所有整数都是大端）**：

```
[0..4)   uint32 len      = 4 + nameLen + bodyLen + 4     （不含自身 4 字节）
[4..8)   uint32 nameLen  = len(typeName) + 1              （包含 1 字节结尾符）
[8..)    typeName 字节 + 1 字节结尾符
[..)     protobuf body
[末4字节] uint32 adler32( 从 nameLen 字段开始到 body 结束 )  即 data[4 : 4+len-4]
```

**机器人发送**（`codec.go:29-64`）：
- `typeName = desc.Name() + " "`，用的是**短名**，结尾符是**空格 0x20**。
- 两种上行消息都定义在 `proto/common/base/message.proto`，该文件**没有 `package`**（`message.proto:1-2`），所以短名和全名相同：`"ClientTokenVerifyRequest "`、`"ClientRequest "`。
- 校验和计算正确（`codec.go:52`）。

**机器人接收**（`codec.go:66-103`）：
- 名字取 `data[8 : 8+nameLen-1]`，也就是**不管最后一个字节是什么都剥掉**。然后用 **全名** 查 `protoregistry.GlobalTypes`（`codec.go:82-83`）。
- 服务端必须发 `"MessageContent\0"` 或 `"ClientTokenVerifyResponse\0"`（C++ 现状是全名加 `\0`，见 `cpp/libs/engine/core/network/codec/codec.cpp:29-32`），并且 **nameLen 必须包含这个结尾字节**。漏掉结尾字节会被剥成 `"MessageConten"`，类型查不到。
- 校验和不一致**只打日志、不丢帧**（`codec.go:97-101`）。
- **类型查不到或 body 解析失败时，`Decode` 返回 err，`readLoop` 直接 `break`，不消费缓冲区**（`connection.go:168-172`）。这条连接从此**永久卡死**，后续所有帧都解不出来。因此服务端绝对不能向机器人发送未注册的类型名或损坏的 body。
- 机器人侧没有最大长度检查。

**服务端（C++ gate 现状）对机器人上行帧的要求**：
- 校验和严格校验，不一致直接 `forceClose`。
- `nameLen >= 2`。
- 名字同样剥掉最后一个字节，所以空格结尾也能接受（`codec.cpp:222-233`、`118-143`、`164-198`）。
- **Java 解码时必须接受以空格结尾的名字**。建议统一规则为"剥掉最后 1 个字节，不检查它的值"。

### 3.2 连接行为（`connection.go`）

- `NewClient` **不会同步拨号**。后台 `connectionManager` 反复 `net.Dial`，失败后 sleep 1s 再试，无限重试（`connection.go:40-68`；`vendor/.../client.go:16-25`）。
  - 所以 `robot/main.go:341-346` 的 "connect failed" 分支实际上不会触发。
  - **如果 assign-gate 返回的端口不可达，机器人会永久卡在 VerifyGateToken**，因为它没有超时。
- **服务端断开 TCP 后，客户端会在 1 秒后自动重连同一个地址**（`connection.go:92-103`）。`incoming` channel 从不关闭，`Recv()` 永远不会报错（`connection.go:251-257`），因此机器人**察觉不到断线**（详见 §7）。
- 读超时 30s，超时后只 `continue`（`connection.go:145-150`），所以**不需要服务端心跳**。写超时 10s（`:216`）。
- `incoming` 缓冲容量 100，**写满时静默丢帧**（`connection.go:47`、`178-182`）。`Send` 是非阻塞写入容量 100 的 `outgoing`，满了返回的错误会被吞掉（`:234-249`，`client.go:31-36`）。

### 3.3 应用层封装（`robot/pkg/client.go`）

**上行**：`SendRequest(messageId, body)`（`client.go:135-152`）发送 `ClientRequest`，字段来自 `proto/common/base/message.proto:196-203`：

| 字段号 | 字段 | 机器人填的值 |
|---|---|---|
| 1 | `id` | 每个 GameClient 从 1 开始自增的 seq（`client.go:144`）。只递增，不会重置 |
| 2 | `service` | 空 |
| 3 | `method` | 空 |
| 4 | `body` | 内层请求 protobuf 序列化后的字节 |
| 5 | `message_id` | 见 `proto/message_id.txt` |

**下行**：机器人只接受 `MessageContent`（`message.proto:18-24`）。

| 字段号 | 字段 | 机器人是否读取 |
|---|---|---|
| 1 | `serialized_message` | **读** |
| 2 | `message_id` | **读** |
| 3 | `id` | **不读**（C++ gate 会回显请求的 id，见 `client_message_processor.cpp:719-720`、`277`、`307`） |
| 4 | `error_message` | **不读** |

**响应匹配方式**：
- **只看 `message_id`，不看 `id`**（`robot/main.go:875`、`robot/login.go:147`）。
- 登录阶段 `sendAndRecv` 同步等待目标 `message_id`。其他帧先 `DeferMessage` 暂存（`client.go:174-181`），等 `RecvLoop` 启动后按 FIFO 顺序补投递（`client.go:271-273`）。
- `RecvOne` 收到非 `MessageContent` 类型的帧时直接报错（`client.go:164-169`），导致当前登录步骤失败，不是 STUCK。
- 进入 `RecvLoop` 后，按 `message_id` 查 `messageHandlers` 表（`robot/logic/handler/message_body_handler.go:31-135`），把 `serialized_message` 反序列化成登记的类型后调用处理函数。
  - 查找 Player 用的是 `client.PlayerId`，找不到时打 Error "Player not found" 并丢弃（`:140-144`）。
  - 未登记的 `message_id` 只打 Info "Unhandled message"（`:149`），**无害**。

### 3.4 由上面推出的三条硬规则

1. **错误必须放在内层响应的 `error_message` 里，不能只放在 `MessageContent.error_message`。**
   机器人不读外层 error。如果外层带错、内层为空，机器人会反序列化出一个 `ErrorMessage==nil` 的空响应并**当成成功**：Login 变成"0 个角色"，EnterGame 变成 `player_id=0`。C++ gate 的限流和超长拒绝恰好就是这种外层错误形状（`client_message_processor.cpp:276-283`、`306-311`）。
2. **登录阶段不能只回 tip。**
   tip（message_id 23，见 `client_message_processor.cpp:257-267`）会被当成非目标帧暂存，机器人会一直等到 15s 超时，记为 STUCK。所以 Login/CreatePlayer/EnterGame 的失败也必须用同一个 `message_id`（48/14/26）回复。
3. **proto3 的 presence 陷阱。**
   机器人判断失败的条件是 `ErrorMessage != nil`，而不是 `id != 0`。Java 在成功时**绝不能调用 `setErrorMessage(...)`**，即使传的是默认实例也不行：那样会把字段 1 序列化成长度为 0 的子消息，Go 端解出来就是非 nil，于是判为失败。这条对 LoginResponse、CreatePlayerResponse、EnterGameResponse 都适用；对 ReleaseSkill/EnterScene 响应只会多打一条 Warn。

---

## 4. 各步骤逐条契约

message_id 以 `proto/message_id.txt` 为准（括号里是 txt 的行号），与 `robot/generated/pb/game/message_id.go` 一致。
内层 proto 的全名只影响 body 的解析方式，**不出现在线上帧里**。线上帧名只有 `ClientRequest`、`ClientTokenVerifyRequest`、`MessageContent`、`ClientTokenVerifyResponse` 四种。
login.proto 的 package 是 `loginpb`（`proto/login/login.proto:10`），所以请求类型的全名是 `loginpb.LoginRequest` 等；scene、base、component 相关文件都没有 package。

### 步骤 A：Token 握手（不是 ClientRequest，而是独立帧）

- 时机：`NewGameClient` 之后 `time.Sleep(500ms)`（`robot/main.go:360`），并且 **只在 `len(token_payload) > 0` 时才做**（`robot/main.go:362`）。token_payload 为空时跳过握手，直接发 Login。
- 上行帧：`ClientTokenVerifyRequest{payload(1)=token_payload, signature(2)=token_signature}`（`message.proto:243-246`，`robot/pkg/client.go:207-216`）。
- 下行：**连接上的第一帧必须是 `ClientTokenVerifyResponse{success(1), error(2)}`**（`message.proto:249-251`）。机器人直接 `c.Recv()` 取第一帧，**没有超时**（`client.go:218`）。
- 判定：
  - 第一帧是其他类型时报 "unexpected response type"。
  - `success=false` 时报 "gate token rejected: \<error\>"。
  - 以上两种情况都按"token 过期"处理：`gate_token_retry++`，**不计 login_fail，也不消耗重试次数**，然后重新 assign-gate（`robot/main.go:363-371`、`301-312`）。连续超过 20 次后 `login_fail++`，放弃这个机器人。
- 机器人把 payload 和 signature 当作**不透明字节**。C++ gate 的做法是：用 hex 形式比对 HMAC-SHA256；解析 `GateTokenPayload`；校验 `gate_node_id`、`target_zone_id`、`expire_timestamp`；失败时先回 `success=false` 再关连接（`client_message_processor.cpp:986-1001`、`1029-1074`）。已验证过的会话再次 verify 直接回 success（`:1004-1008`）。Java 只需要保证"自己签发的 token 自己能验过"。
- 注意：C++ gate 对未验证会话发来的 `ClientRequest` 会直接 `forceClose`（`client_message_processor.cpp:874-877`）。

### 步骤 B：Login（message_id **48**，`message_id.txt:49`）

发送 `ClientRequest{message_id=48, body=loginpb.LoginRequest}`（`robot/main.go:703-707`）。

| LoginRequest 字段（`login.proto:26-32`） | 机器人的值 |
|---|---|
| `account`(1) | `"robot_0001"` 等 |
| `password`(2) | `cfg.Password`，即 `"<robot 冒烟配置里的开发口令>"` |
| `auth_type`(3) | **空串**（password 路径不填）。重试时的 access_token 路径填 `"access_token"`，satoken 路径填 `"satoken"` |
| `auth_token`(4) | password 路径为空；access_token/satoken 路径为对应 token |

等待 `MessageContent.message_id==48`，**15s** 超时（`robot/main.go:698`、`889-891`）。

| LoginResponse 字段（`login.proto:34-42`） | 机器人如何读取 |
|---|---|
| `error_message`(1) | **有值即失败**：`login_fail++`（`main.go:711-714`） |
| `players`(2) `repeated AccountSimplePlayerWrapper{player(1): AccountSimplePlayer}` | 为空时走 CreatePlayer；否则取 `players[0].player.player_id`（`main.go:720`、`741`） |
| `access_token`(3) | 非空时调用 `SetTokens` 保存，用于重连和刷新（`main.go:716-718`） |
| `refresh_token`(4)、`access_token_expire`(5)、`refresh_token_expire`(6) | 同上一起保存；expire 是 unix 秒 |

`AccountSimplePlayer`（`proto/common/base/user_accounts.proto:6-20`）里机器人只读 `player_id`(1)。`class_id`(2)、`gender`(3)、`zone_id`(4)、`name`(5)、`appearance_id`(6) 都不读。

失败（发送错误、超时、有 error_message）时 `login_fail++`；超时额外 `login_stuck++`。

### 步骤 C：CreatePlayer（message_id **14**，`message_id.txt:15`，只在 players 为空时发）

- 请求：`CreatePlayerRequest{}` 全部字段为默认值（`class_id`(1)=0、`gender`(2)=0、`name`(3)=""、`appearance_id`(4)=""），body 是 0 字节（`robot/main.go:722-726`；字段语义见 `login.proto:52-64`，0 或空串表示服务端取默认值并生成名字）。
- 等待 message_id 14，15s 超时。
- 响应 `CreatePlayerResponse`（`login.proto:66-70`）：
  - `error_message`(1) 有值即失败，`login_fail++`。
  - `players`(2) 必须非空，否则 `login_fail++`，错误信息 "no players after create"（`main.go:736-739`）。
  - 机器人取 **`players[0]`**（`main.go:734`、`741`），不是最后一个。

### 步骤 D：EnterGame（message_id **26**，`message_id.txt:27`）

- 请求：`EnterGameRequest{player_id(1)=上一步取到的 id}`，`request_id`(2) **不填**（`robot/main.go:742-747`；`login.proto:72-78`）。
- 等待 message_id 26，15s 超时。
- 响应 `EnterGameResponse`（`login.proto:80-118`）：
  - `error_message`(1) 有值时 `enter_fail++`（`main.go:751-754`）。发送错误或超时时也是 `enter_fail++`，超时另外 `login_stuck++`（`main.go:748`）。
  - **`player_id`(2) 会写入 `gc.PlayerId`**（`main.go:756`）。
  - `post_merge_notice_ts`(3)、`force_rename_required`(4) 不读。
- **`player_id` 必须回填为真实 id**：
  - 如果是 0，`PlayerList` 用 0 做键（`robot/main.go:396-397`），3 个机器人会互相覆盖，导致 NotifyEnterScene 投递给错误的 Player 对象，其余机器人等 60s 超时。
  - `Clients.Register` 对 0 不登记（`pkg/client_registry.go:24`）。
  - 离开时的 Disconnect 也不会发送（`login.go:279`）。
- 成功后：`login_ok++`（同时累计 avg_login/max_login，计时从 verify 完成之后、Login 发出之前开始，见 `main.go:375`）、`enter_ok++`，打 Info 日志 **`entered game`**，字段 `account`、`player_id`（`main.go:387-393`）。
  - 注意：此时**还没有**收到 NotifyEnterScene，所以这条日志不能作为进入场景的证据。

### 步骤 E：NotifyEnterScene（message_id **79**，`message_id.txt:80`，服务端主动推送）

- 顺序：**可以早于 EnterGameResponse(26) 到达**，甚至在 Login 阶段就到达。机器人会暂存，等 RecvLoop 启动后补投递（`robot/login.go:151-154`、`pkg/client.go:271-273`）。
- 投递前机器人已经完成 `PlayerList.Set`（`main.go:396-397`），然后启动 `RecvLoop`（`main.go:405-413`）。
- 超时：EnterGame 成功后 **60s**（`main.go:429-434`）。超时时打 Error "timed out waiting for scene ready"，本轮 `return`（ok=false），进入重试（见 §6 和 §7）。
- 载荷：`MessageContent{message_id=79, serialized_message=EnterSceneS2C}`。

| EnterSceneS2C（`proto/scene/player_scene.proto:23-26`） | 机器人如何读取 |
|---|---|
| `scene_info`(1) `SceneInfoComp` | **必须存在**。为 nil 时处理函数直接 return，**不会发出就绪信号**（`scene_scene_client_player_notify_enter_scene.go:11-13`） |
| `scene_info.scene_config_id`(1) uint32 | 读取；AI 切场景时用。为 0 时 AI 不发 63 |
| `scene_info.scene_id`(2) uint64 | 读取；scene_id 变化时清空已知实体列表（`robot/logic/gameobject/player.go:148-158`） |
| `mirror_config_id`(3)、`dungeon_config_id`(4)、`creators`(5) | 不读（`scene_info.proto:4-11`） |

- 只有**第一次**收到会发出就绪信号（`player.go:160-164`，`sync.Once`）。之后再收到只更新场景信息。
- 打 Info 日志 **`notify enter scene`**，字段 `player`、`scene_id`、`scene_config_id`（handler `:16-20`）。

### 步骤 F：ListSkills（message_id **77**，`message_id.txt:78`）

- 请求：`ListSkillsRequest{}`，空消息（`robot/main.go:437`；`proto/scene/player_skill.proto:44-45`）。这次发送**不计入** `msg_sent`。
- 等待 **5s**，由 `player.WaitSkillsReady` 实现（`main.go:440-444`）。超时只打 Warn "timed out waiting for skill list"，**不影响任何计数器**，流程继续。
- 响应 `ListSkillsResponse`（`player_skill.proto:48-51`）：
  - `error_message`(1) **不检查**。
  - `skill_list`(2) 是 `PlayerSkillListComp{skill_list(1): repeated PlayerSkillComp{id(1), skill_table_id(2)}}`（`proto/common/component/player_skill_comp.proto:6-13`）。机器人只读 `skill_table_id`（`scene_skill_client_player_list_skills.go:10-21`）。
  - `skill_list` 为 nil 时，拥有技能记为空，仍然发出就绪信号，但不打日志。
  - 非空时打 Info **`received skill list`**，字段 `player`、`skills`。
- 技能列表为空时，AI 的 cast_skill 什么也不发，只写一条行为记录。

---

## 5. NotifyEnterScene 之后机器人在冒烟模式下做什么

1. **AI 循环**（`robot/main.go:446-461`；`robot/logic/ai/robot_ai.go:50-83`）：
   - 启动前随机抖动 0-2s，之后每 `action_interval=3s` 执行一次动作。
   - `profile: stress` 对应 `cast_skill:85, switch_scene:15`（`robot/logic/ai/action.go:111-117`），**没有 move、chat、idle**。
   - LLM 关闭。
   - **所有动作都只发请求、不等响应**，响应只影响日志和行为记录，**不影响 login/enter 计数器**。

2. **cast_skill → 发 message_id 84 ReleaseSkill**（`message_id.txt:85`；`robot_ai.go:131-174`）：
   - 前提一：ListSkills 返回了非空技能列表。
   - 前提二：有目标实体。`GetRandomEntity` 从已知实体里随机选一个，没有已知实体时用自身 entityID（`player.go:236-244`）。实体来源是 **21 NotifyActorCreate**（`guid==player_id` 时记为自己的 entity）和 **47 NotifyActorListCreate**（handler 文件 `scene_scene_client_player_notify_actor_create.go:8-13`、`..._list_create.go:8-15`）。两个都拿不到时不发请求，只写一条失败的行为记录。
   - 请求 `ReleaseSkillRequest`（`player_skill.proto:14-19`）：
     - `skill_table_id`(1)：从拥有技能中随机取。
     - `target_id`(2)：上面选出的实体。
     - `position`(3)：`Vector3{x, 0, z}`，x/z 在本地坐标（初始为 0）附近 ±10 随机。
     - `rotation`(4) 不填。
   - 计数：`skill++`、`msg_sent++`。
   - 响应 84 `ReleaseSkillResponse{error_message(1)}`：有 error_message 时打 Warn "release skill rejected"（`scene_skill_client_player_release_skill.go:10-18`）。
   - 推送 70 SkillUsedS2C 和 33 SkillInterruptedS2C 只更新 Player 内部计数。

3. **switch_scene → 发 message_id 63 EnterScene**（`message_id.txt:64`；`robot_ai.go:195-218`）：
   - 前提：NotifyEnterScene 带回的 `scene_config_id != 0`。
   - 请求：`EnterSceneC2SRequest{scene_info(1)=SceneInfoComp{scene_config_id(1)=当前场景配置 id}}`，也就是"切到同一张图"（`player_scene.proto:13-16`）。
   - 计数：`scene_switch++`、`msg_sent++`。
   - 响应 63 `EnterSceneC2SResponse{error_message(1)}`：有 error 打 Warn "enter scene rejected"，否则 Info "enter scene accepted"（`scene_scene_client_player_enter_scene.go:10-19`）。
   - 如果服务端随后再推 79，只更新场景信息。

4. **token 刷新协程**：见 §2.5。

5. **机器人登记了处理函数的下行 id**（`message_body_handler.go:57-65`、`103-115`）：
   - 14/17/26/48/58/127（登录类）
   - 23 SendTipToClient（空操作）、34 KickPlayer（空操作）、124 RedirectToGate（会跟随跨区重定向，见 `scene_client_player_common_redirect_to_gate.go:41-90`；**里程碑内不要发**）
   - 21/47/51/64（实体进出）、31、43、63、79、226
   - 84/70/33/77
   - 移动 130-137

   发送其他 message_id 只会打 Info 日志，没有副作用。

**服务端需要回复哪些消息，机器人才不会计失败**：
- **必须**：48、14（仅在 players 为空时）、26 各自在 15s 内回复；79 在 60s 内推送且带 `scene_info`。
- **建议**：77 在 5s 内回复，否则打 Warn，AI 也放不了技能。
- **可选**：84 和 63 的响应，21/47 的实体推送。回不回都不影响计数器，只影响日志噪声和 AI 是否有实际动作。

---

## 6. 日志与计数器

### 6.1 关键日志（zap，Development 格式，Info 级别，见 `robot/main.go:484-493`）

| 日志 | 级别 | 位置 | 触发条件 |
|---|---|---|---|
| `starting robots` gateway,count | Info | `main.go:215-218` | 启动 |
| `entered game` account,player_id | Info | `main.go:390-393` | 48/14/26 全部成功 |
| `notify enter scene` player,scene_id,scene_config_id | Info | `..._notify_enter_scene.go:16-20` | 收到带 scene_info 的 79 |
| `received skill list` player,skills | Info | `..._list_skills.go:20` | 77 返回非空 skill_list |
| `timed out waiting for scene ready` | Error | `main.go:432` | EnterGame 后 60s 内没有有效的 79 |
| `timed out waiting for skill list` | Warn | `main.go:443` | 77 超过 5s |
| `login flow failed` | Error | `main.go:377` | 48/14/26 任一步失败 |
| `resolve gate address failed` | Error | `main.go:335` | assign-gate 30 次都失败 |
| `gate token verify failed, will refetch` | Warn | `main.go:368` | 握手失败 |
| `retrying login` / `robot gave up after retries` / `...repeated gate-token expiry` | Info/Error | `main.go:279`、`314`、`304` | 重试或放弃 |
| `release skill rejected` / `enter scene rejected` / `enter scene accepted` | Warn/Warn/Info | 见 §5 | AI 响应 |
| `Unhandled message` message_id / `Player not found` | Info/Error | `message_body_handler.go:149`、`142` | 下行分发 |

统计行每 `report_interval` 秒打印一次，格式如下（`robot/metrics/stats.go:188-206`；`tools/scripts/stress_summarize.ps1:161` 用正则解析它）：

```
[stats %s] conn=%d login_ok=%d login_fail=%d login_stuck=%d enter_ok=%d enter_fail=%d msg_sent=%d(%.0f/s) msg_recv=%d(%.0f/s) skill=%d scene_switch=%d avg_login=%s max_login=%s recon_ok=%d recon_fb=%d refresh_ok=%d refresh_fail=%d q_entered=%d q_admitted=%d q_expired=%d q_avg_wait=%s q_max_rank=%d gate_token_retry=%d
```

### 6.2 计数器的精确触发点

| 计数器 | 何时自增 |
|---|---|
| `conn` | `NewGameClient` 返回之后立刻加，此时 TCP 可能还没连上（`main.go:356`）。只增不减；`disconnected` 不打印 |
| `login_ok` | 48、(14)、26 全部成功后，`main.go:387`。只有这一处（stress 路径） |
| `enter_ok` | 与 login_ok 同一时刻，`main.go:388`。**stress 模式下两者恒相等** |
| `login_fail` | ① assign-gate 30 次都失败（`main.go:336`）；② connect failed（`:344`，实际不会发生）；③ 握手连续过期超过 20 次（`:307`）；④ Login 发送错误、超时或带 error_message（`:708`、`:712`）；⑤ CreatePlayer 失败或带 error（`:727`、`:731`）；⑥ 建角后 players 仍为空（`:737`）；⑦ 重试时 access_token 登录路径失败，即使随后回落成功也照记（`login.go:426-452`）；⑧ use_http_login 路径失败（`main.go:819-845`）；⑨ satoken 路径失败（`login.go:343-389`） |
| `login_stuck` | 48/14/26 任一步 15s 超时（`main.go:890`、`login.go:169`）。**同时一定伴随 login_fail++ 或 enter_fail++** |
| `enter_fail` | EnterGame 发送错误、超时或带 error_message（`main.go:748`、`:752`，以及 `login.go:86/90`、`400/404`、`463/467`） |
| `gate_token_retry` | 握手被拒或第一帧不是 verify 响应（`main.go:369`） |
| `recon_ok` / `recon_fb` | access_token 重连成功/失败（`main.go:767`、`:771`） |
| `skill` / `scene_switch` | AI 发出 84 / 63（`robot_ai.go:171`、`:215`） |
| `msg_sent` / `msg_recv` | sendAndRecv 和 AI 的发送；登录阶段读帧加 RecvLoop 分发。暂存帧会被重复计数 |

**冒烟判定建议**：
- `robot_count=3` 时，稳定后的统计行应为 `login_ok=3 enter_ok=3 login_fail=0 login_stuck=0 enter_fail=0 gate_token_retry=0`。
- 日志里有 3 条 `notify enter scene`，没有 `timed out waiting for scene ready`，最好还有 3 条 `received skill list`。
- 注意：如果 79 超时，机器人会重试并再登录一次，**`login_ok`/`enter_ok` 会超过 robot_count**。计数器本身没有专门的"场景失败"项，必须结合日志判断。

**退出码不能作为判定依据**：
- stress 模式一直阻塞到 SIGINT/SIGTERM（`main.go:238-240`），之后 `main` 正常返回，退出码为 0，**不管中间失败了多少次**。
- 只有配置加载失败（`main.go:57-60`）或表加载失败（`log.Fatalf`）才会退出码 1。
- 文档里"退出码 0"的验收口径（`docs/design/guild-phase2/92-handoff.md:425`）说明不了登录链路的结果。

---

## 7. 离开与断开行为

### 7.1 正常结束（收到 SIGINT）

`close(stopAll)` 之后，AI 循环退出，`runRobotOnce` 从 `select` 返回（`main.go:464-467`），接着：

1. 发送 `ClientRequest{17 LeaveGame, LeaveGameRequest{}}`（`main.go:470`；`message_id.txt:18`；`login.proto:120-122`）。**不等响应**；服务端即使回 17 `LoginEmptyResponse`，机器人也只是空处理。
2. 执行 defer（后进先出顺序）：Clients.Unregister → PlayerList.Delete → `disconnected++`。
3. **仅在 `PlayerId != 0` 时**发送 `ClientRequest{58 Disconnect, LoginNodeDisconnectRequest{}}`，其中 `session_id`(1)=0（`robot/login.go:278-287`；`message_id.txt:59`；`login.proto:124-127`）。**不等响应**。
4. `gc.Close()`：立刻 cancel、关闭 socket、关闭 outgoing（`connection.go:259-267`）。

**17 和 58 都是尽力而为，可能根本没写到 socket 上**：writeLoop 在 `ctx.Done` 和 `outgoing` 之间 select，而 Close 是紧跟着发送就执行的（`connection.go:192-199`）。
**结论：Java 必须把 TCP 断开（FIN/RST）当作权威的离线信号。** C++ gate 现状就是在 TCP 关闭时自己替客户端生成 58 Disconnect 发给 login（`client_message_processor.cpp:465-471`）。

一个边界情况：停止信号到达时如果机器人还在 60s 的 `WaitSceneReady` 里，等待不会被打断（`main.go:429` 用的是独立的 context）。

### 7.2 失败路径

- **登录失败**（48/14/26）：直接 return。此时 `PlayerId==0`，所以不发 58，直接关 TCP。随后退避重试：最多 5 次，间隔 3s → 6s → 12s → 24s，上限 30s（`main.go:253-313`）。每次重试都是**新的 assign-gate 加新的 TCP 连接**。
- **79 超时**（EnterGame 已经成功）：return，发 58 并关 TCP，然后重试。因为 token 已缓存（`main.go:383-386`），下一轮先走 `auth_type="access_token"`。
  - 服务端必须支持**同账号在新连接上重新登录并替换旧会话**，因为旧会话的 58 可能已丢失。
  - access_token 登录失败后，机器人会在**同一条连接上再发一次密码 Login**，服务端必须接受这种"同一连接上的第二次 Login"。

### 7.3 服务端主动断开或踢人

- 34 KickPlayer 的处理函数是空操作（`scene_client_player_common_kick_player.go:8-9`）。
- TCP 被关闭后，muduo **1 秒后自动重连同一个 gate 地址**，而且**不会重新握手**；`RecvLoop` 也感知不到断线（§3.2）。
- 结果是 AI 会继续在一条未验证的新连接上发 84/63。C++ gate 对未验证连接的 ClientRequest 直接 `forceClose`（`client_message_processor.cpp:874-877`），于是形成约每秒一次的重连循环。
- 这种情况下**所有计数器都不会变化**，机器人会一直空转到 SIGINT。Java 必须能容忍这种"未握手就发 ClientRequest"的连接（拒绝并关闭即可，不能崩溃）。
- 在登录阶段被断开时，机器人只能等到 15s 超时，记为 STUCK。

---

## 8. 超时与时序汇总

| 环节 | 值 | 位置 |
|---|---|---|
| 机器人启动间隔 | 50ms | `main.go:235` |
| assign-gate / queue-status 单次调用 | 5s | `main.go:609`、`667`、`669`、`684` |
| assign-gate 外层重试 | 30 次，2s 起每次 ×1.5，到 ≥10s 封顶 | `main.go:522-536` |
| 排队总预算 | 5min | `main.go:595` |
| server-list | 5s | `main.go:541` |
| TCP 拨号 | 无超时，每 1s 重试，无限次 | `connection.go:63-68` |
| 握手前等待 | 500ms | `main.go:360` |
| VerifyGateToken | **无超时** | `pkg/client.go:218` |
| Login / CreatePlayer / EnterGame | 各 15s | `main.go:698`；`login.go:23` |
| NotifyEnterScene | 60s | `main.go:429` |
| ListSkills | 5s（超时只打 Warn） | `main.go:440` |
| AI 间隔 | 3s，首次额外抖动 0-2s | `main.go:451-453`；`robot_ai.go:51-52` |
| token 刷新 | 每 30s 检查，剩余 ≤10min 时刷新，冷却 60s，HTTP 5s | `login.go:200-203`、`233` |
| /api/login | 10s | `main.go:826` |
| 读 / 写超时 | 30s（只 continue）/ 10s | `connection.go:145`、`216` |
| 单机器人重试 | 5 次，3s 起翻倍，上限 30s；token 过期另计，200ms 间隔，最多 20 次 | `main.go:253-312` |

---

## Java 必须做到（清单）

1. **HTTP**：`POST {gateway_addr}/api/assign-gate` 接受 `{"zone_id":N}`，返回 **HTTP 200** 和 snake_case JSON：`code`(数字 0)、`gate_ip`(非空)、`gate_port`(非 0 数字)、`token_payload` 和 `token_signature`（**标准 Base64 字符串**）。其他 code 沿用 100/410/429/404/503/500 的语义。`zone_id=0` 时还要提供 `GET /api/server-list` → `{"zones":[{"zone_id":..,"recommended":..}]}`。
2. **帧格式**：`[len][nameLen][name+1 字节结尾符][body][adler32(nameLen..body)]`，全部大端。
   - **解码**：剥掉名字最后 1 字节（机器人用空格结尾，C++ 标准用 `\0`），用全名查类型。
   - **编码**：只发 `MessageContent` 和 `ClientTokenVerifyResponse`，名字写全名加 `\0`，nameLen 包含结尾符，校验和必须正确。
   - **绝不能**向机器人发送它不认识的类型名或损坏的 body，否则连接会被永久卡死。
3. **握手**：机器人发出 `ClientTokenVerifyRequest` 后，**连接上的第一帧**必须是 `ClientTokenVerifyResponse{success=true}`，并且要尽快回（机器人这里没有超时）。要能验证自己签发的 payload/signature。失败时回 `success=false` 再关连接。
4. **按 message_id 回包**：48→48、14→14、26→26、77→77，都用 `MessageContent{message_id=同号, serialized_message=内层响应}`，每步在 15s 内（77 在 5s 内）。机器人不看 `id`，但建议回显 `ClientRequest.id`，与现网行为一致。
5. **错误放在内层 `error_message`；成功时绝对不要设置 `error_message`**（包括默认实例）。登录阶段不能只回 tip(23)，也不能只在外层 `MessageContent.error_message` 里报错。
6. **Login**：接受 `auth_type==""` 的密码登录（`robot_` 前缀加共享密码），账号不存在时自动创建。新账号返回空 `players`。建议返回 `access_token` 和一个远于 10 分钟的 `access_token_expire`（或 0）。支持在同一连接上再次 Login；最好支持 `auth_type="access_token"`，不支持时必须回带 error_message 的 48。
7. **CreatePlayer**：接受全默认值的空请求（服务端决定职业和性别，并生成名字），返回的 `players[0].player.player_id` 就是机器人要进入的角色。
8. **EnterGame**：回复 `player_id`(2) 等于请求里的 id，不能为 0。
9. **NotifyEnterScene(79)**：在 EnterGame 前后都可以推送（机器人会暂存），但必须在 EnterGameResponse 之后 60s 内到达，并且**必须带 `scene_info`**，其中填好 `scene_config_id`(1) 和 `scene_id`(2)。在机器人开始 RecvLoop 之前，不要一次性连发超过 100 帧，否则可能被丢弃；最好优先推送 79。
10. **ListSkills(77)**：5s 内回 `ListSkillsResponse{skill_list(2).skill_list(1)[].skill_table_id(2)}`。
11. **容忍 AI 流量**：约每 3s 一条 84 ReleaseSkill 或 63 EnterScene（同一张图）。回不回都行，但不能因此踢人或崩溃。首个里程碑可以让机器人用 `custom_weights: {idle: 1}` 把这些流量关掉。
12. **离线判定**：17 LeaveGame 和 58 Disconnect 只是尽力而为，可能根本不会到达。**TCP 关闭必须视为离线**。同一账号在新连接上重新登录时必须能替换旧会话。能容忍被断开后自动重连、未握手就直接发 ClientRequest 的连接（拒绝并关闭）。
13. **里程碑内不要发送** 124 RedirectToGate、34 Kick，也不要发送任何非 `MessageContent` 的业务帧。
14. **验收看日志和统计行，不看退出码**：`login_ok=enter_ok=3`，`login_fail=login_stuck=enter_fail=gate_token_retry=0`，3 条 `notify enter scene`，没有 `timed out waiting for scene ready`。