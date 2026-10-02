# login（go/login + client_rpc_router + player_locator + go/shared）

go/login 负责账号认证、建角和进出游戏。认证方式有开发口令、生产 Argon2id 口令、access/refresh token、Sa-Token、微信、QQ。还负责每账号设备数上限，以及 LoginPreGate.AssignGate（选 gate、签令牌、登录排队）。EnterGame 是异步链：先预热数据（Kafka DB 任务），再写会话、经 Kafka 让 gate 绑定会话，最后调 scene_manager.EnterScene。go/player_locator 是玩家会话的唯一真源（Redis player:session:{id}，整块 CAS + 版本号），提供 30s 断线租约、短线重连、顶号判定、租约到期清理（LeaseMonitor 至少一次）和失联 gate 对账。friend/guild/chat 也读这份会话判在线、找推送目标。go/client_rpc_router 按生成的路由表把非场景的 gRPC 类客户端消息（login/chat/friend/guild/team/match/trade）原样转给目标服务，login 按 zone 选实例。go/shared 里和玩法有关的是：playername（名字规则）、assetop（跨服务资产通道）、gameday（05:00 切日）、kafkautil/kafkacmd（向 gate 推送）、scenenode（定位玩家所在 scene）、killswitch（RPC 热关停）、leader（选主）、clientendpoint（选客户端可达地址），其余是 ID 号源、etcd 注册、指标等基础设施。Java 版已有：开发口令登录、建角（名字规则、上限、丢应答重试）、EnterGame（owner_epoch 夺权）、LeaveGame/Disconnect、无排队的 AssignGate、顶号（缺 msg 34）、只接 login 的 gate 路由。缺：token、生产/第三方认证、设备上限、排队、短线重连、会话登记、合服/跨区相关、推送通道、资产通道、热关停。

### login-dev-password-auth — 开发口令认证
- mmorpg: go/login/internal/logic/pkg/auth/providers.go; go/login/internal/logic/pkg/auth/provider.go; go/login/internal/svc/auth_init.go; go/login/internal/logic/clientplayerlogin/loginlogic.go
- client messages: 48 ClientPlayerLoginLogin (C2S)
- tables: none
- depends on: none
- behavior: auth_type 为 "" 或 "password" 时生效。通过条件：账号非空、首尾无空白（Go strings.TrimSpace 口径）、以白名单前缀 robot_ 或 dev_ 开头、口令等于环境变量 LOGIN_DEV_PASSWORD_SHARED_SECRET（常数时间比较）。只允许在 go-zero Mode=dev/test 下注册，与生产 PasswordAuth 互斥，两者都开则 panic；配了 DevSkipAuth 也 panic。两种口令认证都没开时口令路径 fail-closed。任何认证失败一律回 LoginResponse.error_message=2000 kLoginAccountNotFound，不区分原因。
- internal: 进程级 provider 注册表；共享密钥只从环境变量读。
- java: done — xm-login/src/main/java/com/game/login/auth/DevPasswordRule.java、LoginAuthenticator.java（另加 64 码点上限，因 account 列是 VARCHAR(64)）
- size: S
- robot: robot_smoke；login-test: NormalLogin / WrongPassword；Java SmokeScenario
- hazards: 

### login-core — 登录主流程（旧 TCP 路径）：账号锁、取或建账号、角色列表
- mmorpg: go/login/internal/logic/clientplayerlogin/loginlogic.go; go/login/internal/logic/pkg/locker/player_locker.go; go/login/internal/logic/clientplayerlogin/deprecation.go; go/login/internal/logic/clientplayerlogin/legacy_gate_killswitch.go; go/login/internal/logic/clientplayerlogin/role_appearance_backfill.go
- client messages: 48 ClientPlayerLoginLogin (C2S/应答 LoginResponse)
- tables: none
- depends on: login-dev-password-auth; login-session-state; login-device-limit; login-access-refresh-token; home-zone-mapping
- behavior: 步骤：认证 → Redis 锁 account_lock:login:{account}（TTL 20s，只试一次不等待，失败回 2005）→ 若 LegacyGateLoginEnabled=false 回 2015 → 会话 id 为 0 回 2018 → 写 login_session:{sid}=account（失败 2023）→ 设备数检查（2024）→ GetOrInitUserAccount（Lua 一次完成 GET / PERSIST / 不存在则写空账号）→ 签 token（auth_type=access_token 时不签；签发失败不致命，字段留空）→ 回角色列表。角色列表按建角顺序，每项是 AccountSimplePlayer{player_id, class_id, gender, zone_id, name, appearance_id}；zone_id 按 player:zone 映射刷新，缺名/缺外观的从注册表或 PlayerAllData 回源补齐，补齐结果不回写账号。新账号回空列表不报错。成功时应答体里不能出现 error_message。同一连接可以再次 Login，新账号覆盖旧账号。
- internal: 账号角色目录只存在 Redis account:{account}（protobuf UserAccounts），没有 MySQL 副本。分布式锁是 UUID 值 + Lua 安全释放。旧路径调用量有指标 login_auth_path_total{path,auth_type}。Java 必须做到：跨实例防同账号并发 Login（回 2005）；或者明确确认不需要。
- java: partial — xm-login/src/main/java/com/game/login/handler/LoginHandler.java（InFlightKeys 只防本进程并发；store.ensureAccount + listPlayers 从 MySQL 读；回 BindAccount 会话指令；不签 token）
- size: M
- robot: robot_smoke；login-test: DuplicateLoginRequest / RapidLoginSpam / BatchConcurrentLogin / ConcurrentSameAccount；Java SmokeScenario
- hazards: Go 的角色目录只在 Redis 且无 TTL（PERSIST），Redis 数据丢失等于丢角色列表，只有 player_to_account 反向索引能自愈单个角色。Java 落在 MySQL，比基线更安全，不要照抄。Java 的同账号在途闸门只在单进程内有效。

### login-access-refresh-token — access/refresh token 签发、access_token 重登、RefreshToken
- mmorpg: go/login/internal/logic/pkg/token/token.go; go/login/internal/logic/clientplayerlogin/refreshtokenlogic.go; go/login/internal/logic/pkg/auth/providers.go (AccessTokenProvider); go/login/internal/svc/auth_init.go (RegisterAccessTokenProvider)
- client messages: 48 ClientPlayerLoginLogin（LoginResponse 字段 3–6）; 127 ClientPlayerLoginRefreshToken (C2S/应答 RefreshTokenResponse)
- tables: none
- depends on: login-core
- behavior: token 是 32 字节随机数的 base64url 编码，无填充，共 43 字符。access token 有效 2h，refresh token 有效 720h；过期时间以 Unix 秒下发。口令或第三方登录成功后签一对 token。Login{auth_type:"access_token", auth_token} 从 token 解析账号，忽略请求里的 account，不重新签。RefreshToken：refresh_token 为空、无效、已被消费或 Redis 出错一律回 2000；成功时轮换，旧 refresh 一次性作废，返回新的一对。access token 不随轮换作废。
- internal: Redis 键：access_token:{t} 与 refresh_token:{t}，值为 JSON {account, auth_type, device_id, created_at}，带 TTL。account_refresh:{account} 是 ZSET，score 为过期秒；每次签发先清掉过期成员，再封顶 32 个，超出时淘汰最旧的并删除对应 token 键。另有 RevokeAll（删全部 refresh）。Java 可用 Redisson 实现，键统一经 RedisKeys 生成。
- java: missing — LoginHandler 不填 3–6 字段；LoginAuthenticator 对 access_token 一律失败（回 2000）；ClientMessageDispatcher 对 127 回信封错误 1006 kFeatureUnavailable
- size: M
- robot: login-test: AccessTokenReconnect；robot 重试路径；robot.e2e-http.yaml
- hazards: Refresh 的消费是先 GET 再 DEL，靠 DEL 的返回计数防并发双用，不是原子脚本。access token 不绑定设备（device_id 恒为空），也不能被吊销，RevokeAll 只删 refresh。所有失败都映射成 2000，客户端会误以为是账号问题。robot 第二次尝试起先发 access_token，失败再回退口令登录；Java 目前靠这条回退才能跑通。

### login-production-password — 生产口令认证（MySQL Argon2id）与 password_admin 迁移工具
- mmorpg: go/login/internal/logic/pkg/auth/password_provider.go; go/login/internal/svc/auth_init.go; go/login/cmd/password_admin/main.go; go/login/model/migrations/20260803_password_auth.sql; go/login/model/mysql_database_table.sql
- client messages: 48 ClientPlayerLoginLogin
- tables: none
- depends on: login-core
- behavior: 只读权威表 user_accounts.password，格式是 Argon2id PHC（m=64MiB, t=3, p=2, salt 16, key 32）；password 为 NULL 表示该账号禁用口令登录。账号必须是合法 UTF-8、非空、首尾无空白、不超过 191 个字符；口令 1–1024 字节。账号不存在或输入非法时也跑一次 dummy Argon2id，消除时序差。KDF 并发槽默认 2（上限 8），等槽 500ms（上限 5s），等不到算失败。返回的账号取库里的规范值，不回显客户端输入。Login RPC 不提供注册和改密。所有失败对客户端都是 2000。
- internal: password_admin CLI：DSN 从环境变量读，口令从 tty 或 stdin 读两遍，只给尚未迁移的既有账号写一次哈希。迁移 SQL 带校验门禁：空账号、重复账号、超长都拒绝执行。
- java: missing — 
- size: M
- robot: none
- hazards: Java 做 Argon2 需要 BouncyCastle（star 数不到 2 万），按选型规则需写进 tech-stack.md 并说明理由。KDF 每次占 64MiB，必须有并发上限，否则可被打 OOM。

### login-third-party-auth — 第三方认证（Sa-Token / 微信 / QQ / 网易占位）
- mmorpg: go/login/internal/logic/pkg/auth/providers.go; go/login/internal/svc/auth_init.go; go/login/cmd/sandbox_mock/main.go
- client messages: 48 ClientPlayerLoginLogin（auth_type=satoken|wechat|qq|netease，auth_token）
- tables: none
- depends on: login-core; login-access-refresh-token
- behavior: satoken：在独立 Redis 上 GET {TokenName}:{LoginType}:token:{token}（默认 satoken:login:token:...），取到的 loginId 直接当账号，不加前缀。wechat：用 code 调 api.weixin.qq.com/sns/oauth2/access_token；errcode 非 0 或 openid 为空即失败；账号为 "wx_" + unionid，无 unionid 用 openid。qq：用 access_token 调 graph.qq.com/oauth2.0/me?unionid=1&fmt=json，兼容剥掉 JSONP 外壳；client_id 必须等于 AppId；账号为 "qq_" + unionid 或 openid。netease 恒失败（TODO 占位）。未知 auth_type 或任何失败都回 2000。HTTP 超时 5s；可用 Endpoint 覆盖指向 sandbox_mock。
- internal: 按配置注册 provider。sandbox_mock 是开发用的微信/QQ 假服务。
- java: missing — 
- size: M
- robot: none
- hazards: SaTokenProvider 在 Info 日志里打印原始 token（凭据泄露）。Sa-Token 账号没有命名空间前缀，可能与口令账号撞名。只看键是否存在，忽略 Sa-Token 自身的 active-timeout 语义。

### login-device-limit — 每账号设备数上限（2024）
- mmorpg: go/login/internal/logic/clientplayerlogin/loginlogic.go; go/login/internal/logic/pkg/loginsession/loginsession.go; go/login/internal/constants/login_constants.go
- client messages: 48 ClientPlayerLoginLogin（error 2024 kTooManyDevices）
- tables: none
- depends on: login-session-state
- behavior: 只在旧 TCP 路径生效。先清掉 login_account_sessions:{account} 集合里 login_session 已不存在的成员，再 SADD 本会话 id 并设 TTL 30min，然后 SCARD；超过 MaxDevicesPerAccount（3）回 2024。会话在 EnterGame 成功、LeaveGame、Disconnect 时移出集合，所以实际只统计处于「Login → EnterGame 完成」之间的连接。HTTP /api/login 路径不检查。
- internal: Redis SET 加 login_session 存在性自愈清理。
- java: missing — 
- size: S
- robot: none
- hazards: Go 先写 login_session 再检查数量，回 2024 时不回滚：被拒的连接仍是已登录状态，可以接着 CreatePlayer / EnterGame，限额能被绕过；被拒的会话 id 也留在集合里。同一连接换账号登录时，旧账号集合里的条目要到 TTL 才消失，会一直占旧账号的名额。Java 实现时要先判再绑定。

### login-session-state — 登录态（会话 ↔ 账号）
- mmorpg: go/login/internal/logic/pkg/loginsession/loginsession.go; go/login/internal/logic/clientplayerlogin/session_lifecycle.go; go/login/internal/logic/pkg/ctxkeys/ctxkeys.go
- client messages: 14; 26; 17; 58（缺失时回 2028 kLoginSessionNotFound；会话 id 为 0 回 2018）
- tables: none
- depends on: none
- behavior: Redis login_session:{sid}=account，TTL 为 SessionExpireMin（30min）。CreatePlayer 和 EnterGame 都要求它存在，否则回 2028。EnterGame 异步链成功、LeaveGame、Disconnect 时删除，所以进游戏之后再发 CreatePlayer / EnterGame 都回 2028。
- internal: 会话身份由 gate 经 x-session-detail-bin 元数据带过来（SessionDetails{session_id, player_id, gate_node_id, gate_instance_id, ticket_*}）。
- java: not_applicable — Java 把账号绑在 gate 会话上（BindAccount 会话指令），会话已绑玩家时 CreatePlayerHandler / EnterGameHandler 回 2028
- size: S
- robot: login-test: MessageBeforeLogin / LeaveAndReLogin
- hazards: Go 在大厅停留超过 30 分钟后，login_session 过期，CreatePlayer / EnterGame 会回 2028。Java 没有这个过期，属于可接受差异。

### http-login — HTTP /api/login 无会话登录（新路径）
- mmorpg: go/login/internal/logic/clientplayerlogin/loginlogic.go (!isLegacyPath 分支); go/login/internal/logic/clientplayerlogin/legacy_gate_killswitch.go; java/gateway_node (LoginService，HTTP 外壳，不在本区)
- client messages: HTTP POST /api/login; 之后 TCP 48 Login{auth_type:"access_token"}
- tables: none
- depends on: login-access-refresh-token; login-core
- behavior: gateway 不带 SessionDetails 直接调 login.Login：校验凭据、取或建账号、签 token、回角色列表。这条路径不检查设备数，也不写 login_session。HTTP 请求为 snake_case {zone_id, account, password?, auth_type?, auth_token?, device_id?}；应答为 {code, message, retry_after_ms, queue_pos, access_token, refresh_token, access_token_expire, refresh_token_expire}。code：0 成功，100 排队，101 排队超时，401 认证被拒（login 的任何业务错误都映射成 401），429 限流。迁移开关 LegacyGateLoginEnabled=false 时，旧 TCP Login 回 2015。
- internal: 有弃用期指标与告警节流。
- java: missing — xm-gateway 只有 /api/assign-gate 与区服列表，没有 /api/login
- size: M
- robot: robot.e2e-http.yaml（use_http_login: true）
- hazards: 

### create-player — 建角（CreatePlayer 14）
- mmorpg: go/login/internal/logic/clientplayerlogin/createplayerlogic.go; go/login/internal/logic/clientplayerlogin/character_appearance.go; go/login/internal/logic/pkg/playernamereg/playernamereg.go; go/login/internal/svc/player_id_minter.go
- client messages: 14 ClientPlayerLoginCreatePlayer (C2S/应答 CreatePlayerResponse)
- tables: Class; RoleNameRule
- depends on: login-session-state; role-name-rules; player-id-generation; home-zone-mapping
- behavior: 会话 id 为 0 回 2018，无 login_session 回 2028，账号建角锁 account_lock:create:{account} 抢不到回 2005，账号 blob 读不到回 2000、解析失败回 2022、Redis 错回 2021。角色数达到 MaxPlayersPerAccount（5）回 2001。class_id=0 取 Class 表第一行；class 不存在、gender>2、appearance_id 不在白名单（"" 加 15 个固定 id）都回 2015。gender=0 视为 1。RoleNameRule 读不出回 2020。名字预检排在发号之前：敏感词 2034，不合规 2032（parameters=[min,max]），空名由服务端生成。发号失败回 2020。玩家给的名字被占回 2033；但如果占用者就是本账号里 class/gender/appearance 都相同的角色，判定为「上次建角成功只是应答丢了」，直接回当前全量列表。生成名重试用尽回 2020。成功回账号全部角色，新角色在末尾，不带 error_message。
- internal: Go 的顺序固定为：发号 → data_service 名字登记（预算 3s；结果未知时同名重试一次，仍未知则立即释放并延迟再释放一次）→ 登记 player:zone（fail-closed）→ 以建角锁令牌为围栏写账号 blob（锁丢失时回读确认，否则回 2005 或 2023）→ 写 player_to_account:{id} 反向索引（失败不致命）。
- java: done — xm-login/src/main/java/com/game/login/handler/CreatePlayerHandler.java；xm-player-store PlayerStore.createPlayerWithinCap（事务内 SELECT ... FOR UPDATE 锁账号行后计数，再逐个候选名插入，名字唯一索引 uk_player_name_key）
- size: L
- robot: robot_smoke（CreatePlayer {}）；Java SmokeScenario
- hazards: Go 的账号建角锁 TTL 20s，持锁期间串着三次跨进程调用，锁可能在写 blob 前过期，所以加了围栏写。Java 用数据库事务，更简单也更强。丢应答重试的判定：Java 按大小写不敏感的 name_key 找占用者，还要求展示名逐字相同，比 Go（只比 owner id）更严。每次撞名都会烧掉一个号。

### role-name-rules — 角色名规则（规范化、字符集、敏感词、默认名生成）
- mmorpg: go/shared/playername/playername.go; go/shared/playername/testdata/charset_vectors.json; go/login/internal/logic/pkg/playernamereg/playernamereg.go
- client messages: 14（2032 带参数 [min,max]、2033、2034）
- tables: RoleNameRule
- depends on: none
- behavior: 处理顺序：NFKC → TrimSpace → 空则判 EMPTY（由服务端生成）→ 码点数须在 [min_chars, max_chars] 内（配表 2–12，结构上限 32）→ 字符集只允许 0-9、A-Z、a-z、〇(U+3007)、CJK 扩展 A（U+3400–4DBF）、CJK 基本区（U+4E00–9FFF）→ 唯一键为小写化的结果 → 敏感词：子串命中 管理员/客服/官方/系统/运营，或以 gm 开头。默认名 = 前缀（道友）+ suffix_len（6）位 [a-z0-9]：每位取 crypto 随机字节，≥252 丢弃重取，否则 b%36。撞名最多试 max_generate_attempts（5）次。配表规则不自洽时拒绝建角。
- internal: Go 在 login 和 data_service 两处跑同一份实现。
- java: done — xm-login/src/main/java/com/game/login/character/PlayerNames.java、RoleNameRules.java、TableCharacterRules.java；xm-player-store PlayerStore.nameKey
- size: S
- robot: robot_smoke（空名走生成）
- hazards: NFKC 之后再数长度；TrimSpace 的空白集合必须用 Go 口径（Java 有 GoSpaces）。字符集向量 charset_vectors.json 值得接进 Java 单测，防止两版漂移。

### player-id-generation — PlayerId 发号
- mmorpg: go/login/login.go; go/login/internal/svc/player_id_gen.go; go/login/internal/svc/player_id_minter.go; go/shared/idsegment; go/shared/snowflakealloc; go/shared/snowflake
- client messages: none
- tables: none
- depends on: none
- behavior: 客户端只看到不透明的 uint64 player_id。
- internal: Go 优先用 data_service 号段（biz tag 全局唯一，步长 100），失败时按 FallbackToSnowflake 决定是否回退 bwmarrin 雪花（13 位 node 切成 cluster3 + slot10，etcd 槽租约，毫秒水位前推 2s，丢槽即 fence 并退出，水位超过 2h 未写成功也拒发）。发号失败则建角整体失败（2020）。
- java: done — xm-login PlayerIdGenerator + xm-common Snowflake + xm-discovery NodeIdLease（isValid 为假时拒绝发号）
- size: M
- robot: robot_smoke
- hazards: Go 写路径是 INSERT ... ON DUPLICATE KEY UPDATE，撞号会静默串档，所以唯一性必须在发号侧保证。Java 是主键冲突报错，比基线安全。

### enter-game-core — 进游戏（EnterGame 26）与异步失败通知
- mmorpg: go/login/internal/logic/clientplayerlogin/entergamelogic.go; go/login/internal/logic/pkg/sessionmanager/session_manager.go; go/login/internal/svc/gate_command.go; go/login/internal/logic/clientplayerlogin/metrics.go
- client messages: 26 ClientPlayerLoginEnterGame (C2S/应答 EnterGameResponse); 23 SceneClientPlayerCommonSendTipToClient（S2C 推送 {3023}）; 79 NotifyEnterScene（由 scene 推送）
- tables: none
- depends on: login-session-state; session-registry; player-data-preload; replace-login; short-reconnect-lease; enter-game-cross-zone
- behavior: 同步阶段：会话 id 为 0 回 2018；重定向票据持有者与请求 player_id 不符回 2011；无 login_session 回 2028；玩家锁 player_locker:{id}（TTL 120s，每 TTL/3 心跳续期）抢不到回 2005；Redis 出错回 2021；账号 blob 读不到回 2000，解析失败回 2022；角色不属于本账号回 2011（但若反向索引 player_to_account 证明归属，就把角色补回账号再继续）；预热线程池满回 2005。成功应答为 {player_id=请求值}，不带 error_message，post_merge_notice_ts=0、force_rename=false，表示「已受理」而非「已进场」。异步链之后任何失败（预热失败、GetSession 或写会话失败、BindSession 失败、EnterScene 被拒）都经 gate 推 23 {3023}；会话上没有 gate 地址时不推，客户端靠 60s 进场超时兜底。成功后清掉 login_session。
- internal: 异步链：Kafka DB 预热 → player_locator.GetSession → 决策（首登 / 短线重连 / 顶号）→ 身份补齐 → 写会话（版本 +1）→ 经 Kafka 发 BindSessionEvent 给 gate（带 enter_gs_type）→ scene_manager.EnterScene → 写 login_idempotent 键。整条链预算 5min；丢锁时取消整条链。
- java: done — xm-login/src/main/java/com/game/login/handler/EnterGameHandler.java（先向 scene-manager 要落点再夺取 owner_epoch，持有者 3s 不让出回 2005）；xm-gate ClientDispatcher.failEnter 推 23 {3023}
- size: L
- robot: robot_smoke；login-test: DuplicateEnterGame / DisconnectDuringEnter / LoginStuckDetection；Java SmokeScenario
- hazards: login_idempotent:{pid}:{request_id} 只写不读，是死写；真正的 request_id 去重在 scene_manager。Go 的反向索引自愈会在不持建角锁的情况下改写账号 blob，可能和并发建角互相覆盖。Java 同一角色进场还在途时回 2028，基线回 2005（已登记于 PARITY）。

### leave-game — 离开游戏（LeaveGame 17）
- mmorpg: go/login/internal/logic/clientplayerlogin/leavegamelogic.go; go/login/internal/logic/pkg/sessionmanager/session_manager.go (DeleteSession); go/player_locator/internal/logic/markofflinelogic.go
- client messages: 17 ClientPlayerLoginLeaveGame（应答 LoginEmptyResponse；gate 直连模式下以 message_id=58 下发）
- tables: none
- depends on: session-registry
- behavior: 清掉 login_session 和设备集合条目。只有当前会话 id 与请求一致时才 MarkOffline：带 session_id + version 的 CAS 删除会话、旧位置键和租约，然后立即调 SceneManager.LeaveScene，失败则进入租约队列重试。会话已被替换时什么也不做。不推送任何消息。之后需要重新 Login，否则 CreatePlayer / EnterGame 回 2028。
- internal: player_locator 的 CAS 脚本和至少一次的 LeaveScene 重试。
- java: done — xm-login/src/main/java/com/game/login/handler/LeaveGameHandler.java（下发 UnbindPlayer，gate 发 PlayerLeave{voluntary}，保留账号可回选角；行为有意不同，见 PARITY）
- size: S
- robot: robot_smoke 收尾；login-test: LeaveAndReEnter / LeaveAndReLogin / LoginLogoutCycle
- hazards: Go 与 Java 在「LeaveGame 后能否不重新 Login 直接回选角」上口径不同，客户端做回选角前需两边定一个口径。

### disconnect — 断线通知（Disconnect 58，客户端发或 gate 合成）
- mmorpg: go/login/internal/logic/clientplayerlogin/disconnectlogic.go; go/login/internal/logic/clientplayerlogin/session_lifecycle.go
- client messages: 58 ClientPlayerLoginDisconnect（不回包）
- tables: none
- depends on: short-reconnect-lease
- behavior: 清掉 login_session。然后 SetDisconnecting(player_id 取自 SessionDetails, session_id 取自请求体)。客户端自己发的 58 填 0，player_locator 因会话不匹配而忽略，所以实际只起清理登录会话的作用。gate 在 TCP 断开时合成的 58 带真实 session_id，会触发 30s 断线租约。失败时重试 4 次，退避从 200ms 起翻倍，仍失败记 CRITICAL 日志。
- internal: 这是断线租约链的唯一入口：调用丢失则会话永久停在 ONLINE（会话键没有 TTL）。
- java: done — xm-login DisconnectHandler 只记日志；gate TCP 断开时调 ClientMessageService.sessionClosed，scene 立即写回并移除玩家（没有租约）
- size: S
- robot: robot_smoke 收尾；login-test: DisconnectDuringLogin / RapidDisconnectReconnect
- hazards: 请求体里的 session_id 来自客户端，是不可信输入；只是 player_id 取自会话上下文，所以最多影响自己的会话。

### short-reconnect-lease — 短线重连（30s 断线租约、回原位）与租约到期清理
- mmorpg: go/player_locator/internal/logic/setdisconnectinglogic.go; go/player_locator/internal/logic/reconnectlogic.go; go/player_locator/internal/logic/leasemonitor.go; go/player_locator/internal/logic/session_cas.go; go/login/internal/logic/pkg/sessionmanager/session_manager.go (DecideEnterGame / CanReconnect)
- client messages: 26（重连时应答不变，随后收到 79）; 断线租约到期时 gate 直接 forceClose，不发任何消息
- tables: none
- depends on: session-registry; enter-game-core; server-push-to-player
- behavior: 断线时：ONLINE → DISCONNECTING，版本 +1，ZADD player:leases（到期时间 = now + 30s）。30s 内同账号对同一角色 EnterGame，判定为 ShortReconnect：Reconnect 的 CAS 要求状态是 DISCONNECTING 且账号一致，换成新的 session/gate，版本 +1，状态回 ONLINE，同时撤销租约；BindSession 带 LOGIN_RECONNECT；EnterScene 的 ZoneId=0，由 scene_manager 按原位置决定落点。不踢任何连接，客户端照常收到 26 和 79，回到原位。租约到期：LeaseMonitor 每秒按批（100）claim 到期项，用 processing ZSET + token + 30s claim TTL + 10s 心跳保证可靠；只删除 claim 时看到的那一版会话；然后向 gate 发 Kafka PlayerLeaseExpiredEvent（gate 若连接还挂着就 forceClose），并调 SceneManager.LeaveScene（场景取自会话或 player:{id}:location）。任一副作用失败就保留 claim 重试。若存在有效的 player:afk_pass:{id}，租约续 300s；探测失败推迟 10s。
- internal: 所有生命周期变更都是比较完整 protobuf 字节的 Lua CAS，相当于 session_id + version 的双字段 CAS。
- java: missing — Java 断线时 scene 立即写回并移除玩家；重连按首登处理：从 MySQL 重新加载，旁观者先看到 51 再看到 21（PARITY「登录/建角」行写明待做）
- size: L
- robot: login-test: RapidReconnect / RapidDisconnectReconnect
- hazards: afk_pass 键在全仓（cpp/go/java/tools）都没有写入方，是死分支。gate 到 login 的 Disconnect 丢失时，会话永远停在 ONLINE（公会一直显示在线、LeaveScene 永不执行）。Java 实现时，scene 要在租约内保留实体并能重新绑定新会话，同时与 owner_epoch 写回、归属续约协调。

### replace-login — 顶号（同一角色从新连接进游戏，旧连接被踢）
- mmorpg: go/login/internal/logic/clientplayerlogin/entergamelogic.go (persistEnterGameSession / kickReplacedSession); go/login/internal/svc/gate_command.go (buildKickPlayerCommand)
- client messages: 34 SceneClientPlayerCommonKickPlayer（S2C GameKickPlayerRequest{reason:{id:2017}}，发给旧连接）; 26 / 79 给新连接
- tables: none
- depends on: session-registry; server-push-to-player
- behavior: 已有会话且不满足「DISCONNECTING 且同账号」，就判为 ReplaceLogin。向旧会话所在 gate 发 KickPlayerEvent：旧连接先收到 34 {reason 2017}，然后被 shutdown。踢失败只记日志，新会话照样建立（版本 +1，BindSession 带 LOGIN_REPLACE，EnterScene 的 ZoneId=0）。新连接正常收到 26 和 79。
- internal: 经 Kafka gate 命令投递，按 gate_instance_id 防止发给已重启的僵尸 gate。
- java: partial — Java 走归属协议：Redis pub/sub xm:owner-takeover 请持有方 scene 让出，旧连接收到 23 {2017} 后断开；不发 34；持有方 3s 内不让出回 2005（PARITY「顶号」行）
- size: S
- robot: login-test: AccountDisplacement / ConcurrentSameAccount
- hazards: Go 踢旧会话失败只记日志，旧连接可能和新连接同时在线一段时间。Java 若要补 34，需要 gate 在 PlayerKicked 时先发 34 再关连接。

### session-registry — 玩家会话登记与在线状态（player_locator，供 login 和其他服务查询）
- mmorpg: go/player_locator/internal/logic/setsessionlogic.go; go/player_locator/internal/logic/getsessionlogic.go; go/player_locator/internal/logic/markofflinelogic.go; go/player_locator/internal/logic/session_cas.go; go/player_locator/internal/logic/keys.go; go/player_locator/internal/logic/setlocationlogic.go; go/player_locator/internal/logic/getlocationlogic.go; proto/player_locator/player_locator.proto
- client messages: none
- tables: none
- depends on: none
- behavior: 客户端间接可见：好友和公会成员的在线状态、推送能否送达、顶号与重连的判定都依赖它。
- internal: player:session:{id} 存 PlayerSession（session_id, gate_id, gate_instance_id, scene_node_id, scene_id, session_version, state 为 ONLINE/DISCONNECTING/OFFLINE, last_active_ts, request_id, account），没有 TTL。SetSession 要求新版本 = 当前版本 + 1（或内容完全相同），保留原有的 scene 字段；上一轮租约清理还没 ack 时 fail-closed 拒绝写入。friend、guild、chat 直接读这个键判在线、找推送目标；AssignGate 也读它，用来让重连玩家绕过排队。SetLocation 已停用（直接返回错误）；GetLocation 读旧的 player:location:{uid}。
- java: missing — Java 的会话只在 gate 内存里，归属在 MySQL；login 自身用不到这份登记（这一用途不适用）。但没有跨服务的「玩家 → gate 会话 / 在线状态」查询，好友、公会、聊天推送都会需要
- size: M
- robot: 间接：login-test 全部场景
- hazards: 会话键无 TTL，一旦断线租约链丢一步，会话就永久停在 ONLINE。Java 做这份登记时应带 TTL 或心跳续约，不要照抄。

### dead-gate-session-reconciler — 失联 gate 的会话对账
- mmorpg: go/player_locator/internal/logic/session_reconciler.go; go/player_locator/player_locator.go
- client messages: none
- tables: none
- depends on: session-registry; short-reconnect-lease
- behavior: 客户端不可见。gate 崩溃后，挂在它上面的玩家最终会走完离线流程。
- internal: 每 60s SCAN player:session:*。ONLINE 会话的 gate_instance_id 若不在 etcd 存活 gate 列表中，并且连续两轮都如此，就 CAS 改成 DISCONNECTING 并加入租约，交给 LeaseMonitor 清理。
- java: not_applicable — Java：scene 发现 gate 链路断开即按断线写回；gate 丢节点号租约时关闭全部会话；归属租约 30s 兜底（architecture.md §4.2、§6、§7）
- size: M
- robot: none
- hazards: 

### enter-game-cross-zone — 进场去向：home_zone 弹回、重定向票据绑定
- mmorpg: go/login/internal/logic/clientplayerlogin/entergamelogic.go (resolveEnterSceneRoute); go/login/internal/logic/pkg/homezone/homezone.go (ResolveEnterZone / TicketPinsZone)
- client messages: 26（2011：票据持有者与请求不符）; 124 SceneClientPlayerCommonRedirectToGate（S2C RedirectToGateNotify）
- tables: none
- depends on: home-zone-mapping; enter-game-core
- behavior: SessionDetails.ticket_player_id 非 0 且不等于请求的 player_id，在加锁之前就回 2011。ticket_target_zone_id 等于本 zone 时直接进本 zone，不弹回。ShortReconnect 和 ReplaceLogin 的 ZoneId=0，由 scene_manager 按原位置决定。FirstLogin 且会话里没有场景、并开启 RedirectOnEnterEnabled（dev 默认关）时，查 home zone（超时 1.5s），不在本 zone 就由 scene_manager 回 Redirect：客户端收到 124，26 的应答仍然是成功。
- internal: data_service GetPlayerHomeZone；scene_manager 负责签发重定向票据。
- java: missing — Java 的 EnterGame 不看票据和 home zone；xm-gateway 签 gate 令牌时把 target_zone_id 填成 zone_id，而 mmorpg 普通 AssignGate 令牌填 0，表示「普通票据」
- size: M
- robot: travel_smoke.yaml（跨区传送）
- hazards: 

### home-zone-mapping — 角色归属区（player:zone）登记与角色列表刷新（合服支撑）
- mmorpg: go/login/internal/logic/pkg/homezone/homezone.go; go/login/internal/svc/home_zone.go; go/login/internal/logic/clientplayerlogin/createplayerlogic.go (registerHomeZone); go/login/internal/logic/clientplayerlogin/loginlogic.go (buildRoleList); go/shared/placement/placement.go
- client messages: 48（角色的 zone_id）; 14（登记失败回 2020）
- tables: none
- depends on: create-player
- behavior: 建角时调 data_service.RegisterPlayerZone（超时 3s），失败则整体拒绝建角并回 2020，同时释放已登记的名字。可按 Placement.PinOnCreate / NewPlayerStorageId 一并写 storage_id。Login 的角色列表用 BatchGetPlayerHomeZone（超时 500ms）覆盖各角色的 zone_id：查询失败或映射缺失时保留建角时的 zone，结果不回写账号（映射是唯一真源）。开关 RefreshRoleListDisabled 可关掉刷新。
- internal: player:zone:{id} 与 player:placement:{id} 位于映射用的 Redis，无 TTL；合服工具只改映射。
- java: missing — Java 角色的 zone_id 就是 player 行的建角 zone，没有合服映射
- size: M
- robot: none
- hazards: 

### post-merge-flags — 合服后一次性提示（EnterGameResponse 字段 3、4）
- mmorpg: go/login/internal/logic/clientplayerlogin/entergamelogic.go (consumePostMergeFlags); proto/common/component/player_comp.proto (PlayerMergeStateComp)
- client messages: 26 EnterGameResponse.post_merge_notice_ts / force_rename_required
- tables: none
- depends on: home-zone-mapping
- behavior: player_merge_notice:{id} 存在时填 post_merge_notice_ts，并删除该键，只提示一次。player_force_rename:{id} 存在时填 force_rename_required=true，但不删除该键。读取出错时不阻断进场。
- internal: 标记由 tools/merge_zone 写入。
- java: missing — Java 两个字段恒为默认值
- size: S
- robot: none
- hazards: 全仓没有改名 RPC，force_rename 标记一旦写入就永远清不掉，客户端会每次进场都弹强制改名。

### orphan-role-removal-admin — 回档孤儿角色清理（LoginAdmin.RemovePlayersFromAccounts）
- mmorpg: go/login/internal/logic/admin/remove_players_from_accounts.go; go/login/internal/logic/loginadmin/removeplayersfromaccountslogic.go; go/login/client/loginadmin/loginadmin.go
- client messages: 111 LoginAdminRemovePlayersFromAccounts（内部 gRPC，非客户端协议）
- tables: none
- depends on: create-player
- behavior: 运维或回档工具调用。按 player_to_account:{id} 反查账号，把这些角色从账号 blob 里移除，再删反向索引。返回 removed、not_found、failed 三个计数。配合 data_service RollbackPlayer（99）使用。
- internal: 直接改写 Redis 中的账号 blob。
- java: missing — Java 角色是 player 表的行，删行即从列表消失；等 Java 做回档时再定是否需要
- size: S
- robot: none
- hazards: Go 改写账号 blob 时不持建角锁，与并发建角互相覆盖，可能丢掉刚建的角色。

### assign-gate — 分配 gate 与签发 gate 令牌（快速通道）
- mmorpg: go/login/internal/logic/loginpregate/assigngatelogic.go; go/login/internal/logic/pkg/loginqueue/gatetoken.go; go/login/internal/svc/servicecontext.go (CandidatesForZone / buildGateCandidates); go/shared/clientendpoint
- client messages: HTTP POST /api/assign-gate（由 Java gateway 调 118 LoginPreGateAssignGate）; ClientTokenVerifyRequest 握手使用签出的令牌
- tables: none
- depends on: none
- behavior: 从 etcd 取本 zone 的 gate 候选（zone_id=0 表示 watcher 看到的任意 gate），排除标记 draining 的 gate。客户端地址按 clientendpoint.Select 选取，开启 RequireClientEndpoint 时没有客户端地址的 gate 不下发；同一 host:port 只保留 launch_time 最新的那条。按 PlayerCount 升序、NodeID 升序选负载最低的 gate。令牌 GateTokenPayload{gate_node_id, zone_id, expire_timestamp=now+10min, hmac_session_key=32 随机字节}，签名为 HMAC-SHA256 的 64 字节小写 hex。排队开启且账号已有带 gate 的会话时绕过排队。HTTP 成功时 code=0，并带 gate_ip / gate_port / token_payload / token_signature（base64）/ token_deadline。
- internal: gateway 经 gRPC 调 login 签发；密钥来自 Secrets.GateToken.Primary。
- java: partial — xm-gateway AssignGateService / GatePicker（最低负载、过滤 draining、跳过无地址条目）/ GateTokenIssuer（10min 有效、32 字节会话密钥）
- size: S
- robot: robot_smoke（HTTP assign-gate）；Java SmokeScenario（AssignGateClient）
- hazards: Java 对 zone_id=0 回 404，mmorpg 是自动选区（已登记 PARITY）。Java 令牌的 target_zone_id 填了 zone_id，而 mmorpg 填 0（客户端不解析令牌，但语义不同）。Java 没有「同地址保留最新」去重：崩溃 gate 的旧目录条目在 TTL 内仍可能被选中，客户端会被新 gate 以 node 不匹配拒绝。

### login-queue — 登录排队（容量计算、排队令牌、准入分发选主）
- mmorpg: go/login/internal/logic/pkg/loginqueue/queue.go; go/login/internal/logic/pkg/loginqueue/capacity.go; go/login/internal/logic/pkg/loginqueue/dispatcher.go; go/login/internal/logic/pkg/loginqueue/gatetoken.go; go/login/internal/logic/pkg/loginqueue/metrics.go; go/login/internal/logic/loginpregate/assigngatelogic.go; go/login/internal/logic/loginpregate/querystatuslogic.go; go/shared/leader
- client messages: HTTP /api/assign-gate code=100 {queue_token, queue_rank(从 0 起), queue_total, retry_after_ms=2000}; HTTP /api/queue-status {queue_token} → code 0（带 gate 令牌）/ 100 / 410; 内部 118 LoginPreGateAssignGate、138 LoginPreGateQueryQueueStatus（status 0 准入 / 1 排队 / 2 错误 / 3 过期）
- tables: none
- depends on: assign-gate; session-registry
- behavior: zone 容量 = ZoneCapacityOverride，没配则 max(online, 1) × SoftCapMultiplier（1.5）。空位 = 容量 − 在线 − 已准入未入场。有空位且队列为空时走快速通道：原子占一个名额后直接签令牌。否则按毫秒时间 ZADD 入队。排队令牌 = base64url(JSON{k:"queue", q, z, e} + "." + hex HMAC)，有效 1h；验签时遍历全部候选密钥、不短路，支持轮换；签名不符或过期回 EXPIRED（HTTP 410）。分发器是单 leader（Redis 锁 30s，每 TTL/3 心跳），每秒对每个活跃 zone ZPOPMIN 一批：准入时选 gate，客户端来取时才签令牌（有效期从签发时起算 10min）。准入记录 admit:{queueId} 有效 60s，用 GETDEL 保证只能取一次。默认关闭。
- internal: Redis 键：queue:zone:{z}（ZSET）、admitted:zone:{z}（SET，整体滑动 TTL）、admit:{id}、queue:meta:{id}、queue:token:{t}。另有指标：等待时长、过期原因、是否 leader。
- java: missing — xm-gateway AssignGateRequest 解析 queue_token 但按首次请求处理；没有 /api/queue-status
- size: L
- robot: robot 的 HTTP 排队轮询逻辑（http_assign_gate.go）；robot.smoke-5k 等压测配置
- hazards: admitted 集合的成员从不单独删除：快速通道占位的 fast:* 永不 SREM，未来取的准入也一直留着；而整个集合的 TTL 每次 SADD 都会续上，所以持续有人登录时集合只增不减，空位最终归零，所有人都被迫排队（注释说「占位成员按 admitTTL 过期」与 Redis 语义不符）。不再轮询的幽灵条目照样消耗准入名额。选 gate 失败时以 score 0 回插，相当于插到队首。绕过排队只检查账号的第一个角色。Java 实现时应改用带每成员过期时间的 ZSET 做在途集合。

### gate-drain — gate 缩容排空（draining / drained 标记）
- mmorpg: go/login/internal/logic/pkg/loginqueue/gatedrain.go; go/login/internal/logic/pkg/loginqueue/gatedrain_monitor.go; go/login/internal/svc/servicecontext.go (startGateDrainMonitor)
- client messages: none
- tables: none
- depends on: assign-gate
- behavior: 玩家可感知：被标记排空的 gate 不再分到新玩家，已在上面的玩家不受影响。
- internal: 运维给 gate:{id}:draining 打标记（必须带 TTL）。监视器每 5s 检查一次：在线人数 ≤ DrainedBelowPlayers（0），或从标记起已过 Deadline（25min，此时打 ERROR），就写 gate:{id}:drained，其 TTL 取 draining 的剩余 TTL，并顺手清理残留的 drained 标记。缩容脚本 k8s_gate_drain.ps1 等到 drained 才删 Pod。
- java: partial — xm-gateway GatePicker 会过滤 draining，全部 draining 时忽略标记继续分配；但 xm-gate GateNode 恒写 setDraining(false)，没有运维入口，也没有 drained 信号
- size: M
- robot: none
- hazards: 

### killswitch — RPC 热关停（etcd 规则）
- mmorpg: go/shared/killswitch; go/login/login.go (newKillSwitch); go/player_locator/etc/player_locator.yaml (KillSwitchPrefix)
- client messages: 被关停的方法在客户端表现为 1003（经路由服）
- tables: none
- depends on: none
- behavior: 运维在 etcd 写 /mmorpg/killswitch/{service}/{method} 或 {service}/'*'，值为 {"deny":true,"reason":...}，对应方法秒级停止服务，返回 gRPC Unavailable（可配置状态码）。拦截器位于验签之前。etcd 不可用、规则写坏、规则过期时一律放行（fail-open）。
- internal: list-watch + 定期重同步；有指标 killswitch_blocked_total。
- java: missing — Java 没有对应机制（可考虑 Nacos 配置 + Dubbo Filter）
- size: M
- robot: none
- hazards: 

### internal-caller-auth — 内部调用方身份声明验签
- mmorpg: go/login/internal/logic/pkg/callerauth/*.go; go/login/login.go (newSessionInterceptor); go/login/internal/config/secrets.go
- client messages: none
- tables: none
- depends on: none
- behavior: 客户端不可见。防止内网任意进程冒充任意会话或玩家调用 login。
- internal: x-session-detail-bin 必须附 HMAC-SHA256 签名。被签名的串依次为：版本、caller、方法全名、sha256(subject)、毫秒时间戳、nonce。允许时钟偏差 30s；nonce 表上限 50 万条；可配调用方白名单；dev/test 只告警，其余模式强制。不带声明的调用（gateway 新路径）照常放行。密钥分 GateToken / QueueToken / InternalAuth 三把：生产环境要求非占位、不少于 32 字节、互不相同，否则拒绝启动；每把支持「主密钥 + 只验不签的旧密钥」做轮换。
- java: not_applicable — Java 用 Dubbo 调用方鉴权：xm-common DubboCallAuth + xm-api DubboAuthProviderFilter，MAC 输入为「接口|方法|ts」，时间窗 60s
- size: M
- robot: none
- hazards: Java 的 MAC 不覆盖请求体和 SessionContext，也没有 nonce：能截获内网流量的人可以在 60s 内把附件嫁接到伪造的会话上下文上。Java 的 gate 令牌密钥不支持轮换。

### non-scene-client-rpc-routing — 非场景服务客户端 RPC 路由（client_rpc_router）
- mmorpg: go/client_rpc_router/internal/logic/forwardlogic.go; go/client_rpc_router/generated/pb/game/route_table.go; go/client_rpc_router/internal/svc/servicecontext.go; go/client_rpc_router/internal/discovery/node_watcher.go; go/client_rpc_router/internal/rawcodec/rawcodec.go; go/client_rpc_router/internal/constants/errors.go; proto/client_rpc_router/client_rpc_router.proto
- client messages: ClientPlayerLogin 48/14/26/17/58/127; chatpb.ClientPlayerChat、friendpb.ClientPlayerFriend、guildpb.GuildService、teampb.ClientPlayerTeam、match.MatchService、trade.ClientPlayerJubaozhai 的全部方法; BattleClientPlayer 一律拒绝（回 1003）
- tables: none
- depends on: internal-caller-auth
- behavior: Forward(ForwardRequest{ClientRequest, zone_id})：按 message_id 查生成的路由表。不在表里或所属服务不是客户端协议，回 MessageContent{id, message_id, error_message 1005}。目标是 Battle（战斗只走直连）、没有可用实例、拨号失败、上游 gRPC 错误或超时（5s），都回 1003。成功时回 MessageContent{id=请求 id, message_id=请求号, serialized_message=原样应答字节}。LoginNodeService 只在同 zone 实例中随机选，其他类型全局随机。请求缺少 x-session-detail-bin 元数据时回 gRPC Unauthenticated。
- internal: 每种目标类型一个 etcd watcher；连接缓存；用原样字节编解码（不解析 body）；透传全部 x-* 元数据并在响应头回显会话；错误日志按 1/1024 采样；Forward 方法的 Stat 日志不打内容，避免账号口令落日志。
- java: partial — xm-gate MessageRoutes：标了 OptionIsPlayerService 的服务交给 scene，ClientPlayerLogin 走 login（Dubbo）；其余服务回 23 {1003}（与 C++ 直连模式同形，路由模式是信封错误，两者都可接受）
- size: M
- robot: chat_smoke、friend_smoke、guild_smoke、team_smoke、trade_smoke（mmorpg）；Java 只覆盖 login
- hazards: 路由表按「服务是否标了客户端协议」放行，因此服务端推送用的 Notify* 方法（NotifyTeamEvent、NotifyGuildChanged、NotifyFriendEvent 等）客户端也能上行调用，会被转发到后端；Java gate 的白名单同样按服务粒度。后续每接一个后端，就在 SERVICE_BACKENDS 加一项并给出 Dubbo 实现。

### server-push-to-player — 非场景服务向在线玩家推送（单推 / 多推 / 场景广播 / 全服广播）与 gate 会话命令
- mmorpg: go/shared/kafkautil/gate_push.go; go/shared/kafkacmd/command_topic.go; go/login/internal/svc/gate_command.go; go/player_locator/internal/logic/leasemonitor.go (sendLeaseExpiredToGate); proto/contracts/kafka/gate_command.proto; proto/contracts/kafka/gate_event.proto
- client messages: 23 SendTipToClient（login 推 3023）; 34 KickPlayer（经 KickPlayerEvent）; 124 RedirectToGate; 各服务的 Notify* 推送（好友 / 公会 / 组队 / 匹配 / 挑战）
- tables: none
- depends on: session-registry
- behavior: 玩家可感知：好友、公会、组队、匹配的实时通知；进场失败的提示；顶号踢人；断线租约到期断开连接。
- internal: GateCommand{player_id, session_id, target_gate_id, target_instance_id, event_id, payload, enter_gs_type} 写入 gate 命令 topic，分区 = node_id % 256（分区数是寻址契约，由基础设施预建）。事件类型：BindSession、KickPlayer、PushToPlayer、BroadcastToPlayers（会话列表或位图）、BroadcastToScene、BroadcastToAll、RedirectToGate、PlayerLeaseExpired。gate_instance_id 为空或 node_id=0 时拒发（防发给僵尸 gate）。多推按 gate 分组。
- java: missing — Java 的 login 与 scene 用 Dubbo 应答里的会话指令和节点链路下发；其他后端没有给在线玩家推消息的通道
- size: M
- robot: friend_smoke、guild_smoke、team_smoke（mmorpg）
- hazards: 

### asset-channel — 跨服务资产通道（assetop：Go 服务对在线玩家货币 / 物品一次且仅一次地扣发）
- mmorpg: go/shared/assetop/*.go; go/shared/scenenode/locator.go; go/shared/scenenode/conn.go; go/shared/scenenode/watcher.go
- client messages: 间接：公会捐献、公会商店购买、聚宝斋交易时的货币或物品变化（由 scene 推送）
- tables: none
- depends on: session-registry
- behavior: 玩家可感知：公会和交易操作对背包、货币的改动恰好生效一次；余额不足等情况回业务 tip（asset_error_tip）。
- internal: 业务事务内先写一行 outbox，拿到该玩家该流的递增 seq 和 stream_epoch。按 player:{id}:location 定位玩家所在 scene 节点，调 SceneNodeGrpc 的 AssetDebit / AssetCredit / AssetAbortDebit；请求体内带 HMAC 签名和时间窗。只有回复为 APPLIED 或 REJECTED 且 durable=true 时才把 outbox 行改走 PENDING；否则同一请求按 100/200/400ms 重查。对账循环（有界并发）处理滞留的 PENDING。账本分类逻辑与 C++ 共用同一张测试表。部分应用转人工补偿。
- java: missing — Java 尚无公会 / 交易，也没有 scene 侧资产账本
- size: L
- robot: guild_economy_smoke、trade_smoke（mmorpg）
- hazards: 源码注释写明「全部代码未编译，待 Codex 验证」，成熟度存疑。分类逻辑若与 scene 侧分叉会静默丢物或卡单。

### game-day — 游戏日 / 游戏周切点（05:00，UTC+8）
- mmorpg: go/shared/gameday/gameday.go
- client messages: 间接：每日 / 每周次数与限购的重置时刻
- tables: none
- depends on: none
- behavior: 固定时区 UTC+8，不依赖 tzdata，无夏令时，每天 05:00 切日。DayKey 形如 yyyymmdd；WeekKey 为 ISO 年 × 100 + ISO 周号，周一 05:00 切周。提供 NextDailyReset、NextWeeklyReset、PeriodKey（0 不限 / 1 每日 / 2 每周，其他值非法）。
- internal: 纯函数，以 time.Time 为入参，不读墙钟。
- java: missing — 
- size: S
- robot: none
- hazards: 

### player-data-preload — 进场前玩家数据预热与身份补齐（Kafka DB 任务）
- mmorpg: go/login/internal/logic/pkg/dataloader/*.go; go/login/internal/dispatcher/task_result_dispatcher.go; go/login/internal/kafka/key_ordered_producer.go; go/login/internal/kafka/expand_monitor.go; go/login/internal/logic/pkg/consistent/consistent.go; go/login/internal/logic/clientplayerlogin/player_class_backfill.go; go/login/internal/logic/clientplayerlogin/role_appearance_backfill.go
- client messages: none
- tables: none
- depends on: none
- behavior: 客户端只看到预热失败时推的 23 {3023}，以及线程池满时回的 2005。
- internal: 先看 PlayerAllData 父键是否已缓存；未命中的子消息按玩家 id 一致性哈希分区，经有序 Kafka 生产者发 DB 读任务；结果由 Redis Pub/Sub 回调分发，每个任务 8s 超时；预热线程池 256，非阻塞提交。分区数漂移时生产者 fence，不再发送。身份补齐（职业 / 名字 / 外观 / 性别）用 Lua 写回存档，以玩家锁、会话键与位置键都不存在为围栏。
- java: not_applicable — Java scene 直接从 MySQL 加载玩家数据，用 owner_epoch 围栏；职业、名字、外观与角色同在 player 一行，不需要补齐
- size: L
- robot: 间接：robot_smoke
- hazards: 

## Open questions

- LeaveGame 之后能否不重新 Login 直接回选角（Go 不行，Java 可以），需要两版定一个口径。客户端做回选角 UI 前必须先定。
- 短线重连在 Java 里怎么做：scene 在 30s 内保留实体并重新绑定新会话，还是保持现状（断线立即写回、重连按首登）？前者要和 owner_epoch 续约、写回时机一起设计。
- Java 是否需要跨服务的玩家会话登记（类似 player_locator）？好友、公会、聊天的在线状态和推送都依赖它，而 Java 的会话目前只在 gate 内存里。
- mmorpg 登录排队的 admitted 集合只增不减（fast:* 占位与未来取的准入永不 SREM，整体 TTL 随每次 SADD 续上），持续有人登录时空位会归零。是否先在 mmorpg 修复，再让 Java 按修正后的语义实现？
- AssignGate 的 zone_id=0：mmorpg 实际选的是 login watcher 前缀（本 login 的 zone）下的 gate，Java 回 404。需要确认客户端是否真会发 0。
- Java gateway 令牌的 target_zone_id 填了 zone_id，mmorpg 普通令牌填 0（0 表示「普通票据」，非 0 表示重定向票据）。日后 Java 做跨区重定向时这个区分会生效，是否现在就改成 0？
- player:afk_pass:{id}（月卡挂机延长断线租约）在全仓没有写入方，这个功能是否已规划？Java 是否需要保留这个分支？
- force_rename_required 没有对应的改名 RPC，标记永远清不掉。合服强制改名流程是否要补 RPC，Java 要不要跟着做？
- /api/login 新路径与 Java Gateway 的限流（Bucket4j，返回 429）、区服白名单都在 mmorpg 的 java/gateway_node 里，不在本区，需要确认由哪个清单覆盖。
