# 7.5a：服务 protobuf 子面语义门禁候选（2026-10-27）

本批已于 2026-10-27 将 `game-contract-check` 接入独立工作树 reactor；新增模块尚未编译执行测试。来源为仓外审查候选，不涉及 C++、受管 proto/resource/config 或 `contract/SOURCE.properties` 的写入。C++ 仅用 `git show` 读取固定提交 `92952c84b00f5dd45bf3b05ed4d08b9d7b5bd1b5`；Java 静态依据为 `wt-port-next` 的 `03a0708d` 加当前工作区。

## 实际范围

本工具比较两个 `FileDescriptorSet`、完整 `message_id.txt` 和来源文件。它是**客户端/玩家服务 protobuf 子面**，不能标记完整 7.5 或完整客户端契约治理完成。

1. 以 `OptionIsClientProtocolService` 或 `OptionIsPlayerService` 为 true 的服务方法作为根，遍历请求、响应、嵌套消息、递归引用、map entry 和字段枚举闭包。玩家服务集合是保守扩大范围，其中存在内部方法；这不等于宣称它们都允许客户端调用。
2. 全消息号目录独立检查永久历史；所有有描述符的活号绑定独立检查服务全名、请求、响应及流方向。未进入根闭包的内部消息字段不检查。根方法的完整 MethodOptions 原字节变化保守判 BREAK，包括现有 `OptionClientRpcKind`；业务自定义字段 options 尚不解释。
3. 使用现有 `protobuf-java` 4.35.1 和已有测试依赖，不新增依赖坐标或插件。模块无运行时调用方，不让游戏服务依赖它。

以下项目**未覆盖**，在 JSON `notCovered` 和文本 `NOT_COVERED` 同时明示：帧编码及握手固定根、HTTP、事件号历史、tip 数值/文案、配置表 schema/options/数据、导航绑定和字节产物、Java 持久 schema 台账、已发行双入口适配、字段业务 options、已离开闭包类型的累积退役台账。记录 `maps.source.commit` 只绑定审核上下文，不验证导航内容。

## 已有能力没有重做

`MessageIdRegistry` 已解析墓碑，当前同步目录有 8 条：1、40、41、42、56、74、78、81。C++ 发号器也已经保留墓碑。本工具不发号、不改变注册表；单独读取原表是因为运行时 `all()`/`parseMessageIds()` 只保留活号，不足以审查跨版本历史。独立解析器保持“墓碑原名可与新发号的同名活方法并存、墓碑之间可同名、号码不可重复”的既有语义，并对损坏行更严格拒绝。

`game-proto/pom.xml` 已输出 `target/classes/contract/contract.desc`，且已有 `outputDescriptorIncludeImports=true`、`outputDescriptorRetainOptions=true`。本批无需修改 `ContractSync.java` 或描述符导出。比较旧版不加载旧生成 Java 类，只用描述符解析，避免新类覆盖旧 option 的错误。

## 判级与不可豁免

| 等级 | 具体行为 | 退出 |
|---|---|---|
| ERROR | 历史消息号消失/换名/墓碑修改或复活；保留字段/枚举号名删除或复用；旧已解析 RPC 被改成外部排除 | 1，不能写进放行清单 |
| BREAK | 字段同号改名、类型、基数、presence、真实 oneof、map、默认值、JSON 名变化；required 新增/删除；已有 oneof 增成员；枚举同号换名、默认枚举值或 open/closed 变化；RPC 签名/可见性/方法 options 改变；活号退役；闭包类型离开 | 无精确放行时 1 |
| WARN | 非 required 字段删除且同时 reserved 号码和名字；枚举删除且同时 reserved 号码和全部原别名；packed 编码变化 | 0，报告仍保留 |
| INFO | 普通可选字段/类型/枚举/方法/消息号安全新增、来源变化、外部证据变化 | 0 |
| 输入错误 | 缺文件、无效 protobuf/UTF-8、缺 import/类型/可见性 option、空服务根、根方法缺消息号、重复号/符号、外部清单错配、坏 reserved/oneof/map、未支持 editions/客户端扩展字段、非法放行清单 | 2，尽可能写 JSON 错误报告 |

同 wire type 不视为同义，例如 int32→bool/uint64 仍是 BREAK。proto3 optional 的合成 oneof 名变化忽略，presence 仍比较；真实 oneof 按名字比较而不是声明顺序。保留范围合并后比较，拆分相邻范围不产生假差异。未知 import 绝不使用 `allowUnknownDependencies`；异常 import 深度超过 256 拒绝。

`compare(surface, surface)` 返回空列表，包括使用外部方法清单时。`coverage(surface)`、JSON `coverage.old/new`、文本 `COVERAGE`/`UNCHECKED_EXTERNAL` 另外列出覆盖规模及未检查方法。旧内部 RPC 原来有描述符、下版移除该服务再把原 id/key 加进外部清单，仍产出不可豁免的 `RPC_BECAME_UNRESOLVED`；新增清单不能降低旧覆盖。

## 输入、输出与放行

每个输入版本需要：

- 描述符：由该版本构建得到的完整 `contract.desc`，含 imports 和 options。
- 消息号：该版本原 `message_id.txt`，保留墓碑；SOURCE 中 `message_id.sha256` 必须匹配统一 LF 后的文本。
- 来源：原 `SOURCE.properties`，包含两个完整小写 SHA：`mmorpg.commit`、`maps.source.commit`，拒绝重复键。
- 可选外部清单：每行三个 TAB 分隔列 `id<TAB>key<TAB>证据及排除理由`，必须逐行精确匹配缺描述符的活方法；已存在描述符、墓碑、无关行全部拒绝。默认没有排除。

当前 Java 同步器明确排除 `proto/etcd/`。固定源的 11 个 etcd 方法与当前 Java 消息号表逐号对上；证据样例在 `examples/contract-external-methods-92952c84.tsv`。它没有自动加载，也不证明旧来源 `f83da2e1…` 的 etcd 消息内容等同固定源；采用者须按两个实际输入版本各自评审证据。完全不同的外部方法不能因名称前缀碰巧像 etcd 自动排除。

输出可选 `--json`、`--text`、`--old-surface`、`--new-surface`。所有文本固定 UTF-8/LF、排序和转义稳定；JSON 含 format、exitCode、两份 surface SHA、范围、未覆盖清单、coverage、findings。finding 含 SHA ID、severity、code、path、before、after、accepted、reason。CLI 标准输出始终给可读报告；失败没有伪装成通过。每份输入上限 64 MiB。输出父目录须已经存在；拒绝输出覆盖输入、输出重复、输入/输出硬链接或两个输出之间的硬链接。报告按普通文件写入，本地可信输入路径仍由调用方保证执行中不被并发替换。

只有显式 `--accepted reviewed.tsv` 可接受普通 BREAK。每行四个 TAB 分隔列：

```text
oldSurfaceSha256<TAB>newSurfaceSha256<TAB>findingId<TAB>已接受变更的具体原因和评审记录
```

清单不自动生成、不内置全量放行；没有 `--explicit-break` 开关。错误摘要、未知/过期/重复 finding、空理由、ERROR/WARN/INFO 条目都会拒绝。表内记录仍以 BREAK 出现在报告中，仅标 `accepted=true`；原因明确可审查。工具验证清单内容绑定，不替代评审人身份认证；CI 必须从可信评审来源取得清单，不能把 PR 任意自填文本视为授权。

## 精确接线与运行

已将 `game-contract-check/`、本文及证据样例接入，根 `<modules>` 在 `game-proto` 后新增 `game-contract-check`。无需 dependencyManagement 新项，无游戏模块依赖，无受管路径改动。根 CI 的现有全 reactor `verify` 将执行该模块回归；**这只接入比较器单测，不等于已接入真实历史兼容门禁**。仓外原候选及补丁保留作审查证据。

以下命令为后续运行方式，本次未执行：

```powershell
# 冻结解除并正式接入后，独占构建窗口执行（该模块不需要 protoc）。
.\mvnw.cmd -o -B -pl game-contract-check -am test

# pbJar 必须对应仓库 protobuf.version；下例是现有 4.35.1。
$pbJar = Join-Path $env:USERPROFILE '.m2/repository/com/google/protobuf/protobuf-java/4.35.1/protobuf-java-4.35.1.jar'
$contractClasspath = "game-contract-check/target/classes;$pbJar"
java -cp $contractClasspath com.game.contract.check.ContractCheck `
  --old-desc D:/contract-baseline/contract.desc `
  --old-ids D:/contract-baseline/message_id.txt `
  --old-source D:/contract-baseline/SOURCE.properties `
  --old-external D:/contract-baseline/external-methods.tsv `
  --new-desc game-proto/target/classes/contract/contract.desc `
  --new-ids game-proto/src/main/resources/contract/message_id.txt `
  --new-source contract/SOURCE.properties `
  --new-external D:/contract-review/new-external-methods.tsv `
  --json D:/contract-review/report.json `
  --text D:/contract-review/report.txt `
  --old-surface D:/contract-review/old-surface.tsv `
  --new-surface D:/contract-review/new-surface.tsv
exit $LASTEXITCODE
```

Linux 用冒号连接 classpath，其余参数相同。旧基线必须来自可信旧发布构建/锁定提交的完整描述符及原目录；不能拿新 HEAD 同时充当 old/new。首次基线尚无真实发布清单时，不创造“上一版本”，不把自比较称为历史兼容通过。CI 真门禁还需补齐可信 baseline 的固定取得流程、其内容校验与审查清单授权；因此本批没有加入会假绿的 workflow 步骤。未来逐个尚支持的发布基线比较，可以防止仅相邻版本检查漏掉更早客户；长期已退役类型的累积台账仍需独立实现。

## 测试与剩余工作

两个测试类共 33 个 `@Test`，从 protobuf builders 构造真实 descriptor sets，覆盖安全新增、相同 wire 不同语义、删除保留、optional presence、真实/合成 oneof、map key/value 闭包、递归消息、枚举、RPC、墓碑、保留历史、缺引用、错清单、坏二进制、退出码、JSON 转义、来源 SHA、精确放行、覆盖退步与硬链接输出碰撞。无服务或外部依赖。**测试已写，尚未编译或运行；不能报通过。** 静态验证仅完成模块 POM 的 XML 解析和 `git apply --check patches/root-pom.patch`（可适用当前冻结根 POM）；这不是 Java 语法/类型或测试通过证明。

接入后先运行模块测试，再用当前实际 descriptor + 明确外部清单做自比较（只证明当前输入可解析），然后拿一个真实可信历史版本做 BREAK 反例与安全新增样本验收。需完成这三步再登记“服务 protobuf 子面门禁已验证”。

Java 自有 `game.storage.PlayerState` 持久 schema 台账必须另案：定义 SQL/BLOB 根、字段世代、历史退役、升级/回档路径、未知字段全闭包及写口覆盖。本工具既不读取它，也不声称 C++ 的 persistschema 台账可以代替它。C++ 侧本批只读，未补该跨版本工具；双版本共享契约语义治理仍未完成。
