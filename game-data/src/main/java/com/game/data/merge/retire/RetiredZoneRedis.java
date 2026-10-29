package com.game.data.merge.retire;

import com.game.data.merge.ZoneMergeStore;
import com.game.data.merge.pb.ZoneMergeRunRow;
import com.game.discovery.RedisKeys;
import java.sql.SQLException;
import java.util.*;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.RedissonObject;

/** 已持SQL冻结原件时执行的热退休。每次最多清256键；原件与版本水位永久保留，重试同一run。 */
public final class RetiredZoneRedis {
    public record Report(int zone, boolean applied, int candidateKeys, long deletedKeys, int remainingKeys, long version, String proof) { }
    private static final int LIMIT=256;
    /** 直接读取旧RMapCache的值/TTL，不创建带后台eviction任务的RMapCache对象，不续maxIdle。 */
    static final String LEGACY_LIVE="""
            local t=redis.call('TIME'); local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
            if redis.call('HLEN',KEYS[1])>20000 then return redis.error_reply('legacy directory too large') end
            local values=redis.call('HGETALL',KEYS[1]); local live=0
            for i=1,#values,2 do
              local ok,idle,payload=pcall(struct.unpack,'dLc0',values[i+1])
              if not ok then return redis.error_reply('legacy directory payload malformed') end
              local expire=redis.call('ZSCORE',KEYS[2],values[i]); local idleExpire=redis.call('ZSCORE',KEYS[3],values[i])
              local untilMs=expire and tonumber(expire) or math.huge
              if idle~=0 and idleExpire then untilMs=math.min(untilMs,tonumber(idleExpire)) end
              if untilMs>now then live=1 end
            end
            return live
            """;
    private static final String BEGIN="""
            if redis.call('GET',KEYS[4])~='2' then return redis.error_reply('directory protocol 2 required') end
            local count=tonumber(ARGV[6]); local types=redis.call('SMEMBERS',KEYS[5])
            if #types~=count then return redis.error_reply('directory types changed') end
            local t=redis.call('TIME'); local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
            for i=1,count do
              if redis.call('SISMEMBER',KEYS[5],ARGV[6+i])~=1 then return redis.error_reply('directory types changed') end
              if redis.call('ZCOUNT',KEYS[5+i],'('..string.format('%.0f',now),'+inf')~=0 then return redis.error_reply('live zone node') end
            end
            local original=redis.call('GET',KEYS[1]); local leader=redis.call('GET',KEYS[2]) or ''
            if original and original~=ARGV[1] then return redis.error_reply('retirement original differs') end
            if original and leader~='' and leader~=ARGV[2] then return redis.error_reply('retirement worker busy') end
            if not original and leader~=ARGV[3] then return redis.error_reply('leader changed') end
            if (redis.call('GET',KEYS[3]) or '0')~=ARGV[4] then return redis.error_reply('plan version changed') end
            redis.call('SET',KEYS[1],ARGV[1])
            redis.call('SET',KEYS[2],ARGV[2],'PX',30000)
            redis.call('SET',KEYS[3],ARGV[5])
            return 1
            """;
    private static final String CLEAN="""
            if redis.call('GET',KEYS[1])~=ARGV[1] then return redis.error_reply('retirement original differs') end
            if redis.call('GET',KEYS[2])~=ARGV[2] then return redis.error_reply('retirement worker fenced') end
            if redis.call('GET',KEYS[3])~=ARGV[3] then return redis.error_reply('plan version changed') end
            local n=0; for i=4,#KEYS do n=n+redis.call('DEL',KEYS[i]) end
            redis.call('SET',KEYS[3],ARGV[4]); return n
            """;
    private final RedissonClient redis;
    private final RedisKeys keys;
    public RetiredZoneRedis(RedissonClient redis,RedisKeys keys){this.redis=Objects.requireNonNull(redis);this.keys=Objects.requireNonNull(keys);}

    /** deploymentDigest由CLI核实外部升级清单字节后传入，永远绑定本次退休原件。 */
    public Report execute(ZoneMergeRunRow run,String deploymentDigest,Set<String> deployedTypes,boolean apply,
                          ZoneMergeStore.RetirementBudget budget)throws SQLException {
        int zone=run.getSourceZone();
        if(zone==0||run.getMode()!=1||run.getState()!=5||run.getTargetZone()==0)throw new IllegalArgumentException("必须持有已finish的非全局正向合服证明");
        if(!deploymentDigest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("部署证据必须是SHA-256");
        String original=identity(run,deploymentDigest);
        budget.check();
        if(!"2".equals(get(keys.zoneDirectoryFormat(zone))))throw new IllegalStateException("源区尚无v2目录写者能力证据");
        Set<String> types=types(zone);
        if(types.isEmpty()||!types.equals(deployedTypes))throw new IllegalStateException("实际目录类型与已升级部署清单不同");
        // 旧目录也必须为空；未解析的坏原件同样不能当作无活节点。
        for(String legacy:scan(keys.nodeDirectory("*",zone),budget)) {
            budget.check();
            if(legacyHasLiveEntries(legacy))
                throw new IllegalStateException("源区旧目录仍有节点，必须完成升级并停机");
        }
        for(String lease:scan(keys.nodeIdsInZone(zone),budget)) {
            budget.check();if(get(lease)!=null)throw new IllegalStateException("源区节点租约仍被持有，禁止退休");
        }
        List<String> hot=scan(keys.worldZoneKeysPrefix(zone)+"*",budget).stream().filter(key->isHotKey(keys,zone,key)).sorted().toList();
        long version=version(zone);
        String previous=get(keys.worldRetirement(zone));
        if(previous!=null&&!previous.equals(original))throw new IllegalStateException("已退休原件不同");
        // dry-run也核当前活节点；apply会在同槽Lua里再次核验，封住发布竞态。
        for(String type:types) {
            budget.check();
            Number alive=redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                    "local t=redis.call('TIME'); local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000); return redis.call('ZCOUNT',KEYS[1],'('..string.format('%.0f',now),'+inf')",
                    RScript.ReturnType.INTEGER,List.of(keys.zoneNodeExpiry(type,zone)));
            if(alive==null||alive.longValue()!=0)throw new IllegalStateException("源区v2目录仍有活节点");
        }
        if(!apply)return new Report(zone,false,hot.size(),0,hot.size(),version,original);
        String token="retire:"+UUID.randomUUID();
        String leader=Objects.requireNonNullElse(get(keys.worldLeader(zone)),"");
        long next=Math.addExact(version,1);
        long cleanedVersion=Math.addExact(next,1);
        List<Object> beginKeys=new ArrayList<>(List.of(keys.worldRetirement(zone),keys.worldLeader(zone),keys.worldPlanVersion(zone),keys.zoneDirectoryFormat(zone),keys.zoneNodeTypes(zone)));
        var ordered=new TreeSet<>(types);for(String type:ordered)beginKeys.add(keys.zoneNodeExpiry(type,zone));
        List<String> args=new ArrayList<>(List.of(original,token,leader,Long.toString(version),Long.toString(next),Integer.toString(types.size())));args.addAll(ordered);
        budget.check();
        Object begun=redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,BEGIN,RScript.ReturnType.INTEGER,beginKeys,args.toArray());
        if(!(begun instanceof Number number)||number.longValue()!=1)throw new IllegalStateException("退休屏障未确认");
        try {
            budget.check();
            // 屏障后重扫，涵盖首次扫描与BEGIN之间刚出现的热键；此后所有升级写者都已原子拒写。
            hot=scan(keys.worldZoneKeysPrefix(zone)+"*",budget).stream().filter(key->isHotKey(keys,zone,key)).sorted().toList();
            List<Object> cleanKeys=new ArrayList<>(List.of(keys.worldRetirement(zone),keys.worldLeader(zone),keys.worldPlanVersion(zone)));
            cleanKeys.addAll(hot.stream().limit(LIMIT).toList());
            Number deleted=redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,CLEAN,RScript.ReturnType.INTEGER,cleanKeys,
                    original,token,Long.toString(next),Long.toString(cleanedVersion));
            if(deleted==null||deleted.longValue()<0)throw new IllegalStateException("退休清理未确认");
            budget.check();
            int remaining=(int)scan(keys.worldZoneKeysPrefix(zone)+"*",budget).stream().filter(key->isHotKey(keys,zone,key)).count();
            return new Report(zone,true,hot.size(),deleted.longValue(),remaining,cleanedVersion,original);
        } finally {
            // 只释放本次工具锁；永久退休原件和版本绝不删除，失败后由同一原件继续。
            try { redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                        "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end; return 0",
                        RScript.ReturnType.INTEGER,List.of(keys.worldLeader(zone)),token);
            } catch(RuntimeException releaseFailure) {
                org.slf4j.LoggerFactory.getLogger(RetiredZoneRedis.class).warn("退休工作锁释放未确认，等有界TTL后用同一原件续跑 zone={}",Integer.toUnsignedString(zone),releaseFailure);
            }
        }
    }
    boolean legacyHasLiveEntries(String legacy) {
        // 名称算法取当前Redisson依赖的公开helper；不自行猜hash tag或创建cache触发清扫。
        Object result=redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,LEGACY_LIVE,RScript.ReturnType.INTEGER,
                List.of(legacy,RedissonObject.prefixName("redisson__timeout__set",legacy),
                        RedissonObject.prefixName("redisson__idle__set",legacy)));
        if(!(result instanceof Number flag)||flag.longValue()<0||flag.longValue()>1)
            throw new IllegalStateException("旧目录读取未确认");
        return flag.longValue()==1;
    }
    private String get(String key){return redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,"return redis.call('GET',KEYS[1])",RScript.ReturnType.VALUE,List.of(key));}
    private long version(int zone){String value=get(keys.worldPlanVersion(zone));long result=value==null?0:Long.parseLong(value);if(result<0)throw new IllegalStateException("计划版本无效");return result;}
    private Set<String> types(int zone){
        List<?> raw=redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,"return redis.call('SMEMBERS',KEYS[1])",RScript.ReturnType.MULTI,List.of(keys.zoneNodeTypes(zone)));
        if(raw==null)throw new IllegalStateException("目录类型证据缺失");Set<String> result=new TreeSet<>();
        for(Object item:raw){if(!(item instanceof String type)||!type.matches("[a-z][a-z0-9-]{0,63}"))throw new IllegalStateException("目录类型证据损坏");result.add(type);}return result;
    }
    private List<String> scan(String pattern,ZoneMergeStore.RetirementBudget budget)throws SQLException {
        List<String> result=new ArrayList<>();for(String key:redis.getKeys().getKeysByPattern(pattern)){budget.check();if(result.size()>=20_000)throw new IllegalStateException("维护扫描超过单次安全上限，需核对目录");result.add(key);}return result;
    }
    public static boolean isHotKey(RedisKeys keys,int zone,String key) {
        if(zone==0)return false;
        String suffix=key.startsWith(keys.worldZoneKeysPrefix(zone))?key.substring(keys.worldZoneKeysPrefix(zone).length()):"";
        return suffix.matches("ch|desired|cooldown|directory:[0-9]+|resv:[0-9]+|nodes:[a-z][a-z0-9-]{0,63}|node-expiry:[a-z][a-z0-9-]{0,63}");
    }
    public static String identity(ZoneMergeRunRow run,String deploymentDigest){
        return "v2|"+run.getRunId()+"|"+HexFormat.of().formatHex(run.getPlanHash().toByteArray())+"|"+run.getGeneration()+"|"
                +Integer.toUnsignedString(run.getSourceZone())+"|"+Integer.toUnsignedString(run.getTargetZone())+"|"+deploymentDigest;
    }
}
