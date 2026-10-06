package com.game.discovery.battle;

import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * 回合制战斗在 Redis 上的全部脚本与跨进程常量（scene-battle-spec §7.2；scene 与 battle 共用，唯一出处）。
 *
 * <p><b>键</b>（都经 {@link RedisKeys}；脚本只碰经 {@code KEYS} 传入的键，<b>不在 Lua 里用 ARGV 拼键</b>——同一玩家的键共用 {@code {pid}} hash tag，
 * 将来上 Cluster 不用改）：锁 {@code xm:battle:{pid}:lock}（Hash：{@code b n s d p}）、待结算记录 {@code xm:battle:{pid}:settlement}
 * （Hash：字段名 = battle_id，值 = {@code BattleSettlementEvent} 字节）、已销账墓碑 {@code xm:battle:{pid}:settled:<battle_id>}
 * （String，TTL {@link #SETTLED_TOMBSTONE_TTL_SEC}）、活动结果 {@code xm:battle:activity-result:<id>}。
 * 一切比较都用 battle_id 的<b>无符号十进制串</b>；脚本走 {@link ByteArrayCodec}（值是原样字节）。
 *
 * <p><b>必须读主库</b>：全部脚本（含只读的）都以 {@link RScript.Mode#READ_WRITE} 执行。{@code ENTER_READ} / {@code READ_IF_OURS} /
 * {@code READ_LOCK_BATTLE} 的结果直接驱动丢弃与判废（读到「没有锁」→ 结算 DISCARDED 并销账；读到「没有记录」→ FIGHTING 判废删锁），
 * 主从 / 集群部署下 Redisson 缺省把只读脚本发到从库，复制延迟下的「没有」会被当成事实——所以这几段读不许改回 {@code READ_ONLY}。
 *
 * <p><b>已销账墓碑</b>（Java 独有）：销账（{@code ACK}，以及 {@code ACK_IF_SUPERSEDED} 删记录的分支）在同一段 Lua 里给 (玩家, 这一局) 写一个短寿命墓碑；
 * {@code STORE_SETTLEMENT} 见到墓碑就不写、回 {@link #STORE_ALREADY_SETTLED}。防的是「迟到 / 被重放的落库落在销账之后 → 记录被重新造出来 →
 * scene 的账本此时已 forget 这一局 → 下次进场恢复再发一次奖」。墓碑寿命 ≥ 发件箱条目的最长寿命 {@link #OUTBOX_MAX_AGE}。
 *
 * <p><b>重放语义</b>（§7.2、§10.1 第 13 条）：Redisson 配置 {@code retryAttempts = 1}，超时的 EVAL 会被原样重发，第一次可能已经执行；
 * 两次执行之间还可能夹着别的脚本。逐段结论（真 Redis 测试 {@code BattleRedisScriptsIntegrationTest} 每段连跑两次钉住）：
 * <ul>
 *   <li>{@code PREPARE_LOCK}：同局且仍是 {@code P} 的命中按重放成功——回 {@code "0"}，重写 n d p 并续 TTL。同局已 {@code F} 或别的局 → 回现在的 b、
 *       不改字段与 TTL（确认只在备战应答之后才发，「备战 → 确认 → 备战重放」不可达；即使发生也只是拒绝、不把在打的局改回备战期）。
 *       与删锁交错（备战 → 取消删锁 → 重放 / 晚到）会把锁重新写出来，是 §10.5 登记的残余，按备战 TTL 过期。</li>
 *   <li>{@code CONFIRM}：幂等（s = F、同一个 d、同一个 TTL）。两次之间夹了销账 / 删锁 → 重放回 nil（按未命中处理）。</li>
 *   <li>{@code CANCEL_OFFLINE} / {@code deletePreparingIfMatch}（同一段脚本）：第一次回 1、重放回 0（锁已不在，计 {@code offline_absent}；丢的只是回 1 才补的那次组队跟随，见下一条）。
 *       删除被排到 {@code CONFIRM} 之后（Redisson 重排，或首发没执行、重发才执行）→ 回 2、锁不动。</li>
 *   <li>{@code DELETE_IF_MATCH}：状态幂等，第一次回 1、重放回 0。不看 s——只给 reaper 判废用；「只该删备战锁」的路径用
 *       {@link #deletePreparingIfMatch}。返回值有人看：scene 的删锁回调在发起解冻的实例已换时，按「回 1 = 这一次把锁删掉了」决定是否给现任实例
 *       补一次组队跟随（上一条的两个入口回 1 时同样据此补）；重放回 0 时这次补跟随会丢，由下一次跟随事件兜住（与 {@code ACK} 重放丢位 2 同一处残余）。</li>
 *   <li>{@code TOUCH}：幂等（同一组 s d p 与 TTL，回 1）。{@code TOUCH(P)} 落在 {@code CONFIRM} 之后（重放或 Redisson 重排）→ 回 2，
 *       不降级、不改 d / p、不动 TTL；{@code TOUCH(F)} 不受限。</li>
 *   <li>{@code HOLD}：只延不缩，重放最多把 TTL 再顶回 hold（多出重发间隔那几秒），回 1。</li>
 *   <li>{@code ACK}：重放回 0（丢了位 1 / 位 2；丢位 2 = 丢「补一次跟随」，由下一次跟随触发兜住），墓碑被重写、TTL 刷新（无害）。</li>
 *   <li>{@code ACK_IF_SUPERSEDED}：第一次回 2（删了记录、写了墓碑）时重放回 3（按已销账摘除，正确）；回 0 / 1 / 3 的分支没有副作用，重放不变。</li>
 *   <li>{@code STORE_SETTLEMENT}：对自己幂等（同值 HSET、EXPIRE、HLEN 不变）。「落库 → 销账 → 重放 / 晚到的落库」回 {@code -1}、不写、不刷新 TTL，
 *       记录不复活。墓碑过期之后才落地的落库不在保护之内（寿命 10 min，远大于 Redisson 的 4.2 s 最坏耗时与发件箱的重投窗口）。</li>
 *   <li>{@code DELETE_SETTLEMENT_FIELD}：状态幂等，第一次回 1、重放回 0（调用方不看返回值）。不写墓碑：坏字段不对应一场可销账的局。</li>
 *   <li>{@code STORE_ACTIVITY_RESULT}：幂等（同值 {@code SET EX}，TTL 刷新）。「落库 → 消费方 DEL → 重放」会把副本重新造出来，
 *       消费方（4.6）必须自己按 battle_id 幂等——这条通道两版现在都不可达（Q11）。</li>
 *   <li>只读、重放无副作用：{@code ENTER_READ}、{@code READ_IF_OURS}、{@code READ_LOCK_BATTLE}、{@code PROBE_SETTLEMENT}、{@code PROBE_ACTIVITY_RESULT}。</li>
 * </ul>
 *
 * <p><b>与销账（{@code ACK}）交错</b>——某段脚本的重放 / 晚到落在同一局的销账之后：会在键不存在时把键造出来的只有 {@code PREPARE_LOCK} 与
 * {@code STORE_SETTLEMENT} 两段，其余一律落空、<b>不会把锁或记录造回来</b>：{@code CONFIRM} 回 nil（调用方按未命中，不重建冻结）；
 * {@code CANCEL_OFFLINE} / {@code deletePreparingIfMatch} / {@code DELETE_IF_MATCH} / {@code TOUCH} / {@code HOLD} 回 0（{@code TOUCH} 的调用方据此撤销
 * 按旧快照重建的冻结）；{@code ACK} 回 0；{@code ACK_IF_SUPERSEDED} 回 3。{@code STORE_SETTLEMENT} 由墓碑挡下（回 -1）。{@code PREPARE_LOCK} 的重放
 * 不可能晚于同一局的销账：销账在结算之后，而一次备战调用最坏 4.2 s、此时房间还没建。销账只动本局的字段与 b == X 的锁，
 * 别的局（下一局）的锁、记录、墓碑都不受影响。
 *
 * <p>探测、删坏字段、读锁的 b、活动结果的 SET / EXISTS 本可以用普通命令，这里也包成了 Lua（同 §7.2）：为的是全部走 {@link ByteArrayCodec}
 * 与同一条 {@code eval} 路径——值与字段名是原样字节，「回复为空 → 异常完成」只写一处。
 *
 * <p>全部方法异步、不阻塞、线程安全；返回的 future 在 Redisson 回调线程上完成（调用方自己投递回所属线程），出错以异常完成。
 * <b>回整数的脚本若得到空回复（协议异常）一律以 {@link IllegalStateException} 异常完成</b>——没问到结论不能当成任何一种结论（D15）。
 */
public final class BattleRedis {

    // ------------------------------------------------------------------ 跨进程常量（代码常量，不开放配置，§7.2 表）

    /** 锁必须比冻结活得久的余量（基线 {@code kLockExtraTtlSec}）：锁 TTL = 有效期限剩余 + 60 s。node-spec §10.4 的确认补发窗口 180 ≥ 96 + 60 引用它。 */
    public static final long LOCK_EXTRA_TTL_SEC = 60;
    /** 结算应用后锁至少再保持（基线 {@code kSettlementLockHoldSec}）：≥ 重投窗口 10 s × 12 + 60 s。 */
    public static final long LOCK_HOLD_AFTER_APPLY_SEC = 180;
    /** 待结算记录 TTL（7 天，两端同值）。 */
    public static final long SETTLEMENT_TTL_SEC = 604_800;
    /** battle 发件箱重投间隔（基线 {@code room.h:172-176}）。 */
    public static final Duration SETTLEMENT_RETRY_INTERVAL = Duration.ofSeconds(10);
    /** 重投次数上限：12 次重投、第 13 轮判用尽（次数在判定之后才加，§3.4）。 */
    public static final int SETTLEMENT_RETRY_MAX = 12;
    /** 活动结果持久副本 TTL（7 天）。 */
    public static final long ACTIVITY_RESULT_TTL_SEC = 604_800;
    /** 活动结果重发间隔。 */
    public static final Duration ACTIVITY_RETRY_INTERVAL = Duration.ofSeconds(10);
    /** 活动结果重发次数上限（只在重发时计次，30 次重发后第 31 轮用尽）。 */
    public static final int ACTIVITY_RETRY_MAX = 30;
    /** scene reaper 缺省间隔（基线 {@code pb.h:101-102}）；配置只许调小。 */
    public static final Duration REAPER_INTERVAL = Duration.ofSeconds(30);
    /** FIGHTING 判废宽限（D21）：GRACE + REAPER_INTERVAL &lt; LOCK_EXTRA_TTL_SEC，第一次 rescue 时锁一定还在。 */
    public static final Duration FIGHTING_EXPIRY_GRACE = Duration.ofSeconds(10);
    /** 两个发件箱条目登记后的最长寿命（Java 独有）：一直探测 / 定位出错、从不计次的条目按用尽摘除。 */
    public static final Duration OUTBOX_MAX_AGE = Duration.ofMinutes(10);
    /**
     * 已销账墓碑的 TTL（Java 独有）：必须 ≥ {@link #OUTBOX_MAX_AGE}（发件箱里一份结算从登记到摘除的最长寿命；{@code BattleRedisConstantsTest} 钉住），
     * 这样发件箱还可能为这一局发出落库 / 重发的整个窗口里，销账之后的落库都被挡住。
     */
    public static final long SETTLED_TOMBSTONE_TTL_SEC = 600;
    /** {@code STORE_SETTLEMENT} 返回的字段数超过它就 ERROR（正常为 1）。 */
    public static final int SETTLEMENT_FIELDS_WARN = 16;
    /** scene 结算账本容量（基线 {@code ledger.h:32}，异常兜底）。 */
    public static final int LEDGER_CAPACITY = 64;

    /** 锁 Hash 的字段名。 */
    public static final String FIELD_BATTLE = "b";
    public static final String FIELD_NODE = "n";
    public static final String FIELD_STATE = "s";
    public static final String FIELD_DEADLINE = "d";
    public static final String FIELD_PREPARE_DEADLINE = "p";
    /** 锁阶段取值。 */
    public static final String STATE_PREPARING = "P";
    public static final String STATE_FIGHTING = "F";

    // ------------------------------------------------------------------ 返回码

    /**
     * {@link #storeSettlement} 的返回：这一局已经销账（墓碑还在），<b>没有写入</b>。battle 侧按「已销账」处理：不登记发件箱、不投递。
     * 正常返回是落库后的字段数（≥ 1），不会与它混淆。
     */
    public static final long STORE_ALREADY_SETTLED = -1;

    /** {@link #touch} 的返回：没命中（锁不在，或不是这一局）——调用方撤销按锁重建的冻结。 */
    public static final long TOUCH_MISS = 0;
    /** {@link #touch} 的返回：命中，s / d / p 与 TTL 已按入参写入。 */
    public static final long TOUCH_HIT = 1;
    /**
     * {@link #touch} 的返回：命中，但锁上已是 {@code F} 而入参是 {@code P}——<b>什么都没改</b>（不降级 s、不改 d / p、不动 TTL）。
     * 调用方按命中处理（保留冻结）；是否把内存里重建的 PREPARING 升级为 FIGHTING 由调用方自己定。
     */
    public static final long TOUCH_KEPT_FIGHTING = 2;

    /** {@link #cancelOffline} / {@link #deletePreparingIfMatch} 的返回：不是这一局（或锁不在），什么都没做。 */
    public static final long PREPARING_DELETE_MISS = 0;
    /** {@link #cancelOffline} / {@link #deletePreparingIfMatch} 的返回：是这一局且不是 {@code F}，锁已删。 */
    public static final long PREPARING_DELETE_DONE = 1;
    /** {@link #cancelOffline} / {@link #deletePreparingIfMatch} 的返回：是这一局但已是 {@code F}（已确认开战），拒绝、锁不动。 */
    public static final long PREPARING_DELETE_FIGHTING = 2;

    // ------------------------------------------------------------------ 脚本（语义逐条对齐基线，§7.2 表）

    /**
     * 备战写锁（D3）：KEYS[1] = lock；ARGV = X, n, d, p, ttl。键里没有 b → 写入、回 "0"；b == X 且 s == P → 重放，重写 n d p 并续 TTL、回 "0"；
     * 其余（别的局，或同局已 F）→ 回现在的 b（拒绝）。
     */
    static final String PREPARE_LOCK = """
            local cur = redis.call('HGET', KEYS[1], 'b')
            if not cur then
              redis.call('HSET', KEYS[1], 'b', ARGV[1], 'n', ARGV[2], 's', 'P', 'd', ARGV[3], 'p', ARGV[4])
              redis.call('EXPIRE', KEYS[1], ARGV[5])
              return '0'
            end
            if cur == ARGV[1] and redis.call('HGET', KEYS[1], 's') == 'P' then
              redis.call('HSET', KEYS[1], 'n', ARGV[2], 'd', ARGV[3], 'p', ARGV[4])
              redis.call('EXPIRE', KEYS[1], ARGV[5])
              return '0'
            end
            return cur
            """;

    /**
     * 确认（离线确认 / 在线升级 / 迟到确认重建共用）：KEYS[1] = lock；ARGV = X, d, ttl。b ≠ X → nil；否则 s = F、d ≠ "0" 时写 d、
     * ttl ≠ "0" 时续期，回 HGETALL（扁平数组）。
     */
    static final String CONFIRM = """
            if redis.call('HGET', KEYS[1], 'b') ~= ARGV[1] then
              return false
            end
            redis.call('HSET', KEYS[1], 's', 'F')
            if ARGV[2] ~= '0' then
              redis.call('HSET', KEYS[1], 'd', ARGV[2])
            end
            if ARGV[3] ~= '0' then
              redis.call('EXPIRE', KEYS[1], ARGV[3])
            end
            return redis.call('HGETALL', KEYS[1])
            """;

    /**
     * 只删备战锁（离线取消，以及在线取消 / 备战失败这些「只该删 P 锁」的路径共用）：KEYS[1] = lock；ARGV = X。b ≠ X → 0；s == F → 2（拒绝）；否则 DEL → 1。
     */
    static final String CANCEL_OFFLINE = """
            if redis.call('HGET', KEYS[1], 'b') ~= ARGV[1] then
              return 0
            end
            if redis.call('HGET', KEYS[1], 's') == 'F' then
              return 2
            end
            redis.call('DEL', KEYS[1])
            return 1
            """;

    /** 条件删锁（不看 s，只给 reaper 判废用）：KEYS[1] = lock；ARGV = X。b == X → DEL → 1，否则 0。 */
    static final String DELETE_IF_MATCH = """
            if redis.call('HGET', KEYS[1], 'b') == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """;

    /**
     * 登录重建复核（单调，审计 RDS-7 / FRZ-5）：KEYS[1] = lock；ARGV = X, ttl, s, d, p。b ≠ X → 0；锁上 s == F 而入参 s == P → 什么都不改、回 2
     * （迟到 / 被重排到 CONFIRM 之后的 TOUCH(P) 不许把在打的局降回备战、也不许把 TTL 缩回备战期）；否则 HSET s d p + EXPIRE → 1。
     */
    static final String TOUCH = """
            if redis.call('HGET', KEYS[1], 'b') ~= ARGV[1] then
              return 0
            end
            if ARGV[3] == 'P' and redis.call('HGET', KEYS[1], 's') == 'F' then
              return 2
            end
            redis.call('HSET', KEYS[1], 's', ARGV[3], 'd', ARGV[4], 'p', ARGV[5])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """;

    /** 结算后续锁（只延不缩）：KEYS[1] = lock；ARGV = X, hold。b ≠ X → 0；TTL ∈ [0, hold) 就 EXPIRE hold；回 1。 */
    static final String HOLD = """
            if redis.call('HGET', KEYS[1], 'b') ~= ARGV[1] then
              return 0
            end
            local t = redis.call('TTL', KEYS[1])
            if t >= 0 and t < tonumber(ARGV[2]) then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return 1
            """;

    /**
     * 销账与放锁同一段（基线 {@code kAckSettlementScript}）：KEYS = lock, settlement, 墓碑；ARGV = X, 墓碑 TTL。HDEL settlement X 删到 → 位 1；
     * lock 的 b == X → DEL lock、位 2；<b>无论记录在不在都写墓碑</b>（{@code SET EX}——not_durable 的落库可能还在路上）。回位掩码。
     */
    static final String ACK = """
            local r = 0
            if redis.call('HDEL', KEYS[2], ARGV[1]) == 1 then
              r = 1
            end
            if redis.call('HGET', KEYS[1], 'b') == ARGV[1] then
              redis.call('DEL', KEYS[1])
              r = r + 2
            end
            redis.call('SET', KEYS[3], '1', 'EX', ARGV[2])
            return r
            """;

    /**
     * 进场恢复的一致快照：KEYS = lock, settlement。回扁平数组 {TTL(lock), 锁字段数 × 2, 锁的 k v …, 记录的 k v …}。
     */
    static final String ENTER_READ = """
            local out = {}
            out[1] = redis.call('TTL', KEYS[1])
            local lock = redis.call('HGETALL', KEYS[1])
            out[2] = #lock
            for i = 1, #lock do
              out[#out + 1] = lock[i]
            end
            local s = redis.call('HGETALL', KEYS[2])
            for i = 1, #s do
              out[#out + 1] = s[i]
            end
            return out
            """;

    /** rescue（D21）读本局记录：KEYS[1] = settlement；ARGV = X → 记录字节或 nil。 */
    static final String READ_IF_OURS = """
            return redis.call('HGET', KEYS[1], ARGV[1])
            """;

    /** 读锁的 b（结算到达且没有冻结时按锁应用，§7.10 第 7 步）：KEYS[1] = lock → b 或 nil。 */
    static final String READ_LOCK_BATTLE = """
            return redis.call('HGET', KEYS[1], 'b')
            """;

    /** 删单个字段（坏字段，§7.8 第 2 步）：KEYS[1] = settlement；ARGV = 字段名原始字节 → 删到的个数。 */
    static final String DELETE_SETTLEMENT_FIELD = """
            return redis.call('HDEL', KEYS[1], ARGV[1])
            """;

    /**
     * battle 落库：KEYS = settlement, 墓碑；ARGV = X, bytes, ttl。墓碑在（这一局已销账）→ 不写、回 -1；否则 HSET + EXPIRE → 回 HLEN。
     */
    static final String STORE_SETTLEMENT = """
            if redis.call('EXISTS', KEYS[2]) == 1 then
              return -1
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return redis.call('HLEN', KEYS[1])
            """;

    /** battle 探测：KEYS[1] = settlement；ARGV = X → HEXISTS（0 / 1）。 */
    static final String PROBE_SETTLEMENT = """
            return redis.call('HEXISTS', KEYS[1], ARGV[1])
            """;

    /**
     * 离线玩家的「已取代」判定（D17）：KEYS = lock, settlement, 墓碑；ARGV = X, 墓碑 TTL。记录已不在 → 3（已销账）；锁不在 → 0；
     * b == X → 1（仍是本局）；否则 HDEL X + 写墓碑 → 2（已被取代；这也是一次销账，同样要挡住之后的落库）。
     */
    static final String ACK_IF_SUPERSEDED = """
            if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 0 then
              return 3
            end
            local b = redis.call('HGET', KEYS[1], 'b')
            if not b then
              return 0
            end
            if b == ARGV[1] then
              return 1
            end
            redis.call('HDEL', KEYS[2], ARGV[1])
            redis.call('SET', KEYS[3], '1', 'EX', ARGV[2])
            return 2
            """;

    /** 活动结果落库：KEYS[1] = activity-result；ARGV = bytes, ttl。 */
    static final String STORE_ACTIVITY_RESULT = """
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return 1
            """;

    /** 活动结果探测：KEYS[1] = activity-result → EXISTS。 */
    static final String PROBE_ACTIVITY_RESULT = """
            return redis.call('EXISTS', KEYS[1])
            """;

    // ------------------------------------------------------------------ 结果形状

    /**
     * 待结算记录 Hash 里的一个字段（{@code ENTER_READ} 读出）。字段名<b>保留原始字节</b>：坏字段名可能含非法 UTF-8，解成 String 再编回去会变样、
     * HDEL 删不到（审计 RDS-11）——删坏字段一律把 {@link #rawName()} 原样交给 {@link BattleRedis#deleteSettlementField}。
     * 两个数组都不做防御性拷贝，不要改动。
     *
     * @param rawName 字段名的原始字节
     * @param value   字段值（{@code BattleSettlementEvent} 字节）
     */
    public record SettlementField(byte[] rawName, byte[] value) {

        public SettlementField {
            Objects.requireNonNull(rawName, "rawName");
            Objects.requireNonNull(value, "value");
        }

        /** 规范字段：字段名 = battle_id 的无符号十进制（battle 落库的写法；测试与假实现用）。 */
        public static SettlementField of(long battleId, byte[] value) {
            return new SettlementField(unsigned(battleId), value);
        }

        /**
         * 字段名对应的 battle_id；<b>只认规范的无符号十进制</b>（1–20 位数字、没有前导零、不溢出 uint64、不是 0），其余一律回 0 = 坏字段。
         * 比 {@link BattleRedis#parseUnsigned} 严：{@code "05"} / {@code "+5"} 这类写法能被宽松解析成 5，但销账按规范串 {@code "5"} HDEL 删不到它，
         * 当成合法记录应用就会每次进场都再发一次奖——所以按坏字段处理（由调用方按原字节删掉）。
         */
        public long battleId() {
            int n = rawName.length;
            if (n == 0 || n > 20 || rawName[0] == '0') {
                return 0;
            }
            for (byte b : rawName) {
                if (b < '0' || b > '9') {
                    return 0;
                }
            }
            return parseUnsigned(new String(rawName, StandardCharsets.US_ASCII));
        }

        /** 字段名的可读形式（只进日志）：全是可打印 ASCII 就原样，否则 {@code hex:<十六进制>}。<b>不能</b>拿它去删字段。 */
        public String name() {
            for (byte b : rawName) {
                if (b < 0x20 || b > 0x7e) {
                    return "hex:" + HexFormat.of().formatHex(rawName);
                }
            }
            return new String(rawName, StandardCharsets.US_ASCII);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SettlementField f && Arrays.equals(rawName, f.rawName) && Arrays.equals(value, f.value);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(rawName) + Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "SettlementField[" + name() + ", " + value.length + " 字节]";
        }
    }

    /**
     * 进场恢复读到的一致快照。
     *
     * @param lock        锁的字段（没有锁为空表）
     * @param lockTtlSec  锁的 TTL（秒；-2 = 不存在，-1 = 永不过期）
     * @param settlements 待结算记录的全部字段，保持读出顺序（字段名是原始字节，见 {@link SettlementField}）
     */
    public record EnterRead(Map<String, String> lock, long lockTtlSec, List<SettlementField> settlements) {

        public EnterRead {
            lock = Map.copyOf(lock);
            settlements = List.copyOf(settlements);
        }

        /** 锁的 b（无符号十进制解析）；没有锁 / 解析失败为 0。 */
        public long lockBattleId() {
            return parseUnsigned(lock.get(FIELD_BATTLE));
        }
    }

    private final RedissonClient redis;

    public BattleRedis(RedissonClient redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    // ------------------------------------------------------------------ 纯函数

    /**
     * 锁 TTL（基线 {@code LockTtlSecFor}，{@code pb.cpp:229-234}）：{@code max(0, 有效期限 − now) / 1000}（整数截断）+ 60。
     */
    public static long lockTtlSec(long effectiveDeadlineMs, long nowMs) {
        long remaining = effectiveDeadlineMs - nowMs;
        return (remaining > 0 ? remaining / 1000 : 0) + LOCK_EXTRA_TTL_SEC;
    }

    /** 无符号十进制解析；null / 空 / 非法为 0。 */
    public static long parseUnsigned(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseUnsignedLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ scene 侧

    /** {@link #PREPARE_LOCK}：回 "0" = 写入（或重放）成功；否则回现在的 b（被占）。回复为空（协议异常）以异常完成。 */
    public CompletableFuture<String> prepareLock(long playerId, long battleId, int battleNodeId, long deadlineMs,
                                                 long prepareDeadlineMs, long ttlSec) {
        return this.<byte[]>eval(PREPARE_LOCK, RScript.ReturnType.VALUE,
                        List.of(RedisKeys.battleLock(playerId)), unsigned(battleId), text(Integer.toUnsignedString(battleNodeId)),
                        unsigned(deadlineMs), unsigned(prepareDeadlineMs), number(ttlSec))
                .thenApply(raw -> {
                    if (raw == null) {
                        throw new IllegalStateException("PREPARE_LOCK 回复为空");
                    }
                    return new String(raw, StandardCharsets.UTF_8);
                });
    }

    /**
     * {@link #CONFIRM}：b == X 时回锁的全部字段（已改成 F，至少含 b 与 s），否则回 null。{@code deadlineMs} / {@code ttlSec} 为 0 = 不改。
     * 未命中时脚本回 nil；Redisson 把 nil 的多值回复解成 null 还是空表取决于版本，两种都按未命中（命中的回复不可能是空表）。
     */
    public CompletableFuture<Map<String, String>> confirm(long playerId, long battleId, long deadlineMs, long ttlSec) {
        return this.<List<Object>>eval(CONFIRM, RScript.ReturnType.MULTI,
                        List.of(RedisKeys.battleLock(playerId)), unsigned(battleId), unsigned(deadlineMs), number(ttlSec))
                .thenApply(list -> list == null || list.isEmpty() ? null : pairs(list, 0, list.size()));
    }

    /**
     * {@link #CANCEL_OFFLINE}（离线取消）：{@link #PREPARING_DELETE_DONE} 1 删了 / {@link #PREPARING_DELETE_FIGHTING} 2 FIGHTING 拒绝 /
     * {@link #PREPARING_DELETE_MISS} 0 不是本局。
     */
    public CompletableFuture<Long> cancelOffline(long playerId, long battleId) {
        return integer("CANCEL_OFFLINE", CANCEL_OFFLINE, List.of(RedisKeys.battleLock(playerId)), unsigned(battleId));
    }

    /**
     * 只删备战锁：b == X 且 s ≠ F 才删（与 {@link #cancelOffline} 是同一段脚本 {@link #CANCEL_OFFLINE}，另起名字给在线路径用，审计 FRZ-7）。
     * 在线取消备战、备战失败 / 过期后的尽力删锁这些「只该删 P 锁」的路径用它，不用 {@link #deleteIfMatch}：删除被 Redisson 重排 / 重发到
     * {@code CONFIRM} 之后时，这一局已经标成 F 并且（迟到确认）重建了 FIGHTING 冻结，再删就留下没有锁的在打冻结。
     *
     * @return {@link #PREPARING_DELETE_DONE} 1 删了 / {@link #PREPARING_DELETE_FIGHTING} 2 是本局但已 F、没删 / {@link #PREPARING_DELETE_MISS} 0 不是本局或锁不在
     */
    public CompletableFuture<Long> deletePreparingIfMatch(long playerId, long battleId) {
        return integer("DELETE_PREPARING_IF_MATCH", CANCEL_OFFLINE, List.of(RedisKeys.battleLock(playerId)), unsigned(battleId));
    }

    /** {@link #DELETE_IF_MATCH}：1 删了 / 0 不是本局。<b>不看 s</b>（F 也删），只给 reaper 判废用；只该删备战锁的路径用 {@link #deletePreparingIfMatch}。 */
    public CompletableFuture<Long> deleteIfMatch(long playerId, long battleId) {
        return integer("DELETE_IF_MATCH", DELETE_IF_MATCH, List.of(RedisKeys.battleLock(playerId)), unsigned(battleId));
    }

    /**
     * {@link #TOUCH}：{@link #TOUCH_HIT} 1 命中并写入 / {@link #TOUCH_MISS} 0 不是本局 / {@link #TOUCH_KEPT_FIGHTING} 2 命中但锁上已是 F 而
     * {@code state} 是 P，什么都没改（按命中处理）。
     */
    public CompletableFuture<Long> touch(long playerId, long battleId, long ttlSec, String state, long deadlineMs,
                                         long prepareDeadlineMs) {
        return integer("TOUCH", TOUCH, List.of(RedisKeys.battleLock(playerId)), unsigned(battleId), number(ttlSec), text(state),
                unsigned(deadlineMs), unsigned(prepareDeadlineMs));
    }

    /** {@link #HOLD}：1 命中（只延不缩）/ 0 不是本局。 */
    public CompletableFuture<Long> hold(long playerId, long battleId, long holdSec) {
        return integer("HOLD", HOLD, List.of(RedisKeys.battleLock(playerId)), unsigned(battleId), number(holdSec));
    }

    /**
     * {@link #ACK}：位 1 = 删了记录，位 2 = 放了锁。<b>每次调用都给 (玩家, 这一局) 写已销账墓碑</b>（TTL {@link #SETTLED_TOMBSTONE_TTL_SEC}），
     * 此后这一局的 {@link #storeSettlement} 回 {@link #STORE_ALREADY_SETTLED}——所以只许对已经有结论（应用过 / 丢弃了）的局调用。
     */
    public CompletableFuture<Long> ack(long playerId, long battleId) {
        return integer("ACK", ACK, List.of(RedisKeys.battleLock(playerId), RedisKeys.battleSettlements(playerId),
                RedisKeys.battleSettled(playerId, battleId)), unsigned(battleId), number(SETTLED_TOMBSTONE_TTL_SEC));
    }

    /** {@link #ENTER_READ}（读主库，见类注释）。 */
    public CompletableFuture<EnterRead> enterRead(long playerId) {
        return this.<List<Object>>eval(ENTER_READ, RScript.ReturnType.MULTI,
                        List.of(RedisKeys.battleLock(playerId), RedisKeys.battleSettlements(playerId)))
                .thenApply(BattleRedis::toEnterRead);
    }

    /** {@link #READ_IF_OURS}（读主库，见类注释）：本局记录的字节，没有为 null。 */
    public CompletableFuture<byte[]> readSettlement(long playerId, long battleId) {
        return this.<byte[]>eval(READ_IF_OURS, RScript.ReturnType.VALUE,
                List.of(RedisKeys.battleSettlements(playerId)), unsigned(battleId));
    }

    /** {@link #READ_LOCK_BATTLE}（读主库，见类注释）：锁的 b；没有锁（或 b 不是无符号十进制）为 0。 */
    public CompletableFuture<Long> readLockBattleId(long playerId) {
        return this.<byte[]>eval(READ_LOCK_BATTLE, RScript.ReturnType.VALUE,
                        List.of(RedisKeys.battleLock(playerId)))
                .thenApply(raw -> raw == null ? 0L : parseUnsigned(new String(raw, StandardCharsets.UTF_8)));
    }

    /**
     * {@link #DELETE_SETTLEMENT_FIELD}：按<b>字段名的原始字节</b>删一个字段（坏字段；传 {@link SettlementField#rawName()}）。回删到的个数（1 / 0）。
     */
    public CompletableFuture<Long> deleteSettlementField(long playerId, byte[] rawField) {
        Objects.requireNonNull(rawField, "rawField");
        return integer("DELETE_SETTLEMENT_FIELD", DELETE_SETTLEMENT_FIELD, List.of(RedisKeys.battleSettlements(playerId)), rawField);
    }

    // ------------------------------------------------------------------ battle 侧

    /**
     * {@link #STORE_SETTLEMENT}：回落库后的字段数（≥ 1）；这一局已销账（墓碑还在）→ <b>不写</b>、回 {@link #STORE_ALREADY_SETTLED}（-1）。
     */
    public CompletableFuture<Long> storeSettlement(long playerId, long battleId, byte[] payload, long ttlSec) {
        return integer("STORE_SETTLEMENT", STORE_SETTLEMENT,
                List.of(RedisKeys.battleSettlements(playerId), RedisKeys.battleSettled(playerId, battleId)), unsigned(battleId), payload,
                number(ttlSec));
    }

    /** {@link #PROBE_SETTLEMENT}：记录还在吗。回复为空以异常完成（不映射成「不在」，D15）。 */
    public CompletableFuture<Boolean> settlementExists(long playerId, long battleId) {
        return integer("PROBE_SETTLEMENT", PROBE_SETTLEMENT, List.of(RedisKeys.battleSettlements(playerId)), unsigned(battleId))
                .thenApply(n -> n != 0);
    }

    /**
     * {@link #ACK_IF_SUPERSEDED}：3 已销账 / 0 锁不在 / 1 仍是本局 / 2 已被取代（删了记录，并写已销账墓碑——之后这一局的落库回
     * {@link #STORE_ALREADY_SETTLED}）。
     */
    public CompletableFuture<Long> ackIfSuperseded(long playerId, long battleId) {
        return integer("ACK_IF_SUPERSEDED", ACK_IF_SUPERSEDED, List.of(RedisKeys.battleLock(playerId),
                RedisKeys.battleSettlements(playerId), RedisKeys.battleSettled(playerId, battleId)), unsigned(battleId),
                number(SETTLED_TOMBSTONE_TTL_SEC));
    }

    /** 活动结果落库（{@code SET EX}）：回 1。 */
    public CompletableFuture<Long> storeActivityResult(long battleId, byte[] payload, long ttlSec) {
        return integer("STORE_ACTIVITY_RESULT", STORE_ACTIVITY_RESULT, List.of(RedisKeys.battleActivityResult(battleId)), payload,
                number(ttlSec));
    }

    /** 活动结果还在吗（EXISTS）。回复为空以异常完成（不映射成「不在」）。 */
    public CompletableFuture<Boolean> activityResultExists(long battleId) {
        return integer("PROBE_ACTIVITY_RESULT", PROBE_ACTIVITY_RESULT, List.of(RedisKeys.battleActivityResult(battleId)))
                .thenApply(n -> n != 0);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 跑一段回整数的脚本；回复为空（这些脚本都必回整数，空 = 协议异常）以 {@link IllegalStateException} 异常完成。
     * 包内可见：真 Redis 测试用它钉住「空回复 → 异常完成」。
     */
    CompletableFuture<Long> integer(String name, String script, List<Object> keys, byte[]... args) {
        return this.<Long>eval(script, RScript.ReturnType.INTEGER, keys, args).thenApply(n -> {
            if (n == null) {
                throw new IllegalStateException(name + " 回复为空（期望整数）");
            }
            return n;
        });
    }

    /** 一律 {@link RScript.Mode#READ_WRITE}：可变脚本本来就要写主库，只读脚本也必须读主库（见类注释）。 */
    private <R> CompletableFuture<R> eval(String script, RScript.ReturnType type, List<Object> keys, byte[]... args) {
        try {
            return redis.getScript(ByteArrayCodec.INSTANCE).<R>evalAsync(RScript.Mode.READ_WRITE, script, type, keys, (Object[]) args)
                    .toCompletableFuture();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    static EnterRead toEnterRead(List<Object> flat) {
        if (flat == null || flat.size() < 2) {
            throw new IllegalStateException("ENTER_READ 回复形状不对: " + flat);
        }
        long ttl = asLong(flat.get(0));
        long lockLen = asLong(flat.get(1));
        if (lockLen < 0 || lockLen % 2 != 0 || 2 + lockLen > flat.size()) {
            throw new IllegalStateException("ENTER_READ 锁段长度不对: " + lockLen + "（回复共 " + flat.size() + " 项）");
        }
        int settlementsFrom = 2 + (int) lockLen;
        if ((flat.size() - settlementsFrom) % 2 != 0) {
            throw new IllegalStateException("ENTER_READ 记录段不成对: 回复共 " + flat.size() + " 项，锁段 " + lockLen);
        }
        Map<String, String> lock = pairs(flat, 2, settlementsFrom);
        List<SettlementField> settlements = new ArrayList<>((flat.size() - settlementsFrom) / 2);
        for (int i = settlementsFrom; i + 1 < flat.size(); i += 2) {
            // 字段名保留原始字节（RDS-11）：不经 String 往返
            settlements.add(new SettlementField(asBytes(flat.get(i)), asBytes(flat.get(i + 1))));
        }
        return new EnterRead(lock, ttl, settlements);
    }

    private static Map<String, String> pairs(List<Object> flat, int from, int to) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = from; i + 1 < to; i += 2) {
            out.put(asText(flat.get(i)), asText(flat.get(i + 1)));
        }
        return out;
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof byte[] b) {
            try {
                return Long.parseLong(new String(b, StandardCharsets.UTF_8));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("期望整数，得到 " + HexFormat.of().formatHex(b));
            }
        }
        throw new IllegalStateException("期望整数，得到 " + value);
    }

    private static String asText(Object value) {
        if (value instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private static byte[] asBytes(Object value) {
        if (value instanceof byte[] b) {
            return b;
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] unsigned(long value) {
        return text(Long.toUnsignedString(value));
    }

    static byte[] number(long value) {
        return text(Long.toString(value));
    }

    static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 测试用：把扁平数组按 ENTER_READ 的形状拼出来（字段名用原始字节）。 */
    static List<Object> flatEnterRead(long ttl, Map<String, String> lock, List<SettlementField> settlements) {
        List<Object> out = new ArrayList<>();
        out.add(ttl);
        out.add((long) lock.size() * 2);
        lock.forEach((k, v) -> {
            out.add(text(k));
            out.add(text(v));
        });
        settlements.forEach(field -> {
            out.add(field.rawName());
            out.add(field.value());
        });
        return out;
    }
}
