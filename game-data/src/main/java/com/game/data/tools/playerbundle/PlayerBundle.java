package com.game.data.tools.playerbundle;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import java.util.Map;

/**
 * 单玩家排障导出包（{@link PlayerBundleExport} 写、{@link PlayerBundleImport} 读的同一份 JSON）。一份包回答「这名玩家此刻在线上的
 * 权威数据是什么、最近一次存盘落库没有」：库里的 {@code player} 行与 {@code player_state} 原字节、账号（不含口令哈希）、最近的快照 / 资产流水 /
 * 运维作业与回收记录，Redis 里该玩家的相关键，以及工具据此给出的判读 {@link Analysis}。
 *
 * <p>格式只增不改：导入端按 {@link #FORMAT} / {@link #FORMAT_VERSION} 拒绝看不懂的包；新增字段必须可空或有零值语义。
 * 64 位无符号数（玩家号、epoch、快照号、流水号）一律写成十进制字符串，避免 JSON 数字精度丢失与符号问题。
 *
 * @param format         固定 {@link #FORMAT}
 * @param formatVersion  固定 {@link #FORMAT_VERSION}
 * @param playerId       玩家号（无符号十进制）
 * @param exportedAtMs   导出时刻（Unix 毫秒，导出机墙钟）
 * @param operator       操作人（必填）
 * @param reason         原因 / 工单号（必填）
 * @param serverRevision 线上部署对应的 git 提交（建议填；解码用的是本地检出的 proto）
 * @param namespace      部署命名空间（Redis 键前缀与库名）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlayerBundle(String format, int formatVersion, String playerId, long exportedAtMs, String exportedAtUtc,
                           String operator, String reason, String serverRevision, String namespace, Sources sources,
                           Mysql mysql, List<RedisEntry> redis, Analysis analysis) {

    /** 格式名（不带部署命名空间，也不是共享中间件里的名字）。 */
    public static final String FORMAT = "xuanming-java-player-bundle";
    public static final int FORMAT_VERSION = 1;

    /**
     * 数据从哪几台实例读出（主机 / 库，不含口令与整串 URL）。{@code *Identity} 是实例身份（{@link InstanceIdentity}），
     * 导入端据此拒绝写回导出来源。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Sources(String mysqlHost, String mysqlDatabase, String mysqlVersion, String mysqlIdentity,
                          String redisHost, Integer redisDatabase, String redisIdentity) {
    }

    /** 库里与该玩家有关的行，同一个只读一致性快照内读出。读不到的部分记在 {@code errors}（键 = 部分名），不影响其余部分。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mysql(Account account, Player player, State playerState, List<Map<String, Object>> snapshots,
                        List<Map<String, Object>> transactionLog, TxlogCounts transactionLogCounts,
                        List<Map<String, Object>> opsJobs, List<Map<String, Object>> recallSources,
                        Map<String, String> errors, int limit) {
    }

    /**
     * 账号行。<b>口令哈希不导出</b>：只给出有没有设口令（判断这个账号能不能口令登录）。导入端不用账号行——本地登录用
     * {@code --local-account} 指定的本地账号。
     */
    public record Account(String account, long createdAt, boolean hasPasswordHash) {
    }

    /**
     * {@code player} 行的全部列（{@code name_key} 除外：它只由 {@code PlayerStore.nameKey} 从 {@code name} 算，导入端重算）。
     * 无符号列：{@code player_id} / {@code owner_epoch} 是十进制字符串，INT UNSIGNED 列用 long 承载（不会是负数）。
     */
    public record Player(String playerId, String account, long zoneId, String name, long classId, long gender,
                         String appearanceId, long level, long sceneConfigId, double posX, double posY, double posZ,
                         String ownerEpoch, int ownerReleased, long ownerLeaseUntil, long createdAt, long updatedAt) {
    }

    /**
     * {@code player_state} 行。{@code dataBase64} 是原样字节（导入端按它还原，并用 {@code sha256} 校验没被改过）；
     * {@code decoded} 只供查看（{@code StateJson}：本地检出的 proto 解码，不认识的字段另列原字节）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record State(boolean found, String savedEpoch, Long updatedAt, Integer bytes, String sha256, String dataBase64,
                        Map<String, Object> decoded) {

        static State absent() {
            return new State(false, null, null, null, null, null, null);
        }
    }

    /**
     * 资产流水计数：{@code total} 是该玩家作为扣减方或获得方的全部条数；{@code afterStateSave} 是时刻晚于 {@code player_state.updated_at}
     * 的条数（库里的玩法数据可能还不含这些变动）。没有 {@code player_state} 行时 {@code afterStateSave} 为 null。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TxlogCounts(long total, Long afterStateSave) {
    }

    /**
     * Redis 里的一把键（一次原子读出的类型、TTL 与值；键与键之间不是同一时刻，见 {@link PlayerRedis#read}）。
     *
     * @param type        Redis TYPE 的结果；{@code none} = 不存在；读失败为 null（原因在 {@code error}）
     * @param pttlMs      -1 = 无 TTL，-2 = 不存在
     * @param value       string 类型的值：protobuf 值解成 JSON 文本，其余 UTF-8 可读时原样给出，否则为 null
     * @param valueBase64 string 类型的原字节
     * @param hash        hash 类型的全部字段（protobuf 值解成 JSON 文本，其余按 UTF-8，不可读的写成 {@code base64:…}）
     * @param zset        zset 类型的成员与分数（玩家级的小集合，至多 200 个）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RedisEntry(String key, String purpose, String type, Long pttlMs, String value, String valueBase64,
                             Map<String, String> hash, Map<String, Double> zset, String error) {
    }

    /**
     * 判读（{@link BundleAnalyzer}）。
     *
     * @param ownership   归属状态：{@code NO_PLAYER} / {@code RELEASED} / {@code HELD} / {@code HELD_LEASE_EXPIRED} / {@code IMPORT_HOLD}
     * @param online      在线与否的说明
     * @param persistence 最近一次存盘是否落库的结论
     * @param leaseRemainingMs 归属租约相对导出时刻的剩余毫秒（负数 = 已过期多久；未持有为 null）
     * @param warnings    需要排障者注意的事项
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Analysis(String ownership, String online, String persistence, Long leaseRemainingMs, List<String> warnings) {
    }

    /** 读写导出包用的 JSON 映射（缩进输出、遇到不认识的字段不报错——格式只增不改，旧导入端读得了新字段）。 */
    static ObjectMapper json() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
