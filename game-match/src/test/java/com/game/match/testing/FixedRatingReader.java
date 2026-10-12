package com.game.match.testing;

import com.game.match.rating.RatingReader;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link RatingReader} 的测试替身：预置每人的评分（centi），没预置的是缺省 150000。真实现「读失败回落缺省值、永不抛」，所以这里没有故障注入——
 * 要模拟读失败，就别给那个人预置评分。
 *
 * <pre>
 * FixedRatingReader ratings = new FixedRatingReader().set(1001, 162_500).set(1002, 140_000);
 * assertThat(ratings.loads).containsExactly(List.of(1001L));     // 每次调用读了哪些人
 * </pre>
 */
public final class FixedRatingReader implements RatingReader {

    /** 每次调用读的玩家（单读记成一个元素的列表），按调用顺序。 */
    public final List<List<Long>> loads = new CopyOnWriteArrayList<>();
    private final Map<Long, Long> centi = new ConcurrentHashMap<>();

    public FixedRatingReader set(long playerId, long ratingCenti) {
        centi.put(playerId, ratingCenti);
        return this;
    }

    public FixedRatingReader clear(long playerId) {
        centi.remove(playerId);
        return this;
    }

    @Override
    public long loadCentiOrDefault(long playerId) {
        loads.add(List.of(playerId));
        return centi.getOrDefault(playerId, DEFAULT_CENTI);
    }

    @Override
    public Map<Long, Long> loadAllCentiOrDefault(Collection<Long> playerIds) {
        loads.add(List.copyOf(playerIds));
        Map<Long, Long> out = new LinkedHashMap<>();
        for (Long playerId : playerIds) {
            out.put(playerId, centi.getOrDefault(playerId, DEFAULT_CENTI));
        }
        return out;
    }
}
