package com.game.login.handler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 区服暂停创角策略读取接口（对齐权威主仓 {@code zonepolicy.Reader}）。
 *
 * <p>读不到策略或存储抖动时 fail-open 返回 {@code false}：这是运营节奏软开关，拦错代价是整区无法新建角色。
 */
@FunctionalInterface
public interface ZoneCreateRolePolicy {

    /** 恒放行（测试与未接表时的默认策略）。 */
    ZoneCreateRolePolicy OPEN = zoneId -> false;

    /** @return true 表示该区已暂停创建新角色 */
    boolean isCreateRoleClosed(int zoneId);

    /**
     * 基于区服目录表 {@code zone_config.create_role_closed} 的带 2 秒缓存实现（fail-open）。
     * 与权威主仓 2s 轮询生效口径一致，避免每次建角打一次表读。
     */
    final class SqlCached implements ZoneCreateRolePolicy {

        private static final Logger log = LoggerFactory.getLogger(SqlCached.class);
        static final Duration DEFAULT_TTL = Duration.ofSeconds(2);

        private record Cached(boolean closed, long expiresAtNanos) {
        }

        private final DataSource dataSource;
        private final long ttlNanos;
        private final LongSupplier nanoClock;
        private final ConcurrentHashMap<Integer, Cached> cache = new ConcurrentHashMap<>();

        public SqlCached(DataSource dataSource) {
            this(dataSource, DEFAULT_TTL, System::nanoTime);
        }

        SqlCached(DataSource dataSource, Duration ttl, LongSupplier nanoClock) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
            this.ttlNanos = ttl.toNanos();
            this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        }

        @Override
        public boolean isCreateRoleClosed(int zoneId) {
            if (zoneId == 0) {
                return false;
            }
            long now = nanoClock.getAsLong();
            Cached hit = cache.get(zoneId);
            if (hit != null && now - hit.expiresAtNanos() < 0) {
                return hit.closed();
            }
            boolean closed = queryFailOpen(zoneId, hit != null && hit.closed());
            cache.put(zoneId, new Cached(closed, now + ttlNanos));
            return closed;
        }

        private boolean queryFailOpen(int zoneId, boolean previous) {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT create_role_closed FROM zone_config WHERE zone_id = ?")) {
                ps.setLong(1, Integer.toUnsignedLong(zoneId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    return rs.getBoolean(1);
                }
            } catch (SQLException e) {
                log.warn("读取 zone_config.create_role_closed 失败，按策略降级处理 zone={} previous={} err={}",
                        Integer.toUnsignedLong(zoneId), previous, e.getMessage());
                return previous;
            }
        }
    }
}
