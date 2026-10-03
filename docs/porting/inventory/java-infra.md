# 功能清单：mmorpg 现有 Java 代码 + 基础设施 + 部署（对照 Java 版 xuanming-server-mmo）

基线：mmorpg `26ceb70ca`；Java 版 HEAD（xm-gateway 等 14 个模块）。2026-10-02 读源码整理。

**区域概述。** mmorpg 的 Java 部分有三块：`java/gateway_node`（Spring Boot 3.4 + JPA + jetcd + gRPC，对外 HTTP 入口：区服列表、分配 gate、排队轮询、`/api/login`、`/api/refresh-token`、公告、CDN 签名、热更检查，以及 `/admin/**` 运维接口），它在 2026-05 之后已退化为 go-zero login 的**传输垫片**——选 gate、签令牌、排队都在 `go/login` 的 `LoginPreGate`，网关自己只做区服准入（`zone_config` 表 + 1s 缓存）、Bucket4j 限流 / 开服分波、健康探测与负载档；`java/config_node`（配置表查询 / 热加载 REST，基本是演示级）；`java/springboot_satoken_auth_starter`（Sa-Token + JustAuth 的账号认证样例，往 Redis 写 `satoken:login:token:<v>`，由 go/login 的 satoken provider 读）。基础设施侧是 C++ `infra` 库（Kafka 生产 / 消费封装与控制面分区策略、带 owner_epoch 围栏的 Redis 热缓存存盘、Agones GameServer 生命周期与客户端地址自报），`deploy/`（docker compose：Kafka KRaft + 定分区 topic-init、Redis / Redis Cluster、MySQL、Nacos、Loki / Alloy / Grafana、TiDB；K8s：infra / go / java 清单、开区脚本、Agones、告警、发布打包）与 8 条 GitHub Actions。Java 版目前只有 xm-gateway 的「静态区服列表 + 自签令牌的分配 gate」两条 HTTP 路径与 Prometheus 指标；**没有 `/api/login`**（Unity 客户端 `GameClient.EnterZone` 第一步就是它，code≠0 直接失败——Java 版目前接不了 Unity 客户端，只能接 robot 的 assign-gate 直连路径）、没有排队 / 限流 / 公告 / 运维接口、没有任何部署资产（无 Dockerfile、无 compose、无 K8s、无 CI），Kafka 只在选型表里。

## 功能

### gateway-assign-gate — 分配 gate（快速通道）
- mmorpg: java/gateway_node/.../controller/AssignGateController.java、service/AssignGateService.java、dto/AssignGate{Request,Response}.java、grpc/LoginRpcClient.java（AssignGate）；go/login/internal/logic/loginpregate/assigngatelogic.go（signFastPath）、pkg/loginqueue/gatetoken.go（PickGate / SignGateToken）
- client messages: none（HTTP `POST /api/assign-gate`，JSON snake_case，恒 HTTP 200 + body.code）；令牌之后用于 TCP 首包 ClientTokenVerifyRequest
- tables: none
- depends on: gateway-zone-directory（准入）、节点目录（mmorpg etcd `GateNodeService.rpc/`；Java Redis NodeDirectory）
- behavior: 请求 `{zone_id, account, device_id, queue_token}`。准入顺序：zone 不在 zone_config → 404 `zone_not_found`；MAINTENANCE / CLOSED / PREVIEW → 503 `zone_maintenance` / `zone_closed` / `zone_not_open`；DB 查准入失败 → 500 `zone_admission_unavailable`（fail-closed）。之后 login 选在线最少、非排空、有客户端地址的 gate，签 `GateTokenPayload`（TTL 10 min，32 字节会话密钥）→ code 0 + `gate_ip/gate_port/token_payload/token_signature(Base64)/token_deadline`。login gRPC UNAVAILABLE / DEADLINE → 500 `login_unavailable`；其它 gRPC 码 → 500 + 码名；ADMITTED 却无 ip → 500 `admitted_without_endpoint`。客户端（GatewayHttpClient.AssignGateResult）按 0/100/404/410/429/500/503 分支。
- internal: 网关不签令牌，转发 go/login `LoginPreGate.AssignGate`（按 zone 钉实例）。Java 版由 xm-gateway 自己读 Redis gate 目录并签令牌（GatePicker 规则与 loginqueue.PickGate + FilterDrainingGates 一致）。
- java: done — xm-gateway/src/main/java/com/game/gateway/assign/*、gate/GatePicker.java、gate/GateTokenIssuer.java、gate/RedisGateSource.java，单测 GatePickerTest / GateTokenIssuerTest / GatewayHttpApiTest。差异：区服准入来自静态配置（无 PREVIEW / zone_not_open、无运行时切换）；`target_zone_id` 填 zone_id（mmorpg 普通登录填 0）；400 `bad_request` 为 Java 补充；`account / device_id / queue_token` 照收不用。
- size: M
- robot: robot_smoke.yaml（mmorpg Go robot）、xm-robot AssignGateClient；客户端 GameClient.EnterZone 第 2 步
- hazards: ① PARITY 说「mmorpg zone_id=0 为自动选区」不准确：gateway 的 `checkZoneAdmission` 对 0 放行，但 `LoginRpcClient.unaryCall(zoneRequired=true)` 对 zoneId≤0 抛 IllegalArgumentException，被 `catch (Exception)` 兜成 500 `internal_error`——实际上 mmorpg 网关上 zone_id=0 恒失败；客户端 / robot 都先调 server-list 取推荐区，从不发 0。Java 回 404 也是失败，口径一致即可。② mmorpg Login / AssignGate / QueryQueueStatus 都不自动重试（非幂等），Java 若改成走 login Dubbo 需保持。③ `gate.token-secret` / `token-ttl-seconds` 仍留在网关配置里，但已只被 CDN 签名使用（见 gateway-cdn-sign）。

### gateway-server-list — 区服列表
- mmorpg: java/gateway_node/.../controller/ServerListController.java、service/ServerListService.java、dto/{ZoneInfoDto,ManualZoneStatus,ZoneDisplayStatus,LoadLevel}.java、entity/ZoneConfig.java、resources/schema.sql、deploy/mysql-init/gateway_tables.sql
- client messages: none（HTTP `GET /api/server-list` → `{"zones":[{zone_id,name,status,load_level?,maintenance_msg?,open_time?,is_new,recommended}]}`）
- tables: none（MySQL `zone_config`）
- depends on: gateway-zone-directory、gateway-zone-health-probe
- behavior: 按 `sort_order` 升序；`status` 四值 OPEN / MAINTENANCE / CLOSED / PREVIEW：手工状态非 OPEN 时以手工为准；手工 OPEN 且探测 DOWN → 显示 MAINTENANCE；探测 UNKNOWN 仍显示 OPEN 且不下发 load_level。`load_level` 只在显示 OPEN 且探测非 UNKNOWN 时下发；`maintenance_msg` 只在 MAINTENANCE / CLOSED 时下发；`open_time`（Unix 秒）只在 PREVIEW；`is_new` = created_at 在 7 天内；字段名必须是 `is_new`（Jackson 默认会剥成 `new`）。空字段不输出。Unity 客户端 QdaoServerSelectView 展示这些字段；robot 只读 zone_id / recommended。
- internal: 每次请求查一次 `zone_config`（无缓存）+ 读探测快照。
- java: partial — xm-gateway/serverlist/{ServerListController,ZoneInfo}.java + zone/ZoneCatalog.java：区服来自 `xm.gateway.zones` 静态配置，启动后不变；没有 PREVIEW / open_time / load_level，is_new 恒 false，不叠加健康探测。
- size: S（补齐 PREVIEW / is_new / load_level 字段；数据源见 gateway-zone-directory）
- robot: robot.yaml / robot_smoke.yaml 在 zone_id=0 时调用；客户端 GatewayHttpClient.GetServerList
- hazards: ① `ManualZoneStatus.fromCode` 对未知码回 OPEN（fail-open：库里写错状态码等于开服）。② `open_time` 用 `LocalDateTime.toEpochSecond(ZoneOffset.UTC)`，而 JDBC 设 `serverTimezone=UTC`、`created_at` 由 JVM 本地时钟写：JVM 不在 UTC 时 open_time / is_new 会差整数小时。③ 每次请求都打 MySQL，开服洪峰下区服列表是 DB 热点。

### gateway-zone-health-probe — 区服健康探测与负载档
- mmorpg: java/gateway_node/.../service/ZoneHealthProbeService.java、etcd/{GateWatcher,NodeInfoRecord,NodeType}.java、config/{ZoneProbeProperties,EtcdClientConfig}.java
- client messages: none（体现在 server-list 的 status / load_level）
- tables: none（`zone_config.capacity`）
- depends on: 节点目录（gate / scene 在线条目）、gateway-zone-directory
- behavior: 每 5s 探测：某区无 gate → DOWN（server-list 显示 MAINTENANCE）；有 gate 无 scene → DEGRADED（仍显示 OPEN）；都有 → HEALTHY。负载档 = 全区 gate 在线人数 / capacity：<0.5 SMOOTH、<0.8 BUSY、否则 FULL（capacity≤0 → SMOOTH）。探测失败保留 last-known-good 快照，超过 `status-ttl-ms`（15s）或冷启动无快照 → UNKNOWN（不下发 load_level、不降级）。连续 3 次失败升 ERROR 日志。
- internal: 三张结果表与采集时刻作为一个不可变快照原子发布；etcd 查询失败抛 NodeDiscoveryException 而不是返回空表（否则一次抖动全服显示维护）；任一 NodeInfo 记录解析失败整批作废；跳过 `allocated/` 雪花哨兵键。Java 版对应数据在 Redis NodeDirectory（GateNodeInfo.player_count、SceneNodeInfo）。
- java: missing — xm-gateway 不探测、不出负载档（ServerListController 注释写明「本批不叠加节点健康探测」）。
- size: M
- robot: none（robot 不读 status / load_level）
- hazards: ① 注入了 `StringRedisTemplate` 但从未使用。② 探测每轮 `findAll` 一次 zone_config。③ 「无 gate 判 DOWN」修过一次反向 bug（曾判 DEGRADED → 显示「开放 · 流畅」）；Java 实现时 DOWN / DEGRADED 语义要照此区分。④ 读 Redis 目录时要区分「查询失败」与「真的没有节点」（Java RedisGateSource 抛异常时 assign-gate 已 fail-closed，探测侧同理）。

### gateway-zone-directory — 区服目录存储与运维接口（开关区 / 维护）
- mmorpg: java/gateway_node/.../controller/{AdminZoneController,InnoDbDeadlockRetry}.java、repository/ZoneConfigRepository.java、entity/ZoneConfig.java、service/AssignGateService.java（准入缓存）、resources/{schema.sql,seed_stress_3zones.sql}
- client messages: none（运维 HTTP：`GET/POST /admin/zones`、`GET/PUT/DELETE /admin/zones/{id}`、`POST /admin/zones/{id}/maintenance {maintenance_msg}`、`POST /admin/zones/{id}/open`）
- tables: none（MySQL `zone_config`：zone_id、name、manual_status 0..3、capacity 默认 5000、maintenance_msg、open_time、recommended、sort_order、created_at、updated_at）
- depends on: admin-api-auth
- behavior: create = ODKU upsert（已存在则覆盖业务列、保留 created_at），缺 zone_id / name → 400；update 不存在 → 404、绝不插入；delete 204 / 404；maintenance 只改状态与（给了的）文案；open 置 OPEN 并清文案。手工状态变更约 1s 内对 assign-gate / queue-status 生效（准入缓存 TTL 1s）；排队中的玩家轮询时也被拦（fail-closed）。
- internal: 准入快照缓存：按 zone 1s TTL、同 zone 并发 miss 用 CompletableFuture 合并、失败不缓存、最多 4096 项防随机 zone_id 撑爆；写路径「单条主键写 + 同事务非锁定回读」，create 包 InnoDB 1213 有界重试。Java 版需要：可运行时修改的区服目录（MySQL 表或 Nacos 配置）+ 运维接口 + 短 TTL 准入缓存。
- java: missing — xm-gateway zone/ZoneCatalog.java 是启动时一次性加载的不可变静态配置，改状态要改配置重启；无任何 /admin 接口。
- size: M
- robot: none
- hazards: update 的「行不在 → 404」论证依赖 RR 隔离级别的间隙锁，换 RC / TiDB 会出现「没改到却回 200」；`created_at` 用 JVM 本地时间、`updated_at` 有 DB ON UPDATE，两种时钟混用；JSON 直接序列化 JPA 实体（字段名随实体变）。

### admin-api-auth — 运维接口鉴权（X-Admin-Key）
- mmorpg: java/gateway_node/.../config/AdminApiKeyFilter.java、application.yaml `admin.api-key`
- client messages: none
- tables: none
- depends on: none
- behavior: 路径以 `/admin/` 开头的请求必须带 `X-Admin-Key` 且等于配置值，否则 401 `{"error":"Unauthorized"}`；其余路径放行。
- internal: Servlet Filter，最高优先级。
- java: missing — Java 版无管理面 HTTP 接口（actuator 已独立端口 18105 且默认只绑本机，见 architecture.md §11）。
- size: S
- robot: none
- hazards: ① `String.equals` 非常数时间比较。② 默认密钥 `change-me-in-production` 写在仓库配置里（违反 Java 版 §3「秘密只从环境变量注入」）。③ 前缀判断用原始 `getRequestURI()`，而 Spring MVC 匹配会忽略路径段参数——`/admin;x/zones` 之类的请求可能绕过过滤器仍命中控制器（未实测，Java 实现应改用 Spring Security / HandlerInterceptor 在已解析路径上鉴权，或把管理接口放独立端口）。④ 只有一把共享密钥，没有审计日志。

### login-queue — 登录排队（assign-gate code 100 + /api/queue-status）
- mmorpg: go/login/internal/logic/loginpregate/{assigngatelogic,querystatuslogic}.go、pkg/loginqueue/{queue,gatetoken,metrics}.go；java/gateway_node/.../controller/QueueStatusController.java、dto/QueueStatusRequest.java、AssignGateService.queryQueueStatus
- client messages: none（HTTP：`POST /api/assign-gate` 回 `code=100, queue_source="login", queue_token, queue_rank(0 起), queue_total, retry_after_ms`；`POST /api/queue-status {queue_token, zone_id}` 回同形状，直到 code 0（带 gate 令牌）或 410 `queue_token_expired` / `missing_queue_token`）
- tables: none（login.yaml `Queue` 段：Enabled 默认 false、AdmitTTL 60s、QueueEntryTTL 1h、DefaultRetryAfterMs 2000）
- depends on: gateway-assign-gate、login-queue-dispatcher、gateway-zone-directory
- behavior: 决策顺序：带 queue_token → 验 HMAC 后查状态（验不过 → 410）；账号已有在线会话 → 绕过队列直接签；队列关 → 直接签；有空位且队列为空 → Lua 原子占位后直接签；否则入队回 100。重入 / 轮询时被放行的条目在**取走时**才签 gate 令牌（TTL 从客户端拿到时起算）。客户端：`queue_source=="login"` 时保存 token 改调 /queue-status，展示「排队中 rank+1/total」，410 时清 token 从 assign-gate 重来；带 token 的 assign-gate 与 queue-status 都绕过网关限流。
- internal: Redis：`queue:zone:{z}` ZSET、`admitted:zone:{z}`、`admit:{queueId}`（AdmitSlot，只存选好的 gate，不存令牌）、`queue:meta:{id}`、`queue:token:{token}`；queue_token 为 HMAC 签名不透明串（支持多密钥轮换验证）；queue-status 必须路由回签发 token 的那个 zone 的 login 实例。Java 版需要：xm-login（或 xm-gateway）内的 Redisson 队列 + 令牌签名 + HTTP 端点；Java 版 Redis 键走 RedisKeys（`xm:` 前缀）。
- java: missing — AssignGateResponse 注释「排队（100 / 410）与限流（429）不在本批」，带 queue_token 的请求按首次处理；无 /api/queue-status。
- size: L
- robot: robot.stress-*.yaml / robot.stress-3zone-z*.yaml / robot.smoke-5k-z1.yaml（mmorpg robot http_assign_gate.go 自动轮询）；客户端 GameClient.EnterZone
- hazards: ① Enqueue 不幂等：无 token 重试会在队尾再插一条，旧条目成孤儿直到 TTL。② 重连绕过队列只看「账号有在线会话」，可被用来插队（需先有会话）。③ 快速通道必须原子占位，否则开服洪峰并发读到同一个 free>0 会超发（已修，Java 照做）。④ 网关准入缓存对轮询也生效：维护期间排队者收到 503 而非 410，客户端按错误退出。

### login-queue-dispatcher — 排队容量计算与领导者放行
- mmorpg: go/login/internal/logic/pkg/loginqueue/{capacity,dispatcher,metrics}.go、queue.go（PopAdmit / AdmittedCount / TryReserveFastPathSlot）
- client messages: none（效果体现为 queue_rank 递减、最终 code 0）
- tables: none（login.yaml `Queue.ZoneCapacityOverride`、`SoftCapMultiplier` 1.5、`DispatchInterval` 1s、`DispatcherLockTTL` 30s）
- depends on: login-queue、节点目录（各 gate 在线人数）
- behavior: free = max(0, cap − online − admitted)；cap 取 zone 覆盖值，否则 floor(max(online,1) × SoftCapMultiplier)；zone 内没有 gate → 报错不入队（队列没有下游是黑洞）。每秒只有一个 login 副本（Redis 锁选主，TTL/3 续期）对「队列非空或有已放行条目」的 zone 做 ZPOPMIN，给弹出的条目选 gate 写 `admit:{id}`（TTL 60s，过期未取视为放弃）；选 gate 失败把条目放回队头。
- internal: Redis 分布式锁 + 心跳续期；丢锁取消循环，其它副本约一个 TTL 内接手。Java 版可用 Redisson RLock / 节点号租约同款「SET NX PX + 值校验续期」，放行循环跑在单独调度线程。
- java: missing
- size: M
- robot: robot.stress-3zone-z*.yaml、robot.smoke-5k-z1.yaml（开服洪峰）
- hazards: ① 没配 ZoneCapacityOverride 时 cap 随在线人数按 1.5 倍几何增长，新区（online=0）每轮只放 1 人，基本等于没有上限且起步极慢——生产必须配覆盖值。② admitted 集合依赖条目被取走 / 过期清理，清理漏掉会永久吃掉名额。③ 选主锁过期与 GC 停顿叠加时可能短暂双主（ZPOPMIN 原子，最坏是顺序不公平，不会重复放行）。

### gateway-rate-limit — 开服限流（Bucket4j 分区桶 / IP 桶 / 账号冷却 / 分波开放）
- mmorpg: java/gateway_node/.../ratelimit/{AssignGateRateLimiter,RateLimitConfig,RateLimitProperties,RateLimitDecision,WaveSchedule,ClientIpResolver}.java；docs/design/open-server-rate-limit-design.md
- client messages: none（HTTP：assign-gate / login 回 `code=100, queue_source="ratelimit", retry_after_ms, queue_pos`（login 版无 queue_source）或 `code=429, error/message = IP_RATE_LIMIT | ACCOUNT_COOLDOWN`）
- tables: none（`gate.rate-limit.*`：enabled 默认 false、zone-default-rps 500 / burst 1000、zone-overrides、ip-rps / ip-burst、account-cooldown-ms 5000、trusted-proxies、wave.schedule[{offset-sec, allow-zones(-1=全部)}]）
- depends on: gateway-assign-gate、gateway-http-login
- behavior: 依次判：① 分波：zone 不在当前波次 → 100（retry_after = 距开放秒数 × 1000，永不开放给 3600s）；② zone 令牌桶（Redis 分布式）空 → 100（retry_after = 重填等待）；③ IP 桶空 → 429 `IP_RATE_LIMIT`；④ 同账号冷却（按端点分 scope：login / assign，进程内）→ 429 `ACCOUNT_COOLDOWN`。带 queue_token 的 assign-gate、queue-status、refresh-token 不限流。Redis 异常 fail-open 放行。客户端：100 + ratelimit 显示「服务器繁忙」并按 retry_after 重试，429 报错退出。
- internal: Bucket4j-redis（Lettuce 独立连接），桶键 `rl:zone:{z}` / `rl:ip:{ip}`，必须给过期策略（否则每个 IP 一条永久键）；客户端 IP 只在 socket 对端属于可信代理网段时才从 X-Forwarded-For 右往左剥可信跳，不做 DNS 解析。Java 版选型：Sentinel（tech-stack.md 已列「后续批次」）或 Redisson RRateLimiter；分波与 IP 解析自写。
- java: missing — xm-gateway 无任何限流；gate 侧按消息号限频（MessageLimiter）已做，但那是 TCP 消息层，不是这里。
- size: M
- robot: robot.stress-*.yaml（开服洪峰，Bucket4j 路径）
- hazards: ① `queue-timeout-ms` 与 code 101 QUEUE_TIMEOUT 只有定义、从不产生（死配置；客户端 / LoginResponse 文档仍列 101）。② 分波基线缺省为网关进程启动时刻，多副本 / 重启会让各副本波次不同步——生产必须配 start-epoch-sec。③ 账号冷却是进程内的，多副本下等于 N 倍窗口。④ 「login 成功紧接着 assign-gate」曾因共用冷却键必 429，已按端点分 scope，Java 照做。⑤ IP 桶键随客户端 IP 无界增长（靠 1h 空闲 TTL 收）。

### gate-drain — gate 排空（计划内缩容不卡玩家）
- mmorpg: go/login/internal/logic/pkg/loginqueue/{gatedrain,gatedrain_monitor}.go；GatePicker 的 FilterDrainingGates；Agones 侧见 agones-gameserver-lifecycle（battle 的 drain 标签）
- client messages: none（被排空的 gate 不再出现在 assign-gate 结果里；已在上面的玩家不被踢）
- tables: none
- depends on: gateway-assign-gate
- behavior: 运维把某 gate 标为 draining（Redis 键带 TTL，值为标记时刻）→ 新玩家不再分到它；全部 gate 都 draining 时忽略标记照常分配并告警；监视器判定在线降到阈值以下或超时后写 `gate:{id}:drained` 信号，由缩容脚本 / 运维决定何时下线，**不自动踢人**（现有踢人原语会弹「账号在别处登录」，对计划内维护是误导）。
- internal: Redis 标记 + 监视循环。Java 版需要：一个设置 / 清除排空的入口（运维接口或 Nacos 配置）、gate 进程把排空状态写进 GateNodeInfo.draining（或 gateway 读独立键）、drained 判定。
- java: partial — GatePicker 已按 `GateNodeInfo.draining` 过滤并在全排空时忽略（与 mmorpg 同向），但没有任何代码会把 draining 置真（xm-api node_directory.proto 有字段，xm-gate 从不写）。
- size: S
- robot: none
- hazards: 标记带 TTL 是有意的 fail-safe（标记人挂了容量不永久蒸发），Java 若放进 GateNodeInfo 由 gate 自己上报，需另想「谁、怎么撤销」；缺少「服务器维护、为你切换接入点」类 tip 码，若将来要主动迁移玩家需先改契约。

### gateway-http-login — HTTP 登录 `/api/login`（连 gate 之前完成认证、拿 access / refresh token）
- mmorpg: java/gateway_node/.../controller/LoginController.java、service/LoginService.java、dto/Login{Request,Response}.java、grpc/LoginRpcClient.java（`loginpb.ClientPlayerLogin/Login`，手写 protobuf 编解码）；go/login clientplayerlogin（path="new" 计数，deprecation.go）
- client messages: none（HTTP `POST /api/login {zone_id, account, password, auth_type, auth_token, device_id}` → `{code, message, retry_after_ms, queue_pos, players[{player_id,name,level}], access_token, refresh_token, access_token_expire, refresh_token_expire}`）；之后 TCP `48 Login` 用 `auth_type="access_token"`
- tables: none
- depends on: 账号认证 provider（password / satoken / wechat / qq / access_token，在 go/login）、access / refresh token 签发（go/login）、gateway-rate-limit、satoken-auth-service
- behavior: code 0 成功；100 限流排队（客户端显示「前方约 queue_pos 人」后重试）；101 队列超时（从不产生）；401 认证失败（login 回任何业务错误都映射 401，message 带上游文案；非 UNAVAILABLE 的 gRPC 错也是 401）；429 限流；500 login 不可用 / 内部错误。三方认证时以 `tok:<authToken.hashCode 十六进制>` 作冷却键。**Unity 客户端 `GameClient.EnterZone` 第 1 步固定调它（auth_type="password"），code≠0 直接报错退出**；随后 TCP Login 优先用 access_token、无 token 时回落口令。
- internal: 按 zone 钉 login 实例的 gRPC，不重试。Java 版需要：xm-gateway 增加 `/api/login`，经 Dubbo 调 xm-login 的认证 + 发 token 接口（ClientMessageService 是会话型接口，需另开类型化接口）；xm-login 需要 access / refresh token 存储（Redis）与 TCP Login 的 `access_token` 认证类型。
- java: missing — Java 版只有 TCP 48 Login 的开发口令认证（PARITY「登录」行：access token 待做）；xm-gateway 无 /api/login。**这是 Java 版接 Unity 客户端的第一道缺口**。
- size: M（gateway 端点 + login token 发放 / 校验；三方 provider 另计）
- robot: robot.e2e-http.yaml（use_http_login: true）、robot.stress-*.yaml / robot.smoke-5k-z1.yaml（use_http_login）；客户端 GameClient.EnterZone
- hazards: ① `players[].level` 永远是 0：手写解析器只取 player_id(1) 与 name(5)，网关 DTO 却声明了 level。② 认证失败一律 401 且透传上游错误文案，会把「账号不存在」与「密码错误」区分暴露给攻击者（取决于 login 文案）。③ `effectiveAccount` 用 `String.hashCode` 做冷却键，碰撞容易（只影响冷却误伤）。④ HTTP Login 是否会在 login 侧创建「登录会话」与 TCP Login 的会话语义要定清楚（mmorpg 靠 path=legacy/new 区分并计划下线 legacy 路径）。

### gateway-refresh-token — 刷新令牌 `/api/refresh-token`
- mmorpg: java/gateway_node/.../controller/RefreshTokenController.java、service/RefreshTokenService.java、dto/RefreshToken{Request,Response}.java；go/login RefreshToken；客户端另有 TCP 一次性刷新（MessageIds.RefreshToken，GameClient.MaybeRefreshToken）
- client messages: none（HTTP `POST /api/refresh-token {refresh_token}` → `{code 0|401|500, message, access_token, refresh_token, access_token_expire, refresh_token_expire}`）；TCP 侧有同名消息（属 login 区域）
- tables: none
- depends on: gateway-http-login、login 侧 token 存储
- behavior: refresh_token 空 → 401 `empty_refresh_token`；成功即轮换（旧 refresh 作废，一次性）；上游业务错 → 401；login 不可用 → 500 `login_unavailable`。不限流。
- internal: 网关不重试（重试会拿已作废的旧 token 再打一次，把会话烧掉）；token 不带 zone，所以在全部 login 实例间轮询。
- java: missing
- size: S
- robot: robot（http_login.go httpRefreshToken，login.go 优先走 HTTP 刷新）
- hazards: 轮换「先删旧再发新」若在响应丢失时发生，客户端只能完整重登——这是设计取舍，Java 照做即可；Java 若做 token，要决定 token 是否带 zone（mmorpg 的轮询路由依赖它不带 zone）。

### gateway-login-rpc-routing — 网关到 login 的按区路由与发现
- mmorpg: java/gateway_node/.../grpc/{LoginRpcClient,LoginNodeDiscovery}.java、config/LoginGrpcProperties.java、etcd/GateWatcher.fetchAllLoginNodes
- client messages: none
- tables: none
- depends on: gateway-http-login、login-queue
- behavior: 客户端不可见。Login / AssignGate / QueryQueueStatus 必须落到该 zone 的 login 实例（每个 login 只看本区 gate 目录；轮询会让 2/3 的 assign-gate 回「no gate available」），查不到即报错、绝不跨区兜底；RefreshToken 在全体实例间轮询。
- internal: 静态 `<zoneId>=host:port` + 每 5s 从 etcd `LoginNodeService.rpc/` 拉动态池（同区多实例轮询，失败保留 last-known-good）；gRPC 关空闲 keepalive（grpc-go 默认策略会以 too_many_pings 踢断）；每次尝试单独算 deadline。
- java: not_applicable — Java 版 gateway 自己读 Redis gate 目录签令牌，不调 login；xm-login 不按 zone 分片（单 zone 竖切）。将来 /api/login、排队落到 xm-login 时用 Dubbo + Nacos（或 local 直连）路由；多 zone 时需要 Dubbo 按 zone 选提供者（tag 路由 / group），不要照搬 etcd 轮询。
- size: S
- robot: none
- hazards: 手写 protobuf 编解码绕开生成代码，proto 字段号一改就静默错位（Java 版用同步来的生成类即可避免）。

### gateway-announcement — 登录公告
- mmorpg: java/gateway_node/.../controller/{AnnouncementController,AdminAnnouncementController}.java、service/AnnouncementService.java、entity/Announcement.java、repository/AnnouncementRepository.java
- client messages: none（HTTP `GET /api/announcement` → `{"items":[{id,title,content,type,start_time?,end_time?}]}`；运维 `GET/POST /admin/announcements`、`DELETE /admin/announcements/{id}`）
- tables: none（MySQL `announcement`：title、content、type notice / maintenance / update、start_time、end_time、created_at）
- depends on: admin-api-auth
- behavior: 只返回生效中的（start 为空或 ≤ now，且 end 为空或 ≥ now），按 created_at 倒序；时间字段为 Unix 秒，为空不输出。Unity 客户端选服界面（QdaoServerSelectView）拉取展示，失败只打警告。
- internal: JPA 直查，无缓存。Java 版：xm-gateway 加一张表（`xm_java` 库）或 Nacos 配置 + 只读端点 + 运维接口。
- java: missing
- size: S
- robot: none
- hazards: ① start/end 用 `toEpochSecond(ZoneOffset.UTC)` 而生效判断用 JVM 本地 `LocalDateTime.now()`：JVM 非 UTC 时下发的时间戳与实际生效窗口错开整数小时。② 运维 create 直接 `save(entity)`，请求体带 id 时会覆盖已有公告（merge 语义）。③ 每次请求打 DB。

### gateway-zone-whitelist — 区服白名单（运维接口，尚未被准入消费）
- mmorpg: java/gateway_node/.../controller/AdminWhitelistController.java、repository/ZoneWhitelistRepository.java、entity/ZoneWhitelist.java、schema.sql `zone_whitelist`
- client messages: none（运维 `GET /admin/whitelist/{zoneId}`、`POST /admin/whitelist {zone_id, account_id, note}`、`DELETE /admin/whitelist/{zoneId}/{accountId}`）
- tables: none（MySQL `zone_whitelist`，唯一键 (zone_id, account_id)）
- depends on: admin-api-auth、gateway-zone-directory
- behavior: add 幂等（ODKU，note 以最后一次为准，忽略请求体 id），缺字段 400；remove 不存在也 204。**准入不读它**：AssignGateService 注释说明 PREVIEW 区一律 503 `zone_not_open`，白名单的 `account_id BIGINT` 对不上现有字符串账号体系，「开放前先修表结构」。
- internal: ODKU + 同事务回读 + InnoDB 1213 有界重试。
- java: missing
- size: S
- robot: none
- hazards: 功能半成品——表与接口存在但无消费者；Java 版若做 PREVIEW 区内测白名单，应直接按 Java 的账号主键设计，并在 assign-gate 准入里消费，否则不必移植。

### gateway-cdn-sign — CDN 资源签名 URL
- mmorpg: java/gateway_node/.../controller/CdnSignController.java、service/CdnSignService.java、dto/CdnSign{Request,Response}.java、config/CdnProperties.java
- client messages: none（HTTP `POST /api/cdn-sign {resource_path}` → `{signed_url, expire_at}`）
- tables: none（`cdn.base-url`、`cdn.sign-ttl-seconds` 3600）
- depends on: none
- behavior: signed_url = base_url + resource_path + `?expire=<unix>&sign=<hex(HMAC-SHA256(secret, path|expire))>`。Unity 客户端当前不调用（GatewayHttpClient 无此方法）。
- internal: 纯计算，无存储。
- java: missing
- size: S
- robot: none
- hazards: ① 无鉴权、不校验路径：任何人都能为任意路径拿到有效签名，等于签名口令失效。② 复用 `gate.token-secret`（gate 握手令牌密钥）做 CDN 签名——密钥混用；Java 版应独立密钥（环境变量注入）且要求已登录（access_token）才签。③ resource_path 原样拼进 URL，未做规范化 / 编码。

### gateway-hotfix-check — 客户端热更检查（桩）
- mmorpg: java/gateway_node/.../controller/HotfixCheckController.java、service/HotfixCheckService.java、dto/HotfixCheck{Request,Response}.java
- client messages: none（HTTP `POST /api/hotfix-check {client_version, platform}` → `{need_update, force_update, patch_url?, latest_version, changelog?}`）
- tables: none
- depends on: none
- behavior: 恒回 `need_update=false, force_update=false, latest_version="1.0.0"`（TODO：接配置中心或 DB）。Unity 客户端当前不调用。
- internal: 无。
- java: missing
- size: S
- robot: none
- hazards: 桩实现；Java 版移植时应顺带定版本比较规则（语义化版本、按平台）与数据源（Nacos 配置即可），否则不值得移植一个恒假的端点。

### gateway-ops-hardening — 网关运维面（K8s 探针分组、结构化日志、构建信息）
- mmorpg: java/gateway_node/src/main/resources/application.yaml（management.endpoint.health.group.liveness / readiness、logging.structured.format.console=logstash）、pom.xml（build-info 注入 mmorpg.build.version / commit）、deploy/k8s/manifests/java-svc/gateway.yaml（startup / readiness / liveness 探针）
- client messages: none
- tables: none
- depends on: deploy-k8s-zone-orchestration、observability-logs-alerts
- behavior: 运维可见：`/actuator/health/liveness` 只含 livenessState、`/actuator/health/readiness` 只含 readinessState（**不含 DB / Redis**，避免共享依赖一挂全体 NotReady + 重启风暴）；`/actuator/health` 保留复合健康给人看；`/actuator/info` 带构建版本与提交；控制台一行一条 logstash 风格 JSON（与 go-zero 同口径，Alloy 采进 Loki）。
- internal: Spring Boot 配置 + Maven build-info 目标。
- java: partial — 五个进程都有 Actuator + Prometheus（独立管理端口、默认只绑本机，architecture.md §11），但没有 liveness / readiness 分组、没有结构化 JSON 日志、没有 build-info；也没有 xm-gate / xm-scene 的「就绪」语义（例如 gate 节点号租约未拿到时应 NotReady）。
- size: S
- robot: none
- hazards: mmorpg 的 build-info 版本覆盖依赖 Spring Boot 插件的属性写入顺序（pom 注释：升级 Boot 后可能静默失效，需回归）；Java 版的 gate / scene 不是 Web 进程，探针要挂在管理端口上，K8s 清单需对应端口（18103 / 18104）且不能只绑 127.0.0.1。

### satoken-auth-service — 账号认证服务（Sa-Token + JustAuth 三方登录）
- mmorpg: java/springboot_satoken_auth_starter/**（AuthController、OauthService、UserService、SmsService、JustAuthProperties、schema.sql、docker-compose.yml）；go/login/internal/logic/pkg/auth/providers.go（SaTokenProvider）、svc/auth_init.go、config.go `AuthProviders.SaToken`
- client messages: none（HTTP：`GET /auth/login/{provider}` 跳三方授权页；`GET /auth/callback/{provider}` 回 `{ok, account, auth_type:"satoken", token_name, token_value, login_type, redis_key, expires_in_seconds:604800}`；`GET|POST /auth/dev-login`；`GET /auth/logout?token=|account=`）。拿到的 token_value 作为 `/api/login` 或 TCP Login 的 `auth_type="satoken", auth_token=<value>`
- tables: none（自有库 users / user_password / user_phone / user_oauth）
- depends on: gateway-http-login
- behavior: provider 支持 github / wechat（开放平台）/ qq，未配置 client-id 的 provider 抛错；优先 unionid、否则 openid；首次登录建 users + user_oauth；Sa-Token 登录内部 userId，并手工写 Redis `<token_name>:login:token:<token>` = loginId（TTL 7 天）。go/login 的 SaTokenProvider 按 `{TokenName}:{LoginType}:token:{v}` GET 出 loginId 作为账号。
- internal: 独立 Spring Boot 进程（端口 18080，默认 H2 内存库），Redis 是与 go/login 之间唯一的契约面。Java 版：账号体系在 xm-player-store（account 表），可直接在 xm-login 内实现 satoken 式「外部认证令牌 → 账号」provider 抽象，或把本服务当外部组件复用（它本身已是 Java）；**不要**把它并进 Java 版 gateway。
- java: missing — Java 版只有 TCP Login 的开发口令认证（`XM_LOGIN_DEV_PASSWORD`），无 provider 抽象、无三方登录。
- size: M（provider 抽象 + satoken 校验；三方 OAuth 各 S）
- robot: robot（auth_type=satoken 的配置项，dev-login 取 token）
- hazards: ① `/auth/dev-login` 无任何开关，任何人可为任意账号签 token——部署即认证绕过（Java 版必须按 profile 关闭）。② 新用户 id = `System.currentTimeMillis()`，并发首登撞主键。③ 默认配置开 H2 console、`sa-token.debug=true`、`show-sql=true`。④ OAuth state 校验依赖 JustAuth 进程内缓存，多副本下回调落到另一副本会失败。⑤ SmsService / UserService 的手机号、游客、口令能力没有任何端点调用（死代码）；`getOrCreateByPhone` 实际从不查已有用户。⑥ logout 只删手工写的那把键，按 account 登出不删手工键。⑦ production_application.properties 里 `justauth.weixin.appId` 键名与代码读取的 `justauth.wechat.client-id` 不符，配了也不生效。

### config-node — 配置表查询与热加载服务
- mmorpg: java/config_node/src/main/java/com/game/config/{ConfigNodeApplication,controller/ReloadController,controller/TableController,service/TableService}.java（`com/game/table/**` 是导表器生成物）
- client messages: none（运维 HTTP，端口 8090：`GET /api/table/{tableName}/{id}`、`GET /api/table/status`、`POST /api/reload`）
- tables: 全部配置表（JSON 格式，目录 `config.table-dir` 默认 `./config`）
- depends on: none
- behavior: 启动加载全部表；reload 重新加载并递增 loadVersion、回 `{status, loadVersion, lastLoadTimeMs}`；按表名 + id 反射查一行回 JSON，查不到 404。定位为「GM 工具 / 运维看板 / 配表校验」。
- internal: 反射调 `com.game.table.<Name>TableManager.getInstance().getById(int)`，JsonFormat 输出。Java 版对应能力：xm-table 的 `ConfigTables`（编译期生成、加载时校验 manifest / 主键 / 外键）是库而不是服务；运行期热加载两版都没有对外的正式方案。
- java: missing — 无独立配置查询服务、无热加载入口（xm-table 只在进程启动时加载）。是否移植待定：若做，宜作为 xm-gateway / 独立管理进程上受鉴权的只读端点，热加载要整表集原子替换。
- size: S
- robot: none
- hazards: ① **查询基本是坏的**：生成的管理器只有 `findById(int)`；`getById` 只在带名为 Id 的二级索引的表（如 ActivitySchedule）上存在且返回 List，其余表反射抛 NoSuchMethodException 被吞成 null → 恒 404。② `/api/reload` 无鉴权。③ reload 逐表替换快照，期间并发读看到新旧表混合（跨表外键可能临时不一致）；加载失败时前面的表已换新、后面的还是旧的。④ 用 JSON 而非 .pb 加载（`loadTables(dir)` 缺省 JSON），与 C++ / Go 运行时用的二进制不是同一份产物。⑤ id 固定 int，uint64 主键表无法查询。

### kafka-client-infra — Kafka 生产 / 消费基础封装
- mmorpg: cpp/libs/engine/infra/messaging/kafka/{kafka_manager,kafka_producer,kafka_producer_policy,kafka_consumer,kafka_partition_assign_policy,kafka_proto_decoder}.{h,cpp}；go/shared/kafkautil（Go 侧，topic 预建等）
- client messages: none
- tables: none（bin/etc/base_deploy_config.yaml `Kafka.*`；deploy/env/kafka.{dev,prod-like}.env）
- depends on: none
- behavior: 运维可见：broker 不可用时节点照常启动（后台重连），但若 topic 分区数与契约不符则拒绝启动；生产失败日志限流（窗口内前 N 条逐条、其余计数并在窗口过期 / 重建 / 析构时补报）。
- internal: 生产者：幂等（acks=all）、本地队列满先 poll(0) 再重试一次（不阻塞主循环）、ERR__FATAL 时按最小间隔重建实例、purge 回执计数但不再触发重建、停机有界 flush；消费者：`session.timeout.ms=45000`、`max.poll.interval.ms=900000`、可选显式分区 assign + 从末尾开始 + 不提交 offset（控制面用）、后台线程 poll 并把解码后的回调投递回节点 EventLoop；`DecodeKafkaProtoPayload<T>` 空载荷 / 解析失败只记日志丢弃。Java 版：spring-kafka（tech-stack 已选），等价配置为 `enable.idempotence=true`、手动 `assign()` + `seekToEnd()` + `enable.auto.commit=false`，回调投递到 scene 逻辑线程 / gate EventLoop（§3 线程所有权）。
- java: partial（2026-10-03，批次 2.3a）— `xm-audit`（topic 规格、启动期核对：缺就建、分区不符拒绝启动、消费方校正配置）+ scene `AuditPipeline`（专用线程、幂等生产者、兜底日志）+ xm-data `ConsumerLoop`（落库成功才提交、可恢复故障暂停重试、毒丸隔离）。用官方 kafka-clients，不用 spring-kafka 容器。生产者进入致命状态时丢弃、由 30 秒一次的核对重建。尚缺：生产失败日志限流（现在每条失败都打 WARN）；通用消费封装（把解码后的回调投递到 scene 逻辑线程 / gate EventLoop，以及控制面用的显式 assign + seekToEnd + 不提交模式），随首个需要它的批次。
- size: M
- robot: none
- hazards: ① broker 自动建 topic（`auto.create.topics.enable` + `num.partitions=1`）抢在 topic-init 之前会建出 1 分区的 topic，显式分区的消费者永远收不到——必须启动期核对分区数（mmorpg 只在「查到且不等」时拒绝启动，查不到只告警）。② 共享分区上提交 offset 会互相覆盖，所以控制面消费者禁止提交。③ C++ 的 900s max.poll.interval 掩盖了主循环卡顿，Java 若用 group 消费需另配。

### kafka-control-plane-bus — 控制面命令通道（gate-cmd / scene-cmd 定分区 topic）
- mmorpg: proto/contracts/kafka/{gate_command,gate_event,scene_command,player_event,match_event}.proto；go/shared/kafkacmd/command_topic.go；cpp/libs/engine/core/node/system/node/node_command_topic.h；cpp/nodes/gate/handler/event/gate_kafka_command_router.cpp、cpp/nodes/scene/handler/event/scene_kafka_command_router.cpp；deploy/docker-compose.yml `kafka-topic-init`、deploy/k8s/manifests/infra/kafka-topic-init.yaml；docs/design/control-plane-topic-partitioning-20260908.md
- client messages: none 直接（它承载的事件最终变成客户端可见推送：RoutePlayer / Kick / BindSession / RedirectToGate / PushToPlayer / BroadcastToPlayers / BroadcastToScene / BroadcastToAll / Bind/UnbindBattle 等，各自属 gate / 社交 / 战斗区域）
- tables: none
- depends on: kafka-client-infra
- behavior: 运维可见：topic `gate-cmd_g<N>` / `scene-cmd_g<N>` 固定 256 分区、retention 1h；分区数不可原地扩，改分区要升代次 N 建新 topic；topic-init 校验分区数与 retention 不符即失败。
- internal: 一个节点类型一个 topic，**partition = node_id % P**，生产者显式指定分区、消费者只 assign 自己的分区并从末尾读；`GateCommand{target_gate_id, target_instance_id(启动时生成的 uuid), event_id, payload}`，消费者按 instance_id 丢弃发给前任的命令（解决 node_id 回收导致的错投）。Go 服务（friend / chat / guild / login / scene_manager / match）经它给在线玩家推 S2C，at-most-once、无回执、无离线补推。Java 版需要：一条「任意服务 → 玩家所在 gate」的推送 / 控制通道。Java 目前已有的等价物只有 gate↔scene 节点链路与 Redis pub/sub（顶号让出），没有服务 → gate 的通道；可选 Kafka（照此分区契约）或 Dubbo 直调 gate（需 gate 暴露提供者 + 按会话定位 gate）。
- java: missing
- size: L
- robot: chat_smoke / friend_smoke / guild_smoke / team_smoke / battle_smoke（间接依赖推送）
- hazards: ① 老方案「一节点一 topic + group」在 node_id 回收时会让冻结的前任吞掉继任者的命令（登录绑定 / 踢人静默消失）——Java 若用 Kafka 不能用按节点 group 订阅。② 每个分区坐几百个节点，消费者读到的绝大多数是别人的命令，读放大随部署规模线性增长。③ 推送 at-most-once：Kafka 抖动期间的好友 / 聊天推送直接丢，客户端需靠拉取补齐。

### kafka-audit-pipeline — 审计事件管线（交易流水 / 玩家快照 / 异常告警 topic）
- mmorpg: deploy/docker-compose.yml `kafka-topic-init`（`transaction_log_topic_g<N>` 6 分区、`player_snapshot_topic_g<N>` 3 分区，retention 30 天；`game-events` 1 分区 1h）；cpp/libs/modules/transaction_log/anomaly_detector.cpp（`anomaly_alert_topic`）；go/data_service/internal/kafka/consumer.go、etc/data_service.yaml
- client messages: none
- tables: none
- depends on: kafka-client-infra
- behavior: 运维可见：资产变更流水与玩家快照异步落 data_service 库（流水按 tx_id 主键 INSERT IGNORE 幂等、快照按 snapshot_guid 去重），供客服查询 / 回档；异常检测命中发告警 topic。topic 代次 g<N> 与 data_service 配置对齐，分区数不可原地扩。
- internal: 生产方 C++ scene，消费方 go/data_service（攒批落库）。Java 版需要：scene 侧在资产变更点产出流水 / 快照事件（Kafka 或直接写库）+ 落库消费者。
- java: partial（2026-10-03，批次 2.3a / 2.3b）— 资产流水 topic `xm-transaction-log-g<N>`（6 分区、30 天、不限大小）+ xm-data 落 `transaction_log`；玩家快照 topic `xm-player-snapshot-g<N>`（3 分区）+ xm-data 落 `player_snapshot`；异常告警改为日志 + 指标（2.3c 已做，无 Kafka topic）。
- size: M（不含各资产系统本身；业务归 data_service / 资产区域，这里只登记基础管线）
- robot: robot.currency-crash.yaml（currency_crash_window_scenario.go）、robot.data_stress.yaml
- hazards: ① retention 必须逐 topic 显式声明且大于消费者最长滞后（C++ 900s poll 间隔），topic-init 会校验；继承 broker 默认（compose 里 30 min）会在消费者积压时丢审计数据。② 快照「先查后插」非原子，并发同 guid 依赖唯一键兜底。

### redis-hot-cache-save — Redis 热缓存与带归属围栏的存盘
- mmorpg: cpp/libs/engine/infra/storage/redis_client/redis_client.{h,cpp}（MessageSyncRedisClient / MessageAsyncClient，`kSaveAndMarkLuaScript`、`kSaveIfGuardLuaScript`）、cpp/libs/services/scene/core/system/redis.cpp（SetSaveRejectedCallback → PlayerLifecycleSystem::HandlePlayerSaveRejected）；下游 go/db `db_task_zone_<z>` 落 MySQL
- client messages: none
- tables: none
- depends on: 节点号 / owner_epoch 铸造（player_locator）、kafka-client-infra（db_task）
- behavior: 客户端不可见（体现为玩家数据不丢、被顶号的旧节点存盘被拒）。
- internal: 玩家 protobuf 以 `<全名>:<id>` 存 Redis；带围栏存盘用 Lua 一次完成「比对 `player:{id}:owner_epoch` → SET 数据 → SADD dirty_keys_set」，guard 键缺失时补种调用方期望值（Redis 被清空时不把全服在线玩家判成已废黜）；异步客户端有待重试队列、重连后重发、加载失败回调。Java 版走另一条路：MyBatis 直写 MySQL `xm_java.player`，`owner_epoch` + `owner_released` + 租约围栏（architecture.md §7），没有 Redis 热缓存层。
- java: partial — 围栏语义已用 Java 方式实现（离场 / 断线 / 停服 / 被接管写回，带 epoch 围栏）；**缺周期存盘**（architecture.md §10：进程被 kill 丢本次在线增量），也无 Redis 热缓存（目前不需要）。
- size: M（Java 版只需补周期 / 脏标记存盘，不移植 Redis 层）
- robot: robot.currency-crash.yaml（崩溃窗口不丢资产）
- hazards: ① 本检出范围内（go/db、go/data_service、cpp）找不到 `dirty_keys_set` 的读者——若下游确实不读，这个集合只增不减。② 补种 guard 的残余风险已在注释中接受：Redis 清空同时有僵尸节点抢先存盘会把合法持有者挤掉。③ 兼容窗口 epoch==0 走无围栏 Save。

### agones-gameserver-lifecycle — Agones GameServer 生命周期（scene / battle 动态房间）
- mmorpg: cpp/libs/engine/infra/agones/{agones_gameserver_lifecycle,agones_rest_client,agones_gameserver_status}.{h,cpp}；cpp/nodes/scene/main.cpp、battle 节点；deploy/k8s/manifests/go-svc/scene-manager-agones-rbac.yaml；k8s_deploy.ps1 `-SceneOrchestrator agones`、`-AgonesAutoscale`
- client messages: none
- tables: none
- depends on: deploy-k8s-zone-orchestration
- behavior: 运维可见：进程起来后有界退避 POST /ready（30 次，≈1 分钟，失败留在 Starting、拒绝一切创建）；第一个单元（scene 的场景 / battle 的房间）**创建之前**必须拿到 Allocation 许可（POST /allocate，阻塞等 3s，拿不到即拒绝创建，fail-closed）；最后一个单元销毁后回 Ready 由 Fleet 回收；每 2s POST /health；可选：读 `mmorpg.io/drain` 标签进入排空（拒绝新单元、清空后回 Ready），EventLoop 心跳超过 10s 不更新即停发 health 让 Agones 替换实例。停机不调 /shutdown（避免 SIGTERM 递归删 Pod）。
- internal: 状态机 Disabled / Starting / Ready / Allocating / Allocated / ReturningToReady / ShuttingDown / Stopped；HTTP 只在独立 worker 线程；许可对象覆盖「已放行、尚未建出」的在途创建；单元 key 幂等计数。Java 版：xm-scene 若上 Agones，需同等状态机（JDK HttpClient 调 sidecar REST），许可在场景逻辑线程之外获取、结果投递回逻辑线程。
- java: missing — 无 K8s / Agones 部署；Java scene 是常驻进程，场景在启动时按配置创建。
- size: L
- robot: none
- hazards: ① 等待方全部超时后 allocate 晚到成功会留下「Allocated 但零单元」，已加收口；但非阻塞调用方踢起的分配若调用方不再重试仍会空转占容量。② 非阻塞与阻塞两类调用方的标记若残留会造成 allocate / ready 活锁（注释里修过一轮）。③ drain 标签只能由 Fleet allocationOverflow 或 kubectl 打（SDK SetLabel 会加前缀），读取失败保持上次判定（不 fail-open）。

### agones-client-endpoint — 客户端可达地址自报（Agones / 集群外入口）
- mmorpg: cpp/libs/engine/infra/agones/agones_client_endpoint_source.{h,cpp}、agones_gameserver_status.{h,cpp}；node/system/node/client_endpoint.h（`CLIENT_ENDPOINT_SOURCE` / `CLIENT_ENDPOINT_HOST`）；proto `NodeInfo.client_endpoint = 11`；k8s_deploy.ps1 `-ClientEntryMode external`、`-GateServiceType NodePort|LoadBalancer`、zones.yaml `gateNodePortBase`
- client messages: none 直接（决定 assign-gate 回的 `gate_ip / gate_port` 与重定向 / 战斗分配给客户端的地址）
- tables: none
- depends on: gateway-assign-gate、deploy-k8s-zone-orchestration
- behavior: 运维可见：`CLIENT_ENDPOINT_SOURCE=agones` 时节点在发布目录前向本机 sidecar `GET /gameserver` 取 `status.address` + 名为 `client` 的端口；地址为空 / 无 client 端口 / sidecar 不可达按 200ms 起翻倍、封顶 2s 退避，30 次或 60s 预算先到者耗尽即 FATAL 退出由 Agones 重建 Pod；`CLIENT_ENDPOINT_HOST` 可覆盖 host（kind 填 127.0.0.1）。external 模式缺地址的节点被 login 跳过。非 Agones 时 NodePort 模式按 `gateNodePortBase + i` 给每个 gate 固定端口。
- internal: 纯构造期阻塞调用，不在运行中的 EventLoop 上；端口名 `client` 是与部署生成器的跨语言字符串契约。
- java: partial — 已有静态通告地址（`xm.advertise-host` + `XM_GATE_ADVERTISE_PORT`，写进 GateNodeInfo.client_host / client_port，PARITY「节点客户端可达地址」行）；没有 Agones sidecar 来源，也没有按部署模式生成通告地址的编排。
- size: S
- robot: none（k8s 冒烟走 robot_stress.ps1）
- hazards: ① Agones REST 网关字段名有 `object_meta` / `objectMeta` 两种写法、未设置的子消息输出 null，解析要两种都认。② 只靠次数上限会在 sidecar 可连不响应时拖到 ≈113s，所以另设总时间预算。③ Java 版的通告地址缺省 127.0.0.1，在容器里部署时若漏配会把回环地址发给外部客户端（mmorpg external 模式是缺地址即跳过节点）。

### deploy-local-compose — 本地 / 联调基础设施编排（docker compose）
- mmorpg: deploy/docker-compose.yml（Kafka KRaft 单节点 + kafka-topic-init + kafka-ui、Redis、6 节点 Redis Cluster + init、MySQL + mysql-init/*.sql、Nacos、etcd）、docker-compose.login-stack.yml（sandbox-mock + go login + Java gateway，Linux 容器跑登录链压测）、docker-compose.observability.yml（Loki / Alloy / Grafana）、docker-compose.tidb.yml（PD / TiKV / TiDB）、deploy/env/kafka.*.env、deploy/login-stack.linux/
- client messages: none
- tables: none
- depends on: none
- behavior: 开发者 / 运维可见：一条命令起齐依赖；topic-init 预建控制面与审计 topic 并校验分区 / retention；mysql-init 建各 zone 库与授权、gateway 表；MySQL healthcheck 后 Nacos 才起。
- internal: Kafka 资源压到极小（heap 128–256m、tmpfs 日志、broker 默认 retention 30 min）适配开发机。Java 版需要：MySQL（`xm_java` 库 + xm-player-schema.sql 初始化）、Redis（DB 12）、可选 Nacos（`nacos` profile）、后续 Kafka；tech-stack.md「测试」行写着「集成测试用仓库自带的 docker compose」，但仓库里**没有**任何 compose 文件。
- java: missing — README 前置条件是「本地 MySQL（3306）与 Redis（6379）」，启动靠 tools/local/start-slice.sh（裸进程，local profile，Dubbo 直连）。
- size: S
- robot: login-stack 用于 robot 1k/2k/5k 压测（backlog #221）
- hazards: ① 镜像多用 `latest`（kafka、redis、mysql、nacos），不可复现。② MySQL root 口令、Nacos auth token、appuser 口令明文写在 compose / application.yaml 里——Java 版规则要求秘密只从环境变量注入，照搬时改成 `.env`（不入库）。③ Kafka 开着自动建 topic、num.partitions=1，与「分区数契约」冲突，只靠 topic-init 先跑兜底。

### deploy-container-images — 服务镜像构建（Dockerfile 与构建信息）
- mmorpg: deploy/k8s/Dockerfile.java-svc（JDK 23 多阶段、`mvn package`、temurin JRE alpine、uid 10001 非 root、OCI label、`/app/BUILD_INFO`）、Dockerfile.go-svc、Dockerfile.cpp / Dockerfile.runtime（C++ 节点两条路径）、Dockerfile.robot、Dockerfile.sandbox-mock；tools/scripts/{java_svc_image,go_svc_image,k8s_image}.ps1
- client messages: none
- tables: none
- depends on: none
- behavior: 运维可见：每个镜像带 `org.opencontainers.image.{version,revision,created,source,title}` 与 `/app/BUILD_INFO`，`kubectl exec <pod> -- cat /app/BUILD_INFO` 可确认 Pod 跑的提交；基础镜像按 digest 钉死。
- internal: Java 镜像以服务目录为 build context，先 `dependency:go-offline` 缓存依赖层。Java 版需要：多模块 Maven 下每个进程模块（xm-gateway / xm-login / xm-scene-manager / xm-gate / xm-scene）一个可运行 jar 的镜像（可用一个参数化 Dockerfile 或 Spring Boot `build-image` / Jib），并把 `config-data/tables` 打进 scene / gate / login 镜像（`xm.table-dir`）。
- java: missing — 无任何 Dockerfile。
- size: S
- robot: none
- hazards: ① mmorpg Java 镜像用 JDK 23 构建，而 gateway_node pom 写 java.version 23、config_node 写 21——Java 版统一 21。② 两条 C++ 运行镜像路径运行层不等价（README 已警告）。③ Java 版 gate / scene 需要表数据与密钥（`XM_*` 环境变量）在镜像外注入，不能烤进镜像。

### deploy-k8s-zone-orchestration — K8s 开区编排（基础设施 + 服务清单 + 一键开 / 关区）
- mmorpg: deploy/k8s/manifests/infra/{etcd,kafka,kafka-topic-init,redis,redis-match-cluster,mysql,mysql-backup-cronjob,loki}.yaml、manifests/go-svc/*.yaml、manifests/java-svc/gateway.yaml、zones.{sample,ops-recommended,10zones}.yaml、kind-config.yaml、README.md、AGENTS.md、robot_stress.ps1；tools/scripts/k8s_deploy.ps1（dev_tools.ps1 `k8s-zone-up / k8s-all-up / k8s-zone-status / k8s-zone-down / infra-up`）；docs/ops/k8s-open-server-runbook.md
- client messages: none
- tables: none
- depends on: deploy-container-images、agones-client-endpoint、agones-gameserver-lifecycle、kafka-control-plane-bus
- behavior: 运维可见：按 zones.yaml（zoneId、各角色副本数、`gateNodePortBase`、scene 拆 world / instance 两池）为每个 zone 建 namespace 并部署 C++ 节点 + Go 服务 + Java gateway；infra 层 etcd 3 副本 StatefulSet、Kafka 单 broker StatefulSet + PVC、Redis / MySQL 单副本、MySQL 定时备份、可选 Loki；gate 对外用 LoadBalancer（托管云）或 NodePort + 外部 L4（自建）；`-ReleaseProfile staging|prod` 时校验不可变镜像 tag 并跑 release_preflight。Java gateway：2 副本、反亲和、PDB minAvailable 1、startup / readiness / liveness 探针、ConfigMap 挂 /app/config、`SPRING_PROFILES_ACTIVE=k8s`。
- internal: PowerShell 生成器把模板里的 PLACEHOLDER_IMAGE 等替换后 `kubectl apply`；告警规则 owner-epoch-alerts.yaml、scene-manager-alerts.yaml 与看板 JSON。Java 版需要：xm-gateway（无状态，可多副本）、xm-login、xm-scene-manager（Dubbo 提供者，Nacos 注册或 headless Service 直连）、xm-gate（对外 TCP + 通告地址）、xm-scene（链路端口仅集群内）的清单；Redis / MySQL / Nacos 的 infra；环境变量注入 `XM_GATE_TOKEN_SECRET / XM_NODE_LINK_SECRET / XM_DUBBO_SECRET / XM_MYSQL_PASSWORD`（Secret）；管理端口改绑 Pod IP 供 Prometheus 抓取与探针。
- java: missing — Java 版「当前部署方案」只有 tools/local/start-slice.sh 本机起 5 个进程；无任何 K8s 资产。
- size: L（Java 版只需 5 个进程 + 3 个依赖，不必移植 Agones / 多 shard 那部分）
- robot: deploy/k8s/robot_stress.ps1（集群内 robot 压测）
- hazards: ① manifests/go-svc/gateway.yaml 部署一个 Go `gateway`（端口 8080、`-f /app/etc/gateway.yaml`），但 go/ 下没有 gateway 服务，与 java-svc/gateway.yaml 同名 Service `gateway` 冲突——疑似遗留清单。② Java 版 Dubbo 把 127.* 视为无效绑定（architecture.md §4.1），容器里必须用 `DUBBO_IP_TO_BIND` 绑 Pod IP 并配 NetworkPolicy，否则 login / scene-manager 端口对整个集群开放（靠 XM_DUBBO_SECRET 兜底）。③ Java 版 gate 节点号租约与 scene 归属租约都依赖墙钟（§7 要求 NTP），K8s 节点时钟漂移需监控。④ Java 进程默认把管理端口绑 127.0.0.1，直接照搬探针会全部失败。

### release-packaging — 版本发布与制品管理
- mmorpg: .github/workflows/release.yml；tools/scripts/{publish_images,make_release,fetch_images,import_images,artifacts_retention,release_preflight}.ps1、lib/release_common.ps1；docs/design/release-packaging-standard-20260914.md、docs/ops/release-checklist.md；CHANGELOG.md
- client messages: none
- tables: none
- depends on: deploy-container-images
- behavior: 运维可见：快照轨（`g<sha12>`）/ 发布轨（`vX.Y.Z`，拒绝脏树）；cpp / go / java 三族镜像逐个 `docker save` 进仓库外制品根（`MMORPG_ARTIFACT_ROOT`），版本目录不可覆盖、先写 .tmp 再原子 rename，附 images-manifest.json / build-info.json / sha256sums / 分离符号；CHANGELOG 必须有 `## [x.y.z]` 段才能生成 release manifest；目标机先校验再离线导入。发布 workflow 手动触发，前置跑部署契约测试、核对外部模块、检查脏树。
- internal: 镜像 OCI revision 必须等于当前 commit；推 registry 只推用户指定的 dev registry（AI 不执行 push）。Java 版需要：版本号策略（目前恒 0.1.0-SNAPSHOT，PARITY「版本基线」按 mmorpg commit 记录）、`mvn` 打包 + 镜像 + CHANGELOG；Java 版 AGENTS.md 禁止 AI 执行 git push / tag，发布须由用户触发。
- java: missing — 无 CHANGELOG、无版本发布流程。
- size: M
- robot: none
- hazards: 批量 `docker save` 两个层链完全相同的镜像会丢一个（已改逐个 save）；release.yml 跑 pwsh 契约测试的口径照抄 deploy-config-tests.yml，两处要同步改。

### observability-logs-alerts — 日志采集、看板与告警
- mmorpg: deploy/docker-compose.observability.yml、deploy/observability/{alloy/config.alloy,loki/loki.yaml,grafana/**}、manifests/infra/loki.yaml（C++ Pod 日志 sidecar）、deploy/k8s/{owner-epoch-alerts,scene-manager-alerts}.yaml、scene-manager-dashboard.json；gateway / config_node `logging.structured.format.console=logstash`
- client messages: none
- tables: none
- depends on: gateway-ops-hardening
- behavior: 运维可见：所有进程一行一条 JSON 日志（@timestamp / level / logger_name / thread_name / message / stack_trace）经 Alloy 进 Loki（retention 7 天），Grafana「game-logs-overview」看板；Prometheus 告警：`DbStaleOwnerWriteRejected`、`DbOwnerEpochLegacyZero`（归属围栏拒写 / 遗留 epoch 0）、scene-manager 池空 / 饱和 / 负载离散度 / 再平衡停滞 / 失败率 / 镜像同置退化 / 进场交接异常。
- internal: Alloy 采 docker / Pod 日志；告警 PromQL 基于 go-zero 指标名。Java 版已有低基数 Micrometer 指标（architecture.md §11，名字与 Go 版不同），需要：JSON 日志（Spring Boot 3.4+ 自带 `logging.structured.format.console`）、按 Java 指标名重写的告警（如 scene 写回 fenced / failed 计数、gate 链路背压、assign-gate 失败率）与看板。
- java: partial — 指标与 Prometheus 端点已做（五个进程 + gateway）；无结构化日志、无告警规则、无看板、无日志采集编排。
- size: M
- robot: none
- hazards: ① mmorpg 告警规则绑定 Go 指标名，Java 指标名有意不同（PARITY「低基数运行指标」行），不能直接复用。② 高基数值（player_id）不得进 label——Java 版 AGENTS §5 同样禁止，日志里带 player_id 即可。

### ci-workflows — 持续集成门禁
- mmorpg: .github/workflows/{cpp-build-ci,go-modules-ci,login-path-tests,exporter-tests,deploy-config-tests,include-cleaner-ci,release,cr}.yml
- client messages: none
- tables: none
- depends on: none
- behavior: 开发者可见：push / PR 到 main 时按路径触发——C++ 容器内编译（Dockerfile.cpp 只到 builder 阶段）+ 构建脚本契约；Go 全模块 `go build` / `go test`；登录链（Java gateway `./mvnw test` + go/login + robot）；导表器单测（含 config_node 生成物路径）；部署配置 pwsh 契约测试（tools/scripts/tests/*.ps1）；include-cleaner；手动发布；ChatGPT 代码评审（cr.yml）。
- internal: GitHub Actions ubuntu-latest。Java 版需要：`./mvnw -B install`（全部单测）、`java tools/ContractSync.java --mmorpg <checkout> --check`（README 写「CI 用」但没有 CI）、可选 `-Dxm.it.redis` 集成测试（service container 起 Redis / MySQL）、契约同步后强制 `clean install`（AGENTS §4 的 protoc 增量生成坑）。
- java: missing — 仓库无 `.github/`。
- size: S
- robot: login-path-tests 跑 robot 单测（不连服务）
- hazards: ① cr.yml 把 PR diff 发给外部 OpenAI 端点（gpt-3.5-turbo）——Java 版仓库公开且挂简历，引入前要确认不外发密钥 / 个人内容。② ContractSync --check 需要 checkout mmorpg 指定 commit（contract/SOURCE.properties），CI 要能拉到 luyuan-cpp/xuanming-server-mmo。③ login-path-tests 的 push 触发没有 paths 过滤，main 上每次 push 都跑。

## Open questions

1. **`/api/login` 与 access token 归谁做**：Unity 客户端硬依赖 `/api/login`（code 0 才往下走）和 TCP Login 的 `auth_type="access_token"`。Java 版放 xm-gateway（经 Dubbo 调 xm-login）还是让 xm-login 直接暴露 HTTP？token 存储（Redis 键空间 `xm:`）与 refresh 轮换的 owner 是 xm-login——这一项与「登录」区域（go/login）的 access-token 功能重叠，需要和那边的清单合并成一个交付。
2. **排队与限流放哪一层**：mmorpg 是「网关 Bucket4j 粗筛 + go/login Redis 队列权威」两层。Java 版的 gate 选择与签令牌在 xm-gateway 里，排队是否也整体放 xm-gateway（少一跳 Dubbo，但 gateway 需选主放行线程），还是挪到 xm-login 与 mmorpg 同构？Sentinel（选型表）是否足以替代 Bucket4j 的分区桶 + 分波？
3. **区服目录的数据源**：Java 版是否建 `xm_java.zone_config` 表 + 运维接口（与 mmorpg 同构），还是用 Nacos 配置中心热更新（Nacos 目前只做注册中心）？PREVIEW / open_time / is_new 依赖 created_at，用配置中心时需要另存。
4. **zone_id=0 的语义**：PARITY 写「mmorpg 为自动选区」，但按代码 mmorpg 网关对 0 恒回 500 `internal_error`（LoginRpcClient 的 zoneRequired 检查）。建议 PARITY 改口径，两版都要求客户端先拉 server-list。
5. **服务 → 玩家推送通道**：好友 / 聊天 / 帮会 / 组队 / 战斗都依赖 gate-cmd 控制面。Java 版用 Kafka（照分区契约）还是 Dubbo 直调 gate（gate 当 Dubbo 提供者 + 会话定位）？这决定 kafka-client-infra 是否是硬前置。
6. **config_node / satoken 服务是否移植**：config_node 查询功能实际是坏的、热加载不原子；satoken 服务是样例级（dev-login 无开关）。建议 Java 版只吸收「认证 provider 抽象 + satoken Redis 校验」与「受鉴权的配表只读查询」，不整体移植。需用户确认。
7. **部署目标**：Java 版要不要上 K8s / Agones？若只需 compose 级联调 + 单机演示，deploy-k8s-zone-orchestration / agones-* 可标「不适用（写原因）」进 PARITY。
8. **`dirty_keys_set` 的读者**：本检出（sparse）里找不到读取方，可能在未检出的模块或已废弃；与「玩家持久化」区域确认后再决定 Java 周期存盘的形态。
9. **与其它区域的重叠**：gate-drain、login-queue、login-queue-dispatcher 的权威实现在 go/login，kafka-audit-pipeline 的消费方在 go/data_service——若「登录」「数据服务」区域清单也登记了它们，以本文件的 id 为准合并或删掉一边。
