# 登录链路客户端可见契约（Login / CreatePlayer / EnterGame / LeaveGame / Disconnect）

> 快照：mmorpg commit 766cb037c，根目录 `C:\Users\ADMINI~1\AppData\Local\Temp\claude\D--luyuan-wuxingqitan-mmorpg\296f1859-4ee0-4d2d-a51b-ea15b1f39a91\scratchpad\baseline`。下文路径都相对于这个根目录，写成 `file:line`。
> 范围：本文只写客户端能看到的行为，覆盖 robot_smoke 这条链：登录 → 建角 → 进游戏 → NotifyEnterScene → ListSkills → 离开/断线。服务端内部的 Redis 键、Kafka 和 player_locator 只在影响客户端结果时提及。
> 快照里没有 UE 客户端源码（全库只在 docs 里提到 `EnterGameRequest`），所以契约依据是 Go 机器人 `robot/` 和服务端代码。

---

## 1. 消息号与 proto 全名

消息号已与 `proto/message_id.txt` 和 `robot/generated/pb/game/message_id.go` 逐条核对。

| message_id | 名字（message_id.txt） | 请求全名 | 应答全名 | 出处 |
|---|---|---|---|---|
| **48** | ClientPlayerLoginLogin | `loginpb.LoginRequest` | `loginpb.LoginResponse` | message_id.txt:49 |
| **14** | ClientPlayerLoginCreatePlayer | `loginpb.CreatePlayerRequest` | `loginpb.CreatePlayerResponse` | message_id.txt:15 |
| **26** | ClientPlayerLoginEnterGame | `loginpb.EnterGameRequest` | `loginpb.EnterGameResponse` | message_id.txt:27 |
| **17** | ClientPlayerLoginLeaveGame | `loginpb.LeaveGameRequest` | `loginpb.LoginEmptyResponse` | message_id.txt:18 |
| **58** | ClientPlayerLoginDisconnect | `loginpb.LoginNodeDisconnectRequest` | `loginpb.LoginEmptyResponse` | message_id.txt:59 |
| 127 | ClientPlayerLoginRefreshToken | `loginpb.RefreshTokenRequest` | `loginpb.RefreshTokenResponse` | message_id.txt:128 |
| 23 | SceneClientPlayerCommonSendTipToClient（服务端推送） | — | 负载 `TipInfoMessage` | message_id.txt:24 |
| 34 | SceneClientPlayerCommonKickPlayer（服务端推送） | — | 负载 `GameKickPlayerRequest` | message_id.txt:35 |
| 79 | SceneSceneClientPlayerNotifyEnterScene（服务端推送） | — | 见 scene 契约 | message_id.txt:80 |
| 77 | SceneSkillClientPlayerListSkills | 见 scene 契约 | | message_id.txt:78 |
| 124 | SceneClientPlayerCommonRedirectToGate（推送） | — | `RedirectToGateNotify` | robot message_id.go:127 |

**包名。** 这些全名会进 codec 帧里的类型名，必须一字不差：

- `proto/login/login.proto:10` 的包是 `loginpb`。
- 下面这些文件都没有 `package`，所以消息全名就是裸名：
  - `proto/common/base/tip.proto`（`TipInfoMessage`）
  - `proto/common/base/user_accounts.proto`（`AccountSimplePlayer`）
  - `proto/common/base/message.proto`（`MessageContent`、`ClientRequest`、`ClientTokenVerifyRequest/Response`、`GateTokenPayload`）
  - `proto/scene/client_player_common.proto`（`GameKickPlayerRequest`、`RedirectToGateNotify`）

**Service 声明。** `proto/login/login.proto:147-156` 声明 `service ClientPlayerLogin`，带 `option (OptionIsClientProtocolService) = true`。gate 白名单里 48/14/26/17/58/127 都是客户端允许发送的消息号（`cpp/generated/rpc/service_metadata/rpc_event_registry.cpp:1587-1592`）。

---

## 2. 传输信封，以及错误怎么回到客户端

### 2.1 信封

- 客户端发：`ClientRequest{ id=1 (uint64 自增序号), service=2, method=3, body=4 (bytes), message_id=5 }`（`proto/common/base/message.proto:196-203`）。机器人只填 `id`、`message_id`、`body`（`robot/pkg/client.go:135-152`）。
- 服务端回：`MessageContent{ serialized_message=1 (bytes), message_id=2, id=3, error_message=4 (TipInfoMessage) }`（`message.proto:18-24`）。
- `TipInfoMessage{ uint32 id=1; repeated string parameters=2 }`（`proto/common/base/tip.proto:4-8`）。

### 2.2 两种 gate 模式下的应答形状

两种模式都要兼容。dev 默认是路由模式：`tools/scripts/start_game.ps1:20` 的 `-GateRouterMode` 默认值是 `'1'`。

| | 直连模式（`GATE_CLIENT_RPC_ROUTER` 未设或为 0） | 路由模式（`=1`，dev 默认） |
|---|---|---|
| 成功应答 `message_id` | 按**应答类型全名**反查消息号（`cpp/nodes/gate/main.cpp:268-313`） | 照抄请求的 `message_id`（`go/client_rpc_router/internal/logic/forwardlogic.go:187-191`） |
| 成功应答 `id` | **不填，为 0**（main.cpp:310-313 没有 `set_id`） | 照抄请求 `id` |
| 下游 gRPC 失败（login 返回 error 或超时） | 另推一条 **msg 23**，负载 `TipInfoMessage{id:1003 kServiceUnavailable}`（main.cpp:323-348） | `MessageContent{id, message_id=请求号, error_message{id:1003}}`，**body 为空**（forwardlogic.go:170-184, 226-232）；路由转发超时 `ForwardTimeoutMs: 5000`（`go/client_rpc_router/etc/client_rpc_router.yaml:32`） |

直连模式有一个怪癖：`LoginEmptyResponse` 同时被 17 和 58 使用，反查表按消息号升序构建、后写覆盖先写，所以 **LeaveGame 的应答会以 message_id=58 下发**。机器人不等这两个应答，所以没有影响。

### 2.3 业务错误走哪里

结论：**业务错误一律放在应答体自己的 `error_message`（字段号 1）里**，也就是 `LoginResponse.error_message`、`CreatePlayerResponse.error_message`、`EnterGameResponse.error_message`。login 服务从不设置 `MessageContent.error_message`。

`MessageContent.error_message`（字段 4）只由 gate 或路由服在本地拒绝时填，情况有：

- 包体超过 1KB → 1010 kMessageSizeExceeded（`cpp/nodes/gate/handler/rpc/client_message_processor.cpp:269-299`）
- 限流 → 限流码（同文件 301-341）
- 路由服拒绝 / 下游失败 → 1003 或 1005

机器人**完全不看** `MessageContent.error_message`：`robot/login.go:127-172` 只按 `message_id` 匹配，然后把 `serialized_message` 反序列化成应答，再检查 `resp.ErrorMessage != nil`。由此推出两点：

- 如果只回信封错误、body 为空，机器人会把它当**成功**。比如空的 LoginResponse 会被当成“没有角色”，接着去建角。
- **成功时应答体里绝不能出现 `error_message` 字段，哪怕 id=0 也不行。** proto3 的 Go 解码会把一个空的子消息解成非 nil。见 `robot/login.go:37, 66, 89`。

### 2.4 gate 自己在进入 login 之前推的 tip（msg 23）

- `kServiceUnavailable 1003`：没有可用的 login 节点 / 路由服（client_message_processor.cpp:778-783, 830-835）。
- `kRequestMessageParseError 1014`：请求 body 解析失败（同文件 730-735）。

---

## 3. 消息字段定义（`proto/login/login.proto`，`proto/common/base/user_accounts.proto`）

### 3.1 `loginpb.LoginRequest`（login.proto:26-32）

| # | 类型 | 字段 | 含义 |
|---|---|---|---|
| 1 | string | `account` | 账号。只有 password 认证使用它；其它 auth_type 下账号由 token 解析，这个字段被忽略 |
| 2 | string | `password` | 口令 |
| 3 | string | `auth_type` | `""` 等同 `"password"`；其余可选 `"access_token"`、`"satoken"`、`"wechat"`、`"qq"`、`"netease"` |
| 4 | string | `auth_token` | 非 password 类型用的 token 或 code |

### 3.2 `loginpb.LoginResponse`（login.proto:34-42）

| # | 类型 | 字段 | 服务端何时填写 |
|---|---|---|---|
| 1 | TipInfoMessage | `error_message` | 仅失败时出现 |
| 2 | repeated AccountSimplePlayerWrapper | `players` | 成功时填账号下全部角色，按建角先后顺序；没有角色时为空 |
| 3 | string | `access_token` | 成功且 auth_type ≠ `"access_token"` 时填。值为 32 字节随机数的 base64url 无填充编码，共 43 字符（`go/login/internal/logic/pkg/token/token.go:225-231`） |
| 4 | string | `refresh_token` | 同上 |
| 5 | int64 | `access_token_expire` | Unix 秒，now + 2h（`go/login/etc/login.yaml:276-278`） |
| 6 | int64 | `refresh_token_expire` | Unix 秒，now + 720h |

签发失败不算致命，只是 3–6 留空（`go/login/internal/logic/clientplayerlogin/loginlogic.go:201-212`）。

### 3.3 `loginpb.AccountSimplePlayerWrapper`（login.proto:21-24）

只有一个字段：`AccountSimplePlayer player = 1`。

### 3.4 `AccountSimplePlayer`（无包名；user_accounts.proto:6-20）

| # | 类型 | 字段 | 服务端写入的值 |
|---|---|---|---|
| 1 | uint64 | `player_id` | 建角时发的号 |
| 2 | uint32 | `class_id` | 建角时定下的职业，默认 1（见 §5.2） |
| 3 | uint32 | `gender` | 1 或 2，默认 1 |
| 4 | uint32 | `zone_id` | 建角时是 login 自己的 `Node.ZoneId`（dev 为 1，login.yaml:17）；Login 列表会用 player:zone 映射覆盖 |
| 5 | string | `name` | 角色名，全服唯一 |
| 6 | string | `appearance_id` | 建角请求原样写入；机器人建角时为 `""` |

**这个消息没有 `level` 字段。** 只有 Java Gateway 的 HTTP DTO 里有 level。

### 3.5 `loginpb.CreatePlayerRequest`（login.proto:52-64）

| # | 类型 | 字段 | 含义 |
|---|---|---|---|
| 1 | uint32 | `class_id` | 0 表示取 Class 表第一行 |
| 2 | uint32 | `gender` | 0 表示 1（男）；1=男，2=女 |
| 3 | string | `name` | 空串表示由服务端生成 |
| 4 | string | `appearance_id` | 空串是合法值；非空必须在白名单内 |

机器人发的是空消息 `CreatePlayerRequest{}`（`robot/login.go:60`）。

### 3.6 `loginpb.CreatePlayerResponse`（login.proto:66-70）

字段：`TipInfoMessage error_message = 1; repeated AccountSimplePlayerWrapper players = 2;`

### 3.7 `loginpb.EnterGameRequest`（login.proto:72-78）

字段：`uint64 player_id = 1; string request_id = 2;`。机器人不填 `request_id`（`robot/login.go:83`）。

### 3.8 `loginpb.EnterGameResponse`（login.proto:80-118）

| # | 类型 | 字段 | 服务端何时填写 |
|---|---|---|---|
| 1 | TipInfoMessage | `error_message` | 仅失败时出现 |
| 2 | uint64 | `player_id` | 成功时等于请求的 player_id；失败时为 0 |
| 3 | int64 | `post_merge_notice_ts` | 通常 0。只有 Redis 键 `player_merge_notice:{pid}` 存在时才填，取值后删键 |
| 4 | bool | `force_rename_required` | 通常 false。只有 `player_force_rename:{pid}` 存在时才为 true，而且不删键 |

字段 3、4 的逻辑见 `go/login/internal/logic/clientplayerlogin/entergamelogic.go:487-548`。

### 3.9 其余三个消息

- `loginpb.LeaveGameRequest {}`（login.proto:120-122）
- `loginpb.LoginNodeDisconnectRequest { uint32 session_id = 1; }`（login.proto:124-127）
- `loginpb.LoginEmptyResponse {}`（login.proto:129-131）

---

## 4. Login（48）

### 4.1 路径判定

- **gate TCP 路径（旧路径，robot_smoke 走这条）**：请求经 gate 转发时带着 SessionDetails 且 `session_id>0`（`loginlogic.go:82`）。
- **HTTP 路径（新路径）**：Java Gateway 的 `POST /api/login` 直接调 gRPC，不带 session（`loginlogic.go:116-141`）。这条路只做认证、签 token、返回角色列表，**不绑定会话**。之后客户端还必须在 gate 的 TCP 上再发一次 `Login{auth_type:"access_token"}` 来完成绑定（`robot/main.go:794-853`）。
- 旧路径有一个开关：`LegacyGateLoginEnabled`，默认 true（login.yaml:286，`go/login/internal/config/config.go:87`）。设为 false 后旧路径直接返回 2015（`loginlogic.go:102-109`）。

robot_smoke 的配置 `robot/etc/robot_smoke.yaml` 没有设置 `use_http_login`，默认 false（`robot/config/config.go:75-87`）。因此机器人走的是：HTTP assign-gate → TCP → `Login{account, password}`，此时 **auth_type 为空**（`robot/main.go:701-706, 788-791`）。

### 4.2 认证规则（`loginlogic.go:354-387`）

**auth_type 为 `""` 或 `"password"`：** 只使用已注册的口令认证器；没注册就 fail-closed，返回 2000。

开发口令认证 `DevelopmentPasswordProvider`（`go/login/internal/logic/pkg/auth/providers.go:61-76`）规则如下：

- `account` 非空，且等于 `strings.TrimSpace(account)`，即首尾不能有空白。
- `account` 必须以白名单前缀之一开头：**`"robot_"` 或 `"dev_"`**（login.yaml:336-338）。
- `password` 必须与环境变量 **`LOGIN_DEV_PASSWORD_SHARED_SECRET`** 的值完全相等（常数时间比较）。变量名由 `DevPasswordAuth.SharedSecretEnv` 配置（login.yaml:333-335），环境变量缺失或为空时拒绝启动（`go/login/internal/svc/auth_init.go:149-159`）。
- 只在 go-zero `Mode` 为 dev 或 test 时允许启用（`auth_init.go:138-144`；login.yaml:5 为 `Mode: dev`）。与生产 `PasswordAuth`（MySQL Argon2id）互斥（`auth_init.go:28-30`）。
- 通过后，账号字符串就是请求里的 `account` 原样。

robot_smoke 使用的口令是 `password: "<robot 冒烟配置里的开发口令>"`，账号格式 `robot_%04d`（`robot/etc/robot_smoke.yaml`）。`tools/scripts/start_game.ps1:373-381` 在环境变量未设置时，会从 `robot/etc/robot.yaml` 的 `password` 取值作为密钥。

**auth_type 为 `"access_token"`：** 校验 Redis 键 `access_token:{token}`。账号取 token 里存的账号，请求中的 `account` 被忽略（`providers.go:84-91`）。**这种登录不重新签 token**，应答字段 3–6 为空（`loginlogic.go:197-212`）。机器人重连时优先走这条路径（`robot/main.go:763-775`）。

**其它 auth_type：**

| auth_type | 规则 |
|---|---|
| `"satoken"` | 读 Redis `satoken:login:token:{v}`，得到 loginId（`providers.go:100-114`） |
| `"wechat"` | 解析出的账号为 `wx_<unionid 或 openid>` |
| `"qq"` | 解析出的账号为 `qq_<unionid 或 openid>` |
| `"netease"` | 总是失败 |
| 未注册的类型 | 失败 |

以上所有认证失败都统一返回 **2000 kLoginAccountNotFound**（`loginlogic.go:46-51`）。

### 4.3 旧路径的处理步骤与成功时的填充

1. 账号锁 `account_lock:login:{account}`，TTL 20s（login.yaml:77），单次 SETNX。拿不到锁或 Redis 出错都返回 2005（`loginlogic.go:54-62`）。
2. 把 `login_session:{session_id} = account` 写入 Redis，TTL `SessionExpireMin` = 30 分钟（login.yaml:18）（`loginlogic.go:152-156`）。
3. 设备数限制：先清理设备集合里已失效的项，再把当前 session 加进集合，然后 SCARD。结果 **大于 `MaxDevicesPerAccount`=3**（login.yaml:91）时返回 2024（`loginlogic.go:159-188`）。设备集合的成员会在 EnterGame 成功收尾时（Cleanup）移除，所以实际上只计算处于 Login→EnterGame 窗口内的连接。
4. 读取账号角色目录；不存在则以空目录初始化，不存在也不报错（`loginlogic.go:191-194, 331-349`）。
5. 签发 token（`loginlogic.go:197-212`）。
6. 返回 `players`，见下文（`loginlogic.go:215, 234-240`）。

`players` 的构造：账号目录里的角色按原顺序排列，然后做三处覆盖，只改应答，不回写存储：

- `zone_id` 用 data_service 的 player:zone 映射覆盖；查不到时保持原值。
- `name` 为空的，向名字注册表补查，超时 500ms，查不到保持空。
- `appearance_id` 为空的，从 `PlayerAllData` 缓存补 `appearance_id`；同时若 `class_id` 或 `gender` 为 0 也一并补上（`role_appearance_backfill.go:17-64`）。

### 4.4 Login 错误码

| Tip | 何时返回 |
|---|---|
| 2000 kLoginAccountNotFound | 任何认证失败（§4.2） |
| 2005 kLoginInProgress | 账号锁已被占用，或加锁时 Redis 出错 |
| 2015 kLoginUnknownError | 旧路径开关被关闭 |
| 2018 kLoginSessionIdNotFound | 写在代码里（`loginlogic.go:143-147`），但旧路径判定已经要求 session_id>0，**实际走不到** |
| 2023 kLoginRedisSetFailed | 写 login_session、设备集合 pipeline 或 SCARD 失败 |
| 2024 kTooManyDevices | 设备数超过 3 |
| （gRPC error）→ 客户端看到 1003 | 读取或初始化账号目录失败（`loginlogic.go:191-194`，返回 `nil, err`），客户端按 §2.2 的方式收到 1003 |

---

## 5. CreatePlayer（14）（`go/login/internal/logic/clientplayerlogin/createplayerlogic.go:95-315`）

### 5.1 前置条件

- 必须带 session（没有则 2018，101-106 行）。
- 本连接上必须先成功 Login 过，让 `login_session:{sid}` 存在（否则 2028，109-114 行）。
- EnterGame 成功收尾、LeaveGame、Disconnect 都会删除这个键。之后同一连接再发 CreatePlayer 会得到 2028，除非重新 Login。

### 5.2 参数默认值与校验

- **class_id**：0 表示取 `ClassTableManagerInstance.FindAll()[0].Id`，也就是 `generated/tables/class.json` 按文件顺序的第一行，**id=1**（164-168 行）。非 0 但不在 Class 表里（id 1–9）→ 2015（169-173 行）。
- **gender**：0 表示 1；大于 2 → 2015（174-182 行）。
- **appearance_id**：`""` 合法，并**保持为空**，不自动分配。非空时必须在以下白名单内，否则 2015（183-187 行；`character_appearance.go:5-16`）：
  `00_reference_topright_boy`、`01_ice_sword_girl`、`02_fire_talisman_boy`、`03_lotus_healer_girl`、`04_mountain_guardian_boy`、`05_celestial_musician_girl`、`06_thunder_caster_boy`、`07_moon_shadow_assassin_girl`、`08_alchemy_prodigy_boy`、`09_bamboo_archer_girl`、`10_crimson_spear_girl`、`14_short_hair_snow_summoner_girl`、`15_water_dragon_scholar_boy`、`17_ghost_script_calligrapher_boy`、`20_star_formation_master_girl`。
- **每账号角色上限**：已有角色数 **≥ `MaxPlayersPerAccount`** 时返回 2001。这个值在 login.yaml 里没有配置，使用 config.go:381 的**默认值 5**（157-161 行）。
- **并发锁**：`account_lock:create:{account}`，TTL 20s，拿不到返回 2005（117-125 行）。

### 5.3 名字规则（RoleNameRule 表的 id=1 行）

`generated/tables/rolenamerule.json` 的内容：

```
{ "id":1, "min_chars":2, "max_chars":12, "generated_prefix":"道友",
  "generated_suffix_len":6, "max_generate_attempts":5, "desc":"角色名规则" }
```

Schema 是 `RoleNameRuleTable{id=1,min_chars=2,max_chars=3,generated_prefix=4,generated_suffix_len=5,max_generate_attempts=6,desc=7}`（`generated/code/proto/rolenamerule_table.proto`）。每次建角都实时读表（`go/login/internal/logic/pkg/playernamereg/playernamereg.go:138`）。

读表后的校验（playernamereg.go:144-176；`go/shared/playername/playername.go:80-91, 223-247`）：

- 1 ≤ min ≤ max ≤ 32
- 前缀非空，每个字符都在允许集合内，且不命中敏感词
- 1 ≤ suffix_len ≤ 16
- 前缀字符数 + suffix_len 落在 [min, max] 内
- max_generate_attempts 在 [1, 10] 内

任一项不满足就返回 **2020**（193-198 行）。

**玩家提交名字时的归一化与判定**（`playername.Normalize`，playername.go:144-167）：

1. 非法 UTF-8 → Invalid
2. NFKC 归一化
3. TrimSpace；结果为空 → Empty，交给服务端生成
4. 码点数不在 [2, 12] → Invalid
5. 每个码点必须是 `0-9 A-Z a-z`、U+3007 `〇`、U+3400–4DBF 或 U+4E00–9FFF，否则 Invalid（playername.go:189-196）
6. 展示名 = 第 3 步结果；唯一键 = 展示名转小写
7. 敏感词：子串包含 `管理员`、`客服`、`官方`、`系统`、`运营` 之一，或以 `gm` 开头 → Sensitive（playername.go:315-339）

存储和下发的都是归一化后的展示名，所以**返回的 name 可能和输入不同**，例如全角字符会被折叠。

**名字为空时的生成算法**（playername.go:252-298；createplayerlogic.go:416-446）：

- 生成的名字 = `"道友"` + 6 位随机字符。
- 字母表 `"abcdefghijklmnopqrstuvwxyz0123456789"`，随机源是 crypto/rand。
- 每位取 1 个随机字节 b：b ≥ 252 丢弃重取，否则取字母表第 `b % 36` 个字符。
- 撞名（Taken）就重新生成，最多 5 次。
- 整个登记步骤总预算 3s，单次登记 1s，剩余预算不足 1s 时不再尝试（createplayerlogic.go:45；playernamereg.go:40）。
- 全部撞名、预算耗尽或注册表不可用 → 2020。

名字唯一性以 data_service 的 `player_name` 表为准，全服唯一。登记在发号之后、登记 home zone 之前（createplayerlogic.go:231-255）。

**丢响应重试**（240-252、339-349 行）：玩家提交的名字被占，而占用者正是本账号下 class、gender、appearance 都相同的角色时，视为上一次建角其实已成功、只是应答丢了。此时**不报错**，直接返回当前完整列表。本次新发的 id 作废。

### 5.4 成功应答

- `players` 是账号下**全部**角色，**新角色在末尾**（311 行、319-323 行）。
- 新角色字段为：`{player_id: 新号, class_id, gender, zone_id: Node.ZoneId, name, appearance_id: 请求原值}`（277-284 行）。
- 注意：这里的列表**不做** zone、名字、外观的刷新或补齐，这一点与 Login 不同。
- 机器人取 `players[len-1]` 打日志（`robot/login.go:106-116`），进游戏时用 `players[0]`（`robot/login.go:79`）。
- 失败时 `players` 为空（96-98 行初始化为空切片）。

### 5.5 CreatePlayer 错误码

| Tip | 何时返回 |
|---|---|
| 2018 kLoginSessionIdNotFound | 没有 SessionDetails，或 session_id 为 0 |
| 2028 kLoginSessionNotFound | `login_session:{sid}` 不存在 |
| 2005 kLoginInProgress | 建角锁已被占用；或围栏写返回 −1 且回读确认未写入（626-635、647-649 行） |
| 2000 kLoginAccountNotFound | 账号目录键不存在（136-139 行） |
| 2021 kLoginRedisError | 读账号目录遇到其它 Redis 错误。但代码返回的是 `resp, err`（141-143 行），gRPC 会丢掉 resp，**客户端实际看到 1003** |
| 2022 kLoginDataParseFailed | 账号目录反序列化失败 |
| 2001 kLoginAccountPlayerFull | 角色数已达 5 |
| 2015 kLoginUnknownError | class_id 不存在、gender > 2、appearance_id 不在白名单 |
| 2034 kRoleNameSensitive | 名字命中敏感词 |
| **2032 kRoleNameInvalid** | 名字不合法（UTF-8、长度、字符集），或 data_service 复检不通过。**`parameters = ["2","12"]`**，即 min 和 max 的十进制字符串（332-334 行） |
| 2033 kRoleNameTaken | 名字被别人占用，且不符合丢响应重试的条件 |
| 2020 kLoginDataSerializeFailed | 读表失败、发号失败、名字登记故障、生成名用尽或超预算、home zone 登记失败、序列化失败 |
| 2023 kLoginRedisSetFailed | 围栏写报错或结果未知，回读后确认未写入或无法判定 |

---

## 6. EnterGame（26）（`entergamelogic.go:66-418`）

### 6.1 同步应答

同步阶段依次做：

1. 检查 session（72-76 行）。
2. 检查重定向票据持有者：`SessionDetails.ticket_player_id` 非 0 且不等于请求的 player_id → 2011（82-87 行）。
3. 按 `login_session` 查出账号（90-95 行）。
4. 加玩家锁 `player_locker:{pid}`，TTL 120s，只试一次，不重试（97-114 行）。
5. 校验角色归属：先查账号目录，找不到再查反向索引 `player_to_account:{pid}` 并自愈（155-219 行）。
6. 把预加载任务提交到池（381-400 行），然后**立即返回**。

成功时返回 `error_message` 缺省、`player_id = 请求值`，外加合服相关的两个字段（412-417 行）。
**同步成功只表示请求已受理，不表示已经进场**（238-241 行注释）。

### 6.2 异步链和客户端看到的消息顺序

1. 预加载玩家数据：Kafka → DB，单个任务 TTL 8s（380 行）。
2. 查询 player_locator 会话，判定这次是 FirstLogin、ShortReconnect 还是 ReplaceLogin（`go/login/internal/logic/pkg/sessionmanager/session_manager.go:170-185`）。
3. 如果是 ReplaceLogin，并且旧会话的 session_id 不同、gate_id 非空，就**踢掉旧连接**（`entergamelogic.go:794-797, 832-841`，见 §8）。
4. 写入 Online 会话，通过 Kafka 发 `BindSessionEvent` 给 gate。gate 只记录 player_id，**不向客户端发任何东西**（`cpp/nodes/gate/handler/event/gate_event_handler.cpp:199-234`）。
5. 调用 SceneManager.EnterScene，gate_zone 设为本 zone，超时 5000ms（login.yaml:143）。落点规则（646-683 行）：
   - FirstLogin：本 zone。
   - 重连或顶号：ZoneId=0，由服务端按玩家 location 决定。
6. Scene 推送 **NotifyEnterScene（79）** 以及场景相关消息，内容见 scene 契约。机器人收到 79 后才认为场景就绪（`robot/logic/handler/scene_scene_client_player_notify_enter_scene.go:15`）。
7. 链路成功收尾时删除 `login_session:{sid}` 并从设备集合移除（352 行）。

**顺序说明**：NotifyEnterScene 可能**先于** EnterGameResponse 到达客户端。机器人会把非目标消息暂存，等 RecvLoop 启动后再补投（`robot/login.go:151-155`；`robot/pkg/client.go:172-203, 271-273`）。

### 6.3 异步失败怎么通知客户端

- 预加载失败或 apply 阶段失败（包括 EnterScene 被拒绝）时，经 gate 推 **msg 23 `TipInfoMessage{id: 3023 kEnterSceneFailed}`**（453-471 行，`scene_error` 枚举：`generated/code/proto/tip/scene_error_tip.proto:58`）。
- 不踢线，保留登录会话，客户端可以在同一连接上重试 EnterGame。
- 已知会出现重复通知：EnterScene 超时但实际已放行时，客户端会先后收到 79 和 3023。客户端应当“已进场就忽略 3023”（444-445 行）。
- gate 侧还有一条收口：场景转发补发超过上限时，gate 依次推 **msg 23 {3023}**、**msg 34 `GameKickPlayerRequest{reason:{id:3023}}`**，然后 shutdown，1s 后强制关闭连接（`cpp/nodes/gate/handler/event/scene_entry_dispatch.cpp:187-199`）。
- 机器人的 msg 23 和 msg 34 处理函数都是空的，它只靠 60s 等待超时来收口（`robot/main.go:429-434`）。

### 6.4 EnterGame 错误码（同步阶段）

| Tip | 何时返回 |
|---|---|
| 2018 kLoginSessionIdNotFound | 没有 session |
| 2011 kLoginEnterGameGuid | 票据持有者不符；或角色不属于本账号（账号目录里没有，反向索引缺失或指向其它账号） |
| 2028 kLoginSessionNotFound | `login_session` 不存在（常见原因：已经进过游戏一次而没有重新 Login） |
| 2021 kLoginRedisError | 玩家锁 SETNX 时 Redis 出错 |
| 2005 kLoginInProgress | 玩家锁被占用（同一角色的 EnterGame 异步链还没结束），或预加载池已满 |
| 2000 kLoginAccountNotFound | 读账号目录失败，包括键不存在（141-146 行） |
| 2022 kLoginDataParseFailed | 账号目录反序列化失败 |

---

## 7. LeaveGame（17）与 Disconnect（58）

- **LeaveGame**（`leavegamelogic.go:25-39`）：
  - 请求和应答都是空消息。
  - 删除 `login_session`，然后对 player_locator 执行 MarkOffline，只针对**同一个 session_id** 的会话；session 已被替换时什么也不做（`session_manager.go:147-165`）。
  - 之后再登录会被判定为 FirstLogin。
  - 不推送任何东西，应答为 `LoginEmptyResponse`（直连模式下以 message_id=58 下发，见 §2.2）。
- **Disconnect，客户端主动发送**（`disconnectlogic.go:25-38`）：
  - 请求体里的 `session_id` 由客户端填写；机器人发的是空消息，即 0。
  - login 删除 `login_session`，然后调用 `SetDisconnecting(player_id=SessionDetails.player_id, session_id=请求里的 session_id=0)`。player_locator 因 session 不匹配而忽略（`go/player_locator/internal/logic/setdisconnectinglogic.go:49-53`）。
  - **结论：客户端主动发的 58 实际只起清理登录会话的作用。**
- **Disconnect，gate 在 TCP 断开时合成**：
  - 只有这条连接发过 Login，或已绑定 player 时，gate 才向 login 发 58，带 `session_id` 和 player_id（`client_message_processor.cpp:440-483`）。
  - 这会触发 30s 断线租约（`session_manager.go:111-121`）。租约内同一账号再进游戏判定为 ShortReconnect。
  - 客户端看不到这条消息的应答。
- 机器人收尾顺序（`robot/main.go:354-355, 469-470`）：**LeaveGame(17) → Disconnect(58)（仅当 PlayerId≠0）→ 关闭 TCP**。三步都不等应答。

---

## 8. 顶号、重连、并发：客户端视角

| 场景 | 客户端看到什么 |
|---|---|
| 同一账号并发 Login | 后到的那个收到 2005 |
| 设备数超限（处于 Login→EnterGame 窗口的连接超过 3 条） | 2024 |
| 同一角色并发 EnterGame，或上一条异步链还没结束 | 2005。压测记录里有 `enter game: server error id:2005`（`robot/login_test_results.csv:12`） |
| **顶号**：旧会话仍在线，新连接对同一角色 EnterGame | 旧连接先收到 **msg 34**，负载 `MessageContent{message_id:34, serialized: GameKickPlayerRequest{reason: TipInfoMessage{id: 2017 kLoginBeKickByAnOtherAccount}}}`，随后 gate 执行 `shutdown()`（`gate_event_handler.cpp:108-140`；`GameKickPlayerRequest{TipInfoMessage reason=1; string operator=2}` 见 `proto/scene/client_player_common.proto:12-15`）。新连接收到正常的 26 应答，接着收到 79 |
| **短线重连**：旧会话处于断线租约内（30s）且账号相同 | 不踢任何连接；新连接正常收到 26，接着收到 79；落点由 location 决定 |
| 租约过期，或曾经 LeaveGame | 按 FirstLogin 处理 |
| 跨区重定向（dev 默认关闭，`RedirectOnEnterEnabled: false`，login.yaml:205） | msg 124 `RedirectToGateNotify`；26 同步应答仍然是成功 |
| 断线租约到期而 TCP 还挂着 | gate 执行 forceClose，没有任何通知消息（`gate_event_handler.cpp:146-198`） |

---

## 9. robot_smoke 的时序与客户端超时

主流程见 `robot/main.go:328-482`：

1. HTTP `POST /api/assign-gate {zone_id:1}`，单次超时 5s（main.go:609）。排队时（code=100）的总预算 5 分钟，外层最多重试 30 次（main.go:522-596）。
2. TCP 连接 gate，`sleep 500ms`，发送 `ClientTokenVerifyRequest{payload, signature}`，同步等待 `ClientTokenVerifyResponse{success=1, error=2}`（`robot/pkg/client.go:207-232`）。verify 失败不计入重试次数，最多重拿 20 次 token（main.go:261, 301-312）。
3. **Login(48)**，**超时 15s**（`robot/login.go:23`；main.go:698）。
4. 如果 `players` 为空，发 **CreatePlayer(14) `{}`**，超时 15s。建角后 players 仍为空则判失败。
5. **EnterGame(26) `{player_id: players[0].player_id}`**，超时 15s。之后 `gc.PlayerId = er.player_id`，所以**应答里的 player_id 必须填**。
6. 启动 RecvLoop，补投暂存的消息，**等待 NotifyEnterScene(79)，最长 60s**（main.go:429）。
7. 发 **ListSkills(77)**，**等待 5s**；超时只打警告，流程继续（main.go:437-444）。
8. 进入 AI 循环，直到收到停止信号或连接断开，然后执行 §7 的收尾。
9. 失败重试：最多 5 次，退避从 3s 开始翻倍，上限 30s（main.go:253-286）。第二次起优先 `Login{account, auth_type:"access_token", auth_token}`，失败再回退到口令登录（main.go:763-775）。

---

## 10. 本链路涉及的 Tip 码速查

`generated/code/proto/tip/login_error_tip.proto`、`common_error_tip.proto`：

| 码 | 名字 | 码 | 名字 |
|---|---|---|---|
| 1003 | kServiceUnavailable | 2011 | kLoginEnterGameGuid |
| 1005 | kInvalidParameter（路由服） | 2015 | kLoginUnknownError |
| 1010 | kMessageSizeExceeded | 2017 | kLoginBeKickByAnOtherAccount |
| 1014 | kRequestMessageParseError | 2018 | kLoginSessionIdNotFound |
| 2000 | kLoginAccountNotFound | 2020 | kLoginDataSerializeFailed |
| 2001 | kLoginAccountPlayerFull | 2021 | kLoginRedisError |
| 2005 | kLoginInProgress | 2022 | kLoginDataParseFailed |
| 2023 | kLoginRedisSetFailed | 2024 | kTooManyDevices |
| 2028 | kLoginSessionNotFound | 2032 | kRoleNameInvalid（params [min,max]） |
| 2033 | kRoleNameTaken | 2034 | kRoleNameSensitive |
| 3023 | kEnterSceneFailed（scene_error） | | |

---

## 11. 附：HTTP `/api/login`

robot_smoke 不走这条路，只有 `use_http_login: true` 时才用。

- 请求 JSON 使用 snake_case：`{zone_id, account, password?, auth_type?, auth_token?, device_id?}`。
- 应答 JSON：`{code, message, retry_after_ms, queue_pos, access_token, refresh_token, access_token_expire, refresh_token_expire}`（`robot/http_login.go:17-35`）。
- code 含义：0 成功；100 排队；101 排队超时；401 认证被拒；429 限流（http_login.go:39-52）。
- Java Gateway 把 login 返回的任何业务错误都映射为 401（`java/gateway_node/src/main/java/com/game/gateway/service/LoginService.java:47-50`）。
- 机器人只使用返回的 access_token，随后在 TCP 上执行 `Login{auth_type:"access_token"}`（`robot/main.go:826-852`）。

---

## Java 必须做到

1. [ ] 消息号固定为 48 / 14 / 26 / 17 / 58（以及 127、23、34、79、77、124）。proto 全名固定为 `loginpb.*`；`TipInfoMessage`、`AccountSimplePlayer`、`MessageContent`、`GameKickPlayerRequest` 不带包名。
2. [ ] 应答 `MessageContent.message_id` 必须等于请求的 message_id（48、14、26）。`id` 回填请求 id（按路由模式的做法，机器人不校验这个值）。
3. [ ] 业务错误**只**写进应答体的 `error_message`（字段 1），并且应答体不能为空。**成功时应答体里不能出现 `error_message` 字段**，id=0 也不行。
4. [ ] 下游故障时可以推 msg 23 `{id:1003}`。不要只回一个带信封错误、body 为空的 `MessageContent`，机器人会把它当成功。
5. [ ] 开发认证：auth_type 为 `""` 或 `"password"` 时，要求账号首尾无空白、以 `robot_` 或 `dev_` 开头，口令等于环境变量 `LOGIN_DEV_PASSWORD_SHARED_SECRET`（smoke 用 `<robot 冒烟配置里的开发口令>`）。失败返回 2000。
6. [ ] 支持 `auth_type:"access_token"`：账号从 token 解析，忽略请求里的 account，**不重新签 token**。口令登录成功要签 43 字符的 access/refresh token，过期时间为 Unix 秒（2h / 30d）。
7. [ ] Login 成功返回账号下全部角色，按建角顺序排列（`players[0]` 是最早建的角色），填 `player_id / class_id / gender / zone_id / name / appearance_id`。新账号返回空列表，不报错。
8. [ ] CreatePlayer 在 `{}` 时使用默认值：class=1（Class 表第一行）、gender=1、appearance=""、名字 = `道友` + 6 位 `[a-z0-9]`（crypto 随机，拒绝采样 b<252、`b%36`），撞名最多重试 5 次，名字全服唯一。成功返回**全部**角色，新角色在**末尾**。每账号上限 5 个，超出返回 2001。
9. [ ] 名字校验：NFKC → trim → 2–12 个码点 → 字符集（数字、字母、〇、CJK 扩展 A、CJK 基本区）→ 敏感词（管理员 / 客服 / 官方 / 系统 / 运营子串，`gm` 前缀）。对应错误码 2032（`parameters=["2","12"]`）、2034、2033。class、gender、appearance 非法返回 2015。
10. [ ] CreatePlayer 和 EnterGame 要求本连接先成功 Login，否则返回 2028。EnterGame 成功收尾后清掉登录会话。
11. [ ] EnterGame 同步应答：成功时 `player_id` = 请求值，**不带** error_message，`post_merge_notice_ts=0`，`force_rename_required=false`。之后异步推送 NotifyEnterScene(79)，可以早于或晚于 26 应答到达，必须在 60s 内送达。异步失败推 msg 23 `{3023}`。
12. [ ] 角色不属于本账号返回 2011；同一角色的进场还在进行中（或同一账号并发 Login、并发建角）返回 2005。
13. [ ] 顶号：旧连接先收到 msg 34 `GameKickPlayerRequest{reason:{id:2017}}`，然后被关闭；新连接正常进场。30s 断线租约内同一账号重进不踢任何连接，回到原位置。
14. [ ] LeaveGame(17) 和 Disconnect(58) 接受空请求体，返回空应答或不回应答都可以，但不能断开连接或报错。机器人发完 17 和 58 就立即关闭 TCP。TCP 断开本身要按“断线 → 30s 租约”处理。
15. [ ] 延迟预算：Login、CreatePlayer、EnterGame 的同步应答各自必须在 15s 内返回；ListSkills(77) 的应答应在 5s 内返回。