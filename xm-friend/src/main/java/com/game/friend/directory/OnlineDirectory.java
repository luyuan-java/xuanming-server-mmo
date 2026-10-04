package com.game.friend.directory;

import com.game.common.text.GoSpaces;
import com.game.discovery.proto.PlayerPresence;
import com.game.friend.profile.PlayerProfiles.Profile;
import com.game.friend.support.Deadline;
import com.game.friend.support.Deadline.DependencyException;
import com.game.proto.friend.RecommendEntry;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

/**
 * 在线目录（RecommendFriends 的 {@code online_only = true}，mmorpg go/friend internal/data/online_directory.go，friend-spec.md §5.2）。
 *
 * <p>与基线的有意差异（D5）：枚举源是 {@code xm:presence:*}（条目存在、能解码、player_id 与键一致就算在线），资料与 home zone 取自
 * {@code xm_java.player}（基线是 PlayerAllData 缓存 + data_service）。算法、游标格式、每页至多 4 轮 SCAN、COUNT 64、单批 1024 上限、
 * 键按字典序、offset 与 next_cursor 规则逐条照搬：
 * <ul>
 *   <li>游标 {@code v1:<scan>:<offset>}：空串 = 从头；≤ 48 字节；恰好三段；两段数字只认 ASCII 十进制（不收符号）；offset ≤ 1024；</li>
 *   <li>凑满 limit 时：本批还有剩余 → {@code v1:<本轮游标>:<下一个下标>}，否则 → {@code encode(next, 0)}；
 *       没凑满且 next = 0 → 结束（next_cursor 为空，哪怕不足一页）；4 轮用完 → {@code encode(next, 0)}，本页可以为空；</li>
 *   <li>不排除好友，也不排除任一方向的拉黑关系（整条路径不读好友表）；不承诺跨页快照，客户端按 id 去重。</li>
 * </ul>
 * 调用方（工作线程）先校验输入、再计配额，之后调 {@link #list}。依赖故障抛 {@link DependencyException}（回 1003）。
 */
public final class OnlineDirectory {

    static final int SCAN_COUNT = 64;
    static final int SCAN_ROUNDS = 4;
    static final int MAX_BATCH = 1024;
    static final int MAX_CURSOR_BYTES = 48;
    static final int MAX_QUERY_RUNES = 64;

    /** 一次 SCAN 的结果。{@code next} 是无符号 64 位游标。 */
    public record ScanPage(long next, List<String> keys) {
    }

    /** 在线目录需要的 Redis 操作。 */
    public interface DirectoryRedis {
        /** {@code SCAN cursor MATCH <presence 前缀>* COUNT 64}。 */
        CompletionStage<ScanPage> scan(long cursor);

        /** MGET 这些键（值为 null 表示不存在）。 */
        CompletionStage<Map<String, byte[]>> mget(List<String> keys);
    }

    /** 游标。 */
    record Cursor(long scan, int offset) {
    }

    private final DirectoryRedis redis;
    private final String keyPrefix;
    private final BiFunction<List<Long>, Deadline, Map<Long, Profile>> profiles;

    /**
     * @param keyPrefix 在线目录键前缀（{@code xm:presence:}）
     * @param profiles  批量读 player 行（读失败须抛异常，不许返回部分结果当成「不存在」）
     */
    public OnlineDirectory(DirectoryRedis redis, String keyPrefix, BiFunction<List<Long>, Deadline, Map<Long, Profile>> profiles) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.profiles = profiles;
    }

    // ================================================================ 输入

    /** 游标与 query 是否合法（在计配额之前调用：非法输入不占额度）。 */
    public static boolean validInput(String cursor, String query) {
        return parseCursor(cursor) != null && query.codePointCount(0, query.length()) <= MAX_QUERY_RUNES;
    }

    /** 解析游标；非法返回 null。 */
    static Cursor parseCursor(String raw) {
        if (raw.isEmpty()) {
            return new Cursor(0, 0);
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_CURSOR_BYTES) {
            return null;
        }
        String[] parts = raw.split(":", -1); // -1：保留尾部空段（"v1:1:2:" 必须判非法）
        if (parts.length != 3 || !parts[0].equals("v1") || !asciiDigits(parts[1]) || !asciiDigits(parts[2])) {
            return null;
        }
        long scan;
        long offset;
        try {
            scan = Long.parseUnsignedLong(parts[1]);
            offset = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return null; // 越界
        }
        if (offset > MAX_BATCH) {
            return null;
        }
        return new Cursor(scan, (int) offset);
    }

    static String encodeCursor(long scan, int offset) {
        if (scan == 0 && offset == 0) {
            return "";
        }
        return "v1:" + Long.toUnsignedString(scan) + ":" + offset;
    }

    /** 非空且全是 ASCII 数字（{@code Long.parseUnsignedLong} 收前导 '+' 与非 ASCII 数字，必须先挡）。 */
    private static boolean asciiDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Go {@code strings.ToLower(strings.TrimSpace(q))}。 */
    static String normalizeQuery(String query) {
        return goLower(GoSpaces.trim(query));
    }

    /** Go {@code strings.ToLower}：逐码点简单映射（不用 {@code String.toLowerCase(Locale)}：那是完整映射、会改变长度）。 */
    static String goLower(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> out.appendCodePoint(Character.toLowerCase(cp)));
        return out.toString();
    }

    // ================================================================ 枚举

    /** 一页的结果。 */
    public record Page(List<RecommendEntry> candidates, String nextCursor) {
    }

    /**
     * 列一页在线玩家。
     *
     * @param callerZone 调用者的 home zone（调用方已确认非 0）
     * @param limit      页长（入口已钳到 [1, 20]）
     */
    public Page list(long caller, int callerZone, String cursor, int limit, List<Long> exclude, String query,
                     Deadline deadline) {
        Cursor pos = parseCursor(cursor);
        if (pos == null) {
            throw new IllegalArgumentException("游标不合法"); // 调用方已校验，这里是二次防线
        }
        String q = normalizeQuery(query);
        Set<Long> skip = new HashSet<>(exclude);
        skip.add(caller);
        List<RecommendEntry> out = new ArrayList<>();
        for (int round = 0; round < SCAN_ROUNDS; round++) {
            if (deadline.expired()) {
                throw new DependencyException("在线目录超过请求预算");
            }
            ScanPage page = deadline.await(redis.scan(pos.scan()), "扫描在线目录");
            if (page.keys().size() > MAX_BATCH) {
                throw new DependencyException("在线目录扫描批次超出安全上限 " + page.keys().size());
            }
            List<String> keys = new ArrayList<>(page.keys());
            keys.sort(null); // 字典序（"…:10" 排在 "…:9" 前）
            int start = Math.min(pos.offset(), keys.size()); // 上下线让批次变短时整批跳过
            Map<String, RecommendEntry> entries = readEntries(keys.subList(start, keys.size()), skip, q, callerZone, deadline);
            for (int i = start; i < keys.size(); i++) {
                RecommendEntry entry = entries.get(keys.get(i));
                if (entry == null || skip.contains(entry.getCandidatePlayerId())) {
                    continue;
                }
                skip.add(entry.getCandidatePlayerId());
                out.add(entry);
                if (out.size() == limit) {
                    String next = i + 1 < keys.size() ? encodeCursor(pos.scan(), i + 1) : encodeCursor(page.next(), 0);
                    return new Page(out, next);
                }
            }
            pos = new Cursor(page.next(), 0);
            if (page.next() == 0) {
                return new Page(out, "");
            }
        }
        return new Page(out, encodeCursor(pos.scan(), 0));
    }

    private Map<String, RecommendEntry> readEntries(List<String> keys, Set<Long> skip, String q, int zone,
                                                    Deadline deadline) {
        Map<String, Long> idsByKey = new HashMap<>();
        for (String key : keys) {
            if (!key.startsWith(keyPrefix)) {
                continue;
            }
            String digits = key.substring(keyPrefix.length());
            if (!asciiDigits(digits)) {
                continue;
            }
            long id;
            try {
                id = Long.parseUnsignedLong(digits);
            } catch (NumberFormatException e) {
                continue;
            }
            if (id == 0 || skip.contains(id)) {
                continue;
            }
            idsByKey.put(key, id);
        }
        Map<String, RecommendEntry> entries = new HashMap<>();
        if (idsByKey.isEmpty()) {
            return entries;
        }
        List<String> readKeys = new ArrayList<>(idsByKey.keySet());
        Map<String, byte[]> values = deadline.await(redis.mget(readKeys), "读在线目录条目");
        Map<Long, PlayerPresence> online = new HashMap<>();
        for (String key : readKeys) {
            byte[] raw = values.get(key);
            if (raw == null) {
                continue;
            }
            long id = idsByKey.get(key);
            try {
                PlayerPresence presence = PlayerPresence.parseFrom(raw);
                if (presence.getPlayerId() == id) {
                    online.put(id, presence);
                }
            } catch (InvalidProtocolBufferException e) {
                // 坏条目不算在线（gate 续期时发现值不是自己写的就不续，至多存活一个 TTL）
            }
        }
        if (online.isEmpty()) {
            return entries;
        }
        Map<Long, Profile> rows;
        try {
            rows = profiles.apply(new ArrayList<>(online.keySet()), deadline);
        } catch (RuntimeException e) {
            throw new DependencyException("读玩家资料失败", e);
        }
        for (Map.Entry<String, Long> e : idsByKey.entrySet()) {
            long id = e.getValue();
            PlayerPresence presence = online.get(id);
            Profile profile = rows.get(id);
            if (presence == null || profile == null) {
                continue;
            }
            String name = GoSpaces.trim(profile.name() == null ? "" : profile.name());
            // 等级可为 0，原样返回、不伪造；名字为空或职业为 0 的不列；home zone 不同（含 0）的删掉
            if (name.isEmpty() || profile.classId() == 0 || profile.zoneId() != zone) {
                continue;
            }
            if (!q.isEmpty() && !goLower(name).contains(q) && !Long.toUnsignedString(id).contains(q)) {
                continue;
            }
            entries.put(e.getKey(), RecommendEntry.newBuilder()
                    .setCandidatePlayerId(id).setIsOnline(true).setLastActiveMs(presence.getOnlineSinceMs())
                    .setName(name).setLevel(profile.level()).setClassId(profile.classId()).setGender(profile.gender())
                    .setAppearanceId(profile.appearanceId() == null ? "" : profile.appearanceId())
                    .setZoneId(zone).build());
        }
        return entries;
    }
}
