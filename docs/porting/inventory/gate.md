# 功能清单：gate 节点（mmorpg 26ceb70ca ↔ xuanming-server-mmo）

读过的源：cpp/nodes/gate/**（main.cpp、gate_security.h、gate_gm_client_messages.h、gate_router_mode.h、gate_version.h、SECURITY.md、
handler/rpc/{client_message_processor,gate_service_handler}.cpp、handler/event/{gate_event_handler,gate_kafka_command_router,scene_entry_dispatch}.cpp、
scene_route_helper.h、rpc_replies/*）、cpp/libs/services/gate/session/**、cpp/libs/engine/session/**、cpp/libs/engine/core/{network/codec,
network/broadcast_target_codec.h,network_utils,message_limiter,security}、node_kafka_command_filter.h / node_command_topic.h、client_endpoint.h；
Go 侧 scene_manager/gate_redirect.go、shared/kafkautil/gate_push.go、client_rpc_router；Unity 客户端 GameClient.cs / ZoneTravelClient.cs。
Java 侧：xm-gate（GateNode、ClientDispatcher、ClientSession、ClientPipeline、SessionRegistry、SessionIdAllocator、MessageRoutes、
SceneEventRouter、link/*、metrics/GateMetrics）、xm-net client/*、xm-common GateTokens、xm-api node_link.proto / client_call.proto。

## 概要
C++ gate 是唯一的公网入口：muduo 单 EventLoop 上跑 ProtobufCodec 帧、HMAC 令牌握手、上行校验链（白名单 → 1KB → MessageLimiter 限频 →
GM 闸 → 战斗拒绝），再按消息号把 TCP 类消息（scene）经 muduo RPC 转给会话绑定的 scene 节点，把 gRPC 类消息直连 login / scene_manager /
match 或（GATE_CLIENT_RPC_ROUTER=1）原包交给 client_rpc_router。下行有两条：scene 经 muduo RPC 的 SendMessageToPlayer / BroadcastToPlayers
（带 target_player_id 身份栅栏），Go 服务与 login / scene_manager / player_locator 经 Kafka `gate-cmd_g1` 分区命令（RoutePlayer、BindSession、
KickPlayer、RedirectToGate、LeaseExpired、PushToPlayer、BroadcastToPlayers）。进场转发有 CPP-2 欠账补发（20s 预算，超限 3023 + 34 踢线）。
Java xm-gate 已做帧/握手/会话/校验链/限频/login 与 scene 路由/scene 下行/顶号踢出/租约 fail-closed/指标，而且比基线多了握手超时与单会话排队上限；
缺的主要是：Kafka（或等价）命令通道与 Go 服务推送、跨 zone 重定向与票据绑定、跨节点换图的 gate 重绑、GM 指令闸与 GM 签名停机、
可配置连接上限、全服广播。几处客户端可见差异（不发 34、链路断开直接断连、1014 的形态、在途 login 调用阻塞 scene 消息）在各条里标出。

## 功能

### client-frame-codec — 客户端帧编解码
- mmorpg: cpp/libs/engine/core/network/codec/codec.{h,cpp}；cpp/nodes/gate/main.cpp（GateRuntimeContext、未知类型回调）；gate_codec.h
- client messages: 上行 ClientTokenVerifyRequest、ClientRequest；下行 ClientTokenVerifyResponse、MessageContent（帧层，无消息号）
- tables: none
- depends on: none
- behavior: `[int32 len][int32 nameLen][typeName\0][body][int32 adler32]` 大端；len 合法 [10, 64KB]（kDefaultMaxMessageLen 由 64MB 收紧到 64KB）；
  长度 / 校验和 / nameLen / 未知类型 / 解析失败一律清缓冲并 forceClose，不回包；dispatcher 不认识的已注册类型也 forceClose；
  handler 一旦拒绝，同一 read 里 pipeline 的剩余帧不再分发（onMessage 每帧后检查 connected）；错误日志 1/1024 采样。
- internal: 下发统一走 codec.send（曾有裸 conn->send 导致客户端分帧错位的 bug，注释强调必须带帧）。
- java: done — xm-net ClientFrameDecoder / ClientFrameEncoder / ClientFrames（DEFAULT_MAX_LEN=64KB，MIN_LEN=10），ClientPipeline.ACCEPTED 只收两种上行，
  非法帧 metrics.invalidFrame + 采样日志 + close；ClientFramesTest 有 golden bytes。差异：C++ 攒够 14 字节才读 len，Java 4 字节即读（不可见）。
- size: S
- robot: 所有 robot 场景（Go robot、xm-robot SmokeScenario）
- hazards: typeName 末字节 Go robot 写空格、C++ 写 `\0`，解码只能剥最后 1 字节；回错误应答必须带帧。

### token-handshake — 连接令牌握手
- mmorpg: client_message_processor.cpp DispatchTokenVerify；gate_security.h / core/security/token_security.h（HmacSha256Hex、ConstantTimeEquals）
- client messages: ClientTokenVerifyRequest（上行首包）、ClientTokenVerifyResponse（下行）
- tables: none
- depends on: client-frame-codec, startup-security-gates
- behavior: 已验证再发 verify → success=true；签名 = hex(HMAC-SHA256(secret, payload)) 64 字节小写 ASCII，常数时间比较；校验顺序：签名 →
  payload 解析 → gate_node_id == 本节点 → target_zone_id 为 0 或等于本 zone → expire_timestamp > now；失败回 success=false + 固定英文 error
  （invalid token signature / malformed token payload / token not for this gate / token not for this zone / token expired），随即 shutdown +
  forceCloseWithDelay(0.1s)；成功后重置非法包计数、存 hmac_session_key、存票据 player_id / target_zone_id；空密钥 + dev/test 时建连即 verified。
- internal: 密钥来自 base_deploy_config gate_token_secret；令牌由 login AssignGate / scene_manager 重定向签发。
- java: done — ClientDispatcher.onTokenVerify + xm-common GateTokens.verify（同顺序、同文案，sendThenClose）；ClientDispatcherTest「握手通过回成功_重复握手再回成功」
  「握手失败先回原因再断开」「过期令牌与签名错误都被拒」。差异：Java 不存票据字段（见 redirect-ticket-binding）、无 dev 空密钥放行（更严）、
  未握手的 ClientRequest 断开且计指标 MISSING。
- size: S
- robot: 全部场景的 connectAndVerify；xm-robot GameConnection
- hazards: 空密钥 dev 旁路会让 verify 直接 success；C++ 的 forceCloseWithDelay 是 100ms，客户端要在关闭前读到失败应答。

### startup-security-gates — 启动安全门禁与运行模式
- mmorpg: main.cpp ValidateGateTokenSecretOrDie / ValidateGateConnectionLimitOrDie；token_security.h ParseRunMode；SECURITY.md §1/§5
- client messages: none
- tables: none
- depends on: none
- behavior（运维可见）: GATE_RUN_MODE（prod/production/release/live、dev/development/local、test/testing，大小写与空白不敏感，未设 = prod，拼错 = prod + WARN）；
  prod + 空/纯空白 gate_token_secret → LOG_FATAL 拒绝启动，连接层再拒连一次（纵深）；prod + GateMaxConnections=0 或 > 131071 → 拒绝启动；
  dev/test 下 0 = 只关运维阈值，131071 硬上限仍在。
- internal: 同一 RunMode 判据还驱动 GM 客户端指令闸。
- java: partial — GateConfiguration 对 XM_GATE_TOKEN_SECRET / XM_NODE_LINK_SECRET / XM_DUBBO_SECRET 缺失一律启动失败（无 dev 旁路，比基线严）；
  没有运行模式概念，也没有连接上限配置项（见 connection-admission）。
- size: S
- robot: none
- hazards: SECURITY.md 指出 LogLevel 2 实为 INFO、RLIMIT_NOFILE 1024 可能先于 GateMaxConnections 生效。

### connection-admission — 连接准入（上限 / 握手死线 / 写缓冲高水位）
- mmorpg: client_message_processor.cpp HandleConnectionEstablished、OnClientHighWaterMark；SECURITY.md §5；docs/design/gate-connection-admission-control.md
- client messages: none（拒连直接关闭，不回包）
- tables: none
- depends on: session-lifecycle
- behavior: 会话数 ≥ GateMaxConnections（0 时取 131071）→ setContext(无效号) + forceClose，日志 1/1024 采样，不惊动 login；session_id 生成器 node 段为 0
  → 拒连；写缓冲 > 2MB → forceClose。**基线没有握手死线**：未认证连接可无限期占槽（SECURITY.md 承认的 P0 缺口）。
- internal: 依赖单 IO 线程（thread_local 会话表）；拒连路径必须让断线回调早退。
- java: partial — 有握手超时（xm.gate.handshake-timeout 30s，超时断开计 HANDSHAKE_TIMEOUT）、会话号耗尽拒连（SESSION_ID_EXHAUSTED）、
  客户端写水位 1MB/2MB 越过即断（GateNode CLIENT_WATER_MARK + ClientChannelHandler.channelWritabilityChanged）、单会话排队上限 64（Java 独有）；
  缺可配置的并发连接上限（只有 131071 会话号硬上限）。
- size: S
- robot: none（Go robot 压测 stress-* 会触到容量）
- hazards: 被拒连接若不置无效 session_id，断线回调会 bad_any_cast 打 ERROR 并向 login 发 Disconnect，形成放大。

### session-lifecycle — 会话生命周期与断线收尾
- mmorpg: client_message_processor.cpp HandleConnectionEstablished / HandleConnectionDisconnection；services/gate/session/comp/session_info_comp.h；
  network_utils.h（SessionIdGenerator，TransientNodeCompositeIdGenerator）；engine/session/system/session.cpp（x-session-detail-bin）
- client messages: none 直接可见（断线后服务端行为）
- tables: none
- depends on: client-frame-codec
- behavior: session_id = [gate node_id 高 15 位][序号 17 位]，跳过 UINT32_MAX 与在用号；断线时：会话绑定 scene → 发 ProcessClientPlayerMessage{ExitGame,
  带 player_id 反向栅栏}；只有发起过 Login.Login（loginStarted）或已绑定玩家的会话才向 login 发 ClientPlayerLogin.Disconnect（58）（路由模式经路由服）；
  未绑定连接的建立/断开日志 1/1024 采样；erase 会话。
- internal: SessionInfo{conn weak_ptr, playerId, verified, loginStarted, entityIds[nodeType], sceneId, homeZoneId, ownerEpoch, pending...}。
- java: done — SessionRegistry / SessionIdAllocator（同位布局、保留 0xFFFFFFFF、跳过 0 与在用号）、ClientDispatcher.onDisconnected：PlayerLeave{voluntary=false,
  按 scenePlayerId} + loginTouched||playerId≠0 时 sessionClosed；login 调用在途时等完成后再通知并带上迟到身份；ClientDispatcherTest「断线时发PlayerLeave_通知login」
  「断线时login调用在途_等调用完成后才通知login」。
- size: M
- robot: Go robot RapidReconnect / DisconnectDuringLogin / DisconnectDuringEnter / RapidDisconnectReconnect；xm-robot SmokeScenario（断开只关 TCP）
- hazards: session 序号在单实例内 131071 后回绕；C++ 断线 ExitGame 若不带 player_id 会在 gate node_id 复用时踢掉别人（R13）。

### client-request-validation — 上行校验链与非法包计数
- mmorpg: client_message_processor.cpp DispatchClientRpcMessage / CheckMessageSize；message_limiter/illegal_packet_counter.h
- client messages: 任意 ClientRequest；错误应答 MessageContent{message_id=请求号, id=请求 id, error_message{1010}}
- tables: none
- depends on: token-handshake
- behavior: 顺序：会话不存在 → forceClose；未 verified → forceClose（不回包）；message_id 越界或不是客户端协议服务 → 不回包、计非法包；
  ClientRequest 序列化 > 1024B → 信封错误 1010 kMessageSizeExceeded、计非法包；非法包达 GATE_ILLEGAL_PACKET_THRESHOLD（默认 50，0=不踢）→ forceClose；
  verify 成功清零计数。拒绝日志采样（避免日志放大 DoS）。
- internal: 白名单 = 生成的 IsClientMessageId（标 OptionIsClientProtocolService 的服务）。
- java: done — ClientDispatcher.onRequest（未握手断开、route==null 计非法包不回包、>1KB 回 1010、illegalPacketThreshold=50 配置项）；MessageRoutes.of(MessageIdRegistry)
  只收 clientService 方法；测试「未知消息号不回包_累计到阈值断开」「超过1KB的请求回1010信封错误且不转发」「未握手的业务包直接断开不回包」。
- size: S
- robot: Go robot MessageBeforeLogin、RapidLoginSpam
- hazards: 基线曾把白名单放在鉴权前且不计数（未认证日志放大）；超长包曾不计数。Java 判定顺序与基线一致。

### message-rate-limit — 按消息号限频（MessageLimiter）
- mmorpg: core/message_limiter/message_limiter.{h,cpp}；client_message_processor.cpp CheckMessageLimit
- client messages: 任意 ClientRequest；超频应答 MessageContent{message_id, id, error_message{1008 kRateLimitExceeded}}
- tables: MessageLimiter（max_requests、time_window 秒）
- depends on: client-request-validation
- behavior: 每会话每消息号滑动窗口，表里没有的缺省每秒 3 条；超频回信封错误 1008、不转发、计非法包（达阈值断开），被拒请求不占额度；
  检查排在体积之后、GM 闸之前。
- internal: 秒级时钟（TimeSystem::NowSeconds），boost::circular_buffer；error_reporter 记录。
- java: done — MessageRateLimiter（单调时钟纳秒）+ TableMessageLimits（ConfigTables messageLimiter，非法行拒绝启动）；PARITY「gate 按消息号限频」已对齐；
  测试「超频回1008信封错误_不转发_计非法包_窗口滑过后恢复」。
- size: S
- robot: Go chat_smoke 刻意留间隔避开 1008；friend_smoke 处理 1008
- hazards: C++ maxAllowedRequests 是 uint8_t（表值 > 255 被截断）；按整秒判 `now - front > window`，实际窗口最长近 2s；每个被拒包打一行 LOG_ERROR（未采样）。

### gm-client-message-gate — GM 客户端指令闸
- mmorpg: gate_gm_client_messages.h（kGmClientMessageIds）；gate_security.h ClassifyGmClientMessage；client_message_processor.cpp；SECURITY.md §3；
  scene 侧第二道锁 cpp/nodes/scene/handler/rpc/player/player_gm_guard.h（SCENE_RUN_MODE）
- client messages: 37 GmAddCurrency、49 GmDeductCurrency、94 GmBlockCurrency、95 GmUnblockCurrency、175 GmSetPlayerLevel、187 GmGrantPet（C2S）；拒绝时推 23 TipInfoMessage{1006}
- tables: none
- depends on: client-request-validation, message-rate-limit, startup-security-gates（运行模式）
- behavior: GATE_RUN_MODE 非 dev/test（含未设）时：推 23 {1006 kFeatureUnavailable}、计非法包、不转发；dev/test 放行给 scene。排在体积 / 限频之后。
- internal: 清单用生成常量，不写字面量消息号；新增 Gm*/Debug*/Test* 客户端 RPC 必须登记。
- java: missing — xm-gate 无 GM 清单与运行模式，这 6 个号作为 scene 域原样转发；目前安全只因 xm-scene ClientRequestHandler 对未实现方法回
  应答内 error_message{1006}（形态与基线不同：基线是 23 推送 + 计非法包）。scene 一旦实现任何 GM 方法，gate 必须先有闸。
- size: S
- robot: Go robot 的 currency/pet/attribute 冒烟在 dev 下使用 GM 号
- hazards: 消息号由导出器发号会漂移；scene 侧锁防的是绕开 gate 直连 scene 端口。

### login-routing-reply-bridge — login 请求转发与应答桥接
- mmorpg: client_message_processor.cpp HandleGrpcNodeMessage / BuildSessionDetails / ParseMessageFromRequestBody；main.cpp SetIfEmptyHandler / SetIfEmptyFailedHandler；
  engine/session/system/session.cpp
- client messages: 48 Login、14 CreatePlayer、26 EnterGame、17 LeaveGame 等 ClientPlayerLogin 方法（C2S + 应答）；失败推 23 {1003}；坏包推 23 {1014}
- tables: none
- depends on: client-request-validation, session-lifecycle
- behavior: body 先 Clear 再解析（防共享原型残留别人的参数），失败推 23 {1014 kRequestMessageParseError} 不转发；按 zone 随机挑同 zone login；
  SessionDetails{session_id, player_id, gate_node_id, gate_instance_id, ticket_*} 放 gRPC metadata x-session-detail-bin；应答按响应类型反查 message_id
  包成 MessageContent（不回填 id）；gRPC 失败按发出的 metadata 找回会话推 23 {1003}（1/1024 采样日志）；发出 Login.Login 后置 loginStarted。
- internal: 直连模式 gate 持 login/scene_manager/match stub；路由模式见 other-backend-routing。
- java: done — ClientDispatcher.callLogin（Dubbo ClientMessageService.handle(ClientCall)，SessionContext 带账号/玩家/IP）、replyToClient（回显请求 id 与
  message_id，非 Empty 应答 0 字节也回包）、调用失败 23 {1003}、会话指令 BindAccount/EnterScene/CloseSession/UnbindPlayer。**差异**：坏包时
  Java login 回 ClientReply.tip_id=1014 → gate 放进信封 error_message（基线是 23 推送）；同一会话 login 在途时后续请求（含 scene 消息）排队串行（基线不串行）。
- size: M
- robot: Go robot NormalLogin / WrongPassword / DuplicateEnterGame / LoginLogoutCycle；xm-robot SmokeScenario
- hazards: C++ 响应类型 → message_id 反查表在两个方法共享响应类型时取最后一个；C++ 应答不带 id，客户端靠 message_id FIFO 匹配。

### scene-request-forward — 场景类消息转发与回包
- mmorpg: client_message_processor.cpp HandleTcpNodeMessage / ResolveSessionTargetNode；rpc_replies/scene_response_handler.cpp OnSceneProcessClientPlayerMessageReply
- client messages: 所有 OptionIsPlayerService 服务的 C2S（77 ListSkills、134/132/131 移动等）及其应答
- tables: none
- depends on: session-lifecycle, route-player-rebind
- behavior: 会话未绑定可用 scene 节点 / RpcClient 未挂 → 推 23 {1003}；否则 ProcessClientPlayerMessageRequest{session_id, player_id 反向栅栏, message_content{id,
  message_id, body 不解析}}；scene 应答带 message_content 时按 session_id 下发。
- internal: muduo TCP RPC；节点实体失效时清绑定。
- java: done — ClientDispatcher.forwardToScene → NodeLinkFrame.ClientForward{session_id, player_id=scenePlayerId, message_id, body, request_id}；未进场景推 23 {1003}；
  回包经 ToClient 下发。**缺口**：links.send 返回 0（LINK_UNAVAILABLE）时只记指标、不推 23 {1003}（基线会推），客户端等到超时。
- size: M
- robot: Go robot SkillCast / SceneSwitch / MultiRobotBehavior；xm-robot SmokeScenario（ListSkills）、MovementScenario
- hazards: 基线 R13：gate node_id 复用后 scene 的同号 session 可能映射别的玩家，必须带 player_id。

### other-backend-routing — 其他后端的客户端 RPC 路由（含路由服模式）
- mmorpg: client_message_processor.cpp PickRandomNode / HandleRouterForward / SendViaRouter；gate_router_mode.h；main.cpp 出站白名单；
  go/client_rpc_router（Forward，route_table.go：chat 2、friend 11、guild 28、team 15、trade 5、match 10、scene_manager 12、data_service 22、login 9）
- client messages: ClientPlayerChat.*、Friend、Guild、Team、Trade、Match、SceneManager 客户端方法、176 ClientRpcRouterForward（内部）
- tables: none
- depends on: login-routing-reply-bridge
- behavior: 直连模式按 nodeType 随机挑实例（zone-scoped 类型比 zone，match 等全局池不比）；路由模式（GATE_CLIENT_RPC_ROUTER=1/true/on）gate 只连路由服，
  原包 + zone_id 转发，不解析 body，路由服回 MessageContent 原样下发；无实例 → 23 {1003}；断线通知在路由模式也合成 58 走路由服。
- internal: Java 等价物是 MessageRoutes.SERVICE_BACKENDS（服务名 → Dubbo group）+ 通用 ClientMessageService；路由服本身不需要。
- java: partial — 只有 ClientPlayerLogin → login；其余全部 BACKEND_UNSUPPORTED → 23 {1003}（「未接入Java版的域推23服务不可用」）。gate 侧每接一个后端只需加一行
  + Dubbo reference；真正工作量在各后端服务。
- size: S（gate 侧）
- robot: Go chat/friend/guild/team/trade/match 冒烟
- hazards: 基线 PickRandomNode 每次构造 std::random_device；login 的 x-caller-* 验签头 gate 未签（路由服注释承认的缺口）。

### battle-message-reject — 战斗消息经 gate 一律拒绝
- mmorpg: client_message_processor.cpp（targetNodeType == BattleNodeService 分支）；main.cpp 注释 turn-based §22 D66
- client messages: BattleClientPlayer.*（SubmitBattleAction、SetAutoBattle、GetBattleState、StopWatchBattle 等）→ 推 23 {1003}
- tables: none
- depends on: client-request-validation
- behavior: 两种模式都不中继战斗，回 23 {1003}，不计非法包、不断连（合法协议号）；战斗只走客户端直连 battle（BattleTokenVerifyRequest）。
  battle 的开局公告经 Kafka PushToPlayerEvent 下发到大厅。
- internal: gate 不连 battle、不维护战斗绑定（BindBattle/UnbindBattle 事件已删除，生成的空 handler 残留）。
- java: done — BattleClientPlayer 不是 OptionIsPlayerService、不在 SERVICE_BACKENDS → unsupported → 23 {1003}，不计非法包，形态一致（巧合对齐，无专门测试）。
- size: S
- robot: Go battle_direct_conn.go（直连），battle_smoke
- hazards: 若 Java 将来给 battle 域加了 SERVICE_BACKENDS 行就会破坏「只走直连」契约。

### scene-push-to-client — scene 下行单播 / 多播与身份栅栏
- mmorpg: gate_service_handler.cpp SendMessageToPlayer / BroadcastToPlayers；network/broadcast_target_codec.h；services/gate/session/system/session_identity_fence.h；
  network/player_message_utils.cpp（scene 侧编码）
- client messages: scene 发出的全部 S2C 推送（79、21、47、64、51、66、70、137、23 …）
- tables: none
- depends on: session-lifecycle, gate-scene-link
- behavior: 会话不存在 → 丢（DEBUG 日志，曾因逐条 WARN 把日志涨到 2GB）；身份栅栏三态：target_player_id==0 放行（首次打一行 INFO）、
  与会话 playerId 相等投递、否则（含会话未绑定玩家）丢弃并 WARN；多播 session_list / bitmap 两种编码，player_list[i] 对应 session_id 升序第 i 个。
- internal: muduo RPC Gate.SendMessageToPlayer / BroadcastToPlayers。
- java: done（机制不同）— scene → gate 链路帧 ToClient{session_ids, message_content}；SceneEventRouter 先 parse MessageContent（坏包整条丢），
  会话线程上 ClientDispatcher.onToClient 只投递给绑定在（节点, 链路代次）上的会话；测试「ToClient只下发给绑定在该链路代次上的会话」。
  不带 player_id 栅栏：同实例内会话号被复用且新会话进了同一节点同一代次链路时，旧玩家的迟到下行可能写给新会话（概率极低）。
- size: M
- robot: 所有进场后的场景（79/21/47/66 断言）
- hazards: bitmap 解码必须每个置位都取一次 player_list，否则全体错位；兼容位 kDeliverUnfenced 计划一个版本后删除。

### gate-broadcast-scene-all — 按场景 / 全服广播
- mmorpg: gate_service_handler.cpp BroadcastToScene（遍历 sceneId 相等的会话）/ BroadcastToAll（全部会话）；player_message_utils.cpp InternalBroadcastToScene；
  Kafka BroadcastToSceneEvent / BroadcastToAllEvent handler（空实现）；go/shared/kafkautil/gate_push.go BuildBroadcastToScene/AllCommand
- client messages: 任意 MessageContent（系统公告、场景广播）
- tables: none
- depends on: scene-push-to-client, gate-command-channel
- behavior: RPC 版：scene 对每个 gate 发一次，gate 按会话 sceneId / 全部会话下发，**无身份栅栏**；Kafka 版 handler 为空 → Go 服务走 Kafka 的场景 / 全服广播被静默丢弃。
- internal: sceneId 只在进场转发成功后提交。
- java: missing — 无全服 / 跨场景广播通道；Java scene 自己算收件人列表走 ToClient（覆盖场景内广播），全服公告类需要新机制。
- size: S
- robot: none
- hazards: 基线 Kafka 版空实现是现成 bug；RPC 版不带栅栏。

### gate-command-channel — gate 命令通道（Kafka）与服务推送
- mmorpg: main.cpp SetKafkaHandlers；handler/event/gate_kafka_command_router.cpp（生成）；node_kafka_command_filter.h；node_command_topic.h；
  gate_event_handler.cpp PushToPlayerEventHandler / BroadcastToPlayersEventHandler；proto/contracts/kafka/gate_command.proto、gate_event.proto；go/shared/kafkautil/gate_push.go
- client messages: 被推送的任意 MessageContent（friend / guild / match / battle 公告等）
- tables: none
- depends on: session-lifecycle
- behavior: topic `gate-cmd_g1`，256 分区，partition = node_id % 256，消费端 assign 自己的分区不进 group；先按 target_gate_id（缺省回落 target_node_id）
  过滤、再按 target_instance_id 防僵尸；GateCommand{event_id, payload} 按 event_id 解码派发，payload 为空时从信封字段兜底构造事件。
  PushToPlayer：按 session_id 找会话直接下发（无玩家栅栏）；BroadcastToPlayers：session_list / bitmap（无 player_list）。生产方必须填 gate_instance_id（fail-closed）。
- internal: Java 无 Kafka（architecture §10「Kafka 事件」首批不做）；等价物可选 Redis pub/sub（已用于 xm:owner-takeover）或 Dubbo 回调到 gate。
- java: missing — 当前只有 scene 能经链路推送；login / 未来 friend、guild、match 推不到客户端。
- size: M
- robot: Go friend_smoke / guild_smoke / match（battle_smoke）里的推送断言
- hazards: 空 payload 兜底：RoutePlayer 没有 scene_id → 现在会被判 kInvalidRoute 直接 3023 + 踢线；RedirectToGate 兜底发出空地址；PushToPlayer 兜底发空 MessageContent；
  Kafka 版推送没有 player 栅栏（session_id 回绕 / gate 复用时可能写错人）。

### route-player-rebind — 进场路由、登录类型绑定与跨节点换图重绑
- mmorpg: gate_event_handler.cpp RoutePlayerEventHandler / BindSessionEventHandler；scene_route_helper.h ApplyRoute / CompareSceneNode；
  gate_service_handler.cpp BindSessionToGate（RPC 版，只就地更新）；SessionInfo.homeZoneId / ownerEpoch / lastSceneRoute
- client messages: 间接（79 NotifyEnterScene 由 scene 发）；EnterGame 26 / 换图请求触发
- tables: none
- depends on: gate-command-channel, scene-request-forward
- behavior: BindSession（login）写 playerId / sessionVersion / pendingEnterGsType（LOGIN_FIRST / RECONNECT / REPLACE）；RoutePlayer（scene_manager）无条件覆盖
  home_zone_id / owner_epoch，转发 PlayerEnterGameNode{player_id, session_id, enter_gs_type, scene_id, home_zone_id, owner_epoch} 的条件：有登录类型；或
  scene_id 变了（换图 / 换线，enterType=0）；或 scene_id 没变但节点实体变了（world rebalance 迁频道、node_gone 改派）。两者到达顺序任意。
  会话的 sceneId + scene 节点指向只在转发成功后才提交。
- internal: Kafka 两个入口；gate 透传 epoch，不校验、不补默认值。
- java: partial — 首次进场已做：login 回 EnterScene 指令（player_id, scene_node_id, scene_id, owner_epoch）→ PlayerEnter 链路帧（ClientDispatcher.enterScene）；
  同节点换图由 xm-scene 自己完成。缺：跨节点换图 / 迁频道 / 节点下线改派时把会话重绑到新 scene 节点并以「非登录」方式进场的 gate 路径；无 enter_gs_type、home_zone_id。
- size: M
- robot: Go robot SceneSwitch、travel_smoke（跨节点）；xm-robot SmokeScenario（只覆盖首次进场）
- hazards: 同 scene_id 换节点必须也算变化，否则玩家「在线但哪里都不存在」；RebindSceneNode 不能放回转发之前。

### scene-entry-forward-retry — 进场转发补发（CPP-2）
- mmorpg: handler/event/scene_entry_dispatch.{h,cpp}；scene_route_helper.h（BeginPendingSceneEntry、RecordSceneEntryFailure、AttemptPendingSceneEntry、ClassifySceneLink）；
  services/gate/session/comp/pending_scene_entry_comp.h；rpc_replies/scene_response_handler.cpp OnSceneNodeHandshakeReply
- client messages: 失败时推 23 {3023 kEnterSceneFailed} → 34 KickPlayer{reason=3023} → shutdown → 1s 后强关
- tables: none
- depends on: route-player-rebind, gate-scene-link
- behavior: 只有「本地确定没发出去」（节点找不到 / 无 RpcClient / 未连上 / 当前连接未握手）才续期；250ms 扫描、退避 250ms→2s 封顶、总预算 20s、
  节点找不到只给 3s、最多 16 次；scene 握手成功应答盖章（按连接对象）并把指向该节点的欠账提前到下一轮；客户端连接已不可用即撤销欠账；
  欠账属于另一名角色则撤销不踢；scene_id=0 直接放弃。30s 汇总日志。
- internal: 欠账存在会话里，会话 erase 即取消；单调时钟。
- java: partial（有意不同）— SceneLink 在建链 / 握手期间排队帧（xm.gate.link-max-queued-frames 1 万），握手 ack 后按序补发；建链失败 / 排队溢出 / 租约无效 →
  onEnterUndeliverable → abandonEnter 让 login 释放归属 + 推 23 {3023}，会话回到「已登录、未进游戏」不断连（PARITY「进场异步失败后回到…」已登记）；
  没有 20s 重试预算、不发 34。SceneLinkTest「首帧触发建链_握手前排队_ack后按原顺序补发」「排队溢出的进场帧按建链失败回报」。
- size: M
- robot: Go robot DisconnectDuringEnter；无 scene 重启专门场景
- hazards: kSent 之后在路上丢失检测不到（已登记残余）；TCP 连上 ≠ 可交付（scene 收到握手才挂 RpcSession，之前的推送会丢）。

### kick-player — 踢人下线（顶号 / 服务端踢出）
- mmorpg: gate_event_handler.cpp KickPlayerEventHandler（Kafka，login ReplaceLogin 发出）；scene_entry_dispatch.cpp GiveUpSceneEntry（3023 版本）
- client messages: 34 KickPlayer（GameKickPlayerRequest{reason{id}}，S2C push）
- tables: none（tip 2017 kLoginBeKickByAnOtherAccount）
- depends on: gate-command-channel
- behavior: 找不到会话 / 连接已关 → 忽略；推 34{reason=2017}（固定原因，KickPlayerEvent 没有原因字段）后 shutdown（只关写端）；断线收尾由断线回调完成。
  Unity 客户端 DescribeKickReason 按 reason 选断线文案。
- internal: 事件只带 session_id，无玩家栅栏。
- java: partial — 顶号走归属协议：scene 发 PlayerKicked → ClientDispatcher.onPlayerKicked 推 23 {2017} 后 sendThenClose，不发 34（PARITY「顶号」行、robot 契约第 13 条
  有意差异）；没有 login / 运维主动踢人（封号、GM 踢线）的通道。测试「scene踢出会话上的玩家_推2017后断开」。
- size: S
- robot: Go robot AccountDisplacement、ConcurrentSameAccount
- hazards: 基线 shutdown 后不 forceCloseWithDelay，不配合挥手的客户端会把会话挂住；无 player 栅栏，session 复用时可能踢错人。

### lease-expired-zombie-close — 断线租约到期收口假死连接
- mmorpg: gate_event_handler.cpp PlayerLeaseExpiredEventHandler（player_locator LeaseMonitor 发出）
- client messages: none（直接 forceClose）
- tables: none
- depends on: gate-command-channel；短线重连 / 断线租约（login 域，Java 待做）
- behavior: 会话不存在 → 忽略（正常路径）；事件 player_id 与会话玩家不同 → 不踢（WARN）；有连接 → forceClose（交给断线回调统一清理）；无连接的残留会话 → 就地 erase。
- internal: LeaseMonitor 把「投递成功」当 ack 的必要副作用，空实现曾导致假死连接永不清理。
- java: missing — Java 没有断线租约 / player_locator；假死连接目前只能靠 TCP 断开或写缓冲高水位发现（gate 无心跳、无空闲超时）。
- size: S
- robot: none
- hazards: 基线 gate 与客户端都没有心跳，半开连接只能靠这条或 TCP keepalive 收口。

### cross-zone-redirect — 跨 zone 重定向（RedirectToGate）
- mmorpg: gate_event_handler.cpp RedirectToGateEventHandler；go/scene_manager/internal/logic/gate_redirect.go（AssignGateForZone、signRedirectToGate，票据 TTL 300s）；
  客户端 GameClient.cs RedirectFlow / ZoneTravelClient.cs；robot pkg/redirect.go FollowRedirect
- client messages: 124 RedirectToGate（RedirectToGateNotify{target_ip, target_port, token_payload, token_signature, token_deadline}，S2C push）；226 TravelToZone 触发
- tables: none
- depends on: gate-command-channel, token-handshake, redirect-ticket-binding, node-registration-load-report（client_endpoint）
- behavior: gate 只把 Kafka 事件原样包成 124 推给客户端，不断连；客户端探测目标 gate → 新连接 ClientTokenVerify → 目标 zone 重跑 Login + EnterGame，最多 3 跳；
  目标 gate 按 target_zone_id 拒他区票据。scene_manager 按目标 zone gate 的 client_endpoint 选址、player_count 最小、同地址去重取最新。
- internal: 票据由 scene_manager 用 GateTokenSecret 签，含 player_id / target_zone_id。
- java: missing — 无跨 zone（architecture §10 首批不做）；GateTokens 已校验 target_zone_id，gateway 已下发 advertise 地址，可复用。
- size: M（gate 侧推送 S，签票 / 选址在 scene-manager）
- robot: Go travel_smoke、features_smoke（124 处理）、battle_smoke_cross_zone
- hazards: 空 payload 兜底会推出空地址的 124；客户端刻意不按 token_deadline 拦截，以目标 gate 判过期为准。

### redirect-ticket-binding — 重定向票据持票者 / 目标 zone 透传（CZ-8）
- mmorpg: client_message_processor.cpp DispatchTokenVerify（存 ticketPlayerId / ticketTargetZoneId）、BuildSessionDetails（ticket_player_id、ticket_target_zone_id）；
  SessionInfo；login EnterGame 校验（Go）
- client messages: 26 EnterGame 被拒（login 侧码）
- tables: none
- depends on: token-handshake, login-routing-reply-bridge
- behavior: gate 只存不判：验签通过后把 payload.player_id / target_zone_id 写进会话，随每个 gRPC 请求的 SessionDetails 交给 login；login 在 EnterGame 拒绝他人持票，
  看到 target_zone_id == 本 zone 时不按 home_zone 弹回。0 = 普通票据放行。两种转发模式必须走同一个 BuildSessionDetails（曾漏掉一种）。
- internal: none
- java: missing — SessionContext 没有票据字段，GateTokens.verify 的 payload 未被保存。
- size: S
- robot: Go travel_smoke
- hazards: gate 自己要到 BindSession 才知道 player_id，在 gate 拒只会在 login 留下残留会话，所以判定放 login。

### scene-node-loss-handling — scene 节点下线 / 链路断开时的会话处理
- mmorpg: client_message_processor.cpp OnNodeRemoveEventHandler、ResolveSessionTargetNode（失效即清绑定）；scene_route_helper.h CompareSceneNode（无效指向算 kChanged）
- client messages: 之后的 scene 类消息推 23 {1003}；改派成功后重新收到 79
- tables: none
- depends on: route-player-rebind, scene-request-forward
- behavior: 节点被摘除时把所有会话对该节点的绑定置无效，**连接保持**；客户端消息回 1003，直到 scene_manager 的 node_gone 改派经 RoutePlayer 把会话重绑到新节点并以 enterType=0 进场。
- internal: etcd 发现驱动 OnNodeRemoveEvent。
- java: partial（有意不同，未登记 PARITY）— onSceneLinkDown：会话绑定在断开的链路代次上就解绑并直接关闭连接（SCENE_LINK_DOWN），客户端须重连重进；
  测试「scene链路断开时关闭其上的会话_不再发PlayerLeave」「旧代次链路断开不影响已在新代次上进场的会话」。
- size: S
- robot: none
- hazards: 同 uuid 重注册是先销毁再创建且不发 OnNodeRemoveEvent，缓存的实体号会悬空（基线所以每次按 node_id 重解析）。

### gm-graceful-shutdown — GM 签名停机（Gate.GmGracefulShutdown）
- mmorpg: gate_service_handler.cpp GmGracefulShutdown；gate_security.h VerifyGmRequest / GmNonceCache / ResolveGmSkewSeconds；SECURITY.md §2；tests/gate_security_test.cpp
- client messages: none（运维面，125 GateGmGracefulShutdown 非客户端协议）
- tables: none
- depends on: graceful-shutdown-lease-loss
- behavior: operator 字段寄生签名 `<操作人>|<unix 秒>|<nonce>|<hmac hex>`，canonical = `Gate.GmGracefulShutdown\n<node_id>\n<操作人>\n<ts 原文>\n<nonce>\n<reason>`；
  密钥只读 GATE_GM_ADMIN_SECRET，未配一律拒；时钟偏差默认 300s（GATE_GM_AUTH_SKEW_SECONDS 钳到 [30,900]）；nonce 2×skew 内去重、表上限 1024 满即拒；
  先验签后登记 nonce；拒绝只打 ERROR（字段截断 128）不回错误码；通过则 forceClose 全部会话、回 affected_count，queueInLoop 后 Shutdown。
  scene 有同一机制（method 名不同）。
- internal: muduo GameChannel 无 metadata 边信道，所以签名进 operator。
- java: missing — Java 无运维 RPC / 管理接口（architecture §10「GM / 管理接口」首批不做）；只能 SIGTERM（走 graceful-shutdown）。Java 可改用 actuator 管理端口
  （只绑本机）+ HMAC 头，不必照搬 operator 寄生。
- size: M
- robot: none
- hazards: 基线曾 `done->Run()` 空指针崩溃；签名必须绑 node_id，否则一次抓包可重放停全区 gate。

### graceful-shutdown-lease-loss — 停机与节点号冲突时断开全部会话
- mmorpg: main.cpp SetBeforeShutdown / SetOnConflictShutdown（disconnectAllClients）
- client messages: none（连接被关闭）
- tables: none
- depends on: session-lifecycle
- behavior: SIGTERM 或 etcd node_id 冲突 → forceClose 全部客户端连接，各会话照常走断线回调（ExitGame + Login.Disconnect）后进程退出。
- internal: etcd 租约 / CAS。
- java: done — GateNode.stop（ContextClosedEvent 最高优先级：停接客 → closeAll → 等收尾 shutdownDrainTimeout 3s → 关链路 → 释放节点号）；
  onLeaseLost（Redis 节点号租约丢失：停接客、不建新链路、关全部会话，不摘目录条目）。
- size: S
- robot: none
- hazards: Java 若在 Dubbo 引用销毁之后才收尾，断线通知发不出去（已用 @Order 规避）。

### node-registration-load-report — 节点注册、在线人数上报与客户端可达地址
- mmorpg: main.cpp playerCountReportTimer（每 10s 写 etcd NodeInfo.player_count）、dependencyGate；core/node/system/node/client_endpoint.{h,cpp}（CLIENT_ENDPOINT_SOURCE none/static/agones、
  _HOST、_PORT、_REQUIRED）；go/shared/clientendpoint.Select；gate_version.h（启动版本行）
- client messages: 间接：assign-gate HTTP 与 124 里的 ip/port
- tables: none
- depends on: none
- behavior: gate 自报 client_endpoint（与集群内 endpoint 分离），login / scene_manager 选址时优先用它，require 时缺地址跳过；player_count 用于选最空 gate。
  启动打两行 `[gate_version] version commit build_time node_type node_id zone_id`（直写 stdout）。
- internal: etcd；Agones 外部来源。
- java: done — GateNode.publish 每 5s 写 Redis 节点目录 GateNodeInfo{client_host=xm.advertise-host, client_port=xm.gate.advertise-port, player_count}（TTL 15s）；
  PARITY「节点客户端可达地址」已对齐（gate 部分）。版本行未做（属于 observability）。
- size: S
- robot: Go robot http_assign_gate；xm-robot AssignGateClient
- hazards: 稳定地址下崩溃重启会残留旧条目，选址需按地址去重取最新（Go DedupeNewest）；Java 目录 TTL 15s 内同样可能下发已死 gate。

### gate-scene-link — gate ↔ scene 节点链路与握手
- mmorpg: gate_service_handler.cpp NodeHandshake；registration_manager（TCP 连上 0.5s 后握手）；scene_entry_dispatch.cpp SceneLinkHandshakeComp；main.cpp 出站白名单
- client messages: none
- tables: none
- depends on: node-registration-load-report
- behavior: gate 主动连同 zone 的 scene（muduo RPC），握手后 scene 才挂 RpcSession；推送只在握手后的当前连接上有效。
- internal: 服务端内部，两版各自实现。
- java: done（Java 内部）— SceneLinkManager / SceneLink / NettyLinkConnector / LinkHellos：按需建链、每节点一条活链路带代次、LinkHello HMAC（XM_NODE_LINK_SECRET）+ lease_epoch、
  zone / 节点号校验、未就绪排队、写缓冲高水位判死；SceneLinkTest 全套。
- size: M
- robot: 全部进场场景
- hazards: 迟到的旧连接握手应答不能给新连接盖章（基线按连接对象比对；Java 按代次）。

### gate-observability — gate 可观测性（采样日志 / 流量统计 / 版本行）
- mmorpg: core/network/traffic_statistics.{h,cpp}（按 message_id 收发条数 / 字节 / 最大包，NODE_TRAFFIC_STATS_ENABLED，30s 窗口）、message_statistics.h；
  error_reporter（illegal_packet 记录）；scene_entry_dispatch.cpp 30s 汇总；gate_version.h；各拒绝路径 1/1024 采样
- client messages: none
- tables: none
- depends on: none
- behavior（运维可见）: C++ gate 无 Prometheus，只有日志计数与可开关的流量统计；版本六元组行。
- internal: none
- java: partial — GateMetrics（Micrometer + /actuator/prometheus :18103）：sessions_active、scene_links、handshakes、client_requests{route,method,result}、invalid_frames、
  disconnects{reason}、backend_calls、link_frames / dropped / events；拒绝路径 1/1024 采样。缺：按消息号的字节流量统计、启动版本行（build-info / git commit）。
  PARITY 已登记「指标两版各自命名」。
- size: S
- robot: none
- hazards: 不得以 session_id / player_id 做标签（AGENTS.md §5）。

### hmac-session-key — 会话级消息签名密钥（未启用）
- mmorpg: session_info_comp.h hmacSessionKey；client_message_processor.cpp（verify 后保存 payload.hmac_session_key）；go/login loginqueue/gatetoken.go（生成 32 字节）；
  docs/design/hmac-message-signing.md
- client messages: none（帧里还没有 hmac_tag 槽位）
- tables: none
- depends on: token-handshake
- behavior: 当前只存不用，codec 仍只有 adler32；slice B 未落地。
- internal: none
- java: not_applicable — 基线未启用；Java 的 gateway 签发令牌时不生成该字段，gate 也不保存。基线一旦上线逐包签名即变成帧格式契约变更，届时两边同做。
- size: S
- robot: none
- hazards: 上线需改帧格式，属于客户端契约，必须先改 mmorpg 再同步。

### legacy-gate-rpcs — 遗留 / 空实现的 gate RPC 与事件
- mmorpg: gate_service_handler.cpp RouteNodeMessage（空）、RoutePlayerMessage（多跳路由，线性扫会话找玩家）、PlayerEnterGameNode RPC、BindSessionToGate RPC；
  gate_event_handler.cpp PlayerDisconnectedEvent / BindBattle / UnbindBattle（空）；match_event_handler.cpp、player_event_handler.cpp（空）；route_message_response_handler.cpp
- client messages: RoutePlayerMessage 最终把内嵌 ClientRequest 当 MessageContent 下发
- tables: none
- depends on: none
- behavior: 生成代码骨架，多数无行为；RoutePlayerMessage 按 node_list 逐跳转发，到最后一跳在本 gate 按 player_id 线性查会话下发。
- internal: muduo RPC。
- java: not_applicable — Java 的 gate ↔ scene 链路只有 PlayerEnter / Leave / ClientForward / ToClient / EnterResult / Kicked，不需要多跳路由与空壳事件。
- size: S
- robot: none
- hazards: RoutePlayerMessage 曾把业务 node_id 直接当 entt 实体用（已修）；BindBattle / UnbindBattle 事件在 proto 已删、生成 handler 残留。

### idle-heartbeat — 心跳 / 已认证连接空闲超时
- mmorpg: 无实现（grep gate / session / 客户端 GateTcpClient 均无 heartbeat / idle）；gate-connection-admission-control.md 只规划了未认证连接死线
- client messages: none（契约里没有心跳消息）
- tables: none
- depends on: connection-admission
- behavior: 两端都不发心跳；已认证连接只靠 TCP 断开、写缓冲高水位、断线租约到期（lease-expired-zombie-close）收口。scene 侧挂机 600 帧停推不关连接。
- internal: none
- java: not_applicable — 基线没有；Java 只有握手超时（未认证）。若要加读空闲超时（Netty IdleStateHandler）不会改协议，但会断开长时间静止的合法客户端，需两版共同定口径。
- size: S
- robot: none
- hazards: 半开连接在 Java 上目前没有任何收口路径（基线至少有 LeaseExpired）。

## Open questions
1. 不发 34 KickPlayer（顶号只发 23 {2017}）、scene 链路断开即断连（基线保持连接并回 1003 等改派）——后者未登记 PARITY，需确认是否作为有意差异登记。
2. Java 同一会话 login 调用在途时 scene 消息也排队（基线不串行）：EnterGame 在途期间的移动 / 技能包会被延后，是否可接受？
3. 坏请求体 1014：基线 gate 推 23 {1014}，Java 走信封 error_message{1014}（message_id=请求号）。客户端两种都能收口，需要定一种并登记。
4. forwardToScene 在 links.send 返回 0 时不推 23 {1003}（基线会推），客户端只能等超时——建议直接补，属 bug 还是有意？
5. 跨 zone 重定向、票据绑定、Go 服务推送都依赖「gate 命令通道」：Java 用 Kafka（tech-stack 已规划）还是 Redis pub/sub（已有 xm:owner-takeover）？
   决定前不宜做 kick / redirect / push 的 gate 侧。
6. GM 客户端指令闸：Java 是否引入运行模式（XM_RUN_MODE）？若永远不在客户端协议面实现 GM（走管理端口），gate 只需把 6 个号固定判 1006 + 计非法包即可对齐。
7. 可配置并发连接上限（GateMaxConnections）Java 是否要做（基线 prod 必填）？
8. 基线 Kafka BroadcastToScene / BroadcastToAll handler 为空、RoutePlayer 空 payload 兜底会因 scene_id=0 直接踢线——是否需要反馈给 mmorpg 侧修。
9. 基线 MessageLimiter max_requests 用 uint8_t（>255 截断）、整秒窗口；Java 用 int + 纳秒窗口，表值 > 255 时两版行为不同，当前表数据是否有这样的行未核对。
