package com.game.login.compliance;

import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * 实名认证与防沉迷 NPPA 组件的 Redis 键生成器：全部经注入的 {@link RedisKeys#prefix()} 派生，遵守多部署命名空间隔离。
 */
public final class NppaRedisKeys {

    public static final int LEDGER_SHARD_COUNT = 16;

    private final String prefix;

    public NppaRedisKeys(RedisKeys redisKeys) {
        this.prefix = Objects.requireNonNull(redisKeys, "redisKeys").prefix();
    }

    public String realNamePending(String account) {
        return prefix + "login:realname:pending:" + account;
    }

    public String realNameAttempts(String account, String yyyyMmDd) {
        return prefix + "login:realname:attempts:" + account + ":" + yyyyMmDd;
    }

    public String quota(String apiName) {
        return prefix + "login:nppa:quota:" + apiName;
    }

    public int shardOf(String connId) {
        CRC32 crc = new CRC32();
        crc.update(connId.getBytes(StandardCharsets.UTF_8));
        return (int) (Long.remainderUnsigned(crc.getValue(), LEDGER_SHARD_COUNT));
    }

    public String ledgerSessions(int shard) {
        return prefix + "login:nppa:sess:{nppa:" + shard + "}";
    }

    public String ledgerLeases(int shard) {
        return prefix + "login:nppa:live:{nppa:" + shard + "}";
    }

    public String ledgerOutbox(int shard) {
        return prefix + "login:nppa:outbox:{nppa:" + shard + "}";
    }

    public String ledgerSweep(int shard) {
        return prefix + "login:nppa:sweep:{nppa:" + shard + "}";
    }
}
