# 挂机月卡、断线保留离线托管、服务器驱动组队跟随、小游戏同端口 WebSocket 与启动时钟核对设计

> 对齐权威 C++ / Go 仓 `xuanming-server-mmo-cpp` HEAD `5c0a518a6`：
> `java/server_startup/ClockCheck.java`、`docs/design/minigame-websocket-gate.md`（W1–W9）、
> `docs/design/afk-monthly-card.md`、`docs/design/player-connection-lost-hold.md`、
> `docs/design/team-follow-server-trail.md`，以及 `PARITY.md` 2026-11-20/22 安全与收口待做项。

## 1. 需求分析

1. **启动时钟核对（`ClockCheck`）**：
   - 移植主仓 `java/server_startup/ClockCheck.java` 到 `game-common`（`com.game.common.startup.ClockCheck`），纯 JDK 21 零第三方依赖。
   - 在 Kubernetes 环境内探测 API Server `GET /version` 的 `Date` 响应头，偏差超过 30 秒且 `MMORPG_CLOCK_CHECK_MODE != "warn"` 时拒启；非 K8s 或无法访问 API Server 时跳过不阻塞。
2. **小游戏同端口 WebSocket 入口与地址下发（W1–W9）**：
   - `game-gate` 与 `game-battle` 客户端端口同时接受原生 TCP 帧与明文 RFC 6455 WebSocket 升级握手（TLS 由前端边缘终止）：首字节为 `'G'` (`0x47`) 进入 HTTP 升级解析，其余字节直接走原 `ClientFrameDecoder`。
   - 一条 WebSocket 二进制消息承载现有客户端帧字节流，出站编码为 FIN=1 无掩码二进制帧；Ping 回 Pong 且不刷新业务活跃心跳；Close 先交付前面数据帧、回显 Close 后 100ms 短延迟强关；按 RFC 6455 严格校验 8 类违规帧并回 1002/1003/1009 关闭码。
   - `GateTokens.verify` 补齐普通登录票据 `zone_id != 0 && zone_id != selfZoneId` 拒签校验（2026-11-20 审计项 #1）。
   - `ClientWsUrls` 校验 `CLIENT_WS_BASE_URL`（仅 `ws://` / `wss://`、无查询串/片段、宿主机须 IPv4 字面量），经 `GateNodeInfo.client_ws_url` / `BattleNodeInfo.client_ws_url` / `ZoneRedirect.gate_ws_url` 下发到 `AssignGateResponse.gate_ws_url`、`RedirectToGateNotify.target_ws_url`、`BattleAssignedS2C.ws_url` 与 `NetworkProbeTarget.ws_url`。
3. **挂机月卡（`SceneAfkPassClientPlayer` 430–432）**：
   - `GetAfkPass`（430，只读查询）、`BuyAfkPass`（431，客户端幂等键去重、报价一致校验 `32000`、叠加天数上限 `32002`、扣货币 `32001`、受全局冻结与战斗闸保护 `32003`）、`GmSetAfkPass`（432，仅 dev/test 放行、上限 366 天、共用全局可写闸）。
   - 规则表 `AfkPassRule` 接入启动校验（不合法则 `BuyAfkPass` fail-closed 返 `32004`，GM 与托管不受影响）。
   - 权威到期时刻 `afk_pass_expires_at` 与幂等请求号 `afk_pass_last_req_id` 随 `player` 行与 `player_final_save` 同事务按 `owner_epoch` 围栏落盘；Redis 投影仅在确认写成功后标记已同步，失败的 SET/DEL 在后续存盘与 60s 心跳中自愈。
4. **断线保留与离线托管（`ConnectionLostHold`）**：
   - 断线（非主动 `LeaveGame`）进入 25 秒保留窗口（严格小于 login 30 秒租约）：摘除活会话、停止手动移动、保留场景实体与归属；重连时无损接回并补推跟随状态。
   - 持有有效挂机月卡（剩余 > 30s 退场提前量）自动进入离线托管，挂起组队跟随意图（允许兜底 15s 错峰重查，跳过已确认队长与已确认无队墓碑），AOI 与移动广播静默跳过已摘会话的离线托管实体；到期经 15s 未确认宽限后有序写回离场。
5. **服务器驱动组队跟随（`TeamFollowC2S` / `NotifyTeamFollowState`）**：
   - 移植纯逻辑层 `TeamFollowTrail`（累计里程折线 + 捷径回收防折返）与 `TeamFollowFormation`（最多 4 随从、`(joinSeq, playerId)` 排序、间距 1.8m、追赶速度 7.5m/s、最大落后 24m、250ms/转弯/变速广播节流）。
   - 接入 `SceneWorld` 逻辑帧（在 `integrate` 后运行）、`ClientRequestHandler` 注册 `TeamFollow`、`SceneWorld.applyMove` 丢弃被驱动队员的客户端移动上报、`SkillService.registerActionStateExitHook(STATE_TEAM_FOLLOW, ...)` 打断退跟、最后一名随从离开时立即回收空队形。

## 2. 设计分析

- **共享零依赖 Netty WebSocket 复用层（`game-net`）**：
  - `game-net` 仅依赖 `netty-handler`/`netty-codec`/`protobuf-java`（无 `netty-codec-http`）。实现紧凑的 `WebSocketMuxHandler`（`ByteToMessageDecoder`，首字节非 `'G'` 立即自移除，对原生 TCP 零运行时开销）+ `WebSocketBinaryCodec`（`ChannelDuplexHandler`，使用 JDK `MessageDigest("SHA-1")` + `Base64` 完成握手，零拷贝 `write(header)` + `write(payload)` 封装二进制帧）。
  - `game-gate` 的 `ClientPipeline.install` 与 `game-battle` 的 `BattleEdgeServer.initChannel` 共用同一份实现，通过 `WebSocketObserver` 回调分别上报 `gate_websocket_*` 与 `battle_websocket_*` Prometheus 指标。
- **`afk_pass_expires_at` 零冲突强一致持久化**：
  - 将 `afk_pass_expires_at` 与 `afk_pass_last_req_id` 作为可空 `Long` 落 `player` 表与 `player_final_save` 表（`COALESCE(#{afkPassExpiresAt}, afk_pass_expires_at)`），既与并行会话的 `player_state.proto` 持久台账完全解耦，又保证支持 GM 显式清零（传 `0L`）与全链路 `owner_epoch` 围栏原子性。
- **连接保留与离线托管的可开关装配**：
  - `SceneWorld` 默认关闭断线保留以兼容既有「断线立即移除」单测，`SceneNode` 生产装配按 `game.scene.connection-lost-hold-enabled`（默认 `true`）启用，并在 1s 周期任务中驱动 `sweepConnectionLostHolds` 与 60s 托管投影刷新。
- **服务器驱动跟随的 Java 化组织**：
  - `TeamFollowTrail` / `TeamFollowFormation` 保持纯数据与数学无状态耦合（便于单测）；`TeamFollowCoordinator` 挂载于 `SceneWorld`，负责队形生命周期、空队形立即回收、移动节流广播与 `NotifyTeamFollowState` 下发。

## 3. 思想分析

- 保留 C++「按首字节单端口分流、帧编解码零分叉」「纯规则内核（`AfkPassRules`/`ConnectionLostHold`/`TeamFollowFormation`）与世界运行态解耦」的核心架构思想。
- 用 Java 21 + Netty 标准范式替代 C++ 线程局部连接指针表（`weak_ptr`）：Netty `ChannelPipeline` 天然以 Channel 实例为状态宿主、EventLoop 单线程独占且 Channel 关闭即随 GC 自动回收，根除悬垂指针与地址复用隐患。
- 贯彻 Ponytail 原则：不引入额外 HTTP/WS 第三方库、不增设无用配置开关、复用 `ClientRequestHandler` 既有统一冻结/战斗门禁与 `SkillService` 状态退出钩子。

## 4. 漏洞分析

1. **普通登录票据跨区重放**：原 `GateTokens.verify` 仅校验 `target_zone_id`，若外部签发 `zone_id=1, target_zone_id=0` 会被 zone 2 同号 gate 放行。本次强制补充 `zone_id != 0 && zone_id != selfZoneId -> WRONG_ZONE`。
2. **WebSocket 慢速发头与半关未认证连接占用槽位**：握手头硬上限 8192 字节，超限/非法/版本不符即写 400/426 后标记 `closing=true` 丢弃后续入站字节，并在写完或 100ms 后强关；整个握手过程共享现有 10s 未认证连接超时。
3. **WebSocket 违规帧与 Ping 伪造活跃**：非掩码客户端帧、RSV 非 0、控制帧分片或 >125B、文本帧、超大帧均发标准关闭码（1002/1003/1009）并短延迟断开；Ping 只回 Pong 不刷新 `lastInboundNanos`，防止后台空心保活。
4. **`BuyAfkPass` 重试重复扣费与价格热更错价**：强校验 `client_request_id != 0` 与内存+持久幂等记录；报价（币种+价格）与 `AfkPassRule` 不一致直接返 `32000` 不扣费；受全局冻结闸与战斗闸拦截。
5. **离线托管大规模场景广播风暴与空队形泄漏**：`SceneWorld` 所有面向玩家的下行封装统一跳过 `session == null`（断线保留/托管中）实体；最后一名随从离开时立即移除队长空队形，防止 0 随从队长持续 20Hz 更新轨迹。
6. **并发单测与零冲突**：`player.afk_pass_expires_at` 不触碰 `player_state.proto`，不修改并行会话未提交文件。

## 5. 步骤分析

1. **`game-common`**：新增 `ClockCheck`、`ClientWsUrls`；修复 `GateTokens` `zone_id` 校验；补齐单测。
2. **`game-net`**：新增 `WebSocketMuxHandler`、`WebSocketBinaryCodec`、`WebSocketObserver` 与全套 RFC 6455 单测（`WebSocketMuxTest`）。
3. **`game-api`**：在 `GateNodeInfo`、`BattleNodeInfo`、`ZoneRedirect` 追加 `ws_url` 字段并安装。
4. **`game-gate` / `game-battle` / `game-gateway` / `game-scene-manager` / `game-match`**：接入同端口 WebSocket 处理器、指标计数与 `ws_url` 全链路透传；补齐测试。
5. **`game-player-store`**：`player` 与 `player_final_save` 表新增 `afk_pass_expires_at`/`afk_pass_last_req_id` 列及 `PlayerRow`/`PlayerMapper`/`PlayerStore` 支持。
6. **`game-scene`**：实现 `AfkPassRules`/`AfkPassTables`/`AfkPassService`/`AfkPassFeature`（430–432）、`ConnectionLostHold` 断线保留与离线托管、`TeamFollowTrail`/`TeamFollowFormation`/`TeamFollowCoordinator` 服务器驱动组队跟随，接入 `SceneWorld`/`ClientRequestHandler`/`SceneNode` 并补齐单测。
7. **验证与对账**：运行所有受影响模块 Maven `install` 与 `game-robot` 门禁测试，更新 `PARITY.md`，精准暂存提交并推送 `origin/main`。

## 6. 执行步骤

- [x] 核对权威 C++ 源码（`ClockCheck`、`minigame-websocket-gate.md`、`afk_pass*`、`connection_lost_hold.h`、`team_follow_*`）与 Java 现状。
- [ ] 阶段 1：`game-common` + `game-net` + `game-api` 实现与单测通过。
- [ ] 阶段 2：`game-gate` + `game-battle` + `game-gateway` + `game-scene-manager` + `game-match` WebSocket 与 `ws_url` 接线与单测通过。
- [ ] 阶段 3：`game-player-store` + `game-scene` 挂机月卡、断线保留离线托管与服务器驱动组队跟随实现与单测通过。
- [ ] 阶段 4：全量回归验证、更新 `PARITY.md`、选择性暂存提交并推送。
