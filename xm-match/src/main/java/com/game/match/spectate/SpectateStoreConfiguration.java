package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 观战存储的装配（批次 6.5，工作包 W1 负责本文件）。
 *
 * <p><b>现在是先行件放的占位</b>：{@link SpectateStore} bean 的每个读写都抛依赖异常——进程能起、十个号都有处理器、别的包能按接口注入它，
 * 但观战的存储还不可用（163 / 164 的占位处理器不碰它，开局钩子的占位是空实现）。W1 把 {@link #spectateStore()} 换成
 * {@code RedissonSpectateStore}（参数加上 {@code RedissonClient}）；bean 名与类型不变，其余包不用跟着改。
 * 不带条件装配（理由见 {@code MatchConfiguration} 的类注释）：测试里要换存储就把替身标 {@code @Primary}，或直接 new 被测对象。
 */
@Configuration(proxyBeanMethods = false)
public class SpectateStoreConfiguration {

    /** 观战存储。<b>占位</b>：W1 换成 {@code RedissonSpectateStore}。 */
    @Bean
    public SpectateStore spectateStore() {
        return new UnavailableSpectateStore();
    }

    /** 占位实现：同步方法一律抛依赖异常（调用方按「依赖故障」收场），两个尽力方法什么都不做。无状态、线程安全。 */
    private static final class UnavailableSpectateStore implements SpectateStore {

        private static Deadline.DependencyException unavailable(String op) {
            return new Deadline.DependencyException("批次 6.5 施工中：观战存储还没有接上（SpectateStore." + op + "）");
        }

        @Override
        public Entry entry(long playerId, Deadline d) {
            throw unavailable("entry");
        }

        @Override
        public Acquire acquire(long playerId, String markValue, Deadline d) {
            throw unavailable("acquire");
        }

        @Override
        public boolean release(long playerId, String markValue, Deadline d) {
            throw unavailable("release");
        }

        @Override
        public void releaseAsync(long playerId, String markValue) {
        }

        @Override
        public Map<Long, String> marksOf(List<Long> playerIds, Deadline d) {
            throw unavailable("marksOf");
        }

        @Override
        public Snapshot read(long battleId, Deadline d) {
            throw unavailable("read");
        }

        @Override
        public Pick pickRandom(double r, Deadline d) {
            throw unavailable("pickRandom");
        }

        @Override
        public boolean evict(Eviction e, Deadline d) {
            throw unavailable("evict");
        }

        @Override
        public void evictAsync(List<Eviction> batch) {
        }

        @Override
        public boolean publish(BattlePlacement placement, Deadline d) {
            throw unavailable("publish");
        }

        @Override
        public Listed list(int limit, Deadline d) {
            throw unavailable("list");
        }

        @Override
        public Map<Long, Record> readPlacements(List<Long> battleIds, Deadline d) {
            throw unavailable("readPlacements");
        }

        @Override
        public long sweep(Deadline d) {
            throw unavailable("sweep");
        }

        @Override
        public long watchableCount(Deadline d) {
            throw unavailable("watchableCount");
        }
    }
}
