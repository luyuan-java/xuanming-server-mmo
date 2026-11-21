# 命名规范、关联仓库与权限边界（Java 版）

> 对应根索引 [AGENTS.md](../../AGENTS.md)，移植自 C++/Go 服务端仓 `docs/rules/naming-and-boundaries.md` 并结合 Java 版约束。

## 1. 命名规范与语言约定

### 第一原则（强制）
所有命名（Java 类、接口、方法、字段、变量、proto、配置表列引用、错误码、指标、Redis 键与 Kafka topic 等）**必须清晰易懂且语义完全准确**，做到见名知意、不产生歧义：
- 准确表达业务对象、行为动作、状态语义与物理单位（如 `timeoutMs`、`expireAtMs`、`retryDelaySec`、`payloadBytes`），拒绝含糊缩写、误导性历史遗留名与无上下文的泛化名（`data` / `info` / `flag` / `temp` / `handle` / `process`）。
- 布尔值命名明确真值含义（`is*` / `has*` / `can*` / `should*`），不制造双重否定。
- 不把两层概念混进同一个名字（如「是什么」`equipKind` vs「摆在哪」`slotId`；任务 ID vs 奖励内容表 ID）。

### 语言与包名约定
- AI 协作回复、中文注释、中文 commit message、中文文档统一用**中文**；服务端运行期日志与诊断输出使用结构化**英文**。
- Java 21；包名 `com.game.<模块>`；Java 代码不得依赖同步来的 proto 文件目录，按 proto package 与服务语义组织模块。

## 2. 关联 Git 仓库与工作区约束

核心关联仓库（按 GitHub 地址定位，本地克隆目录名因机器而异，不写死个人盘符）：
- Java 服务端（本仓库）：`https://github.com/luyuan-java/xuanming-server-mmo`
- C++/Go 服务端（权威契约源）：`https://github.com/luyuan-cpp/xuanming-server-mmo`
- Python 服务端：`https://github.com/luhailong-python/xuanming-server-mmo-python`
- Unity 客户端：`https://github.com/luhailong-cpp/mmorpg-client`
- 协议转 MySQL 工具库：`https://github.com/luyuan-cpp/proto2mysql`

开发分支（用户 2026-11-08 要求）：统一在主仓库 `main` 分支开发，不再创建并行工作树或临时本地分支；清理已有工作树前先归并未提交改动并保存恢复快照。

## 3. 硬禁令

- ❌ 禁止手改由 `tools/ContractSync.java` 同步的客户端契约与配表产物（见根 `AGENTS.md §1`）。
- ❌ 禁止把 `player_id` 等高基数值作为 Prometheus / Micrometer 指标 label。
- ❌ 禁止未经确认私自新开独立后台线程/线程池（除非压测证实瓶颈并经程序/负责人确认说明原因）。
- ❌ 禁止在仓库中写入密钥、令牌密钥、开发口令或数据库密码（统一走环境变量注入）。
- ❌ 禁止在公开仓库里放入个人 / 面试内容（本仓库公开且挂在简历上）。
- ❌ 禁止 `--no-verify`、跳过/注释失败测试或用 `git reset --hard` 销毁他人进度。
- ❌ 禁止 `git tag`（由用户执行）、禁止 `git push --force` 或改写已推送历史。`git push`：用户 2026-10-02 授权，**每完成一个功能且构建与测试通过、登记 `PARITY.md` 后提交并推送到 `origin/main`**。
