package com.game.scenemanager.world;

import com.game.discovery.RedisKeys;
import com.game.proto.MysteryMerchantShelfEntry;
import com.game.proto.MysteryMerchantState;
import com.game.proto.SceneChannelDirectory;
import com.game.proto.SceneChannelInfo;
import com.game.proto.SceneChannelState;
import com.game.proto.Vector3;
import com.game.table.ConfigTables;
import com.game.table.ItemTable;
import com.game.table.MysteryMerchantGoodsTable;
import com.game.table.MysteryMerchantRuleTable;
import com.game.table.MysteryMerchantSpotTable;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 神秘商人分 zone 领导者调度器（对齐 Go {@code scene_manager/internal/logic/mystery_merchant.go}）：
 * 按规则表周期掷出当前轮次的落点分线、站位与折扣货架，并通过版本只进不退的 Lua 脚本写入 Redis HASH
 * （{@code mystery_merchant:{ctl:<zone>}} 的 {@code v} / {@code state}）；当商人所在分线进入
 * {@code CLOSING} 或连续 3 拍缺席分线目录时，在同轮内保留原货架并 {@code revision + 1} 换线。
 */
public final class MysteryMerchantScheduler {

    private static final Logger log = LoggerFactory.getLogger(MysteryMerchantScheduler.class);

    public static final int VERSION_ROUND_FACTOR = 1000;
    public static final int MAX_REVISION = 999;
    public static final int MISSING_TICKS_BEFORE_RELOCATE = 3;
    private static final long MIN_INTERVAL_SEC = 60L;
    private static final long MAX_INTERVAL_SEC = 24L * 3600L;
    private static final long MIN_STAY_SEC = 30L;
    private static final int MAX_SHELF_SIZE = 32;
    private static final long MAX_GOODS_PRICE = 1_000_000_000L;
    private static final int MAX_LIMIT_COUNT = 99;
    private static final int[] DISCOUNT_PERCENTS = {60, 50, 40};
    private static final byte[] STATE_FIELD = "state".getBytes(StandardCharsets.UTF_8);

    /** 版本只进不退的原子写入脚本（与 Go {@code luaWriteMysteryMerchantState} 同语义）。 */
    static final String WRITE_LUA = """
            local current = tonumber(redis.call('HGET', KEYS[1], 'v') or '0') or 0
            local incoming = tonumber(ARGV[1])
            if not incoming then return 0 end
            if ARGV[4] ~= '1' and current >= incoming then return 0 end
            redis.call('HSET', KEYS[1], 'v', ARGV[1], 'state', ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """;

    /** 持久化读/写抽象（生产走 Redisson Lua，单测注入内存实现）。 */
    public interface Store {
        byte[] readState(int zoneId);
        boolean writeIfNewer(int zoneId, long version, byte[] stateBytes, long ttlSeconds, boolean force);
    }

    public static Store redisStore(RedissonClient redis, RedisKeys keys) {
        Objects.requireNonNull(redis, "redis");
        Objects.requireNonNull(keys, "keys");
        return new Store() {
            @Override
            public byte[] readState(int zoneId) {
                Object raw = redis.getMap(keys.mysteryMerchant(zoneId), ByteArrayCodec.INSTANCE).get(STATE_FIELD);
                return raw instanceof byte[] bytes ? bytes : null;
            }

            @Override
            public boolean writeIfNewer(int zoneId, long version, byte[] stateBytes, long ttlSeconds, boolean force) {
                Object res = redis.getScript(ByteArrayCodec.INSTANCE).eval(
                        RScript.Mode.READ_WRITE, WRITE_LUA, RScript.ReturnType.INTEGER,
                        List.of(keys.mysteryMerchant(zoneId)),
                        Long.toUnsignedString(version).getBytes(StandardCharsets.UTF_8),
                        stateBytes,
                        Long.toString(ttlSeconds).getBytes(StandardCharsets.UTF_8),
                        (force ? "1" : "0").getBytes(StandardCharsets.UTF_8));
                return res instanceof Number n && n.longValue() == 1L;
            }
        };
    }

    public record Rule(long intervalSeconds, long staySeconds, int shelfSize, long[] discountWeights) {
    }

    public record Goods(int id, long weight) {
    }

    public record Spot(int id, double x, double y, double z) {
    }

    public record Tables(Rule rule, List<Goods> goods, Map<Integer, List<Spot>> spotsByConfig) {
    }

    private record ChannelCandidate(int configId, long sceneId, int channelNo) {
    }

    private final Tables tables;
    private final Store store;
    private final LongUnaryOperator random;
    private final Map<Integer, Integer> missingTicksByZone = new HashMap<>();

    public MysteryMerchantScheduler(Tables tables, Store store) {
        this(tables, store, bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    public MysteryMerchantScheduler(Tables tables, Store store, LongUnaryOperator random) {
        this.tables = tables;
        this.store = Objects.requireNonNull(store, "store");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** 从配置表构建并校验神秘商人调度表；配表不合法时返回 null（不阻塞启动，本节点不调度）。 */
    public static Tables loadTables(ConfigTables config, Set<Integer> worldConfigIds) {
        List<MysteryMerchantRuleTable> rules = config.mysteryMerchantRule().all();
        if (rules.isEmpty()) {
            log.warn("mystery_merchant_disabled reason=empty_rule_table");
            return null;
        }
        MysteryMerchantRuleTable r = rules.getFirst();
        long intervalSec = Integer.toUnsignedLong(r.getIntervalSeconds());
        long staySec = Integer.toUnsignedLong(r.getStaySeconds());
        int shelfSize = r.getShelfSize();
        long[] weights = {
                Integer.toUnsignedLong(r.getWeightDiscount60()),
                Integer.toUnsignedLong(r.getWeightDiscount50()),
                Integer.toUnsignedLong(r.getWeightDiscount40())
        };
        if (intervalSec < MIN_INTERVAL_SEC || intervalSec > MAX_INTERVAL_SEC
                || staySec < MIN_STAY_SEC || staySec > intervalSec
                || shelfSize < 1 || shelfSize > MAX_SHELF_SIZE
                || weights[0] + weights[1] + weights[2] == 0) {
            log.warn("mystery_merchant_disabled reason=invalid_rule interval={} stay={} shelf={}", intervalSec, staySec, shelfSize);
            return null;
        }
        Map<Integer, ItemTable> items = new HashMap<>();
        for (ItemTable item : config.item().all()) {
            items.putIfAbsent(item.getId(), item);
        }
        List<Goods> goods = new ArrayList<>();
        for (MysteryMerchantGoodsTable g : config.mysteryMerchantGoods().all()) {
            if (g.getWeight() == 0) {
                continue;
            }
            ItemTable item = items.get(g.getItemId());
            if (item == null || g.getItemCount() == 0
                    || Integer.compareUnsigned(g.getItemCount(), item.getMaxStackSize()) > 0
                    || g.getPrice() <= 0 || Long.compareUnsigned(g.getPrice(), MAX_GOODS_PRICE) > 0
                    || g.getLimitCount() == 0 || Integer.compareUnsigned(g.getLimitCount(), MAX_LIMIT_COUNT) > 0) {
                log.warn("mystery_merchant_disabled reason=invalid_goods id={}", g.getId());
                return null;
            }
            goods.add(new Goods(g.getId(), Integer.toUnsignedLong(g.getWeight())));
        }
        if (goods.isEmpty()) {
            log.warn("mystery_merchant_disabled reason=no_eligible_goods");
            return null;
        }
        Map<Integer, List<Spot>> spots = new LinkedHashMap<>();
        for (MysteryMerchantSpotTable s : config.mysteryMerchantSpot().all()) {
            if (!worldConfigIds.contains(s.getSceneConfigId())) {
                log.warn("mystery_merchant_disabled reason=spot_not_world spot_id={} scene_config_id={}",
                        s.getId(), s.getSceneConfigId());
                return null;
            }
            spots.computeIfAbsent(s.getSceneConfigId(), ignored -> new ArrayList<>())
                    .add(new Spot(s.getId(), s.getX(), s.getY(), s.getZ()));
        }
        if (spots.isEmpty()) {
            log.warn("mystery_merchant_disabled reason=empty_spots");
            return null;
        }
        return new Tables(new Rule(intervalSec, staySec, shelfSize, weights),
                List.copyOf(goods), Collections.unmodifiableMap(spots));
    }

    /** 领导者在每次目录发布成功后推进一拍神秘商人调度。 */
    public void tick(int zoneId, Map<Integer, SceneChannelDirectory> directories, long nowMs) {
        if (tables == null || directories == null || directories.isEmpty()) {
            return;
        }
        Rule rule = tables.rule();
        long nowSec = Math.max(0L, nowMs / 1000L);
        long roundId = (nowSec / rule.intervalSeconds()) * rule.intervalSeconds();
        long appearAtMs = roundId * 1000L;
        long expireAtMs = (roundId + rule.staySeconds()) * 1000L;
        if (nowMs >= expireAtMs) {
            missingTicksByZone.remove(zoneId);
            return;
        }
        MysteryMerchantState current = null;
        boolean forceOverwrite = false;
        byte[] raw = store.readState(zoneId);
        if (raw != null && raw.length > 0) {
            try {
                MysteryMerchantState parsed = MysteryMerchantState.parseFrom(raw);
                if (parsed.getZoneId() != zoneId || parsed.getRoundId() == 0) {
                    forceOverwrite = true;
                } else {
                    current = parsed;
                }
            } catch (InvalidProtocolBufferException e) {
                forceOverwrite = true;
            }
        }
        long ttlSec = Math.max(MAX_INTERVAL_SEC, rule.intervalSeconds() * 2L);
        if (current != null && !forceOverwrite) {
            if (Long.compareUnsigned(current.getRoundId(), roundId) > 0) {
                return;
            }
            if (current.getRoundId() == roundId) {
                SceneChannelState chState = findChannelState(directories, current.getSceneConfigId(), current.getSceneId());
                if (chState == SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH
                        || chState == SceneChannelState.SCENE_CHANNEL_STATE_BUSY
                        || chState == SceneChannelState.SCENE_CHANNEL_STATE_FULL) {
                    missingTicksByZone.remove(zoneId);
                    return;
                }
                boolean immediate = chState == SceneChannelState.SCENE_CHANNEL_STATE_CLOSING;
                if (!immediate) {
                    int missing = missingTicksByZone.merge(zoneId, 1, Integer::sum);
                    if (missing < MISSING_TICKS_BEFORE_RELOCATE) {
                        return;
                    }
                }
                if (current.getRevision() >= MAX_REVISION) {
                    return;
                }
                ChannelCandidate picked = pickChannel(directories, current.getSceneId());
                if (picked == null) {
                    return;
                }
                Spot spot = pickSpot(picked.configId());
                int nextRev = current.getRevision() + 1;
                MysteryMerchantState relocated = current.toBuilder()
                        .setRevision(nextRev)
                        .setSceneConfigId(picked.configId())
                        .setSceneId(picked.sceneId())
                        .setChannelNo(picked.channelNo())
                        .setSpotId(spot.id())
                        .setPosition(Vector3.newBuilder().setX(spot.x()).setY(spot.y()).setZ(spot.z()))
                        .build();
                long version = versionOf(roundId, nextRev);
                if (store.writeIfNewer(zoneId, version, relocated.toByteArray(), ttlSec, false)) {
                    missingTicksByZone.remove(zoneId);
                    log.info("mystery_merchant_relocated zone={} round={} rev={} scene_id={} channel={}",
                            zoneId, Long.toUnsignedString(roundId), nextRev,
                            Long.toUnsignedString(picked.sceneId()), picked.channelNo());
                }
                return;
            }
        }
        ChannelCandidate picked = pickChannel(directories, 0L);
        if (picked == null) {
            return;
        }
        Spot spot = pickSpot(picked.configId());
        List<MysteryMerchantShelfEntry> shelf = rollShelf(rule);
        MysteryMerchantState spawned = MysteryMerchantState.newBuilder()
                .setZoneId(zoneId)
                .setRoundId(roundId)
                .setRevision(1)
                .setSceneConfigId(picked.configId())
                .setSceneId(picked.sceneId())
                .setChannelNo(picked.channelNo())
                .setSpotId(spot.id())
                .setPosition(Vector3.newBuilder().setX(spot.x()).setY(spot.y()).setZ(spot.z()))
                .setAppearAtMs(appearAtMs)
                .setExpireAtMs(expireAtMs)
                .setIntervalSeconds((int) rule.intervalSeconds())
                .addAllShelf(shelf)
                .build();
        long version = versionOf(roundId, 1);
        if (store.writeIfNewer(zoneId, version, spawned.toByteArray(), ttlSec, forceOverwrite)) {
            missingTicksByZone.remove(zoneId);
            log.info("mystery_merchant_spawned zone={} round={} scene_id={} channel={} shelf_size={}",
                    zoneId, Long.toUnsignedString(roundId), Long.toUnsignedString(picked.sceneId()),
                    picked.channelNo(), shelf.size());
        }
    }

    public static long versionOf(long roundId, int revision) {
        return roundId * VERSION_ROUND_FACTOR + Integer.toUnsignedLong(revision);
    }

    private static SceneChannelState findChannelState(Map<Integer, SceneChannelDirectory> directories,
                                                      int configId, long sceneId) {
        SceneChannelDirectory dir = directories.get(configId);
        if (dir == null) {
            return SceneChannelState.SCENE_CHANNEL_STATE_UNKNOWN;
        }
        for (SceneChannelInfo ch : dir.getChannelsList()) {
            if (ch.getSceneId() == sceneId) {
                return ch.getState();
            }
        }
        return SceneChannelState.SCENE_CHANNEL_STATE_UNKNOWN;
    }

    private ChannelCandidate pickChannel(Map<Integer, SceneChannelDirectory> directories, long excludeSceneId) {
        List<ChannelCandidate> open = new ArrayList<>();
        List<ChannelCandidate> full = new ArrayList<>();
        for (int configId : tables.spotsByConfig().keySet()) {
            SceneChannelDirectory dir = directories.get(configId);
            if (dir == null) {
                continue;
            }
            for (SceneChannelInfo ch : dir.getChannelsList()) {
                if (ch.getSceneId() == 0 || ch.getSceneId() == excludeSceneId) {
                    continue;
                }
                ChannelCandidate c = new ChannelCandidate(configId, ch.getSceneId(), ch.getChannelNo());
                if (ch.getState() == SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH
                        || ch.getState() == SceneChannelState.SCENE_CHANNEL_STATE_BUSY) {
                    open.add(c);
                } else if (ch.getState() == SceneChannelState.SCENE_CHANNEL_STATE_FULL) {
                    full.add(c);
                }
            }
        }
        List<ChannelCandidate> pool = !open.isEmpty() ? open : full;
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get((int) random.applyAsLong(pool.size()));
    }

    private Spot pickSpot(int configId) {
        List<Spot> spots = tables.spotsByConfig().get(configId);
        return spots.get((int) random.applyAsLong(spots.size()));
    }

    private List<MysteryMerchantShelfEntry> rollShelf(Rule rule) {
        List<Goods> pool = new ArrayList<>(tables.goods());
        int count = Math.min(rule.shelfSize(), pool.size());
        List<MysteryMerchantShelfEntry> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long totalWeight = 0L;
            for (Goods g : pool) {
                totalWeight += g.weight();
            }
            long roll = random.applyAsLong(totalWeight);
            int chosen = pool.size() - 1;
            long acc = 0L;
            for (int j = 0; j < pool.size(); j++) {
                acc += pool.get(j).weight();
                if (roll < acc) {
                    chosen = j;
                    break;
                }
            }
            Goods picked = pool.remove(chosen);
            int discount = rollDiscount(rule.discountWeights());
            out.add(MysteryMerchantShelfEntry.newBuilder()
                    .setGoodsId(picked.id())
                    .setDiscountPercent(discount)
                    .build());
        }
        return out;
    }

    private int rollDiscount(long[] weights) {
        long total = weights[0] + weights[1] + weights[2];
        long roll = random.applyAsLong(total);
        long acc = 0L;
        for (int i = 0; i < DISCOUNT_PERCENTS.length; i++) {
            acc += weights[i];
            if (roll < acc) {
                return DISCOUNT_PERCENTS[i];
            }
        }
        return DISCOUNT_PERCENTS[DISCOUNT_PERCENTS.length - 1];
    }
}
