# 配置表（Java 版）

> 两版共享的是**表数据**（mmorpg 导表器产出的 `.pb` 与 `manifest.json`）和**权威 schema**；
> 怎么在 Java 里读它们是 Java 版自己的事。本文件说明 Java 版的做法与理由。

## 1. 流水线

```
mmorpg                                     本仓库
data/schema/*_table.proto ─┐
data/schema/cfg_options.proto ─┤ ContractSync ─▶ xm-table/src/main/proto/        （去掉非服务端列，注入 java 选项）
generated/code/proto/{tip,operator} ─┘                    │ protoc（xm-table 构建）
                                                          ├─▶ 行消息类 com.game.table.<Sheet>Table
                                                          └─▶ 描述符集 target/table-descriptors/config-tables.binpb
                                                                       │ javac 注解处理器（xm-table-codegen）
                                                                       ▼
                                                 com.game.table.ConfigTables、<Sheet>Rows（target/generated-sources）
generated/tables/*.pb + manifest.json ── ContractSync ─▶ config-data/tables/ ──▶ ConfigTables.load(dir)（运行时）
cpp/generated/table/code/constants/*.h ─ ContractSync ─▶ com.game.table.TableConstants（具名行 id）
```

- **schema 取权威版本**：`data/schema/*_table.proto` 用 `cfg_*` option 写明了主键、二级键、多值键、索引、外键；
  导表器产出的 `generated/code/proto/*_table.proto` 是去掉这些 option 的副本，信息不全。
- **与本批数据对齐**：表数据是导表器某次导出的产物，可能比 mmorpg HEAD 的 schema 旧（当前这批导出自 `1bca719`、脏工作树，
  见 `contract/SOURCE.properties` 的 `table.data.*`）。同步时以同批产出的 `generated/code/proto/*_table.proto` 为准逐字段对齐：
  schema 新加、数据里还没有的字段改写成 `reserved`（否则 Java 会把它读成恒 0，例如冷却 0 = 无冷却），还没导出的表不同步，
  都记在 `table.pending.*`；同号字段名字 / 类型不一致或产物有 schema 没有的字段 → 同步失败。mmorpg 重新导表后再同步即可补上。
- **非服务端列**（`cfg_owner` 不是 server / common：策划备注、constants_name、空 owner）导表器不写进 `.pb`，
  同步时改写成 `reserved <号>;`——Java 行类里不出现恒为默认值的「字段」，字段号也不会被误复用。
- **访问代码编译期生成，不同步**。旧做法是复制导表器产出的 83 个 Java 管理器（全局单例、非 `volatile` 的快照字段，
  热更时跨线程可见性没有保证；多张表各自替换，同一时刻可能读到新旧混杂的数据）。

## 2. 生成的 API

```java
ConfigTables tables = ConfigTables.load(Path.of("config-data/tables"));   // 一次读完、整体校验
SkillTable skill = tables.skill().get(13);                                  // 没有即 NoSuchElementException
Optional<WorldTable> w = tables.world().find(id);
boolean ok = tables.classTable().contains(classId);                         // Java 关键字表名加 Table 后缀
List<TestMultiKeyTable> rows = tables.testMultiKey().findAllByMUint32Key(k); // 多值键 / 索引
int id = TableConstants.GlobalVariable.ABNORMAL_LOGOUT;                     // 具名行 id
```

| schema | 生成 |
|---|---|
| 主键（`cfg_primary_key`，缺省 `id`） | `get` / `find` / `contains`；主键重复 → 加载失败 |
| 主键上 `cfg_multi`（主键可重复） | 只有 `findAll(id)` / `contains`，没有取单行的方法（编译期就挡住「当单值用」） |
| `cfg_key` | `findByX(key)` → `Optional`；取值重复时按表序取第一行并告警（见 §4） |
| `cfg_key` + `cfg_multi`，或非主键标量列只标 `cfg_multi` | `findAllByX(key)` → `List` |
| `cfg_index`（标量或 repeated，repeated 按每个元素建） | `findAllByX(key)` → `List` |
| `cfg_fk = "T"` / `"T.col"`、`cfg_gfk = "T"` | 加载时校验，不生成方法 |
| `cfg_tip_ref`，或整型列名含 `tip`（含结构体列的子列） | 加载时校验 tip 引用，不生成方法（§3 第 6 条） |
| `cfg_expr_type = "double"` + `cfg_expr_param`（字符串列存公式） | 加载时逐行预编译；生成 `evalXxx(row, 参数…)` 与带 `RandomGenerator` 的重载（§3 第 7 条） |

全部返回值不可变；`ConfigTables` 是一份不可变快照，热更 = 加载新快照、整体替换引用（同一快照内各表一致）。

## 3. 加载期校验（fail-closed）

`ConfigTables.load` 任何一项不过即抛 `TableLoadException`，不返回半成品：

1. 目录与 `manifest.json` 存在且格式正确；
2. manifest 登记的每张表：数据文件存在，**sha256 与行数都对得上**（文件损坏、被手改、不是同一批产物）；
3. 行里（含结构列的子消息）没有 schema 未声明的字段、manifest 里没有 schema 不认识的表（schema 与数据不是同一次同步）；
4. 主键（未声明 `cfg_multi`）不重复；
5. **外键**：每个非空取值（不是 0 / -1 / 空串，与导表器口径一致）都能在目标列里找到，失配全部列出（前 50 条）；
   目标列没有任何非空取值（空表）时跳过并告警——与导表器一致（「无法校验」不是「校验失败」）。
6. **tip 引用**（基线导表器 enum_gen.py validate_tip_references 在生成前做同一检查）：整型列标了 `cfg_tip_ref`，或列名含 `tip`
   （大小写不敏感；结构体列展开后的子列同样纳入，如 `ActorActionState.state.state_tip`），每个取值必须是 0 或同步来的
   `tip/*.proto` 里现存的码（码表由 codegen 编译期从描述符集取出），失配全部列出（前 50 条）——tip 码轴重排后表里的旧数字不能静默变成未知码；
7. **表达式列**（`cfg_expr_type` / `cfg_expr_param`，基线 C++ exprtk、Go 不支持）：每行公式加载时编译成不可变语法树（`TableExpression`），
   语法错误、引用未声明的参数、未知函数或参数个数不对即失败；只收 数字、声明过的参数、`+ - * / %`、`^`（右结合，高于一元负号）、括号、
   `min / max / abs / floor / ceil / random()`；空串按 0。求值无状态、线程安全，`random()` 由调用方注入随机源（缺省 ThreadLocalRandom）。
   与基线的差别：基线编译失败不检查（返回值未定义）、每次求值重新编译、「先设参数再取值」共享可变状态。

schema 里有、manifest 没登记的表按空表处理并告警（正常同步不会出现：还没导出的表根本不同步）。

## 4. 与 mmorpg 的差异

- **二级唯一键重复**：导表器只校验主键唯一，不校验 `cfg_key`；mmorpg 的 C++（`emplace`，先写胜出）与 Go（后写覆盖）
  已经不一致。Java 取表序第一行（与 C++ 一致，场景逻辑以 C++ 为准）并在加载时告警。mmorpg 待做：导表器校验 `cfg_key` 唯一。
- 运行时外键校验、manifest 校验是 Java 版额外加的纵深防御；mmorpg 只在导表时校验。
- 只读 `.pb`，不读 `.json`。

## 5. 维护

- 改表结构 / 加表：先在 mmorpg 改 schema 并导表，再 `java tools/ContractSync.java --mmorpg <mmorpg>`，之后 `./mvnw clean install`。
- CI / 提交前检查是否与 mmorpg 一致：`java tools/ContractSync.java --mmorpg <mmorpg> --check`。
- 生成器的 schema 规则在 `xm-table-codegen` 的 `TableSchemaReader`，生成模板在 `TableSourceGenerator`。
- 访问器名以 protoc 实际生成的行类为准（同消息里 `x` 与 `x_count` 冲突时 protoc 会在名字后追加字段号），
  见 `ConfigTableProcessor#resolveAccessors`；找不到访问器是编译错误，不会生成调不通的代码。
