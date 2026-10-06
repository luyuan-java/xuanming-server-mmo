# xm-robot：端到端探针客户端

纯 Java 客户端（Netty + JDK HttpClient + Jackson，不依赖 Spring 运行时），按客户端契约连 Java 版服务端，
跑固定场景并逐条断言，**退出码即结论**（与 Go robot 不同：Go robot 的退出码不反映登录链路结果）。

- 帧格式复用 `xm-net` 的 `ClientFrameEncoder` / `ClientFrameDecoder`；消息号一律经 `MessageIdRegistry.requireId(服务, 方法)` 解析，不写死数字。
- 依据的契约：`docs/reference/mmorpg-client-contract-robot.md`（登录链路）、`...-scene.md`（进场）、`...-movement.md`（移动）、`...-aoi.md`（视野与 66）。
- 探针只是客户端：不启动、不停止任何服务端进程。

## 构建

```bash
./mvnw -B -f xm-robot/pom.xml clean install        # 产物 xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar（可 java -jar）
```

单元测试（帧往返、assign-gate JSON 解析、收件箱、假 gate 上的握手与请求应答、移动断言辅助）不需要服务端。

## 对本地竖切运行

1. 按 `tools/local/start-slice.sh` 顶部注释导出环境变量并拉起本机切片（进程清单以脚本的 `SERVICES` 为准；MySQL / Redis / Kafka 先就绪）：

   ```bash
   export XM_MYSQL_PASSWORD=... XM_GATE_TOKEN_SECRET=... XM_LOGIN_DEV_PASSWORD=... XM_NODE_LINK_SECRET=... XM_DUBBO_SECRET=...
   tools/local/start-slice.sh
   ```

2. 同一个 shell 里（探针只读 `XM_LOGIN_DEV_PASSWORD`，与 xm-login 相同；口令不接受命令行传入）：

   ```bash
   java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar smoke                       # 3 个账号 robot_java_0001..0003
   java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar smoke --count 10
   java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar movement                    # 每次新账号 robot_java_mv<时间标签>_a / _b
   java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar movement --expect-jump correct
   java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar --help
   ```

3. 停：`tools/local/stop-slice.sh`。

Windows 注意：

- 若报 `failed to open a new selector` / `Unable to establish loopback connection`，是 JDK 的 NIO 选择器在临时目录建 Unix 域套接字失败，
  加 `-Djdk.net.unixdomain.tmpdir=<一个短的纯 ASCII 目录>`（例如仓库的 `run/jtmp`），或经 `JAVA_TOOL_OPTIONS` 传入。
- Git Bash 里中文乱码时加 `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`。

## 参数（命令行优先于环境变量）

| 选项 | 环境变量 | 缺省 | 说明 |
|---|---|---|---|
| `--gateway` | `XM_ROBOT_GATEWAY` | `http://127.0.0.1:18081` | xm-gateway 地址 |
| `--zone` | `XM_ROBOT_ZONE` | `1` | assign-gate 的 `zone_id` |
| `--prefix` | `XM_ROBOT_ACCOUNT_PREFIX` | `robot_java_` | 账号前缀，须在 xm-login 的 `dev-account-prefixes` 白名单里（缺省 `robot_` / `dev_`） |
| `--count` | `XM_ROBOT_COUNT` | `3` | smoke 账号数 |
| `--run-tag` | `XM_ROBOT_RUN_TAG` | 当前时间（36 进制） | movement 账号标签；固定它可复用账号，但跳跃会移走 A 的存档位置，复用时 A、B 可能不再同处出生点 |
| `--connect-timeout-ms` | `XM_ROBOT_CONNECT_TIMEOUT_MS` | `5000` | HTTP 与 TCP 建连 |
| `--request-timeout-ms` | `XM_ROBOT_REQUEST_TIMEOUT_MS` | `15000` | 握手 / 48 / 14 / 26 / 77 各自等应答；movement 里 A 断开后等 51 |
| `--enter-scene-timeout-ms` | `XM_ROBOT_ENTER_SCENE_TIMEOUT_MS` | `15000` | 发出 26 后等 79 |
| `--observe-timeout-ms` | `XM_ROBOT_OBSERVE_TIMEOUT_MS` | `2000` | 每条移动输入后 B 收到 66、跳跃后 A 收到 137 |
| `--expect-jump` | `XM_ROBOT_EXPECT_JUMP` | `auto` | `auto` 纠偏或 fail-open 都接受 / `correct` 必须回 137 / `accept` 必须原样接受 |

上表只列 smoke / movement 用到的选项；其余子命令的选项（管理端口地址、`--expect-dev`、`--slow`、`--crash-*` 等）以 `--help` 为准，
battle-settle 与 rollback 用到的在下面各自的小节里说明。

退出码：`0` 全部检查通过；`1` 有检查失败或流程中断；`2` 参数错误。

## 场景与断言

下面只展开其中几个场景；全部子命令的一行说明与前提以 `--help` 为准。

**smoke**（N 个账号并发，间隔 50ms）：assign-gate → TCP → 首帧握手 → Login(48) → 没角色就 CreatePlayer(14) → EnterGame(26) →
15s 内收到带 `scene_info`（配置号、场景号非 0）的 79 → ListSkills(77) 非空 → 关 TCP。
登录阶段按 `error_message` **是否存在**判失败（robot 契约 §3.4 第 3 条）；26 回的 `player_id` 必须等于请求；
进游戏撞上 2005（归属还没释放）等 1s 重试，最多 4 次。逐账号打印各步耗时。

**movement**（两个新账号，A 先进、B 后进同一场景）：

1. 进场：A、B 同一 `scene_id`、相距 ≤ 10 m；B 收到含 A 的 47 / 21，实体号与位置同 A 自己的 21（AOI §3.1）。
2. 行走：A 每 250ms 一条 MoveStart → MoveSync ×3（最后一条上报 30 m/s）→ MoveStop，沿 +x 约 4.4 m。断言：
   每条输入后 B 在观察时限内收到 A 的 66——Start / Sync 带对应 velocity，超速的截断到 10 m/s，Stop 带全零 velocity、位置 = 停止点
   （movement §6.1、§4.3）；66 位置都在路径上、rotation = 上报值（§5、§4.3 第 4 步）；停下后 1s 静默（§6.1）；
   A 对 134/132/131 没有任何回包（§0 第 1 条）、不收 137（§4.3 第 5 步）；66 不发给被同步者自己（§6.1，AOI §5.1）。
3. 重登：A 断开 → B 收到 A 的 51（AOI §2.5）→ A 重新进场，自己的 21 位置 = 停止点（movement §7）。
4. 跳跃：MoveStart 后 250ms 发一条前跳 200 m 的 MoveSync。收到 137 → 校验 `input_seq` 回显、水平偏差 > 0.5 m、
   `server_time_ms` 是 UTC 毫秒、信封 `id=0`（§3.5、§4.3 第 5 步），客户端在 `server_location` 停下；
   没收到 → 按 fail-open（无导航网格原样接受，§4.3 第 2 步）在上报位置停下。随后重登，落盘位置必须等于服务端的裁决位置。
   Java 版 xm-scene 有位移令牌桶（`MoveGuard`，比基线严，movement §9 第 6 条），对 Java 版建议 `--expect-jump correct`。

**cross-node**（批次 5.2 跨节点换图与归属交接，scene-handoff-spec §10.8；三个新账号 `前缀 + xn + 标签 + _1 / _2 / _3`）。
前提：两个 scene 节点、per-node 覆盖、每图每节点一个频道（本机切片缺省），这样「同图不同 `scene_id`」即不同节点：

```bash
XM_SCENE_NODES=2 tools/local/start-slice.sh      # 第二个 scene：xm-scene-2，链路 21001、资产通道 21101、管理端口 18114
java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar cross-node
```

1. 落位：账号 1、2 用 79 的 `scene_id` 判断是否同场景，同场景就让账号 2 发 LeaveGame 断开、等 6 s（节点目录 5 s 刷新）后重登，至多 4 次；
   再登账号 3，它与谁同场景谁当 A（它当留在 S_A 的观察者 C），另一个当 B（在 S_B）。
2. A 发 63 `{scene_id = S_B}`：应答 `{0}` 且先于 79；79 是 S_B，自己的 21 换了新实体号、同图保留坐标（D10），收到含 B 的 47；
   B 收到 A 的 21；C 收到 A 旧实体的 51；A 没收到 23。
3. A 在 S_B 上走两步，B 收到 A 的 66；A 发 77 有应答（源节点已移除实例，能答的只有目标节点）。
4. A 断开（B 收到 A 的 51）后立即重连，回到 S_B 原实例、原位置。
5. A 发 63 换回 S_A，断言同第 2 步（C 收 21、B 收 51）。
6. A 发 63 `{scene_id = 不存在}`：应答 `{0}` 后收到 23 `{3023}`，没有 79，A 留在原地、77 照常应答。
7. A 连发两条 63 `{S_B}`：第一条 `{0}`、第二条 3014，最终恰好一条 79。
8. 顶号：从另一条连接进 A，旧连接收到 23 `{2017}`（gate 必须已把会话的归属代次换成交出后的那一代），新连接回到 S_B 原实例。

**battle-settle**（批次 6.3 scene 侧战斗冻结与结算应用，scene-battle-spec §13.8；两个新账号 `前缀 + bs + 标签 + _a / _b`：A 参战、B 同场景观察）。
匹配（6.4）还没有做，备战与建房经 xm-battle 的 dev 接口 `POST /admin/battle/dev/gather` 走 scene 的真实备战。
前提：切片带 xm-battle、xm-team 与 xm-scene，dev 运行模式，运维令牌（环境变量 `XM_ADMIN_TOKEN`，或切片脚本生成的 `run/xm-admin-token`——
所以要从仓库根目录运行）；读 `--table-dir` 的 World / Skill / Item 表。

```bash
java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar battle-settle
java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar battle-settle --slow
# 双 scene 切片（XM_SCENE_NODES=2 tools/local/start-slice.sh）：两个节点的管理端口都给上
java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar battle-settle --scene-metrics-url http://127.0.0.1:18104,http://127.0.0.1:18114
```

| 选项 | 环境变量 | 缺省 | 说明 |
|---|---|---|---|
| `--battle-admin-url` | `XM_ROBOT_BATTLE_ADMIN_URL` | `http://127.0.0.1:18112` | xm-battle 管理端口（dev 接口与指标） |
| `--scene-metrics-url` | `XM_ROBOT_SCENE_METRICS_URL` | `http://127.0.0.1:18104` | xm-scene 管理端口（抓指标）；可给逗号分隔的两个地址（双 scene 切片的两个节点），第 13 步按节点之和判定 |
| `--expect-dev` | `XM_ROBOT_EXPECT_DEV` | `allow` | `deny`（xm-battle 以 prod 运行）：只跑第 2 步，并核对 gather / 取消接口回 403 |
| `--slow` | `XM_ROBOT_SLOW` | `false` | 另跑慢用例：第 5 步的备战到期（约 70 s）、第 9 步离线结算越过重投窗口（房间结束后再等 130 s）；写 `--slow` 即 true |
| `--crash-window` | `XM_ROBOT_CRASH_WINDOW` | `none` | 故障变体：`scene-after-150` / `battle-after-store`，见下一小节 |
| `--crash-phase` | `XM_ROBOT_CRASH_PHASE` | `arm` | 故障变体的阶段：`arm` / `verify` |
| `--crash-state` | `XM_ROBOT_CRASH_STATE` | `run/battle-crash-window.state` | 故障变体的状态文件 |

步骤编号同规格 §13.8；实际执行次序是 1 → 2 → 3–4 → 5 → 12（前半）→ 11 → 6–7 → 8 → 9 → 10 → 13：

1. 准备：A、B 进同一场景；A 用 GM 187 领宝宝并 183 出战、194 接任务 12。
2. 大厅连接上发 149 / 140 / 162 / 165 → 每条 23 `{1003}`，不断连。
3. 只备战：快照逐项核对；B 在备战那一刻收到 A 速度 0 的 66。
4. 在途闸（PREPARING）：63 → 3023、168 → 25011、185 / 187 → 26008、192 → 1005、84 → 7004、B 对 A 放 84 → 7002，173 与 194 照常，
   134 静默丢，再备战 → 1006。
5. 取消：闸立即解除、再取消幂等、再备战成功后取消；`--slow` 另跑备战到期（reaper 只摘冻结，锁留到备战 TTL）。
6. 完整一局：dev gather 建房 → 大厅 177 后 143 → 直连握手 → 断开大厅重登收到 144 且 63 仍 3023 → 补签重连直连 → 162 挂机打完 → 直连 150；
   大厅随后 184（有宝宝条目时）先于 150，大厅 150 与直连那份逐字段相同。
7. 结算效果：金币增量 = gold_gain、背包按掉落 / 消耗变化、气血、宝宝气血、任务 12 可领并领取、重复领取被拒；闸解除，锁已放（2 s 内再备战成功）。
8. 重登不重发：金币、背包、任务原样，10 s 内没有第二条 150。
9. 离线结算：开局挂机后立即断开大厅与直连，等房间打完（`--slow` 再等过重投窗口）→ 登录收到 150、金币只增一次。
10. 确认后销毁：FIGHTING 拒绝取消、销毁后没有 150、63 一直 3023，直到期限 + 10 s 宽限后 reaper 判废。
11. 与跨节点换图互斥——**只在双 scene 切片上跑**：PREPARING 时 63 指向另一节点上的同图频道 → 应答就是 3023（不是 3014），A 留在原地；
    取消后同一条 63 跨节点成功。目标取 B 登录时所在的频道；`--scene-metrics-url` 给了两个地址（= 声明双 scene 切片）而 A、B 恰好同频道时，
    另用探针账号 `_c` 落位。找不到另一个节点上的频道：声明了双 scene 判失败，否则跳过并写一条观察记录。
12. 队伍视图——**需要 xm-team**：A 建单人队，`in_battle` 备战前 false → 只备战后 true → 取消后 false；第 6 步确认之后 true、
    第 7 步销账放锁之后变回 false（后两次嵌在第 6–7 步里采样，失败记在第 12 步名下）。
13. 指标：scene 的结算应用 / 销账放锁有增长，在途闸 enter_scene / attribute / pet / bag_sort / skill / move 逐个有增长；battle 发件箱
    `acked` 有增长、`exhausted` 不变。**双 scene 切片要把两个节点的管理端口都给 `--scene-metrics-url`**：A 落在哪个节点不确定，
    第 11 步之后还会换到另一个节点；只给一个地址而增量不达标时，记一条写明原因的失败。scene 指标开头或结尾没抓到时这几组不判，只写观察记录。

结尾在观察记录里写一行汇总：全部通过是 `BATTLE_SETTLE_OK battle_id=… gold=… mission=… relogin=… offline=…`（后两项取 `ok` / `skip`），
否则是 `BATTLE_SETTLE_FAIL step=…`——失败的步骤名，逗号分隔，按第一次失败的先后（`1`、`2`、`3-4`、`5`、`5-slow`、`6-7`、`8`–`13`、
`prod-403`，流程中断记 `流程`）。退出码照常是 0 / 1 / 2。

**battle-settle 故障变体**（scene-battle-spec §13.8「故障变体」）：kill -9 的两个崩溃窗口，各用一个新账号。robot 只做客户端的两段——
`--crash-phase arm` 打到断点、在断点处原子地写出状态文件，`--crash-phase verify` 在进程重启之后读状态文件、重登核对；
杀进程与重启由 `tools/local/battle-crash-window.sh` 编排，一般不直接调 robot。在仓库根目录执行：

```bash
tools/local/battle-crash-window.sh scene-after-150      # 大厅收到 150 之后立即 kill -9 xm-scene → 重启 → 重登核对
tools/local/battle-crash-window.sh battle-after-store   # 结算落库之后、大厅 150 之前 kill -9 xm-battle → 等 scene 的 rescue → 重启 → 重登核对
tools/local/battle-crash-window.sh <变体> --dry-run     # 只检查前置、打印将要做的事；不跑 robot、不杀进程
tools/local/battle-crash-window.sh <变体> --no-restart  # 杀掉之后不重启、不跑 verify（之后要自己把切片整片重启）
tools/local/battle-crash-window.sh <变体> -- --run-tag t1   # 「--」之后的参数原样传给 robot 的两次调用
```

前置（缺一样就在动手之前以退出码 2 退出，什么都不杀）：

- 切片是 `start-slice.sh` 起的、正在跑，dev / test 运行模式；scene 的 reaper 间隔是切片缺省的 2 s（battle-after-store 等的就是它）。
- 在启动切片的同一个 shell 里执行，或导出同一组环境变量：重启出来的进程读的是脚本的环境。切片脚本生成的四个秘密从 `run/` 下的文件读回。
- 已执行 `./mvnw -DskipTests install`。xm-robot 的旧包会被拒绝：帮助里要认 `--crash-window`，帮助里报的判定版本 `[crash-window-rev=N]`
  要等于脚本的 `ROBOT_CRASH_REVISION`，jar 不能比 xm-robot 的源码 / pom 旧。
- `scene-after-150` 只支持单 scene 切片（`XM_SCENE_NODES=1`）；`battle-after-store` 两种切片都行，第二个 scene 节点在跑时脚本把两个
  管理端口都传给 `--scene-metrics-url`。

脚本的退出码：`0` 两个阶段都通过；`1` 有检查失败或流程中断；`2` 用法错误或前置不满足。robot 的输出在
`run/logs/battle-crash-window-{arm,verify}.log`，被杀进程的日志另存为 `run/logs/<实例名>.before-kill.log`。robot 没到断点就退出、
或等断点超时（300 s）时，脚本不杀任何进程。

robot 每个阶段结尾写一行 `BATTLE_CRASH_OK variant=… phase=… battle_id=… outcome=…` 或 `BATTLE_CRASH_FAIL variant=… phase=… step=…`。
`outcome` 写明这一次落在了哪一种（没有结局可写的阶段是 `-`）：

| 变体 | outcome | 含义 |
|---|---|---|
| `scene-after-150` | `recovered` | kill 落在「已应用、还没落盘」：重登后进场恢复按待结算记录重放，恰好一条 150 |
| `scene-after-150` | `durable` | kill 落在落盘之后：重登不重发 |
| `battle-after-store` | `rescued` | 大厅 150 不早于期限 + 10 s 到达：scene 的 reaper 读到待结算记录并应用。只有它算通过 |
| `battle-after-store` | `early` / `missing` | 150 在期限 + 10 s 之前就到了 / 一直没等到：arm 阶段自己失败，verify 读到这样的状态文件停在 `state` 步，不会写 OK |

`scene-after-150` 落在哪一种由时序决定，脚本只保证尽快杀；两种都要求金币恰好增一次，重登后收到 ≥ 2 条本局的 150 判失败（重复应用）。
`battle-after-store` 的「落库之后、投递之前」在 battle 进程里只有几毫秒，从进程外杀不中：robot 先用 GM 94 封禁自己的金币获取，让首投被 scene
延后（零副作用），把「已落库、未应用」的状态撑到 battle 被杀，然后用 95 解封，等 reaper 在期限 + 10 s 时应用。
两个变体都拿金币当「恰好一次」的见证：这一局必须打赢且 gold_gain > 0，否则 arm 停在 `fight` 步、不写状态文件，脚本不杀任何进程。

**rollback**（批次 7.2b GM 回档，data-ops-spec §12.6；一个新账号 `前缀 + rb + 标签`）。经 xm-data 的运维接口（`--data-url`，缺省
`http://127.0.0.1:18106`；带运维令牌、操作人与幂等键）回档。前提：dev / test 运行模式（GM 加币）、xm-data 打开写开关
（`XM_DATA_OPS_ENABLED=true`，本机切片缺省打开，并把沉降与目标时刻下限调小）、xm-guild 在跑（帮会检查）、Kafka 审计链路在跑
（第 6 步的 LOGOUT 快照）、运维令牌。

检查项现为 19 条（以 `RollbackScenario` 为准）：

1. 加钻石 1000 → LeaveGame → 拍手工快照 S（GM_MANUAL）：归属已释放、内容是已落盘状态。
2. 再加 500 → 下线 → 离线回档到 S（`ifOnline=reject`）：作业 SUCCEEDED、明细 RESTORED 带安全快照号；同一个幂等键重提回同一个作业；
   回档后差异接口没有资产差异。
3. 重登钻石 = 1000；在线再加 300 → `reject` 回档：作业 REJECTED `player_online`、余额不变、连接仍在；`kick` 回档：旧连接收到
   23 `{2017}`，作业 SUCCEEDED。
4. 重登钻石 = 1000；`PRE_ROLLBACK` 安全快照里是被覆盖之前的 1300；流水有 `TX_ROLLBACK_RESTORE`（1300 → 1000，关联号 = 作业号）。
5. 撤销：下线后以 kick 回档的 `preSnapshotId` 再回档一次 → 作业 SUCCEEDED 且差异接口没有资产差异；重登钻石回到 1300。
6. 按时刻选源：LeaveGame → scene 发的 LOGOUT 快照经 Kafka 落库（内容 1300）→ 再加 250、下线 → 以那份快照的内容时刻作 `targetTimeMs` 预演，
   选中的就是它。
7. 运维持有归属期间进游戏：上一步的作业出现 CLAIMED 事件之后立刻进游戏，这条连接上第一条 EnterGame 应答是 2005；随后作业 SUCCEEDED、
   写回的就是那份 LOGOUT 快照，差异接口没有资产差异，钻石 = 1300。

没有覆盖的：帮会联动（快照之后捐献 → 回档被拒 / 带原因放行）、整区回档与 `/admin/zone-snapshots`。第 5–7 步与第 2 步的差异核对是
2026-10-06 追加的，当天在本机单 scene 与双 scene 切片上各跑过一遍、19 项全部通过（记录在 docs/porting/data-ops-spec.md §13.4 的
「最终验证」）。
