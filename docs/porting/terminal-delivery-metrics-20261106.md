# 终局发件箱逐行投递计数

本批为 `BattleCompletionReplay` 增加 Java Micrometer 计数，补充上一批 `terminal-outbox-backlog-20261105.md` 明确未覆盖的逐行处理结局。只改变观测和显式依赖装配，不改变 SQL、资产归属、投递重试或客户端契约。

状态：源码和测试用例已写，已做静态差异及调用点核对；**未编译、未执行测试或全量验证**。

## 固定来源

- C++/Go 仓库只读对照提交：`993b6da9d4ea51d5e4a27cc6ec17004af0fb97c1`。
- `go/match/internal/metrics/outcome.go`：计数名称、固定 kind/outcome 及副本求和语义；blob `6083928d6937e5b57f8d90a0cc32c65052a5e5f0`。
- `go/match/internal/authority/outcome_outbox.go`：`runOutboxJobs`、`publishOutcomeResult`、`deliverSettlementSource` 的逐行观测边界；blob `a9738be8708a66a57ef734a44a8b54e96dbb22e9`。
- `go/match/internal/svc/servicecontext.go`：`OutcomeOutboxOptions.Observe` 的正式装配。
- Java 工作树基线 HEAD：`0b578ea767321fcfa6006f0eefa8a7e61776c2b8`，延续该工作树已有的独立迁移改动。

本项实现范围为 game-match 与本说明；同轮其他功能及总体状态登记在根 PARITY。未修改 C++/Go 文件或同步 proto。

## 指标与标签

Micrometer 名称：`game.match.outcome.outbox.delivery`。Prometheus 导出名称：`game_match_outcome_outbox_delivery_total`。

只接受 `MatchMetrics.TerminalDeliveryKind` 和 `TerminalDeliveryOutcome` 两个枚举；不接受来源行、异常或外部字符串作为标签。`kind` 固定为 `result`、`first`、`waiting`；`outcome` 固定为下表八值。24 个组合启动时预注册为零，避免通过行号、玩家号、账号、seq 或异常正文扩大标签基数。

`first` / `waiting` 由本次读取的个人来源行 `inbox_seq == 0` / `!= 0` 决定，贯穿整次处理；首投拿到 seq 后仍记入 `first`，不能在回写后改记成 `waiting`。本指标按实例记录处理次数，多副本看板可对计数速率求和；它不是全库积压、唯一消息数、唯一对局数或资产发放次数。

| outcome | 本批 Java 的记录边界 |
| --- | --- |
| `published` | 全局原件通过现有校验，Kafka future 已确认，并且 `resultPublished` 的持锁 SQL 回写正常返回。 |
| `pending` | Inbox 回执通过现有签名、方法、请求摘要、身份、永久 seq、内容摘要与状态检查；回执为 PENDING，`inboxReceipt` 回写正常返回。 |
| `applied` | 同上，回执为 APPLIED，且本地 `inboxReceipt` 回写正常返回。 |
| `failed` | Kafka / Inbox 调用、空 future、空回复、超时、中断或未通过验证的回执；UNKNOWN/未指定状态在此类，绝不计为成功。 |
| `corrupt` | 现有持久原件检查未通过，包括摘要、全局协议/正文、个人固定 owner 或签发时间。沿用 Java 原有检查，没有新增或放松业务准入。 |
| `claim_lost` | 个人行 `claimInboxAttempt` 返回 false，当前行不发 RPC；包括原行已推进或本次到期领取已被其他处理者占用。 |
| `store_error` | 个人行领取调用失败，或 Kafka / Inbox 已确认后本地持久回写失败/提交结果未知；不能由远端成功推断本地确认成功。 |
| `deferred` | 保留固定源的封闭词汇。Java 当前没有该 Go 版的整轮认领预算，本批不产生此结局。零值不代表预算已实现或系统无积压。 |

每个进入逐行处理的来源，在其原有处理完成或抛出异常时记录一次当前结局。扫描查询失败尚未取得来源行，不能虚构逐行 `store_error`；空页、截止终局裁决、尚未派发的行也不增加本计数。处理中断仍恢复线程中断标记并结束原轮次，后面的未处理行不虚构 `deferred` 或成功。

成功计数表示本副本观察到相应回写正常返回。至少一次投递、并发副本和回执丢失仍可能使同一业务原件被处理多次；计数不提供去重、到账或释放 ready 票的授权。

## Java 实现与未改变的边界

指标名称、枚举和计数器集中在既有 `MatchMetrics`。`RatingConfiguration` 将现有 `MatchMetrics` bean 通过构造器注入 `BattleCompletionReplay`，没有静态全局注册表、额外线程或新依赖。

恢复器按现有步骤维护局部枚举结局，并在 `finally` 中观测一次：个人路径仍先领取退避，再校验原件，再发 Enqueue / Lookup，验证回执后才写回；全局路径仍先验证原件，再等 Kafka 确认，最后写回发布标记。指标记录的 `RuntimeException` 在观测边界隔离并记录固定说明，不覆盖业务异常、不改变中断、不阻断后续来源。

以下既有 Java 行为保持不变：500 ms 节拍、单线程/互斥轮次、16 个截止候选、16 个全局来源、首投与 waiting 各 8 个名额、公平扫描游标、单次 future 的 3250 ms 等待、个人领取的 2 秒 / 15 秒退避、原 owner/修订号/签发时间/原字节、永久 seq、SQL 锁与提交合同，以及停机和排空规则。

固定 Go 源还有本批不移植的调度差异：结果行 CAS 租约、30 秒认领租约、26 秒整轮认领预算、可配置批量/并发、个人动态退避以及 SQL 失败后的停止派发。Java 继续保留原有调度，不能因这次新增了计数，就声称这些行为已对齐。特别是 Java 全局路径没有 `claim_lost`，`deferred` 也没有产生路径；预注册零序列只是标签合同。

`game-data` 的只读 backlog / expired 观察器未改动，其数量、新鲜度和 UNKNOWN 状态继续承担独立的库存观测职责。

## 验证范围

新增 14 个 `@Test` 方法，尚未执行：

- `BattleCompletionReplayMetricsTest` 的 13 个方法覆盖：Kafka ACK 与本地回写顺序；同步失败、失败 future、空 future、超时；ACK 后 SQL UNKNOWN 与后续行继续；损坏原件；first / waiting 各自的 PENDING / APPLIED；认领失败与 SQL 异常；个人原件检查顺序；未知/空 Inbox 回复；RPC 异常和永久 seq 变化；APPLIED 后回写 UNKNOWN；空页/扫描失败无虚构计数；中断及未处理行；指标异常不改变业务成功或阻断后行。循环用例同时检查总计数，避免一次处理被重复归类。
- `MatchMetricsTest` 新增一个方法，固定检查三类八结局的零值、计数与仅 `kind` / `outcome` 两个标签；重复观测不产生额外序列。原指标名精确集合仅追加本指标。

`BattleTerminalRecoveryTest` 只增加显式指标依赖并适配构造调用，原业务断言保留；现有 H2 / 显式 TiDB 资格用例的开关和语义不变。构造调用已做全仓静态检索，`git diff --check` 用于检查本批修改的空白错误，不替代编译或测试。后续仍需执行相关测试和完整构建，总体进度见根 PARITY。
