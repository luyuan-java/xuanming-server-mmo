# 基础设施与业务对账残余收口设计（熔断、来源限速、排队重试、探测分散、寄售标题审核与删角邮件拒收）

## 需求分析
围绕 `PARITY.md` 尚未收口的 6 项核心基础设施与业务对账条目，以最小、原生、零冗余的 Java 21 实现全部对齐 C++/Go 权威口径：
1. **按后端隔离的三态熔断（`game-common` + `game-gate`）**：对齐 Go `shared/circuitbreaker` 与 `client_rpc_router`——默认连续 5 次基础设施/超时故障打开 10 秒，`HalfOpen` 仅放行单条探测请求，调用方断连/取消不泄漏探测锁，熔断打开时直接快速回带请求号的信封 `1003`（不计非法包、不断连、不阻滞 lane），业务拒绝与 `1008` 永不计入熔断。
2. **仅公网来源前缀限速与 Battle 未认证连接 O(1) 计数（`game-common` + `game-battle`）**：IPv4 按 `/24`、IPv6 按 `/64` 聚合，自动跳过私网/回环/链路本地/CGNAT/`0/8`/`::/64`/`fc00::/7`/`fe80::/10` 等非公网来源，仅在承压时激活、65,536 条满表 fail-open 并计数；`BattleEdgeServer` 以 O(1) 原子计数维护未认证直连数，不再扫描会话表。
3. **网关排队重试下限、抖动与 0.1 IP 令牌扣减（`game-gateway`）**：分波未开或区桶空时 `retry_after_ms = max(queueRetryMinMs, rawWaitMs) + rand[0, queueRetryJitterMs)`（默认下限 2000ms、抖动 2000ms）；区桶空时不再全额退还 IP 令牌，而是按 `queueRetryIpCostMilli`（默认 100 = 0.1 个令牌）扣减，防止不守提示的客户端高频刷区桶热键。
4. **全服探测目标机房内按玩家 HRW 分散与无合格机房回 16023（`game-match`）**：`GlobalMatchService.plan` 在每个机房内对可接纳节点按 `(player_id, instance_id)` 执行最高随机权重哈希（Rendezvous/HRW）选台，保持均摊、稳定、增减只挪相关玩家且单台行为不变；目录可读但无合格目标时回业务码 `16023`（`kMatchNoRegionWithinLatency`），仅目录不可用时回故障码 `16004`。
5. **人民币寄售标题内容安全审核（`game-trade`）**：`RmbConsignmentService.create` 在数据库事务外对标题执行 `TEXT_SCENE_TRADE_TITLE` fail-closed 审核（`game.trade.content-guard-enabled=true` 时经 Dubbo `ContentGuardService` 接入）。
6. **已注销角色个人邮件拒收 `17011`（`game-mail`）**：`MailStore.send` 对个人邮件（`kind=1`）支持已删角判定并回 `kMailRecipientDeleted`（17011），让榜单/活动异步发奖直接识别放弃而不积压人工单。

## 设计分析
- **`com.game.common.net.EndpointCircuitBreaker`**：无状态/并发安全，按 `key` 维护 `ConcurrentHashMap<String, Entry>`，每条 `Entry` 用轻量 `synchronized` 守护 `CLOSED / OPEN / HALF_OPEN` 状态机与 `probeInFlight` 标记；提供 `tryAcquire(key)`、`recordSuccess(key)`、`recordFailure(key)`、`releaseProbe(key)`、`isOpen(key)` 与 `evict(key)`；支持 `disabled()` 零开销空实现。
- **`com.game.common.net.SourcePrefixRateLimiter`**：先由 `isPublicAddress(InetAddress)` 零分配判公网，再由 `prefixKey(InetAddress)` 提取 IPv4 `/24` 或 IPv6 `/64` 固定键；`tryAdmit(address, underPressure)` 在未承压或非公网直接放行，承压时查令牌桶（`65536` 上限满表 fail-open 并递增 `untrackedCount`）。
- **`game-gate` `ClientDispatcher`**：在 `callBackend` 入口调用 `backendBreaker.tryAcquire(domain)`，未获准立即回带请求 ID 的信封 `1003`；在 `future.whenComplete` 中统一执行 `recordSuccess` / `recordFailure`（先于会话关闭短路，保证 `HalfOpen` 探测锁在客户端中途断连时也必定释放）。
- **`game-battle` `BattleEdgeServer` / `BattleEdgeHandler`**：`DirectSession` 持有 `AtomicBoolean authCounted`，注册时 `unauthenticatedConnections` +1，验票成功 / 探测开始 / 连接断开时幂等仅减一次，维护精确 O(1) 未认证池大小。
- **`game-gateway` `RateLimitSettings` / `RateLimiter` / `RedisRateLimitStore`**：保持旧 9 参构造器与 2 参 `RateLimitStore.admit` 默认委托兼容现有测试；Lua `ADMIT` 脚本支持第 7 参数 `zoneEmptyIpCostMilli`，在区桶空时扣 `math.min(ipT, cost)`。
- **`game-match` `GlobalMatchService`**：`selectProbeNode(playerId, candidates)` 使用 SHA-256 `(playerId, instanceId)` 64 位无符号打分选最高分节点；`plan` 目录读取成功且无目标时返回带 `kMatchNoRegionWithinLatency` 的 `GetNetworkProbePlanResponse`。
- **`game-trade` `RmbConsignmentService`**：通过函数式接口 `TextGuard` 解耦，事务外调用，未开启配置时默认放行。
- **`game-mail` `MailStore`**：新增 `RECIPIENT_DELETED = kMailRecipientDeleted_VALUE` 与可选 `LongPredicate recipientDeleted`，在个人邮件落库前拦截已删角。

## 思想分析
- **基础设施下沉复用**：将三态熔断器与公网前缀限速器沉淀在零业务依赖的 `game-common`，同时服务 `game-gate`、`game-battle` 及未来节点，不重复造轮子。
- **不破坏存量契约与测试**：所有新增参数均保留原有构造函数/接口默认委托，确保数千条既有单测零误伤；同时在生产装配与新增单测中覆盖完整新行为。
- **防泄漏与防误伤**：熔断只在基础设施/超时故障触发，绝不将业务拒绝或客户端限频 `1008` 计入；来源限速跳过私网/边缘代理地址，避免把 Nginx/LB 后的全部玩家误伤为同一来源。

## 漏洞分析
1. **`HalfOpen` 探测锁泄漏导致永久熔断**：若客户端在探测包返回前断开，`onBackendCompleted` 会因 `s.closed` 提前返回 → 在 `future.whenComplete` 阶段（而非 `onBackendCompleted` 判关闭后）结算熔断器状态，彻底杜绝锁泄漏。
2. **私网/NAT 代理误限速**：经小游戏边缘或内网 LB 进入的连接 `remoteAddress` 为私网 IP → `isPublicAddress` 严格过滤 RFC1918、回环、链路本地、CGNAT `100.64/10`、`0/8`、`::/64`、`fc00::/7`、`fe80::/10`。
3. **区桶空重试风暴打爆热键**：原 Lua 退还全部 IP 令牌，单 IP 忽视 `retry_after_ms` 可无限刷区桶 → 每次区桶空扣 0.1 个 IP 令牌并强制 `retry_after_ms >= 2000ms + jitter`。
4. **探测目标单点热点**：每个机房固定选最小 `nodeId` 会把全服探测打到同一台 battle → HRW 哈希把玩家均匀分散到机房内所有可接纳节点，扩缩容仅迁移 `1/N` 玩家。
5. **寄售标题绕过内容安全**：寄售创建前事务外先调用 `TextGuard`，拒绝或审核服务不可用均 fail-closed，不写数据库、不冻资产。

## 步骤分析
1. 在 `game-common` 新增 `EndpointCircuitBreaker`、`SourcePrefixRateLimiter` 与 `NetProtectionTest`，安装验证。
2. 在 `game-gate`、`game-battle`、`game-gateway`、`game-match`、`game-trade`、`game-mail` 依次落码并补齐针对性单测。
3. 逐模块执行 Maven 测试并安装，确认全绿。
4. 更新 `PARITY.md` 对应条目的对账状态并提交推送。

## 执行步骤
1. 已完成 6 个模块的现有实现定位与依赖边界核对。
2. 编写 `game-common` 公共保护原语并验证安装。
3. 依次更新 6 个业务模块与回归测试，验证全部通过后更新 `PARITY.md` 并推送。
