# 区服满员处理与 Gate 水位容量选台（Java 版设计）

> 对齐权威主仓 `mmorpg@de4107d76` 的 `docs/design/zone-full-handling.md`、`docs/design/gate-load-balancing-design.md`、`docs/design/login-queue-2026-05.md` 与 `PARITY.md` 第 629 / 639 / 643 / 644 / 645 / 646 项。

## 需求分析

1. **区服默认容量对齐（U-2）**：`zone_config.capacity` 建表缺省、`SeedZone` 默认值与 `ZoneAdminController` 默认值统一由 `5000` 调整为 `45000`，避免开服第 5001 人提前进入排队。
2. **Gate 自报连接上限与连接准入防护**：
   - `GateNodeInfo` 增加 `uint32 max_connections = 11`（`0` 表示未声明上限，用于 dev / 旧版兼容）；
   - `game-gate` 增加 `game.gate.max-connections`（默认 `5000`，`0` 表示不限制），建连时执行硬上限保护与承压激活的公网来源前缀限速（`SourcePrefixRateLimiter`），拒连计入低基数指标 `game.gate.disconnects{reason=max_connections|source_rate_limited}`。
3. **Gate 单次分档选台与 90% 水位容量封顶**：
   - `GatePicker` 统一 90% 水位判定（`watermarkCapacity(max) = ceil(max * 90 / 100)`，整数算 `(max * 90 + 99) / 100`）；
   - `eligible` 合并排空与满载为一次分档：完全合格（未排空且未到水位）> 连得上的（排空中或到水位）> 无候选；
   - `admissionCapacity` 计算本区在接客 Gate 到 90% 水位的总承载（全排空时退到全部可连 Gate；任一计入 Gate 的 `max_connections == 0` 时不封顶）；
   - `QueueCapacity` 与 `ZoneHealthProbe` 统一采用 `min(配置/软上限容量, Gate 水位承载)` 作为区级放行预算与负载档分母，杜绝排队放行后在 Gate 被无声拒连。
4. **本进程 20s 在途票记账与排队取票复核**：
   - 新增 `GateInflight`（20 秒滑动窗口），在 `AssignGateService` 快速通道签票成功与 `QueueDispatcher` 放行挑定时记账；挑台比较 `player_count + inflight`（水位判定仍只看上报 `player_count`）；
   - 排队轮询取走放行槽时，按实时 Gate 目录复核所选 Gate 是否仍在线且健康（未排空、未到水位），否则按当前目录重挑一台（目录读失败时照签原放行 Gate）。
5. **区服满员与暂停创角全链路（保留选区）**：
   - `zone_config` 新增 `create_role_closed`（默认 `0`）与 `role_capacity`（默认 `0`，不设上限），`PUT /admin/zones/{id}` 整行覆盖不改动这两列；
   - `game-data` `ZoneAdminController` 新增 `POST /admin/zones/{id}/create-role/close`、`POST /admin/zones/{id}/create-role/open` 与 `PUT /admin/zones/{id}/role-capacity`；
   - `GET /api/server-list` 下发 `create_role_closed`，`recommended` 改为服务端计算：仅保留显示 `OPEN`、未暂停创角且非 `FULL`（负载未知保留）的运维推荐区；全不满足时自动推荐负载率最低的开放且可创角区（并列按 `sort_order` 靠前；全爆满仍选最空；负载全未知不补推）；
   - `POST /api/login` 的 `players[]` 增加 `zone_id`，供客户端在排队前预判目标区暂停创角且无角；
   - `game-login` `CreatePlayerHandler` 在进入账号建角锁之前读取目标区 `create_role_closed`（2 秒本地缓存，读失败 fail-open），命中时直接回 `LoginErrorTip.kLoginZoneCreateRoleClosed`（`2037`），不影响已有角色登录进场；
   - `game-gateway` `RoleCapacityGuard` 每 10 秒仅轮询「`role_capacity > 0 && !create_role_closed`」的区，经 `AccountLoginService.countZoneRoles` 统计 `player` 表该区历史总建角数（走 `idx_player_zone`，删角不减），达线时 CAS 原子关闭 `create_role_closed`（带 `role_capacity = expected` 条件，只关不开，问不到不关）；导出开放区数与可创角区数指标用于告警。

## 设计分析

- **零额外基础设施**：Java 版 `game-gateway` 与 `game-login` 共用同一份 `game` MySQL 与既有 `AccountLoginService`（Dubbo Triple）链路，因此无需引入 etcd 同步进程；`game-login` 直接用 2 秒 TTL 缓存点读 `zone_config.create_role_closed`（fail-open），`game-gateway` 经现有 `AccountLoginService.countZoneRoles(zoneId)` 读取 `player` 表按区计数，复用既有发现、鉴权与超时治理。
- **Java 标准库与无锁/轻量并发设计**：
  - `GateInflight` 采用 `ArrayDeque<Long>` + 单调时钟 `System::nanoTime`（`O(1)` 尾部追加、头部过期淘汰），避免切片拷贝与墙钟回拨干扰；
  - `SqlZoneCreateRolePolicy` 使用 `ConcurrentHashMap<Integer, CachedEntry>` 缓存每区 2 秒布尔值，查询异常回落 `false` 并记 WARN，避免单次库抖动阻断整区建角；
  - `ZoneRow` / `ZoneInfo` / `HttpLoginResponse.PlayerInfo` / `GateProperties` 均保留既有构造重载，确保跨模块与既有单测平滑演进。
- **单一事实源与口径统一**：
  - 90% 水位计算、分档筛选、总承载求和全部收口在 `game-discovery` 的 `GatePicker`，供 `AssignGateService`、`QueueDispatcher`、`ZoneHealthProbe` 与 `TravelRouter` 共用；
  - `GatewayStore.closeCreateRoleIfCapacityReached` 使用单条条件 `UPDATE`，保证多副本 `game-gateway` 并发检测与运维调高上限之间无竞态。

## 思想分析

- **背压前置与口径一致**：把「满载」从 Gate 端的无声 `close()` 提前到区级准入与区服列表，使排队放行预算、区服列表负载分母、Gate 选台水位共享同一条 `ceil(max_connections * 0.90)` 边界，余下 10% 吸收 5 秒目录上报滞后。
- **软约束优先于全局串行锁**：注册角色上限属于导量软目标，采用异步 10 秒轮询 + 数据库 CAS 关闭，而不是在每次建角事务里争抢区级计数行锁，保证高并发建角吞吐与水平扩展性。
- **面向故障的降级哲学**：策略读不到时建角 fail-open、角色数问不到时不自动关闭、全 Gate 排空/满载时仍回落可连集合而不拒绝、取票复核查目录失败时仍签发原放行 Gate——避免把局部依赖抖动放大为全服不可用。

## 漏洞分析

1. **排空 Gate 被提前剔除导致容量少算在线**：若先剔除排空 Gate 再数在线人数，放行循环会低估实际在线而多放人。修复：`QueueCapacity.online` 始终统计全部候选 Gate，`admissionCapacity` 仅统计在接客档的水位上限。
2. **多副本并发改上限与自动关竞态**：若先读 `role_capacity=100`、随后运维上调至 `200`，普通 `UPDATE` 会误关创角。修复：CAS SQL 绑定 `WHERE role_capacity = #{expected} AND role_capacity > 0 AND create_role_closed = 0`。
3. **无上限旧版 Gate 与 0 除风险**：旧版或 dev 模式 `max_connections = 0` 时，若按 0 累加会把整区容量压为 0。修复：凡计入集合中存在 `max_connections == 0` 的 Gate，直接返回 `bounded = false`（不封顶）。
4. **H2 / 未建 `zone_config` 单测环境误拦建角**：`game-login` 单测只初始化 `game-player-schema.sql`。修复：`SqlZoneCreateRolePolicy` 捕获 `SQLException` 时 fail-open 返回 `false`，与生产「策略不可读时不拦建角」规则一致。
5. **Gate 建连硬上限绕过与瞬时风暴**：未在 `channelActive` 拦截会导致连接耗尽文件描述符与会话号。修复：在 `ClientChannelHandler.channelActive` 入口先判 `maxConnections` 与承压 `SourcePrefixRateLimiter`，超限立即关闭并计数。

## 步骤分析

1. 扩展 `game-api`（`GateNodeInfo.max_connections = 11`、`AccountLoginService.countZoneRoles`）与 `game-player-store`（`PlayerStore.countByZone`），安装到本地仓库。
2. 升级 `game-discovery`（`GateInflight`、`GatePicker` 水位/承载/一次分档/在途负载选台）与 `game-gateway-store`（默认 45000、`create_role_closed`、`role_capacity`、单列改与 CAS 关），安装到本地仓库。
3. 升级 `game-gate`（`maxConnections` 配置与建连硬上限/来源限速、目录发布 `max_connections`）。
4. 升级 `game-data`（`ZoneAdminController` 默认 45000、展示新列、新增 `/create-role/close`、`/create-role/open`、`PUT /role-capacity`）。
5. 升级 `game-login`（`ZoneCreateRolePolicy`、`SqlZoneCreateRolePolicy`、`CreatePlayerHandler` 锁前拦截返回 `2037`、`LoginAccountService.countZoneRoles`）。
6. 升级 `game-gateway`（`SeedZone` 默认 45000、`QueueCapacity` Gate 水位封顶、`ZoneHealthProbe` 有效分母与负载率、`ServerListController` 服务端推荐与新字段、`AssignGateService` / `QueueDispatcher` 20s 在途票与取票复核、`HttpLoginResponse.PlayerInfo.zoneId`、`RoleCapacityGuard` 10 秒巡检与告警指标）。
7. 运行跨模块回归（含 `HotfixCheck*Test` 闭合 `PARITY.md` 第 628 项）并更新 `PARITY.md`。

## 执行步骤

- [x] 安装 `game-api` 与 `game-player-store` 变更
- [x] 安装并验证 `game-discovery` 与 `game-gateway-store` 变更
- [x] 实现并验证 `game-gate` 与 `game-data` 变更
- [x] 实现并验证 `game-login` 与 `game-gateway`（含 `HotfixCheck*Test`）变更
- [x] 更新 `PARITY.md`、提交并推送到 `origin/main`
