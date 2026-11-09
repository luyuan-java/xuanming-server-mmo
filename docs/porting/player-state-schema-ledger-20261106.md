# PlayerState 持久字段历史门禁

2026-11-06 接续。C++/Go 只读；对照固定 `993b6da9` 的持久字段检查器，以 Java 21、现有 protobuf descriptor API 和 JUnit 实现，无新增第三方库。

## 两代真实存档

`game-player-store/src/test/resources/persistent-schema` 保存连续、只追加的台账；普通 Maven `test` / `verify` 运行 `PlayerStateSchemaLedgerTest`。测试依赖 `game-contract-check`，不会把检查器带进生产服务。

| 世代 | 内容 | SHA-256 |
|---|---|---|
| 0001 | 技能接入前的 PlayerState，根字段 1–10 | `3cd2f18d61634eaabe7d5805df027877451816242bb41ff5aecb704177d7877d` |
| 0002 | 追加根字段 `skills = 11`、`PlayerSkillBookState` 和 `PlayerSkillEntry` | `5c13dc94d4269abc54d2f5c1bdcd0fd7ae5569e5689e3d855b237708bfbb522d` |

初代来源是本轮改动前冻结的 `player_state.proto`，源字节 SHA-256 为 `8a9a041b99aab2b342ec75137886a5e167219badf580f2f806bf30fb266de098`，与 `frozen-20261106-resume-ledger-inputs.json` 一致。两代均使用 protoc 4.35.1、JDK 21 `javac --release 21` 和 protobuf-java 4.35.1 隔离生成并编译，通过真实 `PlayerState.getDescriptor()` 导出；未使用旧 target 类、文本正则或手写描述符替代。

初代工具调用 `snapshot`，第二代调用 `append`，初代原字节保留。隔离源码、生成类和命令原件保存在仓外 `D:/luyuan/tools/review-port-next/java-20261106/pre-skill-schema` 与 `skill-schema`。普通构建从当前正式 proto 再生成类，门禁要求其闭包与第二代逐字节一致。

## 演进与未知字段

台账沿字段引用遍历消息、枚举及 map entry 的真实闭包，规范化次序后保存 descriptor，固定首代与尾代两个摘要。检查包括：

- 已有字段号、名字、类型、label、presence、oneof、packed、默认值及 JSON 名称的变化。
- 字段删除必须同时保留原号和原名；所有历史 reserved 均不得复用或缩小。
- 枚举既有值、首值和 alias 语义；新增 required 或既有 oneof 成员拒绝。
- 历史类型退役后再次可达仍按全部历史核对。
- 删除或改写初代、中间代、尾代、断号，以及未显式登记的当前 schema 拒绝。

旧类型默认冻结。允许新增字段的集合保留已有领域往返证据对应的 `AttributeState`、战斗账本及资产操作账本，并为本次技能根字段新增 `PlayerState`：`ScenePlayer` 恢复时保存根未知字段，业务修改后再次持久化保留它们，技能业务测试验证该链。新技能消息本次是首次登记，不需要加入解冻集合；未来增加其字段前仍需独立审查。

技能重数与修习货币一起属于资产回档组。`RestoreBuilderTest` 增加三条业务回归：FULL/assets 同时恢复费用与重数、旧无技能字段的快照清除后续修习、只恢复等级时保留存储重数和已扣货币。生效重数由场景按当前等级重算。

## 后续追加

使用当前编译消息的 classpath 运行：

```text
com.game.contract.check.persist.PersistentSchemaTool append --message-class com.game.player.store.state.PlayerState --directory game-player-store/src/test/resources/persistent-schema --genesis <固定初代摘要> --head <当前尾代摘要> --allow-additions <与门禁相同的消息全名列表>
```

审查新代后更新测试里的尾代摘要和预期世代；首代摘要不变。工具使用 `CREATE_NEW`，不覆盖任何旧代，不在缺失台账时自动初始化。`check` 不写文件；`prefix` 可核对可信 Git 基线与候选的历史前缀。

这些摘要检测误改，不是签名；若代码、锚和全部台账一起被改，仍依赖可信 Git 审查。没有新增运行时 SQL schema 世代围栏，也不据此宣称任意混版本写入或跨存储强一致已经解决。

## 验证

本轮已运行 `game-contract-check`：62 个测试、0 失败、0 错误、0 跳过，其中 29 个覆盖新增持久台账库和 CLI。PlayerState 普通构建门禁与技能生产链等待本轮全量构建；最终结果见本轮接续报告，不能把隔离 descriptor 导出当作全服务构建通过。
