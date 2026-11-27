package com.game.common.workshard;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * 后台工作按固定 64 片做最高随机权重哈希（HRW / Rendezvous）分工（对齐主仓 {@code go/shared/workshard/workshard.go}）。
 *
 * <p>分工只省重复劳动，不承担正确性：成员表为空或过期时回落为本副本承担全部 64 片（保活优先）；
 * 成员表非空但本副本不在其中（尚未注册或租约刚失效）时不承担任何片，交给成员表内的存活副本。
 * 写路径正确性仍由各业务 SQL 提交点的行锁、版本条件或租约保证。
 */
public final class WorkShard {

    /** 每类后台工作的固定片数。 */
    public static final int SHARDS = 64;
    /** 成员表默认刷新间隔（5 秒）。 */
    public static final long DEFAULT_REFRESH_INTERVAL_MS = 5_000L;
    /** 成员表连续失联超 30 秒视作过期、回落到全片负责。 */
    public static final long DEFAULT_STALE_AFTER_MS = 30_000L;

    private WorkShard() {
    }

    /** 跨进程一致的 HRW 权重：SHA-256(member + 0x00 + key) 的前 8 字节大端无符号 uint64。 */
    static long weight(String member, String key) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(member.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(key.getBytes(StandardCharsets.UTF_8));
            byte[] digest = sha.digest();
            return ByteBuffer.wrap(digest, 0, 8).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** 返回 key 的负责成员；没有非空成员返回空串。结果只取决于成员集合，与输入顺序无关。 */
    public static String owner(String key, Collection<String> members) {
        if (key == null || members == null || members.isEmpty()) {
            return "";
        }
        String bestOwner = "";
        long bestScore = 0L;
        for (String member : members) {
            if (member == null || member.isEmpty()) {
                continue;
            }
            long score = weight(member, key);
            if (bestOwner.isEmpty()
                    || Long.compareUnsigned(score, bestScore) > 0
                    || (score == bestScore && member.compareTo(bestOwner) < 0)) {
                bestOwner = member;
                bestScore = score;
            }
        }
        return bestOwner;
    }

    /** 报告 self 是否负责 key；self 为空或不在 members 中返回 false。 */
    public static boolean owns(String self, String key, Collection<String> members) {
        return self != null && !self.isEmpty() && self.equals(owner(key, members));
    }

    /** kind 类工作第 shard 片的归属键。 */
    public static String shardKey(String kind, int shard) {
        return kind + ":" + shard;
    }

    /** 实体 id 所属片号（与 SQL {@code MOD(column, 64)} 同算法，无符号取模）。 */
    public static int shardOf(long entityId) {
        return (int) Long.remainderUnsigned(entityId, SHARDS);
    }

    /** SQL WHERE 过滤片段与对应绑定参数。 */
    public record SqlFilter(String sql, List<Object> args) {
        public static final SqlFilter ALL = new SqlFilter("1=1", List.of());
        public static final SqlFilter NONE = new SqlFilter("1=0", List.of());

        public SqlFilter {
            sql = (sql == null || sql.isEmpty()) ? "1=1" : sql;
            args = args == null ? List.of() : List.copyOf(args);
        }

        public boolean isAll() {
            return "1=1".equals(sql) && args.isEmpty();
        }

        public boolean isNone() {
            return "1=0".equals(sql);
        }
    }

    /**
     * 构造放在 {@code LIMIT} 之前的 SQL 分片过滤条件：全部 64 片返回 {@link SqlFilter#ALL}，
     * 0 片返回 {@link SqlFilter#NONE}，其余返回 {@code MOD(<column>,64) IN(?,?,...)}。
     */
    public static SqlFilter sqlFilter(Predicate<String> owns, String kind, String column) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(column, "column");
        if (owns == null) {
            return SqlFilter.ALL;
        }
        List<Object> shards = new ArrayList<>(SHARDS);
        for (int s = 0; s < SHARDS; s++) {
            if (owns.test(shardKey(kind, s))) {
                shards.add(s);
            }
        }
        if (shards.size() == SHARDS) {
            return SqlFilter.ALL;
        }
        if (shards.isEmpty()) {
            return SqlFilter.NONE;
        }
        String placeholders = String.join(",", Collections.nCopies(shards.size(), "?"));
        return new SqlFilter("MOD(" + column + "," + SHARDS + ") IN(" + placeholders + ")", shards);
    }

    /**
     * 一类后台工作的活跃 stable 成员表快照（canary 不纳入成员表，防未跑后台的灰度副本占片）。
     * 读路径无锁、线程安全。
     */
    public static final class Membership {
        private final long staleAfterMs;
        private final LongSupplier clockMs;
        private final AtomicReference<String> self = new AtomicReference<>("");
        private final AtomicReference<List<String>> members = new AtomicReference<>();
        private final AtomicLong listedAtMs = new AtomicLong();

        public Membership() {
            this(DEFAULT_STALE_AFTER_MS, System::currentTimeMillis);
        }

        public Membership(long staleAfterMs, LongSupplier clockMs) {
            this.staleAfterMs = staleAfterMs;
            this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        }

        public void setSelf(String nodeUuid) {
            self.set(nodeUuid == null ? "" : nodeUuid.strip());
        }

        public String selfId() {
            return self.get();
        }

        /** 更新活跃 stable 成员表（自动去重升序）；canary 由调用方过滤不入表。 */
        public void updateMembers(Collection<String> stableNodeUuids) {
            TreeSet<String> sorted = new TreeSet<>();
            if (stableNodeUuids != null) {
                for (String id : stableNodeUuids) {
                    if (id != null && !id.isBlank()) {
                        sorted.add(id.strip());
                    }
                }
            }
            members.set(List.copyOf(sorted));
            listedAtMs.set(clockMs.getAsLong());
        }

        /** 返回当前未过期的成员列表；未读到或已过期返回空列表。 */
        public List<String> members() {
            List<String> cur = members.get();
            if (cur == null) {
                return List.of();
            }
            if (staleAfterMs >= 0 && clockMs.getAsLong() - listedAtMs.get() > staleAfterMs) {
                return List.of();
            }
            return cur;
        }

        /** 成员表为空或已过期 -> true（全片保活）；本副本不在非空表内 -> false；否则按 HRW 判。 */
        public boolean owns(String key) {
            List<String> cur = members();
            if (cur.isEmpty()) {
                return true;
            }
            return WorkShard.owns(self.get(), key, cur);
        }

        public SqlFilter sqlFilter(String kind, String column) {
            return WorkShard.sqlFilter(this::owns, kind, column);
        }
    }
}
