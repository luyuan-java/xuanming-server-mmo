package com.game.data.consistency;

import com.game.discovery.RedisKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** 仅TYPE/SCAN/SSCAN；Lua使用READ_ONLY，返回条数和每项长度也有硬边界。 */
public final class RedissonConsistencyReader implements ConsistencyRedisReader {
    static final String SCAN = "local r=redis.call('SCAN',ARGV[1],'MATCH',ARGV[2],'COUNT',ARGV[3]); "
            + "if #r[2]>tonumber(ARGV[3]) then return redis.error_reply('scan_limit') end; "
            + "for _,v in ipairs(r[2]) do if #v>192 then return redis.error_reply('value_limit') end end; return r";
    static final String SSCAN = "local r=redis.call('SSCAN',KEYS[1],ARGV[1],'COUNT',ARGV[2]); "
            + "if #r[2]>tonumber(ARGV[2]) then return redis.error_reply('scan_limit') end; "
            + "for _,v in ipairs(r[2]) do if #v>32 then return redis.error_reply('value_limit') end end; return r";
    static final String TYPE = "return redis.call('TYPE',KEYS[1]).ok";
    private final RedissonClient client;
    private final RedisKeys keys;
    public RedissonConsistencyReader(RedissonClient client, RedisKeys keys) { this.client = client; this.keys = keys; }
    @Override public boolean completeTopology() {
        // 多master、代理/哨兵尚未证明拓扑覆盖；不使用单节点SCAN伪称全服覆盖。
        return client.getConfig().isSingleConfig();
    }
    @Override public Page scanRankKeys(String cursor, int count, ConsistencyBudget budget) {
        return page(call(SCAN,RScript.ReturnType.MULTI,List.of(),budget,cursor,rankPrefix(keys)+"*",count));
    }
    @Override public Page scanRankZones(String cursor, int count, ConsistencyBudget budget) {
        return page(call(SSCAN,RScript.ReturnType.MULTI,List.of(keys.guildRankZones()),budget,cursor,count));
    }
    @Override public String type(String key, ConsistencyBudget budget) {
        Object result = call(TYPE,RScript.ReturnType.VALUE,List.of(key),budget);
        if (!(result instanceof String text)) throw new ConsistencyBudget.Failure("redis_decode_failed");
        return text;
    }
    private Object call(String script, RScript.ReturnType type, List<Object> names,
            ConsistencyBudget budget, Object... args) {
        int remaining = budget.requestMillis();
        var future = client.getScript(StringCodec.INSTANCE).evalAsync(RScript.Mode.READ_ONLY,script,type,names,args);
        try {
            Object value = future.toCompletableFuture().get(Math.min(remaining,budget.requestMillis()),TimeUnit.MILLISECONDS);
            budget.check(); return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); future.cancel(false); throw new ConsistencyBudget.Failure("interrupted");
        } catch (Exception e) {
            future.cancel(false); throw new ConsistencyBudget.Failure("redis_read_failed");
        }
    }
    private static Page page(Object raw) {
        if (!(raw instanceof List<?> values) || values.size()!=2 || !(values.get(1) instanceof List<?> keys))
            throw new ConsistencyBudget.Failure("redis_decode_failed");
        String cursor = String.valueOf(values.getFirst());
        if (!cursor.matches("[0-9]{1,20}")) throw new ConsistencyBudget.Failure("redis_decode_failed");
        var out = new ArrayList<String>();
        for (Object key : keys) {
            if (!(key instanceof String text)) throw new ConsistencyBudget.Failure("redis_decode_failed");
            out.add(text);
        }
        return new Page(cursor,out);
    }
    static String rankPrefix(RedisKeys keys) {
        String zero = keys.guildRankZone(0); return zero.substring(0,zero.length()-1);
    }
}
