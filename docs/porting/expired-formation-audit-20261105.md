# 异常到期成局周期只读观察（2026-11-05）

本批为 Java 运维服务接入周期检查，不改变匹配、票据或终局事务。基线为 Java `0b578ea767321fcfa6006f0eefa8a7e61776c2b8`；C++ / Go 行为固定复核 `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`，最新对照 `993b6da9` 的同项计数 SQL 未变。所有源仓库只读，不改客户端契约、地图或其他会话代码。

## 源行为与 Java 放置

原版 `go/match/internal/logic/global_expired_audit.go` 每 60 秒用 3 秒预算调用 `CountStrandedExpiredFormations`，只报告无法进入正常到期恢复的形状。该循环不占正常匹配恢复的 500 ms 节拍，不自动中止或释放票据。原版多副本分别观察，指标不能跨副本相加当作唯一成局数。

Java 已有 `BattleCompletionReplay` 的正常到期恢复，以及 `game-data` 的一次性 `match.expired_unrecoverable` 只读检查。本批将周期能力放在 `game-data.consistency`：复用独立 SELECT 账号池、连接强读/namespace/剩余预算校验及 `MatchExpiredShape` 判定，没有新增 `game-match → game-data` 依赖或第二份形状判定。

与 Java 真实恢复保持一致：范围为 state 2/3/4、存在非空 Create、有效 deadline 且数据库时钟超过 deadline 60 秒，并排除已有终局决定 1/2 的历史行（包括仍为 state 4 的完成房间）。空 Create 的未创建出口、未来截止、state 1/5 不属于本周期候选。无效 deadline/空或 NULL Create 不在本周期范围，仍须由其他检查核对。state 4 是 Java 已建房但终局仍待定的范围，不能照抄原版 state 2/3 条件。

## 观察、分页与生命周期

- 默认每 60 秒独立单线程工作，整轮 3 秒，页长 128、每轮至多 1024 行。属性只允许预算不超过 3 秒、页长不超过 256、行数不超过 4096。任何查询都经原只读端口，借连接与读取仍受剩余预算约束。
- 先验证依赖列/权限，再捕获本次候选最大 unsigned battle ID，以 `BigDecimal` 游标分页。未走完时下一轮继续，失败行也推进到下一项；到上界后重新开始。上界后的新增行、插入到已走过区间的行延后到下一巡回。持续查询失败或坏游标仍只能报告 UNKNOWN，不能保证数据库不可读期间的发现时限。
- 每轮只发布本轮观察，不把跨轮结果累加为全库计数。`windowFinished` 只说明游标到达此前上界，不保证整个窗口在同一事务快照，也不代表所有成局健康。
- 坏形状必须立即再读原行且原字段未改变才计下界；缺失、变化或二读失败不视作确认。两次读取均沿强读端口，但没有两秒间隔，不宣称长期持续异常。只有 SQL 形状合法、却没有 Create 深读证明的行仍为 UNKNOWN。
- 默认启用；未配独立只读账号时每轮只报告 UNKNOWN，不借业务池、不启动 Redis、不反复输出缺配置日志。该池现有 `maxActive=1`，与人工一次性巡检竞争的等待也在预算内。
- 停止后不再发布迟到 SQL 结果；取消定时器、请求工作线程退出并有界等候。STOPPED 表示禁止发布，只有单独的 `worker.terminated=1` 才证明专属线程已退出；不把取消或等待超时解释为 JDBC 已完成。

## 配置与指标

配置前缀为 `game.data.consistency.expired-formations`：`enabled=true`、`interval=60s`、`budget=3s`、`stale-after=150s`、`page-size=128`、`max-rows=1024`。开关环境变量为 `GAME_DATA_EXPIRED_FORMATION_AUDIT_ENABLED`。继续复用 `GAME_DATA_CONSISTENCY_JDBC_URL/USERNAME/PASSWORD`；口令不进日志或报告。

低基数指标（Prometheus 导出时点号转为下划线）：

| 指标 | 含义 |
|---|---|
| `game.data.match.expired.bad.lowerbound` | 本副本本轮确认坏形状下界；未知且无已确认异常、过期、停止、禁用为 NaN。只对 >0 告警，不跨副本求和。 |
| `game.data.match.expired.scanned` | 本轮读取行数；失败时可能只是已读部分。 |
| `game.data.match.expired.uncertain` | 本轮缺少深读或发生变化的行数；不是全库未知项总数。 |
| `game.data.match.expired.probe.state{state=…}` | 固定 UNKNOWN / OBSERVED / STALE / STOPPED / DISABLED 状态；OBSERVED 不是健康断言。 |
| `game.data.match.expired.window.finished` | 最近成功观察是否走完此前上界；UNKNOWN、过期或停止为 NaN。 |
| `game.data.match.expired.worker.terminated` | 已请求停止且实际工作线程退出才为 1。 |

无玩家/battle/operation/instance 标签，无未经约束的异常文本标签。未增加管理 HTTP 接口或告警配置，也未产生持久报告、分布式单飞、修复作业。

`scanned` / `uncertain` 在开始过一轮但没有 SQL 配置时可能为 0，只表明本轮没有读到行；状态仍 UNKNOWN，`bad.lowerbound` 仍 NaN。不得单独把这两个计数的零值作为健康判定。进程刚启动、过期、禁用或停止时两者为 NaN。

## 验证与边界

新增 21 个普通测试源码：16 个行为/生命周期用例、2 个实际 H2 固定 SQL 用例、3 个 Spring 装配/绑定用例；另修改原 `ConsistencyConfigurationTest` 仅提供 MeterRegistry 并禁用该旧夹具的周期任务，全部原断言保留。覆盖 uint64 高半区、候选历史过滤、公平游标、原件变化/深读缺口、预算/连接失败、查询无写操作、并发 tick、迟到回包、过期/停止和默认配置。另含端口实际延迟超过整轮预算及真实单工作线程启动/停止，避免只靠假件异常证明生命周期。H2 的 DECIMAL(20,0) 用例核对 unsigned 游标排序，不替代生产 TiDB 的协议/权限/强读实测。测试源码尚未编译或运行，不能登记为通过。

最新 `993b6da9` 源已加入终局 outbox 的 result / first / waiting 三类积压数量与最老年龄；不属于本次异常形状子项，仍待移植。也未实现 Create 正文深读、告警路由、持久历史、跨副本去重、自动 ABORT、释放票据或 HA/RPO0 验收。
