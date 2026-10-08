package com.game.team.store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 内存版 {@link TeamRedis}（测试替身）：{@link TeamScript} 七段 Lua 的 Java 等价实现，外加一只<b>手拨的 Redis 时钟</b>
 * （对应基线测试的 miniredis：时钟固定，用 {@link #advance} / {@link #setNowMs} 推进，「开战锁自然过期」这类真 Redis 拨不动时钟的用例靠它）。
 *
 * <p>逐段照 {@code TeamScript} 的脚本正文翻译：判定段与写入段的先后、缺失值的占位（{@code ""} / {@code "0"}）、epoch 的起种（TIME 毫秒数 + 1）、
 * TTL 的续期点都与脚本一致；回复形状同 Redisson 的 ByteArrayCodec（整数是 {@code Long}、字符串是 {@code byte[]}）。键过期按手拨时钟惰性生效。
 * 与真脚本不漂移靠「同一批服务层用例同时在本替身与真 Redis 上跑」（{@code TeamServiceTest} / {@code TeamMatchRedisIntegrationTest}）。
 *
 * <p>线程安全：每次 {@code eval} / {@code hget} 整段加锁，等价于 Redis 的单线程原子执行。
 */
public final class InMemoryTeamRedis implements TeamRedis {

    private final Map<String, Map<String, byte[]>> hashes = new HashMap<>();
    private final Map<String, byte[]> strings = new HashMap<>();
    private final Map<String, Map<String, Double>> zsets = new HashMap<>();
    private final Map<String, Long> expireAtMs = new HashMap<>();
    private long nowMs;

    /** @param nowMs 时钟的起点（Redis TIME，毫秒） */
    public InMemoryTeamRedis(long nowMs) {
        this.nowMs = nowMs;
    }

    public synchronized long nowMs() {
        return nowMs;
    }

    /** 把时钟往前拨 {@code millis}（到期的键随之消失）。 */
    public synchronized void advance(long millis) {
        nowMs += millis;
    }

    public synchronized void setNowMs(long millis) {
        nowMs = millis;
    }

    /** 直接写一个 hash 字段（测试造数用，绕过脚本，同基线 {@code mr.HSet}）。 */
    public synchronized void hset(String key, String field, String value) {
        hashes.computeIfAbsent(key, k -> new HashMap<>()).put(field, ascii(value));
    }

    /** 直接删键（测试造数用）。 */
    public synchronized void del(String key) {
        delete(key);
    }

    @Override
    public synchronized CompletionStage<Object> eval(TeamScript script, List<Object> keys, List<byte[]> args) {
        try {
            List<String> k = new ArrayList<>(keys.size());
            for (Object key : keys) {
                k.add((String) key);
            }
            List<String> a = new ArrayList<>(args.size());
            for (byte[] arg : args) {
                a.add(text(arg));
            }
            Object reply = switch (script) {
                case COMMIT -> commit(k, a, args);
                case READ -> read(k);
                case READ_MEMBERS -> readMembers(k);
                case INVITE_LIST -> inviteList(k);
                case INVITE_PRUNE -> invitePrune(k, a);
                case TOUCH -> touch(k, a, args);
                case HEAL_ORPHAN -> healOrphan(k, a);
            };
            return CompletableFuture.completedFuture(reply);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public synchronized CompletionStage<byte[]> hget(String key, String field) {
        return CompletableFuture.completedFuture(hgetRaw(key, field));
    }

    // ================================================================ 七段脚本（KEYS / ARGV 从 0 起，脚本里从 1 起）

    private Object commit(List<String> keys, List<String> argv, List<byte[]> raw) {
        String cur = hgetText(keys.get(0), "ver");
        if (argv.get(0).equals("new")) {
            if (cur != null) {
                return list(0L);
            }
        } else if (cur == null || !cur.equals(argv.get(0))) {
            return list(0L);
        }
        String tid = argv.get(4);
        long ttl = Long.parseLong(argv.get(3));
        int nJ = Integer.parseInt(argv.get(5));
        int nK = Integer.parseInt(argv.get(6));
        int nL = Integer.parseInt(argv.get(7));
        int nIA = Integer.parseInt(argv.get(8));
        int nID = Integer.parseInt(argv.get(9));
        int inviteCap = Integer.parseInt(argv.get(10 + nIA));
        for (int i = 1; i <= nJ; i++) {
            String v = hgetText(keys.get(1 + i), "tid");
            if (v != null && !v.equals("0") && !v.equals(tid)) {
                return list(-1L, (long) i);
            }
        }
        for (int i = 1; i <= nK; i++) {
            String v = hgetText(keys.get(1 + nJ + i), "tid");
            if (v != null && !v.equals(tid)) {
                return list(-2L, (long) i);
            }
        }
        int base = 2 + nJ + nK + nL; // 脚本里的 base：KEYS[base + i] 即 keys.get(base + i - 1)
        for (int i = 1; i <= nIA; i++) {
            String key = keys.get(base + i - 1);
            Map<String, Double> z = zset(key);
            if (z == null || !z.containsKey(tid)) {
                long live = z == null ? 0 : z.values().stream().filter(score -> score > nowMs).count();
                if (live >= inviteCap) {
                    return list(-3L, (long) i);
                }
            }
        }
        String newVer = Long.toString((cur == null ? 0 : Long.parseLong(cur)) + 1);
        List<Object> out = new ArrayList<>();
        out.add(1L);
        out.add(ascii(newVer));
        if (argv.get(1).isEmpty()) {
            delete(keys.get(0));
            delete(keys.get(1));
        } else {
            Map<String, byte[]> rec = hashes.computeIfAbsent(keys.get(0), k -> new HashMap<>());
            rec.put("ver", ascii(newVer));
            rec.put("pb", raw.get(1).clone());
            expire(keys.get(0), ttl);
            strings.put(keys.get(1), raw.get(2).clone());
            expire(keys.get(1), ttl);
        }
        for (int i = 1; i <= nJ + nK; i++) {
            String e = setIndex(keys.get(1 + i), tid, ttl);
            out.add(ascii(tid));
            out.add(ascii(e));
        }
        for (int i = 1; i <= nL; i++) {
            String key = keys.get(1 + nJ + nK + i);
            String v = hgetText(key, "tid");
            if (v == null || v.equals(tid)) {
                String e = setIndex(key, "0", ttl);
                out.add(ascii("0"));
                out.add(ascii(e));
            } else {
                String epoch = hgetText(key, "epoch");
                out.add(ascii(v));
                out.add(ascii(epoch == null ? "0" : epoch));
            }
        }
        for (int i = 1; i <= nID; i++) {
            Map<String, Double> z = zset(keys.get(base + nIA + i - 1));
            if (z != null) {
                z.remove(tid);
            }
        }
        for (int i = 1; i <= nIA; i++) {
            String key = keys.get(base + i - 1);
            Map<String, Double> z = zsets.computeIfAbsent(key, k -> new HashMap<>());
            z.values().removeIf(score -> score <= nowMs);
            z.put(tid, Double.parseDouble(argv.get(9 + i)));
            expire(key, 3600);
        }
        return out;
    }

    /** 脚本里的 setIdx：tid 变了才动 epoch（存在 +1，缺失或 0 按 TIME 毫秒数 +1 起种）；无论变没变都续期。 */
    private String setIndex(String key, String value, long ttl) {
        String old = hgetText(key, "tid");
        String epochText = hgetText(key, "epoch");
        long epoch = epochText == null ? 0 : Long.parseLong(epochText);
        if (!value.equals(old)) {
            epoch = epoch == 0 ? nowMs + 1 : epoch + 1;
            Map<String, byte[]> index = hashes.computeIfAbsent(key, k -> new HashMap<>());
            index.put("tid", ascii(value));
            index.put("epoch", ascii(Long.toString(epoch)));
        }
        expire(key, ttl);
        return Long.toString(epoch);
    }

    private Object read(List<String> keys) {
        byte[] now = ascii(Long.toString(nowMs));
        byte[] tidNow = hgetRaw(keys.get(0), "tid");
        byte[] epoch = hgetRaw(keys.get(0), "epoch");
        byte[] ver = hgetRaw(keys.get(1), "ver");
        byte[] pb = hgetRaw(keys.get(1), "pb");
        return list(orEmpty(tidNow), epoch == null ? now : epoch, orEmpty(ver), orEmpty(pb), ttlSeconds(keys.get(1)), now);
    }

    private Object readMembers(List<String> keys) {
        List<Object> out = new ArrayList<>();
        out.add(orEmpty(hgetRaw(keys.get(0), "ver")));
        out.add(orEmpty(hgetRaw(keys.get(0), "pb")));
        out.add(ascii(Long.toString(nowMs)));
        for (int i = 1; i < keys.size(); i++) {
            out.add(orEmpty(hgetRaw(keys.get(i), "tid")));
            byte[] epoch = hgetRaw(keys.get(i), "epoch");
            out.add(epoch == null ? ascii("0") : epoch);
        }
        return out;
    }

    private Object inviteList(List<String> keys) {
        List<Object> out = new ArrayList<>();
        out.add(ascii(Long.toString(nowMs)));
        Map<String, Double> z = zset(keys.get(0));
        if (z != null) {
            z.values().removeIf(score -> score <= nowMs);
            List<Map.Entry<String, Double>> entries = new ArrayList<>(z.entrySet());
            // ZRANGE 0 -1：按 score 升序，同分按成员字典序
            entries.sort((x, y) -> {
                int byScore = Double.compare(x.getValue(), y.getValue());
                return byScore != 0 ? byScore : x.getKey().compareTo(y.getKey());
            });
            for (Map.Entry<String, Double> e : entries) {
                out.add(ascii(e.getKey()));
                out.add(ascii(score(e.getValue())));
            }
        }
        return out;
    }

    private Object invitePrune(List<String> keys, List<String> argv) {
        Map<String, Double> z = zset(keys.get(0));
        Double s = z == null ? null : z.get(argv.get(0));
        if (s != null && score(s).equals(argv.get(1))) {
            z.remove(argv.get(0));
            return 1L;
        }
        return 0L;
    }

    private Object touch(List<String> keys, List<String> argv, List<byte[]> raw) {
        String cur = hgetText(keys.get(0), "ver");
        if (cur == null || !cur.equals(argv.get(0))) {
            return 0L;
        }
        long ttl = Long.parseLong(argv.get(1));
        expire(keys.get(0), ttl);
        if (!alive(keys.get(1)) || !strings.containsKey(keys.get(1))) {
            strings.put(keys.get(1), raw.get(2).clone());
        }
        expire(keys.get(1), ttl);
        for (int i = 2; i < keys.size(); i++) {
            if (argv.get(3).equals(hgetText(keys.get(i), "tid"))) {
                expire(keys.get(i), ttl);
            }
        }
        return 1L;
    }

    private Object healOrphan(List<String> keys, List<String> argv) {
        if (alive(keys.get(1)) && hashes.containsKey(keys.get(1))) {
            return 0L;
        }
        if (!argv.get(0).equals(hgetText(keys.get(0), "tid"))) {
            return 0L;
        }
        String epochText = hgetText(keys.get(0), "epoch");
        long epoch = epochText == null ? 0 : Long.parseLong(epochText);
        epoch = epoch == 0 ? nowMs + 1 : epoch + 1;
        Map<String, byte[]> index = hashes.get(keys.get(0));
        index.put("tid", ascii("0"));
        index.put("epoch", ascii(Long.toString(epoch)));
        expire(keys.get(0), Long.parseLong(argv.get(1)));
        return 1L;
    }

    // ================================================================ 键空间

    /** 键还在不在（到期即删，惰性）。 */
    private boolean alive(String key) {
        Long at = expireAtMs.get(key);
        if (at != null && at <= nowMs) {
            delete(key);
        }
        return hashes.containsKey(key) || strings.containsKey(key) || zsets.containsKey(key);
    }

    private void delete(String key) {
        hashes.remove(key);
        strings.remove(key);
        zsets.remove(key);
        expireAtMs.remove(key);
    }

    private void expire(String key, long seconds) {
        if (alive(key)) {
            expireAtMs.put(key, nowMs + seconds * 1000);
        }
    }

    /** Redis 的 TTL：-2 不存在 / -1 没有 TTL / 否则剩余秒数（四舍五入到秒，同 Redis）。 */
    private long ttlSeconds(String key) {
        if (!alive(key)) {
            return -2L;
        }
        Long at = expireAtMs.get(key);
        return at == null ? -1L : (at - nowMs + 500) / 1000;
    }

    private byte[] hgetRaw(String key, String field) {
        if (!alive(key)) {
            return null;
        }
        Map<String, byte[]> h = hashes.get(key);
        return h == null ? null : h.get(field);
    }

    private String hgetText(String key, String field) {
        byte[] raw = hgetRaw(key, field);
        return raw == null ? null : text(raw);
    }

    private Map<String, Double> zset(String key) {
        return alive(key) ? zsets.get(key) : null;
    }

    /** Redis 回报 score 的写法：整数值不带小数点（我们写进去的只会是十进制毫秒数）。 */
    private static String score(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e17 ? Long.toString((long) value) : Double.toString(value);
    }

    private static List<Object> list(Object... items) {
        return new ArrayList<>(List.of(items));
    }

    private static byte[] orEmpty(byte[] raw) {
        return raw == null ? new byte[0] : raw;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String text(byte[] raw) {
        return new String(raw, StandardCharsets.ISO_8859_1);
    }
}
