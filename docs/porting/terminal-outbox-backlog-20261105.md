# 终局发件箱三类积压观察

参考为 C++ 仓库固定提交 `993b6da9d4ea51d5e4a27cc6ec17004af0fb97c1` 的 `go/match/internal/authority/outcome_outbox.go:377-415`、`logic/global_expired_audit.go:76-96` 与 `metrics/outcome.go`。工作区后来移动不改变本项参考。Java 基于 `0b578ea767321fcfa6006f0eefa8a7e61776c2b8` 及 2026-11-05 已集成的异常成局周期巡检；不修改 C++、协议或业务表。

本次补齐只读库存指标。已有结果发布、个人首投、永久 Inbox seq 查询及 APPLIED 确认不变；观察器没有投递、租约、释放票据、维护排空或修复权利。

## 读数与状态映射

| kind | Java 固定谓词 | 数量单位 |
|---|---|---|
| result | `match_battle_completion WHERE terminal_decision=1 AND result_published=0` | 未确认 Kafka 发布的 COMPLETE 整局 |
| first | `match_battle_player_completion WHERE applied=0 AND owner_revision>0 AND inbox_seq=0` | 尚无永久 Inbox seq 的个人来源 |
| waiting | `match_battle_player_completion WHERE applied=0 AND owner_revision>0 AND inbox_seq>0` | 已入箱、未确认 APPLIED 的个人来源 |

参考的 `dispatch_state=1` 在 Java 合法来源中对应 `applied=0 AND owner_revision>0`；旧 Kafka 无 owner 的纯归档不参与投递，不计 first。此映射不证明坏行完整性；不能靠过滤后的积压零值判断所有终局原件完整。seq 在 SQL 按无符号列比较，超过 signed long 的合法 seq 仍归 waiting。

Java 没有参考的 `archived_ms` 字段。`GlobalMatchStore` 的 COMPLETE 事务先读数据库 stamp，再用同一个 stamp 写整局和每个个人来源的 `issued_at_ms`；重复提交不会刷新它。因此 MIN 使用 `issued_at_ms`。年龄为 `(SQL 当前毫秒 - 最老 issued_at_ms) / 1000.0`，未来时间截为零，空类数量与年龄均为零。`retry_ms` 是下次尝试时间，不用于年龄，不过滤库存。首投/重放页大小也不限制库存计数。

非空类存在 NULL 时间，或者返回的最老时间为零、负数或无法安全解码时，整轮为 UNKNOWN。聚合仅在 `COUNT(issued_at_ms)=COUNT(*)` 时返回 MIN，避免 MIN 忽略某行 NULL 后掩盖证据缺失。合法数据库列是非空；这条约束用于防止异常 schema/数据被显示为可信年龄，不是逐行时间完整性审计。它比参考对坏时间的处理更严格，不改变合法来源语义。

## 一致性与预算边界

`TerminalOutboxBacklogQueries` 用一条 SELECT，三个 UNION ALL 聚合及外层数据库时钟在同一个 statement 中读取。三行固定 kind、count、oldest、db_now 必须完整、唯一、合法且时钟一致；解码完成、预算未过期、服务尚未关闭时，才把不可变 Observation 一次发布。任意查询、解码或预算失败都使三类数量与年龄一起失效，不保留部分新值冒充完整成功。

这表示该 SQL statement 强读快照内的库存观察，不保证读取后没有并发投递。Prometheus 逐个调用 Gauge，抓取期间可能跨两次 Observation 发布；不能把整次 scrape 称为原子快照。它也不与别的观察器或业务操作组成全局事务。

观察器有自己的单线程调度、互斥轮次、60 秒节拍和整轮 3 秒预算；单次数据库请求仍受 `ConsistencyBudget` 的 2 秒上限。与已集成的 `ExpiredFormationAudit` 不共用游标、Observation、调度器或关闭状态。独立配置 `game.data.consistency.terminal-backlog` 可关闭或收紧周期预算，有效期默认 150 秒。

依赖现有 `JdbcConsistencyReader` 的专用只读连接、namespace/强读证明、网络和资源关闭预算；没有普通 DataSource 回退、DDL、后台遗留查询或业务写入口。此 reader 的 maxActive=1 与人工一致性检查、异常成局巡检共享，竞争耗尽预算即 UNKNOWN，不扩大池逃避限制。

COUNT/MIN 返回三行仅限制响应体，不意味着数据库扫描成本恒定；真实数据规模下仍须验证执行计划与预算。当前表没有专门覆盖全部分类和 MIN 时间的索引，本补丁不修改 schema。

## 指标与失效语义

- `game.data.match.outbox.backlog.rows{kind}`，Prometheus 名 `game_data_match_outbox_backlog_rows`。
- `game.data.match.outbox.oldest.pending.age.seconds{kind}`，Prometheus 名 `game_data_match_outbox_oldest_pending_age_seconds`。
- `game.data.match.outbox.probe.state{state}`，固定 UNKNOWN、OBSERVED、STALE、STOPPED、DISABLED 的小写标签。
- `game.data.match.outbox.worker.terminated`，只有请求停止且专属线程确已退出才为 1。

只有 OBSERVED 的数量和年龄可用；初始、未配置、失败、过期、关闭和禁用均为 NaN。查询失败不会写健康零。有效期按单调时钟计算，不用进程墙上时钟算归档年龄。close 先禁止发布，再中断并有界等待线程；驱动即使忽略中断晚返，也不能把 STOPPED 恢复为 OBSERVED。STOPPED 不等于在途线程已经退出，后者由单独指标报告。

多副本读取同一逻辑全局库，同 namespace 的数量/年龄采用 max 聚合，不能 sum。三类单位不同，不能相加解释为玩家数。看板应同时检查采样状态与覆盖情况，避免有效副本的零值掩盖其他副本 UNKNOWN。指标没有 battle_id、player_id、owner 或异常原文标签。

参考在读失败时保留上次 gauge，本 Java 实现以显式 UNKNOWN/NaN 标明其已不可证明；这与本轮异常成局巡检的失效约定一致。默认 enabled=true；未配只读凭据时只报告 UNKNOWN，不能隐式连接业务库。独立开关为 `GAME_DATA_TERMINAL_BACKLOG_AUDIT_ENABLED`。

## 验证范围

新增测试源码覆盖真实 H2 执行原生产 SQL：空类、分类谓词、旧归档/APPLIED 排除、未来 retry、无符号高位 seq、MIN 时间、同 statement db_now、投递推进、超过重放页大小、只读不改行、混合 NULL 时间。H2 测试不是 TiDB 强读/高可用证明。

行为测试覆盖三类整体发布、零值覆盖、分数秒/未来年龄、坏数字或时间、缺少/重复 kind、时钟不一致、schema/强读失败、真实 100ms 预算晚返、单调有效期、真实线程重复启动和关闭、忽略中断的迟到回包、Prometheus 导出名和低基数标签。配置测试覆盖默认、禁用、专用 reader、非法预算；既有两项配置测试只明确关闭这个新后台观察器，保留所有原断言。

候选阶段未运行 Java、Maven 或容器。以候选 manifest 的源码摘要、静态补丁检查和独立审查为准；定向测试、真实 TiDB 和全量 clean verify 仍待取得构建时段后完成，不能宣称通过。

逐行投递 `outcome_outbox_delivery_total`、运行时 schema 世代 fence、其他分布式差距未由此项补齐；这不是全部 C++ 能力已移植的结论。

2026-11-06 追加：逐行投递计数已另补 Java 源码与 14 项测试源码，尚未编译运行，见 [逐行投递计数](terminal-delivery-metrics-20261106.md)。上段是本项库存观察自身的范围边界，不再表示逐行计数仍无实现。
