# 实名认证、防沉迷、账号注销与角色栅栏（Java 独立版设计）

## 需求分析

对照权威主仓 `xuanming-server-mmo-cpp`（HEAD `5c0a518a6`）的实名防沉迷、账号注销、渠道身份记录与角色栅栏能力，Java 版需在不依赖任何 C++/Go 进程的前提下闭环以下客户端与运维契约：

1. **国家新闻出版署（NPPA）实名认证与防沉迷**：
   - `POST /api/real-name`（`game-gateway` → `game-login` `AccountLoginService.submitRealName`）：以 `access_token` 鉴权，姓名与身份证号只走本地 GB 11643 校验与 NPPA AES-128-GCM 加密报文，不落库明文、不进日志；支持实名通过（`AuthOK`）、48 小时待查（`AuthPending` + 登录自动回查 `resolvePending`）、不匹配（`AuthFailed`）、每日次数上限、全服 Redis GCRA 限流（Check 80/s burst 10、Query 240/s burst 20、Loginout 8/s）与 16 分片 Redis 上下线会话账本 + 1s 后台 `NppaReporter`。
   - 登录期防沉迷裁决（`AntiAddictionGate`）：对非平台托管渠道（`SelfManaged`）未实名账号拒绑会话（`kLoginRealNameRequired = 2042`），已实名未成年人按 `AntiAddictionRules.Calendar` 校验周五/六/日及法定节假日 20:00–21:00 可玩时段（非时段拒登 `kLoginMinorNotInPlayTime = 2048`；可玩时段返回 `play_deadline_ms`）。
   - `game-gate` 会话截止踢线：收到 `BindAccount{play_deadline_ms > 0}` 时在连接专属 Netty EventLoop 上定时到点推送 `kLoginMinorPlayTimeOver = 2049` 并关闭会话。
   - `game-payment` 实名联动：经 Dubbo `LoginAdminService.realNamePiOfPlayer` 查询玩家对应账号的 `real_name_pi`，使 `minor-spending-gate=realname` 闭环生效。
2. **账号注销全链路（申请、冷静期、撤销、执行、回执与客服代办）**：
   - `POST /api/account/delete`（需重新凭据认证，不收 `access_token`；Apple 账号可选先换票再提交冷静期后撤销授权）与 `POST /api/account/delete/cancel`（冷静期凭 `access_token` 撤销）。
   - 登录应答（`LoginResponse` / `HttpLoginResponse`）带回 `account_deletion_state`、`account_deletion_execute_after`、`previous_account_deleted_at` 与 `anti_addiction`；冷静期/执行中账号禁止建角与进游戏（`kLoginAccountDeletionPending = 2038`），已注销账号用旧 `access_token` 登录不复活账号行。
   - 后台执行器（`AccountDeletionExecutor`）：每 30s 按租约认领冷静期满或租约过期的账号 → 吊销全部令牌家族（`LoginTokens.revokeAll`）→ 经 `OwnerTakeovers` 踢线 → 逐角通过跨域角色栅栏校验并删除角色 → 0 角色后写入只含账号 SHA-256 摘要的 `account_deletion_receipt` 并删除 `account` 行。
3. **运维接口与角色栅栏 / 渠道身份档案 / 刷新令牌 IP 限流**：
   - `LoginAdminService`（Dubbo group `login`）：`resetAccountRealName`（426 语义，清 `real_name_pi` 与待查 Redis 键）、`scheduleAccountDeletion`（427 语义，跳过 `guild_leader` 自助拦阻，回执 `source=2`）、`realNamePiOfPlayer`。
   - `account_channel_identity` 表与 `upsertAccountChannelIdentity` / `findPlayerChannelIdentity`，登录落库渠道与 `open_id`。
   - 角色栅栏：在既有 `RolePendingFence` 基础上补齐 `RoleDeletionPrecheck`（检查寄售冻结 `role_listed`、最终存档阻塞、跨域挂起与帮主拦阻 `guild_leader`）及 `RoleFenceParticipantService` 契约接口。
   - 顺带收口 `PARITY.md` 2026-11-20 登记项：`POST /api/refresh-token` 接入共享 IP 令牌桶（`RateLimiter.checkIpOnly`，跳过分波、区桶与账号冷却，超限回 HTTP 200 `code=429` `IP_RATE_LIMIT`）。

## 设计分析

### 1. 数据层权威（`game-player-store`）
沿用 Java 版单一事务权威库（`${game.namespace:game}`），零引入新进程：
- `account` 表新增 6 列：`real_name_pi VARCHAR(64) NOT NULL DEFAULT ''`、`deletion_state TINYINT UNSIGNED NOT NULL DEFAULT 0`、`deletion_requested_ms BIGINT NOT NULL DEFAULT 0`、`deletion_execute_after_ms BIGINT NOT NULL DEFAULT 0`、`deletion_lease_until_ms BIGINT NOT NULL DEFAULT 0`、`deletion_source TINYINT UNSIGNED NOT NULL DEFAULT 0`。
- 新增 `account_deletion_receipt`（`account_digest BINARY(32) PK`、`requested_ms`、`deleted_ms`、`source`）与 `account_channel_identity`（`account VARCHAR(64) PK`、`channel`、`open_id`、`updated_at`）。
- `PlayerStore` 在 `account` 行锁事务内原子实现：`readAccountLifecycle`、`setRealName`（只写空列 CAS）、`resetRealName`、`findRealNamePiByPlayerId`、`requestAccountDeletion`、`cancelAccountDeletion`、`claimDueAccountDeletions`、`removeRoleForDeletion`、`finishAccountDeletion`；并在 `createPlayerWithinCap` 锁 `account` 行时直接拒绝 `deletion_state != 0`。

### 2. 令牌家族全量吊销与防沉迷组件（`game-login`）
- `LoginTokens` 新增 `revokeAll(String account)`，`RedisLoginTokens` 读取账号家族索引 ZSET 逐族调用既有 `REVOKE_SCRIPT` 并删除索引。
- `com.game.login.compliance` 包收敛全部合规逻辑：`NppaClient`（纯 JDK `HttpClient` + `Cipher("AES/GCM/NoPadding")` + SHA-256）、`NppaQuota`（Redis GCRA Lua）、`NppaLedger`（16 分片 `{nppa:<shard>}` Lua）、`NppaReporter`（1s `ScheduledExecutorService`）、`RealNameService`、`AntiAddictionGate`、`AccountDeletionService`、`AccountDeletionExecutor`（30s `ScheduledExecutorService`）、`LoginAdminRpcService`。
- `NppaRedisKeys` 通过构造器注入的 `RedisKeys.prefix()` 派生键名，遵守多租户命名空间约束且不产生硬编码 `game:`。

### 3. 网关与门服对接（`game-api` / `game-gateway` / `game-gate` / `game-payment`）
- `game-api`：`AccountLoginService` 新增 `submitRealName`、`requestAccountDeletion`、`cancelAccountDeletion` 默认方法；新增 `LoginAdminService` 与 `RoleFenceParticipantService`；`client_call.proto` 的 `BindAccount` 追加 `uint64 play_deadline_ms = 2`。
- `game-gateway`：`HttpLoginResponse` 追加可空字段（零值省略，保持存量 JSON 单测逐字节兼容）；`LoginController` 增加 `/api/real-name` 并为 `/api/refresh-token` 接入 `RateLimiter.checkIpOnly`；新增 `AccountDeletionController`（`/api/account/delete`、`/api/account/delete/cancel`）；`RateLimiter.Scope` 新增 `REAL_NAME("real-name")`。
- `game-gate`：`ClientSession` 增加 `playDeadlineTask`，`ClientDispatcher.applyDirectives` 处理 `BindAccount.play_deadline_ms` 挂上 EventLoop 定时器并在断线时取消。
- `game-payment`：`PaymentConfiguration` 通过可选 `@DubboReference` 接入 `LoginAdminService` 暴露 `RealNameReader` bean，开启 `minor-spending-gate=realname` 时自动生效。

## 思想分析

- **单一真相与最小增量**：Java 版所有业务域已共用同一事务权威 SQL 库与 `RolePendingFence`，因此账号注销预检、角色墓碑删除、实名绑定直接复用 `PlayerStore` 的行锁事务与 `RolePendingFence`，不另起类似 Go `data_service` 的冗余代理进程。
- **Java 标准并发与生命周期**：NPPA 加密/签名全部用 JDK 21 标准 JCA/JCE 与 `java.net.http.HttpClient`；`NppaReporter` 与 `AccountDeletionExecutor` 使用平台守护线程 `ScheduledExecutorService` + Spring `SmartLifecycle` 优雅停机；`game-gate` 未成年人到点踢线直接挂在连接所属 Netty `EventLoop`，零跨线程竞态。
- **配置默认安全与向后兼容**：默认 `game.login.anti-addiction.enabled=false` 保持存量开发/机器人切片零破坏；开启时强制校验 `NPPA_SECRET_KEY`（32 位 hex）与日历配置，缺失即 fail-fast 拒启。

## 漏洞分析

1. **隐私泄露风险（PII / 凭据进日志或堆栈）**：姓名、身份证号、口令、`access_token`、`authorization_code` 绝不进日志与 DTO `toString()`；`account_deletion_receipt` 只保存账号的 `SHA-256` 32 字节摘要。
2. **并发越权建角 / 复活已注销账号**：
   - 建角事务 `createPlayerWithinCap` 必须在锁住 `account` 行后复查 `deletion_state == 0`，防止注销提交与并发建角竞态。
   - `access_token` 路径调用 `readAccountLifecycle(account, create=false)`，避免注销完成后凭旧缓存/旧令牌自动重建账号行。
3. **重放与抢绑实名**：`setRealName` 采用条件更新 `WHERE account = ? AND real_name_pi = ''`，同一 `pi` 重放幂等成功，不同 `pi` 返回库中已有 `pi` 并回 `kLoginRealNameAlreadyBound (2046)`。
4. **重复申请刷新冷静期 / 执行中误撤销**：`requestAccountDeletion` 对 `PENDING/DELETING` 原样返回既有 `execute_after_ms` 不刷新倒计时；`cancelAccountDeletion` 在 `DELETING` 状态严格回 `TOO_LATE (2040)`。
5. **未成年人边界与时钟回拨**：`game-gate` 若收到 `play_deadline_ms <= now` 直接安排 0ms 延迟在发送完应答后踢线；NPPA 上报严格过滤超过 150s 的旧事件避免整批被拒绝，配额不足时先放回待发队列而非丢弃。
6. **IP 限流误伤正常续期**：`/api/refresh-token` 仅过 `checkIpOnly`（共享 IP 桶 + fail-open），绝不经过波次、区桶与账号冷却。

## 步骤分析

1. **`game-player-store`**：升级 `game-player-schema.sql`、`PlayerMapper`、`RoleDeletionPrecheck` 与 `PlayerStore`（实名、注销全状态机、渠道身份、建角注销门禁），补充 H2 事务回归测试。
2. **`game-api`**：扩展 `client_call.proto`（`BindAccount.play_deadline_ms`）、`AccountLoginService`，新增 `LoginAdminService` 与 `RoleFenceParticipantService`，编译验证并安装。
3. **`game-login`**：实现 `LoginTokens.revokeAll`、`com.game.login.compliance.*`（`NppaClient`/`NppaQuota`/`NppaLedger`/`NppaReporter`/`RealNameService`/`AntiAddictionGate`/`AccountDeletionService`/`AccountDeletionExecutor`/`LoginAdminRpcService`/`ComplianceProperties`），接入 `AccountLogin`/`LoginHandler`/`CreatePlayerHandler`/`EnterGameHandler`/`LoginAccountService`/`LoginClientMessageService`/`LoginConfiguration`，补充单测。
4. **`game-gateway`**：扩展 `HttpLoginResponse`、`LoginController`（`/api/real-name`、`/api/refresh-token` IP 限流）、`RateLimiter.checkIpOnly`、`LoginHttpService`、`AccountDeletionController` 及 DTO，补充单测。
5. **`game-gate` & `game-payment`**：`game-gate` 实现 `BindAccount.play_deadline_ms` 定时踢线（2049）并补单测；`game-payment` 接入可选 `LoginAdminService` 作为 `RealNameReader` 并验证。
6. **全链路回归与收口**：按依赖顺序 `mvn -o -B install` 并运行全部目标模块测试，更新 `PARITY.md` 后提交推送。

## 执行步骤

1. 落盘设计文档 `docs/design/real-name-deletion-and-role-fence.md`。
2. 改造 `game-player-store` 并通过单测与本地安装。
3. 改造 `game-api` 并通过单测与本地安装。
4. 改造 `game-login`、`game-gateway`、`game-gate`、`game-payment` 并逐模块运行全量回归测试。
5. 更新 `PARITY.md`，精确暂存本批文件与对账段落，中文提交并推送 `origin/main`。
