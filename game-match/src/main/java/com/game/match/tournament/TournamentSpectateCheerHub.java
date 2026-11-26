package com.game.match.tournament;

import com.game.proto.match.TournamentSpectateCheerItem;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 锦标赛 L0/L1/L2 通用观战预设喝彩聚合器（与 Go {@code tournament.SpectateCheerHub} 逐条同构）。
 *
 * <p>严格只接受白名单短语 {@code 1..6} 与阵营 {@code 0/1}，每名观众 5 秒冷却，单场保留最近 32 条递增 {@code seq} 喝彩，
 * 零自由文本输入、零高基数指标标签。
 * shortcut: 进程内聚合, 多 match 副本场景下升级为 Redis HINCRBY + Stream 聚合。
 */
public final class TournamentSpectateCheerHub {

    public static final long COOLDOWN_MS = 5_000L;
    private static final int RING_LIMIT = 32;
    private static final int INITIAL_RETURN = 12;
    private static final long ROOM_IDLE_TTL_MS = 2L * 3600 * 1000;
    private static final long EVICT_SWEEP_INTERVAL_MS = 60_000L;
    private static final int COOLDOWN_PRUNE_THRESHOLD = 1024;
    private static final int MAX_SENDER_NAME_CODEPOINTS = 16;

    public record Snapshot(long battleId, long countA, long countB, long cooldownUntilMs,
                           List<TournamentSpectateCheerItem> recentCheers, long serverTimeMs) {
    }

    public record SendOutcome(Snapshot snapshot, boolean cooldownHit, boolean invalidArg) {
    }

    private static final class Room {
        long countA;
        long countB;
        long nextSeq;
        long lastActiveMs;
        final ArrayDeque<TournamentSpectateCheerItem> recent = new ArrayDeque<>(RING_LIMIT + 1);
        final Map<Long, Long> cooldowns = new HashMap<>();
    }

    private final Map<Long, Room> rooms = new HashMap<>();
    private long lastEvictMs;

    public synchronized SendOutcome send(long battleId, long playerId, String senderName, int phraseId, int side, long nowMs) {
        if (battleId == 0L || playerId == 0L
                || !TournamentRules.isValidSpectateCheerPhrase(phraseId)
                || !TournamentRules.isValidSpectateCheerSide(side)) {
            return new SendOutcome(new Snapshot(battleId, 0L, 0L, 0L, List.of(), nowMs), false, true);
        }
        evictIdleLocked(nowMs);
        Room room = rooms.computeIfAbsent(battleId, k -> {
            Room r = new Room();
            r.lastActiveMs = nowMs;
            return r;
        });
        room.lastActiveMs = nowMs;
        long until = room.cooldowns.getOrDefault(playerId, 0L);
        if (until > nowMs) {
            return new SendOutcome(new Snapshot(
                    battleId, room.countA, room.countB, until,
                    collectAfter(room.recent, 0L, INITIAL_RETURN), nowMs), true, false);
        }
        if (side == 0) {
            room.countA++;
        } else {
            room.countB++;
        }
        room.nextSeq++;
        TournamentSpectateCheerItem item = TournamentSpectateCheerItem.newBuilder()
                .setSeq(room.nextSeq)
                .setPhraseId(phraseId)
                .setSide(side)
                .setSenderName(sanitizeSenderName(senderName))
                .setCreatedMs(nowMs)
                .build();
        room.recent.addLast(item);
        while (room.recent.size() > RING_LIMIT) {
            room.recent.removeFirst();
        }
        long nextUntil = nowMs + COOLDOWN_MS;
        room.cooldowns.put(playerId, nextUntil);
        if (room.cooldowns.size() > COOLDOWN_PRUNE_THRESHOLD) {
            room.cooldowns.entrySet().removeIf(e -> e.getValue() <= nowMs);
        }
        return new SendOutcome(new Snapshot(
                battleId, room.countA, room.countB, nextUntil, List.of(item), nowMs), false, false);
    }

    public synchronized Snapshot snapshot(long battleId, long playerId, long afterSeq, long nowMs) {
        if (battleId == 0L) {
            return new Snapshot(0L, 0L, 0L, 0L, List.of(), nowMs);
        }
        Room room = rooms.get(battleId);
        if (room == null) {
            return new Snapshot(battleId, 0L, 0L, 0L, List.of(), nowMs);
        }
        long until = room.cooldowns.getOrDefault(playerId, 0L);
        long effectiveCooldown = until > nowMs ? until : 0L;
        int limit = afterSeq == 0L ? INITIAL_RETURN : RING_LIMIT;
        return new Snapshot(battleId, room.countA, room.countB, effectiveCooldown,
                collectAfter(room.recent, afterSeq, limit), nowMs);
    }

    private void evictIdleLocked(long nowMs) {
        if (rooms.isEmpty()) {
            return;
        }
        if (rooms.size() < 64 && lastEvictMs != 0L && nowMs > lastEvictMs && nowMs - lastEvictMs < EVICT_SWEEP_INTERVAL_MS) {
            return;
        }
        lastEvictMs = nowMs;
        Iterator<Map.Entry<Long, Room>> it = rooms.entrySet().iterator();
        while (it.hasNext()) {
            Room r = it.next().getValue();
            if (nowMs > r.lastActiveMs && nowMs - r.lastActiveMs >= ROOM_IDLE_TTL_MS) {
                it.remove();
            }
        }
    }

    private static List<TournamentSpectateCheerItem> collectAfter(ArrayDeque<TournamentSpectateCheerItem> deque,
                                                                  long afterSeq, int limit) {
        if (deque.isEmpty()) {
            return List.of();
        }
        List<TournamentSpectateCheerItem> out = new ArrayList<>();
        for (TournamentSpectateCheerItem item : deque) {
            if (Long.compareUnsigned(item.getSeq(), afterSeq) > 0) {
                out.add(item);
            }
        }
        if (limit > 0 && out.size() > limit) {
            return List.copyOf(out.subList(out.size() - limit, out.size()));
        }
        return List.copyOf(out);
    }

    private static String sanitizeSenderName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "观战道友";
        }
        String trimmed = raw.strip();
        int cp = trimmed.codePointCount(0, trimmed.length());
        if (cp <= MAX_SENDER_NAME_CODEPOINTS) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_SENDER_NAME_CODEPOINTS));
    }
}
