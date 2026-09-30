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

1. 按 `tools/local/start-slice.sh` 顶部注释导出环境变量并拉起五个进程（MySQL / Redis 先就绪）：

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

退出码：`0` 全部检查通过；`1` 有检查失败或流程中断；`2` 参数错误。

## 场景与断言

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
