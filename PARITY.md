# 双版本对账本（PARITY）

本仓库是 [mmorpg（C++ 节点 + Go 微服务）](https://github.com/luyuan-cpp/xuanming-server-mmo) 的 Java 版。
两个版本并行演进，**任何功能两边都要做**（mmorpg `AGENTS.md` §12）。本文件是唯一对账处：

- 「版本基线」表：Java 版本号 ↔ 已对齐到的 mmorpg commit。
- 「功能对齐」表：逐功能记录两边状态。任一边完成一个功能都在这里登记一行（或更新已有行）。
- 只追加、只更新状态，不删旧行。

## 版本基线

| Java 版本 | 对齐的 mmorpg commit | 日期 | 说明 |
|---|---|---|---|
| 0.1.0-SNAPSHOT | `766cb037c` | 2026-09-29 | 移植起点：仓库骨架 + 登录进场景竖切（端到端已验证） |
| 0.1.0-SNAPSHOT | `9c9c012b7` | 2026-09-29 | 契约同步到 mmorpg HEAD：proto 111 → 113 个文件（新增 guild_internal、match_internal），消息号追加 239–243（帮会活动 / 试炼，Java 版 gate 暂回「服务不可用」）；配表数据无变化；竖切端到端复验通过 |

## 功能对齐

状态取值：`已对齐` / `Java 待做` / `mmorpg 待做` / `Java 进行中` / `不适用（写原因）`。

| 功能 | mmorpg 位置 | mmorpg commit | Java 模块 | Java 版本 | 状态 | 备注 |
|---|---|---|---|---|---|---|
| 客户端协议（帧格式 + 握手 + 客户端可见 proto） | `cpp/nodes/gate`、`proto/` | `766cb037c` | xm-net、xm-proto、xm-common | 0.1.0-SNAPSHOT | 已对齐 | 帧格式有 golden bytes 单测；robot 实测兼容 |
| 分配 gate / 区服列表（HTTP） | `java/gateway_node`、`go/login` AssignGate | `766cb037c` | xm-gateway | 0.1.0-SNAPSHOT | 已对齐（部分） | 排队 / 限流 / 管理接口 / `/api/login` 待做；`zone_id=0` 回 404（mmorpg 为自动选区） |
| 登录 / 建角 / 进游戏 / 离开 / 断线 | `go/login` | `766cb037c` | xm-login | 0.1.0-SNAPSHOT | 已对齐（部分） | 开发口令认证；access token、设备数上限、短线重连（30s 断线租约回原位）待做。顶号见下方「顶号」行；建角上限的跨实例互斥已由数据库保证（见「每账号角色上限」行），其余闸门（同账号并发 Login）仍只在进程内 |
| 场景分配 | `go/scene_manager` EnterScene | `766cb037c` | xm-scene-manager | 0.1.0-SNAPSHOT | 已对齐（部分） | 不预占名额、不跨 zone |
| gate 接入与路由 | `cpp/nodes/gate` | `766cb037c` | xm-gate | 0.1.0-SNAPSHOT | 已对齐（部分） | GM 闸、战斗直连、重定向、Kafka 命令待做；按消息号限频见下方「gate 按消息号限频」行 |
| 进场景与初始同步（79/21/47）、ListSkills、ReleaseSkill、场景内换图、离场广播 | `cpp/nodes/scene`、`cpp/libs/services/scene` | `766cb037c` | xm-scene | 0.1.0-SNAPSHOT | 已对齐（部分） | 移动同步、格子 AOI、属性同步、技能结算、跨节点换图、副本待做 |
| **登录 → 进场景竖切（验收）** | robot `etc/robot_smoke.yaml` 形状 | `766cb037c` | 全部 | 0.1.0-SNAPSHOT | **已对齐** | 2026-09-29 robot 3 账号实测：login_ok=3 enter_ok=3，0 失败；stress AI 75s 稳定（skill=57，scene_switch=15）；老角色重登走围栏自增 |
| 角色名唯一大小写不敏感（唯一键 = NFKC → 去首尾空白 → 小写） | `go/shared/playername`（`name_norm` 唯一索引） | `766cb037c` | xm-player-store | 0.1.0-SNAPSHOT | 已对齐 | Java 列名 `name_key`、索引 `uk_player_name_key`；存量库迁移见 `docs/design/db-migrations.md` M1。建角撞主键抛异常（回 1003），不报重名 |
| LeaveGame 后回选角（会话保留账号） | `go/login` leavegamelogic | `766cb037c` | xm-login、xm-gate | 0.1.0-SNAPSHOT | 不适用（行为有意不同） | Java：LeaveGame 下发 `UnbindPlayer`，gate 发 `PlayerLeave{voluntary=true}` 并解绑玩家、保留账号，之后可建角 / 进游戏。mmorpg：LeaveGame 删登录会话，之后回 2028 须重新 Login。robot 发完 17 即断开，看不到差异；客户端做回选角前需两边定一个口径 |
| 节点号租约有效期判定（续期滞后即停发号） | 无对应（mmorpg 用 etcd 租约 / 各自 snowflake） | — | xm-discovery、xm-login | 0.1.0-SNAPSHOT | 不适用（Java 内部） | `NodeIdLease.isValid()`：未丢失且距上次成功续期 < 2/3 TTL；`PlayerIdGenerator` 发号前检查 |
| gate ↔ scene 节点链路握手鉴权 | 无对应（mmorpg 内部链路不同） | — | xm-common、xm-gate、xm-scene | 0.1.0-SNAPSHOT | 不适用（Java 内部） | 共享密钥 `XM_NODE_LINK_SECRET`，HMAC-SHA256 + 60s 时间窗；见 architecture.md §4.2。2026-09-29 起 MAC 输入加入 gate 节点号租约的防护代次 `lease_epoch`，scene 拒绝代次更低的链路 |
| 已在游戏里的会话再次 EnterGame 回 2028 | `go/login` entergamelogic（成功即删 `login_session`） | `766cb037c` | xm-login、xm-gate | 0.1.0-SNAPSHOT | 已对齐 | 会话上已绑定玩家（不论同不同角色）一律 2028。小差异：上一次进场还在途时再发 26，Java 回 2028（会话已绑定玩家），基线回 2005（玩家锁被占）——两者都是拒绝，客户端都只能等 |
| 进场异步失败后回到「已登录、未进游戏」 | `go/login` entergamelogic §6.3（失败保留登录会话） | `766cb037c` | xm-gate | 0.1.0-SNAPSHOT | 已对齐 | scene 回 3023 / 建链失败 / 链路层已关：gate 推 23 {3023} 并清掉玩家与场景绑定，同一连接上可重试 EnterGame（任一角色）或 CreatePlayer |
| 顶号（同一角色从新连接进游戏，旧连接被踢） | `go/login` ReplaceLogin + gate `gate_event_handler.cpp` | `766cb037c` | xm-login、xm-scene、xm-gate | 0.1.0-SNAPSHOT | 已对齐（部分） | Java 经归属协议实现（architecture.md §7）：login 夺权撞上仍被持有 → Redis pub/sub 请持有的 scene 让出 → 旧实例写回释放、旧连接收 23 {2017} 后断开 → 新连接进场。与基线差异：不发 34 `GameKickPlayerRequest`（robot 契约第 13 条：本里程碑不发 34）；持有者 3s 内没让出（scene 宕机、与库失联）回 2005，等归属租约（30s）过期后才能进 |
| 玩家数据归属：释放后才能再夺权（owner_epoch + 释放标记 + 租约） | 无对应（mmorpg 用 player_locator / Kafka 存盘链） | — | xm-player-store、xm-login、xm-scene、xm-gate | 0.1.0-SNAPSHOT | 不适用（Java 内部） | 修复：新归属先于旧实例写回落库时，旧写回被围栏拒掉、进度静默丢失。存量库迁移见 db-migrations.md M2 |
| 每账号角色上限（5，超出 2001） | `go/login` createplayerlogic | `766cb037c` | xm-player-store、xm-login | 0.1.0-SNAPSHOT | 已对齐 | Java 在数据库事务里锁账号行后计数（多 login 实例并发也突破不了）；基线用 Redis `account_lock:create`（TTL 失守时理论上可突破） |
| gate 按消息号限频（MessageLimiter 表，缺省每秒 3 条，超频 1008 + 计非法包） | `cpp/nodes/gate` CheckMessageLimit、`MessageLimiter` | `766cb037c` | xm-gate | 0.1.0-SNAPSHOT | 已对齐 | 同一份 MessageLimiter 配表；Java 用单调时钟纳秒滑动窗口（基线按整秒），语义一致、边界略宽松 |
| Dubbo 调用方鉴权 | 无对应（mmorpg 内部是 gRPC） | — | xm-common、xm-api、xm-login、xm-scene-manager、xm-gate | 0.1.0-SNAPSHOT | 不适用（Java 内部） | 共享密钥 `XM_DUBBO_SECRET`，HMAC-SHA256(接口\|方法\|ts) + 60s 时间窗，过滤器经 Dubbo SPI 自动激活；见 architecture.md §4.1 |
| 低基数运行指标 + Prometheus 抓取端点（gate / login / scene-manager / gateway） | Go 服务 go-zero `Prometheus` 段（login `:9101`、scene_manager `:9150`、db `:9160`，开发环境只绑 127.0.0.1）；C++ gate 未接 Prometheus（`scene_entry_dispatch.cpp` 注释：只走日志计数） | `9c9c012b7` | xm-gate、xm-login、xm-scene-manager、xm-gateway | 0.1.0-SNAPSHOT | 不适用（服务端内部，两版各自命名） | Java：Micrometer + Actuator，`/actuator/prometheus`，端口 gateway 18081、login 18101、scene-manager 18102、gate 18103，默认只绑本机；指标名与标签见 architecture.md §11，不与 Go 版指标名对齐（stress 脚本不能直接复用）。xm-scene 指标待做；mmorpg C++ gate 的 Prometheus 待做 |
