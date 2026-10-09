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
   tools/local/start-slice.sh                      # 单 zone、单 scene：13 个进程
   XM_SCENE_NODES=2 tools/local/start-slice.sh     # 区 1 再起一个 scene 节点 xm-scene-2（cross-node 等场景用）
   XM_ZONES=2 tools/local/start-slice.sh           # 再起区 2 的 xm-scene-z2 与 xm-gate-z2，共 15 个进程（battle-cross-zone 用）
   ```

   `XM_ZONES`（1 / 2，缺省 1；只有 `start-slice.sh` 读，可与 `XM_SCENE_NODES=2` 同用）= 2 时，在 `SERVICES` 的十三项之外再起区 2 的两个实例：
   `xm-scene-z2`（节点链路 21010、资产通道 21110、管理端口 18115）与 `xm-gate-z2`（客户端 11010、管理端口 18123），其余进程两个区共用。
   区 2 的 scene / gate 节点号也是 1 号（节点号按区分配，与区 1 同号是有意的）。区 2 在区服列表里的状态由脚本跟着 `XM_ZONES` 改——=2 时置为 OPEN（库里还没有就建出来），
   =1 时置为维护——脚本等 `GET /api/server-list` 反映出来才报「全部就绪（场景节点 N 个、区 M 个）」。所以回到单 zone 切片后区 2 显示 MAINTENANCE 是预期的；手工改过的区 2 状态下次启动会被覆盖。

2. 同一个 shell 里（探针只读 `XM_LOGIN_DEV_PASSWORD`，与 xm-login 相同；口令不接受命令行传入）：

   ```bash
   java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar smoke                       # 3 个账号 robot_java_0001..0003
   java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar smoke --count 10
   java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar smoke --zone 2              # 双 zone 切片（XM_ZONES=2）：区 2 能登录、进场
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
battle-settle、battle-smoke / match-activity / match-5v5（`--match-admin-url`）、battle-cross-zone（`--visit-zone`）与 rollback 用到的在下面各自的小节里说明。

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
它不经匹配：备战与建房经 xm-battle 的 dev 接口 `POST /admin/battle/dev/gather` 走 scene 的真实备战（场景要「只备战不建房」和可控的建房时机；
这类房间不发对局结果、不进评分）。经真排队开局的端到端见下面的 battle-smoke（批次 6.4）。
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
- 双 zone 切片（`XM_ZONES=2`）上两个变体照常能做，但只在区 1 上做：robot 必须登录区 1（缺省）；「--」之后给了 `--zone`、或设了 `XM_ROBOT_ZONE` 而取值不是 1 的，同样在动手之前拒绝。
  区 2 的两个实例（`xm-scene-z2`、`xm-gate-z2`）脚本不杀、不重启、不检查。

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

**battle-smoke**（批次 6.4 匹配端到端 + 批次 6.5 观战段，match-spec §15.5、spectate-spec §10.7；对应基线 `robot/battle_smoke_scenario.go`——匹配段是它的 A 侧，
观战段是它的 B 侧——另加排队语义、补签、1V1 评分、切磋，以及重看、观众补签、观战中排队、开局清退）。匹配段三个新账号 `前缀 + bm + 标签 + _a / _b / _c`（A / B / C），
观战段另用四个 `_w / _x / _y / _z`（SA 参战、SB 指定观战、SC 列表与随机观战、SD 第二局参战），两段互不牵连。全程经 gate → xm-match 真排队，不用 dev 建房接口；评分与指标经 xm-match 的管理端口读。
前提：切片带 xm-match、xm-battle 与 xm-scene，Kafka 就绪（评分靠 xm-battle 发的对局结果），dev 运行模式，运维令牌（同 battle-settle：环境变量
`XM_ADMIN_TOKEN` 或切片脚本生成的 `run/xm-admin-token`，所以要从仓库根目录运行）。单 zone 切片即可；双 zone 切片上照常在区 1 跑。

```bash
java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar battle-smoke
java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar battle-smoke --match-admin-url http://127.0.0.1:18113
```

| 选项 | 环境变量 | 缺省 | 说明 |
|---|---|---|---|
| `--match-admin-url` | `XM_ROBOT_MATCH_ADMIN_URL` | `http://127.0.0.1:18113` | xm-match 管理端口：dev 接口 `GET /admin/match/dev/rating/{pid}`、`POST /admin/match/dev/activity-battle` 与指标；battle-smoke / battle-cross-zone / match-activity / match-5v5 共用 |

两套步骤编号不合并：观战段用 S0–S13，匹配段沿用 6.4 的第 1–12 步（原第 10 步——6.4 期间 163 / 164 的临时应答——已随观战落地删除）。实际执行次序：
第 1 步前半（核对运维令牌、抓 xm-match 指标作基数）→ 观战段 S0–S11 → 第 1 步后半（A、B、C 登录）→ 第 2–9 步（S12 的两半都在第 9 步里）→ 第 11 步 → S13。
结果行里的步骤名一律小写（括号里的就是）。

观战段（四个号在这一段结束时就 LeaveGame 下线）：

- S0（`s0-login`、`s0-list`、`s0-preclean`）：SC 发 164 `{limit = 0}` → 条数 ≤ 20、`created_at_ms` 不增、都没过期（创建不到 360 s；对 robot 本机时钟两头各留 5 s）。
  随后**预清理**（只记观察、不参与判定）：已结束的战斗会在列表里留到创建满 360 s，前几遍留下的残留场会让 S8 的随机观战一再扑空——每轮发一条 164 `{limit = 50}` 加一条 163(0)（每次扑空都会懒剔除残留场），
  直到列表空了为止，至多 30 轮；挑中别人的活战斗就 165 退出并停止清理。
- S1（`s1-queued-watch`）：SA 发 157 `{mode = 4 PVE_SOLO, config = 1}` → 战斗 X → 直连、**不开自动**（屏障：X 靠 6 s 的回合超时活着，直到 S9 放行）；SA 自己发 163(X) → `{16014, 匹配中无法观战}`（持票，票据检查先于战斗锁）。
- S2（`s2-list`）：SC 发 164 `{limit = 50}`，X 在列表里（开局公告先于登记进索引，不在时每 1 s 重试、上限 5 s）：`mode = 4`、`battle_config_id = 1`、`player_names` = [SA 的角色名]、`created_at_ms` 落在发 157 到收到 177 之间（±5 s）；再发 `{limit = 1}` → 至多一条。
- S3（`s3-watch`）：SB 发 163(X) → 应答 `battle_id = X`、没有 `error_message`；大厅收到 177 `{role = 2}`，期限同 SA 的票、签名是 64 位小写 hex；直连上握手应答之后**紧跟** 161 `{observer_count = 1}`（冷却全空、没有 `self_items`）。
- S4（`s4-rewatch`）：SB 再发 163(X)（重看同一场）→ 成功；重推的 177 经**直连**到达（有活直连时大厅公告直写），与第一条逐字节相同，随后再一条 161；大厅上 1 s 内没有新的 177，直连上没有 166、连接没有被关。
- S5（`s5-reissue`）：SB 发 179(X) → assignment 与 177 逐字节相同（role 2）。
- S6（`s6-queue-watching`）：观战中的 SB 排一条凑不成局的 1V1（config = 900000 + 标签的低 16 位，本轮独有）→ 受理，直连上 1 s 内没有 166（只排队不清退）；再发 163(0) → 16014；发 148(该票) 之后 153 → NOT_QUEUED。
- S7（`s7-ghost`）：SC 发 163(不存在的 id) → `{16018, 该战斗不存在或已结束}`。
- S8（`s8-random`）：SC 发 163(0)（随机；16017 每 1 s 重试、上限 10 s）→ 成功、`battle_id ≠ 0` → 177 `{role = 2}` → 直连 161（挑中的是 X 时 `observer_count = 2`）→ 在直连上发 165：应答成功 → FIN，**没有** 166。
- S9（`s9-finish`）：放行屏障，SA 开自动打到 150；SB 的直连上 ≥ 1 条 158 → 166 `{FINISHED, outcome 同 SA 的 150}` → FIN；SB 的大厅连接上 139 / 158 / 161 / 166 都是 0 条（战斗帧只走直连）。
- S10（`s10-ended`）：SB 再发 163(X) → `{16018, 该战斗不存在或已结束}`；SC 的 164 里没有 X（上一条 163 已把它从索引里剔除）。
- S11（`s11-evict`）开局清退：SD 开 PVE 战斗 Z、直连不开自动；SB 观战 Z（直连收到 161）后去排 PVE_SOLO → Z 的直连上 10 s 内收到 166 `{ONGOING, REMOVED}` 后 FIN，大厅收到新战斗 W 的 177 `{role = 1}` / 143（清退不阻断开局）；
  之后 SD、SB 都开自动打完各自的局。四个号下线之前再等这两局的**大厅 150**（结算落到 scene 才推；上限是最后一条直连 150 之后 25 s，等不到只记观察）——否则参战者在结算应用之前离场，xm-battle 要对着离线玩家重投约两分钟才放弃。
- S12 在匹配段第 9 步里，分两半：`s12-ready-residue`——**切磋开局之前**每 1 s 发一条 153，把 A 上一局 1V1 的 ready 票（开局成功后留 60 s；它在时 163 一律回 16014）等掉，直到 NOT_QUEUED；
  过了「第 8 步收到 177 的时刻 + 62 s」仍是 READY、或是 QUEUED / MATCHED 记一条失败后继续。`s12-in-battle`——切磋局里（A、B 开自动之前）A **只发一次** 163(0)，必须是 `{16015, 战斗尚未结束,无法观战}`，再见 16014 直接失败。
  不在切磋局里等 ready 票过期：切磋双方带着上一局 1V1 的结算血量进场，不开自动的切磋局可以只有 1 回合（6 s）。
- S13（`s13-metrics`，场景末尾）：xm-match 指标本轮的增量——`xm_match_watch_battle_total` 的 `outcome="ok"` ≥ 4（S3、S4、S8、S11）、`not_found` ≥ 2（S7、S10）、`queued` ≥ 2（S1、S6）、`in_battle` ≥ 1（S12）；
  `xm_match_spectate_evictions_total{reason="enter_gather",result="removed"}` ≥ 1。

**屏障期的时间预算**：从 SA 收到 X 的 177 起，S1–S8 合计预算 30 s（正常约 6 s）。新号单人 PVE（Dungeon 1）最少打 7 回合，不开自动的 X 至少活 7 × 6 s = 42 s，预算比它短一个回合以上，脚本慢了先报的是超预算而不是「X 提前结束」。
第一次超出记一条 `step=s<n>-budget` 的失败，之后照常往下跑；S2–S9 每步开始前看 SA 的直连，X 已经出了 150 或连接已关 → `step=s<n>-x-ended-early` 并中止观战段（匹配段照常跑）。
等观众票 177 与 161 各 15 s，等收尾 166 120 s。不在 robot 里验的：16016（只在真并发下出现）、16017 的「全服没有可观战战斗」、16019，由 xm-match 的组件测试覆盖。

匹配段：

1. 登录（`1-login`）：A、B、C 进场；A 发 153 → NOT_QUEUED，两个秒数都是 0。
2. 拒绝码（`2-reject-codes`）：157 `{mode = 2}` → 16002、`{mode = 5, config = 2}` → 16003；`error_code == error_message.id`、`parameters[0]` 逐字节、不带票号。
3. 排队与取消（`3-queue-cancel`）：1V1 入队拿到票 T → 再排 16001 且带 T → 153 QUEUED → 148(`"stale"`) 1 s 内无回包、仍 QUEUED → 148(T) 无回包、153 → NOT_QUEUED。
4. PVE_SOLO（`4-pve-solo`）：157 `{mode = 4, config = 1}` 受理 → 大厅**先 177 后 143**、同一 battle_id、票据签名形状、`expire_at_ms` ≈ 发起时刻 + 300 s（±5 s，对 robot 本机时钟）→ 153 是 MATCHED 或 READY。
5. 补签（`5-reissue`）：A 发 179 → assignment 与 177 逐字节相同；非成员 C 发同一局 → 1005、无票；不存在的局 → 1005 `该战斗不存在或已结束`。
6. 直连（`6-direct-fight`）：A 凭补签的票直连 → 战斗中再排 → 16000 → 开挂机（162 的应答必须无错误）打到 150：SIDE_A_WIN、settlement 指向本局本人、回合数 ≥ 1 → FIN。
7. 再排（`7-requeue`）：A 立即再排 PVE_SOLO——结算落地之前的 16000 按过渡态重试，**不得**出现 16001（ready 残留必须已自愈）→ 第二局同样直连、开挂机打到 150 后 FIN；旧局的 179 → 1005。
   **第二局只要求「打完」，不断言胜负**：150 的外层与 settlement 一致、是胜 / 负 / 平之一、指向本局本人、回合数 ≥ 1。血量随上一局的结算带进下一局、种子每局随机，同一个号的第二局阵亡是合法结果
   （阵亡后 scene 在结算时原地复活，后面的步骤不受影响）；基线 robot 也只对第一局断言胜利。
8. 1V1 与评分（`8-pvp-rating`）：A 的受理应答回来之后 B 再排（A 是锚点）→ 两人同一 battle_id、A 在 0 队 B 在 1 队 → 都挂机打到 150 → 10 s 内经 dev 评分接口查到 games 各 + 1；
   胜负且不满 30 回合时两人的增量互为相反数、|Δ| = 16，平局或打满 30 回合时都是 0。
9. 切磋（`9-challenge`）：挑战自己 16007 → A 挑 B，B 收到 156（发起者、账号名、过期时刻 ≈ 现在 + 60 s）→ C 挑 B 16011 → C 应答别人的邀请 16013（不消费）→ B 拒绝，只有 A 收到 154 false →
   B 再应答同一条 16012 →（S12 前半：先用 153 把 A 的 ready 票等掉）→ A 再挑、B 接受 → 双方 154 true 与同一局的 177 / 143 → 开自动之前 C 挑 A → 16009、A 发一次 163(0) → 16015（S12 后半）→ 打完 → C 下线后 A 挑 C：越过过渡态 16010 直到 16008。
10. （已删除：6.4 期间这里断言 163 → in-band 1006、164 → 空列表的临时应答；观战的 match 侧随 6.5 落地，改由上面的观战段覆盖。）
11. 指标（`11-metrics`）：抓 xm-match 的 `/actuator/prometheus`，按**本轮的增量、带标签**断言——`xm_match_gathers_total{outcome="success"}` 按模式 PVE_SOLO ≥ 2、1V1 ≥ 1、PVP_CHALLENGE ≥ 1；
    `xm_match_battle_ticket_reissues_total{result="ok"}` ≥ 1；`xm_match_challenges_total` 的 invite / ok ≥ 2、respond / declined ≥ 1、respond / accepted ≥ 1；`xm_match_rating_updates_total{outcome="applied"}` ≥ 1。

结尾在报告之后写一行汇总（第 12 步）：全部通过是
`BATTLE_SMOKE_OK battle_id=… a_turns=… a_direct_turns=… pvp_battle_id=… challenge_battle_id=… spectate_battle_id=… b_spectate_turns=… b_direct_spectate_turns=… removed_ok=1 s12_ready_residue=0|1`，
否则是 `BATTLE_SMOKE_FAIL step=<第一条失败检查所在的步骤> reason=<检查名：细节>`（步骤名小写：`s3-watch`、`s5-budget`、`7-requeue`……）。退出码照常是 0 / 1 / 2。
后五个字段是观战段的：`spectate_battle_id` 是战斗 X；`b_spectate_turns` / `b_direct_spectate_turns` 是 SB 在 X 上收到的观众回合帧数（观战帧只走直连，两个数相同；字段名同基线）；
`removed_ok` 是 S11 的开局清退是否按预期发生；`s12_ready_residue` 是「切磋开局之前的 153 见到过 READY」（A 上一局 1V1 的 ready 票那时还没过期），0 与 1 都正常，只是观察值。

节奏与注意（match-activity、match-5v5 与 team 的开战段同样适用）：

- gate 对 match 的十个号都按缺省每秒 3 条限频，同一会话相邻请求隔 400 ms；过渡态（157 的 16000、152 的 16010 / 16009、211 的 4025 / 4026）每 1 s 重试、上限 20 s；等开战 30 s、等终局 120 s。
  一次全失败的 battle-smoke 可能要几分钟才出结果。观战段让一遍正常的 battle-smoke 多出约 2–4 分钟（X 要靠回合超时活过 S1–S8、S11 另打两局单人 PVE、S12 前半可能等 ready 票过期至多几十秒），外层脚本对单个场景设了时限的要放宽。
- 各阶段独立：前一阶段中断时记失败并继续后面的阶段；收尾时给还连着的排队者各发一条 148(`""`)，免得中断的那一步把 6 小时的排队票留在队列里。
- 1V1 / 5V5 的 config 0 队列全服共享：切片上同时有别的排队者时，第 3 / 8 步或 match-5v5 会失败。battle-smoke 里只有第 6 步（新号的第一局 PVE）断言 SIDE_A_WIN，数值表调整后可能要放宽；
  第 7 步的第二局只要求打完（见上）。可观战索引也是全服共享的：切片上有别人的活战斗时，S8 的随机观战可能挑中别人的局（照常通过，只是 `observer_count` 不按 X 核对）。
- 场景的单元测试跑在按规格手写的本机假服务端（`FakeMatchWorld`）上，它不依赖 xm-match，不是服务端行为的证明；服务端行为的证据是活切片上的运行。

**battle-cross-zone**（批次 6.5 跨区 1V1，spectate-spec §10.8；对应基线 `robot/battle_smoke_cross_zone_scenario.go`，补上它的几处弱点并加跨区观众与第二局；
三个新账号 `前缀 + xz + 标签 + _a / _b / _c`）。A 经 `--zone` 的 gate 登录，B 与观众 C 经 `--visit-zone` 的 gate 登录；xm-match 与 xm-battle 不分区，两个区的 gate 把匹配请求转给同一组 xm-match。
前提：**`XM_ZONES=2` 的切片**（两个区各有 gate 与 scene，并且都在 gateway 的区服列表里显示 OPEN——切片脚本报「全部就绪」时已经满足），切片带 xm-match、xm-battle，Kafka 就绪，
dev 运行模式，运维令牌（同 battle-smoke，从仓库根目录运行）。

```bash
XM_ZONES=2 tools/local/start-slice.sh
java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar battle-cross-zone --zone 1 --visit-zone 2
java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar battle-cross-zone --zone 2 --visit-zone 1    # 反过来：A 在区 2，B、C 在区 1
```

| 选项 | 环境变量 | 缺省 | 说明 |
|---|---|---|---|
| `--zone` | `XM_ROBOT_ZONE` | `1` | A 登录的区 |
| `--visit-zone` | `XM_ROBOT_VISIT_ZONE` | `2` | 另一个区（≥ 1）：B 与观众 C 登录的区。只有 battle-cross-zone 要求它与 `--zone` 不同（相同是参数错误、退出码 2），别的子命令不看它 |
| `--match-admin-url` | `XM_ROBOT_MATCH_ADMIN_URL` | `http://127.0.0.1:18113` | xm-match 管理端口（评分与指标），同 battle-smoke |

步骤（括号里是结果行用的步骤名，一律小写）：

- Z0（`preflight`）：`GET /api/server-list` 里 `--zone` 与 `--visit-zone` 两个区都是 OPEN，否则失败、不跳过（原因多半是切片没有用 `XM_ZONES=2` 起）。没有运维令牌同样在登录之前就失败（`z8-admin-token`），不白打一局。
- Z1（`z1-login`）：A、B、C 顺序登录进场；两个区 assign-gate 给的 gate 端点不同（切片上是 11000 / 11010）。
- Z2（`z2-baseline`）：抓 xm-match 指标与 A、B 的评分作基数。
- Z3（`z3-queue`）：A 发 157 `{mode = 3 1V1, config = 1}`，**A 的回包到了** B 才发（A 一定是锚点）；两个回包都受理、票号是 UUID。
- Z4（`z4-announce`）：两侧大厅上 177 都在 143 之前、同一个非 0 的 battle_id；两张票的 `host:port`、签发节点与实例相同，`player_id` 各是自己；143 里 A 在 0 队、B 在 1 队。
- Z5（`z5-direct-watch`）：A、B 各自直连（握手的 battle_id 一致、补拉 140 成功），都不开自动；`--visit-zone` 的 C 发 163(该局) → 成功 → 177 `{role = 2}` 经那个区的 gate 到达 → 直连 → 161 `{observer_count = 1}`。
- Z6（`z6-fight`）：A、B 开自动打到 150：两侧同一个终局（胜 / 负 / 平之一，不断言谁赢）、`total_rounds` 相同、直连回合数 ≥ 1、大厅上没有 139；C 收到 ≥ 1 条 158，然后 166 `{FINISHED, 同一个终局}` 后 FIN。
- Z7（`z7-lobby-end`）：两侧各等**大厅**上这一局的 150（scene 应用结算之后才推，证明结算回到了各自所在区的 scene），上限是直连 150 之后 25 s。
- Z8（`z8-rating`）：收到 150 后 10 s 内经 xm-match 的 dev 评分接口查到 games 各 + 1；胜负且不满 30 回合时两人的增量互为相反数、绝对值 16，平局或打满 30 回合都是 0（新号都从 1500 起）。
- Z9（`z9-second-queue` / `-announce` / `-direct` / `-fight` / `-lobby-end` / `-rating` / `-battle`）：立即再排打第二局（16000 按过渡态每 1 s 重试、上限 20 s，**不得**出现 16001），重复 Z4、Z6、Z7 与评分，不带观众；
  第二局的 battle_id 与第一局不同、games 再各 + 1。它覆盖「连续对局」的三处：0 血带入下一局、ready 残留挡住再排、把上一局迟到的 150 当成本局的。
- Z10（`z10-metrics`）：xm-match 指标本轮的增量——`xm_match_gather_zone_mix_total{mix="cross"}`（对 `mode` 求和）≥ 2、`xm_match_watch_battle_total{outcome="ok"}` ≥ 1。
- Z11（`z11-leave`）：三人 LeaveGame。收尾（含失败路径）给 A、B 各发一条 148(`""`)，免得把排队票留在全服共享的队列里凑走下一遍的 A。

结果行：全部通过是
`CROSS_ZONE_MATCH_OK battle_id=… zone_a=… zone_b=… a_turns=… b_turns=… a_direct_turns=… b_direct_turns=… observer_zone=… c_spectate_turns=… second_battle_id=…`
（前七个字段与基线同名同序；回合帧只走直连，所以 `*_turns` 与 `*_direct_turns` 相同），否则是 `CROSS_ZONE_MATCH_FAIL step=… reason=…`。除纯核对之外各步都是「不通过即中止」。

注意：

- 1V1 的 1 号配置队列全服共享（Unity 客户端的 1V1 也排它）：切片上同时有别的客户端或 robot 在排它时，A 或 B 会被凑走，场景在 Z4 失败。它与 battle-smoke 的 1V1（config 0）分开，两个场景并行不会互相凑走对手。
- 区 2 的 scene / gate 节点号与区 1 一样是 1 号——这正是场景要验的形态：只按节点号寻址的代码会把区 2 玩家的公告、确认、结算送到区 1 的同号节点（被实例过滤丢掉），表现为等不到 177 / 大厅 150。
  失败时先看 `run/logs/xm-gate-z2.log`、`xm-scene-z2.log`、`xm-battle.log`、`xm-match.log` 的 ERROR。
- 角色的归属区：5.4（跨 zone 传送与归属区路由）还没有做，从区 2 进来的新号归属区记成 1；1V1 不读归属区，不影响本场景，「角色列表里的 zone_id」没有断言。
- 节奏与时限同 battle-smoke（相邻请求隔 400 ms、等开战 30 s、等终局 120 s、等观众票与 161 各 15 s）；场景的单元测试同样跑在 `FakeMatchWorld`（补了第二个区）上，不是服务端行为的证明。

**match-activity**（批次 6.4 帮会活动开战的 dev 入口，match-spec §15.5、§7.1–§7.2；账号 `前缀 + ma + 标签 + _a / _b / _c`）。真正的调用方 xm-guild 随批次 4.6 接入，
这里由 robot 经 xm-match 的 dev 管理口 `POST /admin/match/dev/activity-battle`（`--match-admin-url`）调同一个实现。前提：切片带 xm-match、xm-battle，dev 运行模式（prod 下这个口回 403，
robot 不切运行模式，403 由 xm-match 自己的测试覆盖），运维令牌。

```bash
java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar match-activity
```

1. 登录（`1-login`）：C **最先**进场并立即 LeaveGame 断开（它的下线要先于第 3 步在 Redis 里生效，A、B 随后的两次进场是留给它的时间余量，不是确定性保证），然后 A、B 进场并保持在线。
2. `2-invalid-argument`：名单 [A, B]、上下文的发起人 = B → `INVALID_ARGUMENT`，offender = 0、不发 battle_id。
3. `3-member-offline`：名单 [A, C] → `MEMBER_OFFLINE`，offender = C。
4. `4-start`：名单 [A, B]、上下文合法 → 受理，`battle_id ≠ 0`；A、B 在大厅收到这一局的 177 / 143（PVE：两人都在 0 队）。
5. `5-member-in-battle`：两人已直连、未开自动时再发同样的请求 → `MEMBER_IN_BATTLE`，offender = A。
6. `6-fight`：两人直连挂机打到 150。

`guild_id` / `activity_id` 用**不存在的值**：dev 口建的是正常房间，打完照常结算、照常发活动结果事件；消费方（4.6）还没有，xm-battle 把这条结果重发到上限后摘除并记一条 ERROR——
这是预期内的。结果行 `MATCH_ACTIVITY_OK battle_id=… player_a=… player_b=…` / `MATCH_ACTIVITY_FAIL step=… reason=…`。

**match-5v5**（批次 6.4，可选；5V5 排队成局与蛇形分队，match-spec §15.5、§2.9；十个新账号 `前缀 + m5 + 标签 + _0 … _9`）。前提同 battle-smoke。

```bash
java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar match-5v5
```

1. 登录（`1-login`）。
2. 排队（`2-queue`）：十人按次序逐个发 157 `{mode = 1, config = 0}`，上一个人的受理应答回来之后才发下一个（入队次序 = 账号次序）；第十人入队后十人在大厅收到**同一个** battle_id 的 177 / 143。
3. 分队（`3-teams`）：十个新号评分都是 1500，开局时重读评分、按弹出序稳定排序后蛇形分队，按入队次序的队号是 `0,1,1,0,0,1,1,0,0,1`。
4. 打完（`4-fight`）：全员直连、开挂机，都收到 150。
5. 评分（`5-rating`）：10 s 内每人 games + 1；胜负且不满 30 回合时胜方每人 + 16、负方每人 − 16，平局或打满都是 0。

结果行 `MATCH_5V5_OK battle_id=… outcome=<枚举数值> rounds=…` / `MATCH_5V5_FAIL step=… reason=…`。5V5 / config 0 的队列全服共享，切片里同时有别人在排时弹出的十个人不全是本场景的账号，第 2 步会失败并写明。

**team 的开战段**（批次 6.4 整队开战，match-spec §15.5、team-spec §5.5；账号 `前缀 + tm + 标签 + …`）。`team` 场景在 S6（换图跟随）之后、S9（解散）之前加了三步，需要切片带 xm-match、
xm-battle 与 xm-scene；原来「211 回 4027」的断言随之换掉：

```bash
java -jar xm-robot/target/xm-robot-0.1.0-SNAPSHOT.jar team
```

- `match-rejects`：非队长 B 发 211 → 4018；队长 A 发 211(2)（未配置组队人数的副本）→ 4027 且视图 IDLE；B 持 1V1 排队票时 A 发 211(1) → 4026[B] 且 IDLE，随后 B 发 148 取消并用 153 确认 NOT_QUEUED。
- `s7-team-battle`（S7）：A 发 211(1) → 回包是 STARTING 视图；B 收到 213 MATCH_STARTED（发起人 A 不收）；两人收到同一个 battle_id 的 177 / 143 → 都直连挂机打到 150 → 两人都收到 213 MATCH_ENDED。
  遇到 4025 / 4026 的过渡态在时限内重试。
- `s8-member-in-battle`（S8）：B 单人 PVE 开战、尚未出手（持有战斗锁）时 A 发 211 → 4025，`parameters[0]` = B。S8 有固有的竞态（同基线 Go robot 的做法）。

结尾写 `TEAM_SMOKE_OK team_id=… zone=… player_a=… player_b=… player_d=… battle_id=…` 或 `TEAM_SMOKE_FAIL step=… reason=…`（同基线 Go robot 的结果行）。

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
