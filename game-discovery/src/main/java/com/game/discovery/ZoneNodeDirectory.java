package com.game.discovery;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/** 按区目录v2：所有类型与退休原件同槽；读写均在主节点执行，不依赖本机时钟。 */
final class ZoneNodeDirectory {
    private static final String TIME = "local t=redis.call('TIME'); local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000); ";
    /** seen 的数字字段仍保存原 epoch|instance；seq:/state: 元字段永久围住同代迟到命令。 */
    private static final String ORIGINAL = """
            local previous=redis.call('HGET',KEYS[3],ARGV[1]); local oldEpoch='0'; local oldInstance=''
            local oldSequence=redis.call('HGET',KEYS[3],'seq:'..ARGV[1]); local oldState=redis.call('HGET',KEYS[3],'state:'..ARGV[1])
            if previous then
              local delimiter=string.find(previous,'|',1,true)
              if not delimiter then return redis.error_reply('directory epoch original malformed') end
              oldEpoch=string.sub(previous,1,delimiter-1);oldInstance=string.sub(previous,delimiter+1)
              if not string.match(oldEpoch,'^%d+$') or (#oldEpoch>1 and string.sub(oldEpoch,1,1)=='0') then
                return redis.error_reply('directory epoch original malformed')
              end
              if oldEpoch~='0' and oldInstance=='' then return redis.error_reply('directory instance original missing') end
            elseif oldSequence or oldState then return redis.error_reply('directory sequence original missing') end
            if oldSequence then
              if not string.match(oldSequence,'^[1-9]%d*$') or (oldState~='live' and oldState~='removed') then
                return redis.error_reply('directory sequence original malformed')
              end
            elseif oldState then return redis.error_reply('directory sequence original malformed') end
            oldSequence=oldSequence or '0'
            """;
    static final String PUBLISH = TIME + """
            if redis.call('EXISTS',KEYS[4])==1 then return 0 end
            local format=redis.call('GET',KEYS[5])
            if format and format~='2' then return redis.error_reply('unsupported zone directory format') end
            """ + ORIGINAL + """
            local epoch=ARGV[5]
            if #oldEpoch>#epoch or (#oldEpoch==#epoch and oldEpoch>epoch) then return -1 end
            if epoch~='0' and oldEpoch==epoch and oldInstance~=ARGV[6] then return -1 end
            local sequence=ARGV[7]
            if oldEpoch==epoch and oldInstance==ARGV[6] then
              if #oldSequence>#sequence or (#oldSequence==#sequence and oldSequence>sequence) then return -1 end
              if oldSequence==sequence then
                if oldState=='live' and redis.call('HGET',KEYS[1],ARGV[1])==ARGV[2] then return 1 end
                return -1
              end
            end
            redis.call('HSET',KEYS[1],ARGV[1],ARGV[2])
            redis.call('ZADD',KEYS[2],now+tonumber(ARGV[3]),ARGV[1])
            redis.call('HSET',KEYS[3],ARGV[1],epoch..'|'..ARGV[6])
            redis.call('HSET',KEYS[3],'seq:'..ARGV[1],sequence,'state:'..ARGV[1],'live')
            redis.call('SET',KEYS[5],'2')
            redis.call('SADD',KEYS[6],ARGV[4])
            return 1
            """;
    static final String READ = TIME + """
            if redis.call('EXISTS',KEYS[4])==1 then return {1,{}, {}} end
            local ids
            if ARGV[1]~='' then ids={ARGV[1]} else ids=redis.call('HKEYS',KEYS[3]) end
            local seen={}; local values={}
            for _,id in ipairs(ids) do
              if string.match(id,'^%-?%d+$') and redis.call('HEXISTS',KEYS[3],id)==1 then
                seen[#seen+1]=id
                local expiry=redis.call('ZSCORE',KEYS[2],id)
                if expiry and tonumber(expiry)>now then
                  local value=redis.call('HGET',KEYS[1],id)
                  if not value then return redis.error_reply('zone directory payload missing') end
                  values[#values+1]=id; values[#values+1]=value
                end
              end
            end
            return {0,seen,values}
            """;
    static final String REMOVE = """
            if redis.call('EXISTS',KEYS[4])==1 then return 0 end
            """ + ORIGINAL + """
            local epoch=ARGV[2]; local instance=ARGV[3]; local sequence=ARGV[4]
            if #oldEpoch>#epoch or (#oldEpoch==#epoch and oldEpoch>epoch) then return 0 end
            if previous and oldEpoch==epoch then
              if oldInstance~=instance then return 0 end
              if #oldSequence>#sequence or (#oldSequence==#sequence and oldSequence>sequence) then return 0 end
              if oldSequence==sequence then return oldState=='removed' and 1 or 0 end
            end
            redis.call('HSET',KEYS[3],ARGV[1],epoch..'|'..instance,'seq:'..ARGV[1],sequence,'state:'..ARGV[1],'removed')
            redis.call('HDEL',KEYS[1],ARGV[1]); redis.call('ZREM',KEYS[2],ARGV[1]); return 1
            """;
    record Snapshot(boolean retired, Set<String> seen, Map<String, byte[]> values) { }
    private final RedissonClient redis;
    private final RedisKeys keys;
    private final String type;
    ZoneNodeDirectory(RedissonClient redis, RedisKeys keys, String type) {
        if(type==null||!type.matches("[a-z][a-z0-9-]{0,63}"))throw new IllegalArgumentException("节点类型无效");
        this.redis=Objects.requireNonNull(redis);this.keys=Objects.requireNonNull(keys);this.type=type;
    }
    private List<Object> keyList(int zone) {
        if(zone==0) throw new IllegalArgumentException("全局zone0不使用退休目录");
        return List.of(keys.zoneNodeDirectory(type,zone), keys.zoneNodeExpiry(type,zone), keys.zoneNodeSeen(type,zone),
                keys.worldRetirement(zone),keys.zoneDirectoryFormat(zone),keys.zoneNodeTypes(zone));
    }
    void publish(int zone,int node,byte[] payload,Duration ttl,long epoch,String instance,long sequence) {
        if(ttl==null||ttl.toMillis()<=0)throw new IllegalArgumentException("目录TTL必须为正");
        if(epoch<0||epoch>0&&instance.isBlank()||sequence<=0)throw new IllegalArgumentException("目录租约代次、实例身份与发布序号无效");
        Long result=redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,PUBLISH,RScript.ReturnType.INTEGER,
                keyList(zone),bytes(Integer.toString(node)),payload,bytes(Long.toString(ttl.toMillis())),bytes(type),bytes(Long.toString(epoch)),bytes(instance),bytes(Long.toString(sequence)));
        if(result!=null&&result==-1)throw new IllegalStateException("旧代、同代异实例或旧发布序号禁止发布节点目录");
        if(result==null||result!=1)throw new IllegalStateException("已退役区禁止发布节点目录");
    }
    void remove(int zone,int node,long epoch,String instance,long sequence) {
        redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,REMOVE,RScript.ReturnType.INTEGER,
                keyList(zone),bytes(Integer.toString(node)),bytes(Long.toString(epoch)),bytes(instance),bytes(Long.toString(sequence)));
    }
    CompletableFuture<Snapshot> read(int zone,Integer node) {
        return redis.getScript(ByteArrayCodec.INSTANCE).evalAsync(RScript.Mode.READ_WRITE,READ,RScript.ReturnType.MULTI,
                keyList(zone),bytes(node==null?"":Integer.toString(node))).toCompletableFuture().thenApply(ZoneNodeDirectory::parse);
    }
    CompletableFuture<Boolean> retired(int zone) {
        return redis.getScript(ByteArrayCodec.INSTANCE).evalAsync(RScript.Mode.READ_WRITE,
                "return redis.call('EXISTS',KEYS[1])",RScript.ReturnType.INTEGER,List.of(keys.worldRetirement(zone)))
                .toCompletableFuture().thenApply(raw -> {
                    if(!(raw instanceof Number value)||value.longValue()<0||value.longValue()>1)
                        throw new IllegalStateException("无法确认节点目录退休状态");
                    return value.longValue()==1;
                });
    }
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static String text(Object raw){if(raw instanceof byte[] value)return new String(value,StandardCharsets.UTF_8);throw new IllegalStateException("目录键编码错误");}
    private static Snapshot parse(Object raw) {
        if(!(raw instanceof List<?> list)||list.size()!=3||!(list.get(0) instanceof Number flag)
                ||!(list.get(1) instanceof List<?> seen)||!(list.get(2) instanceof List<?> pairs)||pairs.size()%2!=0)
            throw new IllegalStateException("目录v2响应结构错误");
        if(flag.longValue()!=0&&flag.longValue()!=1)throw new IllegalStateException("目录v2退休标记错误");
        Set<String> ids=new HashSet<>();for(Object id:seen)ids.add(text(id));
        Map<String,byte[]> values=new LinkedHashMap<>();
        for(int i=0;i<pairs.size();i+=2){if(!(pairs.get(i+1) instanceof byte[] value))throw new IllegalStateException("目录值编码错误");values.put(text(pairs.get(i)),value);}
        return new Snapshot(flag.longValue()==1,Set.copyOf(ids),Map.copyOf(values));
    }
}
