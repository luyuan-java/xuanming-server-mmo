# 审计组位点探针（2026-10-27）

实现已于 2026-10-27 从仓外审查候选接入独立工作树 `D:\luyuan\wt-port-next`。新增源码与测试尚未编译执行，也未运行真实 Kafka、容器或服务；C++ 与 Java main 未改动。此报告不是测试通过报告。

## 已实现的候选范围

固定源基线 `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5` 的 `go/data_service/internal/kafka/lag_probe.go` 已独立探测消费组；`deploy/k8s/data-service-audit-alerts.yaml` 使用组级最大分区积压、保留缺口和失败计数。Java 当前 `ConsumerLoop` 只算本实例 assignment 的积压和，不能代替组级原件。

本候选新增 Java 21 的窄接口、不可变原件、构造器注入和独立 `SmartLifecycle`。没有复制 Go 的连接对象或线程写法，也不改消费者、SQL 落库和位点提交行为。

- `AuditProbeConfiguration` 自动被 game-data 扫描；实际 topic generation 来自 `AuditProperties`，实际消费组复用 `DataNode` 的解析方法，分区数来自 `AuditTopics`，client ID 经注入 `KafkaNames` 生成。默认开启，没有关闭探针而隐藏未知状态的开关。
- 仅使用已有 kafka-clients 3.9.2 的 `Admin.describeTopics`、`listOffsets`、`listConsumerGroupOffsets`；不创建 Consumer，不加入组，不提交/重置位点，不创建 topic。
- 每个固定目标读取 metadata → beginning → committed → end → metadata；完整核对 6 / 3 分区和非零 topic ID。单次和进程内后续同代采样都不自动接受 topic 重建。
- 每目标总预算默认 10 秒，逐请求受剩余时间限制，Future 超时/中断取消；一条专用守护线程，两个目标顺序执行，默认间隔 30 秒，90 秒过期。停止立即中断及 `Admin.close(Duration.ZERO)`，最多等待自有线程 2 秒，晚到结果不覆盖 STOPPED，旧线程未退不允许新线程重叠启动。
- 不完整响应、坏位点、提交位点超过 end、总量溢出、broker 失败均为 UNKNOWN。无提交历史单独为 NO_COMMIT_HISTORY；现存积压从 beginning 算，但不证明此前没有缺失。组不存在、组历史过期与合法首次消费不能由探针自行裁决。
- 记录总 lag、最大分区 lag、已提交位点低于 beginning 的缺口下界。混合“部分分区有缺口、其他分区无历史”仍通过专用 lowerbound 指标和 RETENTION_GAP 状态暴露风险，完整 gap 指标为 NaN。
- 首次、失败、过期、停止不发布健康零；保留最近成功原件和本进程最近缺口证据/最大缺口，下次追平不会抹掉曾见风险。计数不把重复采样累加为丢失条数。
- `GET /admin/audit/consumer-probes` 只读内存，既经过原有令牌/操作人过滤器，又检查过滤器私有认证标记；offset 和计数为十进制字符串，避免 JSON 读取端丢失 64 位精度。200 只代表查询成功，状态需看返回字段。

## 指标及含义

所有指标仅含固定 `consumer`（transaction_log / player_snapshot），状态指标加固定 `status`，计数加固定 `outcome`；不带 topic、group、generation、partition 或玩家标签。多实例均观察同一组，应取 max 而非 sum。

| Micrometer 名 | 含义 |
|---|---|
| `game.data.audit.group.lag` | 组总 offset 积压差，非精确消息条数；失效 NaN |
| `game.data.audit.group.max.partition.lag` | 最大分区 offset 积压差；失效 NaN |
| `game.data.audit.retention.gap` | 所有分区都有提交历史时的缺口和；无历史或失效 NaN |
| `game.data.audit.retention.gap.lowerbound` | 已有历史分区上确认的缺口下界；无历史分区不掩盖其正值；失效 NaN |
| `game.data.audit.uncommitted.partitions` | 无提交历史的分区数；失效 NaN |
| `game.data.audit.probe.age.seconds` | 最近成功读取开始距今时间；从未成功 NaN |
| `game.data.audit.probe.status` | OK / NO_COMMIT_HISTORY / RETENTION_GAP / UNKNOWN / STALE / STOPPED 的小写 one-hot |
| `game.data.audit.probe.attempts` | ok/error 计数，导出名补 `_total` |
| `game.data.audit.retention.gap.observed` | 本进程曾见缺口，追平后保持 1 |
| `game.data.audit.retention.gap.highwater` | 本进程采样缺口下界的最大值，不重复累计 |

配置前缀 `game.data.audit-probe`：`interval` ∈ [5s,10m]，`timeout` ∈ [100ms,30s]，`stale-after` ∈ [interval + 2 × timeout,1h]。默认 30s/10s/90s，不需改现有 yaml。新鲜度用单调时钟，不靠系统 UTC 时钟跳变。

## 文件与接线

已接入 `game-data/src/main` 下全部新文件和 `game-data/src/test` 下测试。探针所改既有文件只有以下两处；仓外候选保留精确补丁 `existing-files.patch` 和基线/候选 SHA-256 `existing-files-hashes.json`：

1. `game-common/.../KafkaNames.java` 增加 `dataAuditProbeClientId()`，保留旧名字。
2. `game-data/.../admin/AdminAuthFilter.java` 给路径增加固定 `audit_consumer_probes` 标签，保留原鉴权和全部旧分支。

集成时已核对两个文件基线并应用精确补丁，保留同批一致性检查的过滤器分类。没有删除旧测试断言，没有新增 Maven 依赖，没有修改受管契约或 C++。

## 静态核对与待运行测试

已有 6 个新测试文件、39 个 `@Test` 方法，覆盖完整组分区、最大积压、缺口/无历史/混合状态、缺 key 与显式 null、范围/溢出/代次身份、预算/取消/中断、陈值/恢复/停止、命名空间实际组名、Prometheus 导出名称与标签、鉴权及 64 位 JSON 字符串。尚未编译执行。

实际已做：源码复核、精确补丁检查、输入哈希核验与独立工作树集成。新增代码尚未执行全量构建；真实 Kafka 截断/重平衡/故障恢复及服务启动后的 HTTP 路径验收仍待后续窗口。

API 口径核对了 Apache Kafka 3.9.2 的公开源码：[组查询规格](https://github.com/apache/kafka/blob/3.9.2/clients/src/main/java/org/apache/kafka/clients/admin/ListConsumerGroupOffsetsSpec.java)、[结果的 null 语义](https://github.com/apache/kafka/blob/3.9.2/clients/src/main/java/org/apache/kafka/clients/admin/ListConsumerGroupOffsetsResult.java)、[分区错误会省略 key 的处理器](https://github.com/apache/kafka/blob/3.9.2/clients/src/main/java/org/apache/kafka/clients/admin/internals/ListConsumerGroupOffsetsHandler.java)。实现使用未弃用的 Map + Spec 入口；完整 key + null 是无历史，缺 key 必须 UNKNOWN。

## 明确未完成与不能外推的保证

此候选只完成观察面，不等于批次 7.6 或全量移植完成。

- broker 多次读取不是事务快照；beginning > committed 表示该已提交恢复位点不能从 Kafka 完整重放。批次可能已读入并正在 SQL 落库，因此不能把 offset 差直接宣称精确丢失消息条数，更不能直接暂停/补偿玩家资产。
- 轮询会漏掉两轮之间出现又消失的缺口。消费者的 `auto.offset.reset=earliest` 仍未修改，完整的丢失证据/拒绝回档策略及持久裁决尚待另一个安全闭环；仅靠本探针不能声称回档审计完整。
- topic ID 钉定和曾见缺口只存在本进程内，重启后没有历史原件；应另补持久审计记录/发布原件。重启不是“风险已修复”，历史可能已丢的组无提交位点也不能自动判定合法新组。
- Admin 构造里的 DNS 解析不一定可被 Java 中断；预算从构造前开始，返回后必复核，但无法为驱动构造本身提供操作系统级硬截止。该线程为 daemon，停止等待有界、状态 STOPPED，未退线程不能被重启覆盖。
- 尚无 PrometheusRule、PodMonitor/ServiceMonitor、absent 告警、规则测试/安装证据和外部日志持久化；本候选提供真实信号，不宣称告警已经送达。
- 不自动调 retention、不改变消费组 offsets、不持久化缺口、不联动回档/回收门禁、不部署 K8s，不声明 HA/RPO0 已验收。
