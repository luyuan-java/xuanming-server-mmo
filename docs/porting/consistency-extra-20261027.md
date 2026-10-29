# 7.4 一致性巡检：好友、帮会、交易与作业明细

本批是 `consistency-core-20261027.md` 的第二批扩展，已由根代理核对候选、依赖源码与补丁 SHA-256 后接入独立工作树；尚未编译或运行。原候选、静态复核及集成回执保留在仓外。没有改 C++、Java main 或同步契约，没有新增依赖。

固定源基线为 C++/Go `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`。原版 `tools/data_consistency_check/main.go` 实际仅登记五项检查，不能把旧 Java 规格的完整检查目录当成 C++ 已有的实现。本批按 `docs/porting/data-tools-spec.md` §1.4 和更新后的 `inventory/full-migration-tools-20261026.md` §7，为当前 Java SQL 权威表补足检查。表、状态和维护窗口以本次 Java 源码为准。

## 实际范围

| 检查 | 判据 | 结果 |
|---|---|---|
| friend.capacity | 容量计数与同语句边计数不同；或存在边但无容量行 | BLOCK；零边且无容量行合法 |
| guild.member | 成员和申请的帮会、玩家两端引用 | 缺帮 BLOCK、缺玩家 WARN；不扫永久锁守卫 `guild_player_state` |
| guild.leader | 名义帮主是本帮 role=3 成员，且本帮恰有一个 role=3 | BLOCK；角色编码取 Java 正式表定义，不把空号 2 当帮主 |
| guild.capacity | 实际成员数超过该帮 `max_members` | WARN；不硬编码配置上限 |
| zone.guild_member_home | 正式成员的玩家归属与帮会归属不同 | BLOCK；不将待审批的异区申请当成员 |
| zone.trade_listing | 活跃挂单 market_zone 为零、缺区或退休区 | BLOCK |
| zone.trade_listing_home | 活跃挂单 market_zone 与卖家当前归属不同 | WARN；比较当前市场，不比较审计字段 seller_zone_at_listing |
| trade.seller | 活跃挂单卖家、收藏记录收藏者缺玩家原件 | WARN；不因历史挂单不存在而把收藏者误判为孤儿 |
| ops.job_residue | 逐玩家明细缺作业原件；或 SUCCEEDED 作业仍有 PLANNED 明细 | WARN；**只覆盖部分子规则，整项永远保留 UNKNOWN 覆盖缺口** |

交易活跃状态是 ESCROWING=1、LISTED=2、LOCKED=3、RETURNING=5；SOLD=4、RETURNED=6、ESCROW_REJECTED=7 是历史终态，不强套卖家当前归属。未知状态不能被当成历史终态跳过。当前 Java `ListingStatuses` 与契约枚举由 `TradeTablesTest` 逐值对拍，本检查复用已有依赖中的枚举，不引入 game-trade 服务装配。

三个新增 zone 检查在读取实体的同一条 SQL 中观察 `route_meta`。`ZoneMergeStore` 先以事务关闭全局 `writable`，再分阶段迁移玩家、帮会与挂单，最后才重新打开；关闭期间归属不同是合法中间态，返回 UNKNOWN(concurrent_maintenance)。元数据缺失、代次格式或分片数不合法也返回 UNKNOWN。代次、写闸和实体投影共同参与二读稳定性检查，避免独立预检后维护开始的竞态。空源仍通过固定 SQL 的依赖证明确认引用表及列存在；空源没有实体原件时不据此证明全局 route 元数据健康，需另选 `route.meta`。

`ops.job_residue` 尚不判断 `ops_active` 心跳与终态残留。`OpsJobRunner` 写终态后还会调用 `afterResult` 才释放活动槽，简单“终态 + 槽存在”会把合法收尾当坏行；心跳过期也必须采用所有实例共同的 stale-after 与时钟政策。候选不猜阈值、不清活动槽、不把空明细伪装为全项通过。FAILED、CANCELLED、INTERRUPTED、REJECTED 等终态的 PLANNED 明细是合法未写记录。

默认 `CORE` 从 11 项扩为 20 项。本批新增八项具备上表定义的完整 SQL 规则，`ops.job_residue` 为部分规则；核心原有匹配深读等边界仍以核心文档为准。默认运行会明确保留作业残留项的未知覆盖；只有显式选择已完整实现且实际完整读完的项才可能退出 0。这不代表持久 run、分布式单飞、调度、深读、Redis Cluster 或全部旧检查已经完成。

`account.player_cap` 保留 UNKNOWN(login_cap_authority_unavailable)：上限是 game-login 每实例的运行配置，不能在 game-data 硬编码默认 5。`own.lease_skew`、`own.abandoned` 分别保留租约时钟/观察政策缺失的固定 reasonCode，不用本机墙钟猜共享阈值。

## 读取与验证

本批仅增加固定 SQL 计划，复用核心的专用 SELECT 账号池、生产 TiDB 强读核验、namespace 核验、单调总截止、单次最多两秒请求、主键 upper/keyset 分页和两秒后二读。不调用好友、帮会、交易或运维的恢复型业务读口，也不借公共业务池。子查询 COUNT 按 friend.player_id、guild_member.guild_id 的主键前缀读取；仍需真实 TiDB/MySQL 的 EXPLAIN 验证访问计划，不能把 H2 或源码检查当查询计划证据。

样本继续按每轮盐摘要，不输出账号、玩家名字、请求正文、物品内容、密码或令牌。新增投影只包括判据、原件身份与版本；缺依赖、预算耗尽、第二读失败或原件变化保留 UNKNOWN 和已确认发现的下界。

新增 `ConsistencyExtraSqlPlansTest` 共 27 个 `@Test`，覆盖各规则、所有交易状态、双读变化、合服中间态与代次变化、孤儿/未知作业形状、空表缺依赖、分页预算、零号及 UINT64 高半区组合主键。它们使用进程内 H2 的最小列投影，不读取真实数据库环境，不是正式 DDL 或 TiDB 强读测试。所有旧断言保留；本批只有源码与静态审查证据，没有 JUnit 运行结果。

整批集成和全量 `clean verify` 必须由根代理在构建时段归还后统一执行。仓外 `candidate-manifest.json` 记录待新增文件、wiring patch、依赖源码 SHA-256 与固定源 Git blob；`integration.patch` 是可审核的完整候选补丁。
