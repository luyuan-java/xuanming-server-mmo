package com.game.team.store;

import com.game.common.deadline.Deadline.DependencyException;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.Decision;
import com.google.protobuf.InvalidProtocolBufferException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 组队脚本回复的解析（基线 store.go:694-720 read、:774-809 commit 的回复部分、:416-436 ListInvites、:913-938 unmarshalRecord / parseUint）。
 * 纯函数，无 I/O。回复形状不对、记录解不开都抛 {@link DependencyException}（基线返回 error，服务层回 4030）。
 *
 * <p>回复来自 ByteArrayCodec + MULTI：整数是 {@code Long}，字符串是 {@code byte[]}（为了健壮也接受 {@code String}）。
 */
final class TeamReplies {

    /** Redis 回给我们的 score 只会是写入时的十进制整数（S_COMMIT 用 expire_at_ms 十进制 ZADD）；接受一般的十进制浮点写法。 */
    private static final Pattern DECIMAL_FLOAT = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");
    private static final double TWO_POW_63 = 0x1p63;
    private static final double TWO_POW_64 = 0x1p64;

    private TeamReplies() {
    }

    /** S_COMMIT 的结局（store.go:740-748）。 */
    enum CommitStatus {
        OK,
        /** {@code {0}}：版本冲突，或非建队遇到记录已不存在。 */
        CONFLICT,
        /** {@code {-1,i}}：第 i 个新成员已在别的队。 */
        MEMBER_IN_TEAM,
        /** {@code {-2,i}}：第 i 个保留成员的索引不是本队。 */
        INDEX_MISMATCH,
        /** {@code {-3,i}}：第 i 个被邀请人待处理邀请已达上限。 */
        INVITE_LIMIT
    }

    /**
     * 一次 S_COMMIT 的解析结果（store.go:750-754）。
     *
     * @param index  {@code {-1,i}} / {@code {-2,i}} / {@code {-3,i}} 的 i（从 1 起；回复里不是合法正整数时为 0，调用方按越界处理）
     * @param result {@link CommitStatus#OK} 时的提交结果
     */
    record CommitOutcome(CommitStatus status, int index, CommitResult result) {
    }

    /**
     * 解析 Lua 返回的十进制字符串或整数（store.go:922-938 parseUint）：空串、非法串、溢出、负整数一律为 0。
     * 只认 ASCII 数字（与 Go {@code strconv.ParseUint(x, 10, 64)} 一致：不认符号、空白、下划线）。
     */
    static long parseUint(Object v) {
        if (v instanceof Long n) {
            return n < 0 ? 0 : n;
        }
        String s = text(v);
        if (s == null || !isDigits(s)) {
            return 0;
        }
        try {
            return Long.parseUnsignedLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 玩家索引 tid 字段的严格解析（store.go:722-731：{@code strconv.ParseUint} 出错即返回 error）。
     *
     * @throws DependencyException 不是合法的无符号十进制（含空串、溢出）
     */
    static long parseIndexTid(byte[] raw, long playerId) {
        String s = new String(raw, StandardCharsets.ISO_8859_1);
        if (isDigits(s)) {
            try {
                return Long.parseUnsignedLong(s);
            } catch (NumberFormatException ignored) {
                // 溢出，落到下面
            }
        }
        throw new DependencyException("组队索引 tid 不是无符号十进制 player=" + Long.toUnsignedString(playerId) + " tid=" + s);
    }

    /** 回复转数组；不是数组抛 {@link DependencyException}。 */
    static List<Object> multi(Object raw, String what) {
        if (raw instanceof List<?> list) {
            return Collections.unmodifiableList(new ArrayList<>(list));
        }
        throw new DependencyException(what + " 返回形态非法: " + describe(raw));
    }

    /** S_READ 回复（store.go:694-720）：{@code {tidNow, epoch, ver, pb, recTTL, nowMs}}。 */
    static Snapshot parseRead(long playerId, long teamId, Object raw) {
        List<Object> arr = multi(raw, "S_READ");
        if (arr.size() != 6) {
            throw new DependencyException("S_READ 返回形态非法: 长度 " + arr.size());
        }
        long version = parseUint(arr.get(2));
        TeamRecord record = version != 0 ? unmarshalRecord(arr.get(3)) : null;
        long ttl = arr.get(4) instanceof Long n ? n : 0;
        return new Snapshot(playerId, parseUint(arr.get(0)), parseUint(arr.get(1)), teamId, version, record, ttl,
                parseUint(arr.get(5)));
    }

    /**
     * S_COMMIT 回复（store.go:774-809）。返回码只认 1 / 0 / -1 / -2 / -3；拒绝分支长度必须是 2；成功分支长度必须是
     * {@code 2 + 2(nJ+nK+nL)}，{@code indexes} 按 J、K、L 顺序配对。回复首项不是整数时按 0（冲突）处理（同基线 {@code code, _ := arr[0].(int64)}）。
     *
     * @param nowMs 本轮 S_READ 的 nowMs（写进结果，不是 S_COMMIT 自己的 TIME）
     */
    static CommitOutcome parseCommit(Object raw, long teamId, Decision d, long nowMs) {
        List<Object> arr = multi(raw, "S_COMMIT");
        if (arr.isEmpty()) {
            throw new DependencyException("S_COMMIT 返回形态非法: 空数组");
        }
        long code = arr.get(0) instanceof Long n ? n : 0;
        CommitStatus rejected;
        if (code == 1) {
            rejected = null;
        } else if (code == 0) {
            return new CommitOutcome(CommitStatus.CONFLICT, 0, null);
        } else if (code == -1) {
            rejected = CommitStatus.MEMBER_IN_TEAM;
        } else if (code == -2) {
            rejected = CommitStatus.INDEX_MISMATCH;
        } else if (code == -3) {
            rejected = CommitStatus.INVITE_LIMIT;
        } else {
            throw new DependencyException("S_COMMIT 未知返回码 " + code);
        }
        if (rejected != null) {
            if (arr.size() != 2) {
                throw new DependencyException("S_COMMIT 拒绝分支返回形态非法: " + describe(arr));
            }
            long idx = arr.get(1) instanceof Long n ? n : 0;
            return new CommitOutcome(rejected, idx < 1 || idx > Integer.MAX_VALUE ? 0 : (int) idx, null);
        }
        List<Long> people = new ArrayList<>(d.joined().size() + d.kept().size() + d.left().size());
        people.addAll(d.joined());
        people.addAll(d.kept());
        people.addAll(d.left());
        if (arr.size() != 2 + 2 * people.size()) {
            throw new DependencyException("S_COMMIT 成功分支长度 " + arr.size() + "，期望 " + (2 + 2 * people.size()));
        }
        Map<Long, IndexEntry> indexes = new LinkedHashMap<>();
        for (int i = 0; i < people.size(); i++) {
            indexes.put(people.get(i), new IndexEntry(parseUint(arr.get(2 + 2 * i)), parseUint(arr.get(3 + 2 * i))));
        }
        return new CommitOutcome(CommitStatus.OK, 0,
                new CommitResult(teamId, parseUint(arr.get(1)), d, Collections.unmodifiableMap(indexes), nowMs));
    }

    /** S_INVITE_LIST 回复（store.go:416-436）：{@code {nowMs, tid_1, score_1, ...}}，长度必须是奇数。 */
    static InviteList parseInviteList(Object raw) {
        List<Object> arr = multi(raw, "S_INVITE_LIST");
        if (arr.isEmpty() || arr.size() % 2 != 1) {
            throw new DependencyException("S_INVITE_LIST 返回形态非法: 长度 " + arr.size());
        }
        long nowMs = parseUint(arr.get(0));
        List<InviteIndexEntry> entries = new ArrayList<>((arr.size() - 1) / 2);
        for (int i = 1; i + 1 < arr.size(); i += 2) {
            String score = text(arr.get(i + 1));
            if (score == null) {
                score = "";
            }
            entries.add(new InviteIndexEntry(parseUint(arr.get(i)), parseScore(score), score));
        }
        return new InviteList(nowMs, entries);
    }

    /**
     * score 原字符串 → 截止毫秒（store.go:429-433：{@code strconv.ParseFloat(score, 64)} 后 {@code uint64(expire)}，向零截断）。
     * 只接受十进制浮点写法（我们写进去的只会是十进制整数）；负数为 0，≥ 2^64 饱和为无符号最大值。
     *
     * @throws DependencyException score 不是十进制数（基线返回 error，ListMyInvites 整体回 4030）
     */
    static long parseScore(String score) {
        if (!DECIMAL_FLOAT.matcher(score).matches()) {
            throw new DependencyException("邀请索引 score 非法: \"" + score + "\"");
        }
        double v = Double.parseDouble(score);
        if (!(v > 0)) {
            return 0;
        }
        if (v >= TWO_POW_64) {
            return -1L;
        }
        if (v < TWO_POW_63) {
            return (long) v;
        }
        return new BigDecimal(v).toBigInteger().longValue();
    }

    /** TeamRecord 反序列化（store.go:913-920）。不是字节串时按空字节解析（同基线 {@code s, _ := v.(string)}）。 */
    static TeamRecord unmarshalRecord(Object v) {
        byte[] bytes = v instanceof byte[] b ? b : v instanceof String s ? s.getBytes(StandardCharsets.ISO_8859_1) : new byte[0];
        try {
            return TeamRecord.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new DependencyException("TeamRecord 反序列化失败", e);
        }
    }

    /** 字节串按 ISO-8859-1 解成字符串（逐字节、无损，可原样编回去做 CAS）；不是字符串返回 null。 */
    static String text(Object v) {
        if (v instanceof byte[] b) {
            return new String(b, StandardCharsets.ISO_8859_1);
        }
        if (v instanceof String s) {
            return s;
        }
        return null;
    }

    /** 非空、只有 ASCII 数字（前导零允许，同 Go；溢出交给 {@link Long#parseUnsignedLong} 判）。 */
    private static boolean isDigits(String s) {
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

    private static String describe(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> parts = new ArrayList<>(list.size());
            for (Object o : list) {
                String t = text(o);
                parts.add(t != null ? "\"" + t + "\"" : String.valueOf(o));
            }
            return parts.toString();
        }
        return raw == null ? "null" : raw.getClass().getSimpleName();
    }
}
