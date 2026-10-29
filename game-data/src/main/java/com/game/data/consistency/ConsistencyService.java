package com.game.data.consistency;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.game.data.consistency.ConsistencyReport.*;
import static com.game.data.consistency.ConsistencySqlPlans.*;

/** 一次性只读运行器；报告只在本次响应中返回，不伪装已实现持久runs或全域单飞。 */
public final class ConsistencyService {
    public static final List<String> CORE = java.util.stream.Stream.concat(
            java.util.stream.Stream.of("route.meta", "zone.player", "zone.guild", "zone.guild_rank",
                    "account.player_account", "friend.orphan", "mail.state_orphan", "match.expired_unrecoverable",
                    "player.state_orphan", "own.fence", "friend.symmetry"),
            ConsistencyExtraSqlPlans.CHECKS.stream()).toList();
    public static final List<String> CATALOG = List.of("route.meta","route.fences","zone.player","zone.guild","zone.guild_rank",
            "zone.trade_listing","zone.world_registry","zone.guild_member_home","zone.trade_listing_home",
            "account.player_account","account.player_cap","player.name_key","player.state_orphan","player.state_decode",
            "assetop.seq_watermark","own.fence","own.lease_skew","own.abandoned","own.zombie","own.ghost_presence",
            "friend.orphan","friend.symmetry","friend.capacity","guild.member","guild.leader","guild.capacity",
            "guild.rank_score","trade.seller","ops.job_residue","mail.state_orphan","match.expired_unrecoverable",
            "battle.terminal_original","retire.original","retire.references","retire.redis_barrier","retire.hot_state",
            "world.directory","assetop.cleanup_watermark");
    public record Request(List<String> checks, Integer pageSize, Integer maxRows, Long budgetMillis,
            Long checkMillis, Boolean failOnWarn) {
        Request normalized() {
            List<String> ids = checks == null || checks.isEmpty() ? CORE : List.copyOf(checks);
            if (ids.size()>CATALOG.size() || new HashSet<>(ids).size()!=ids.size() || !CATALOG.containsAll(ids))
                throw new IllegalArgumentException("检查ID不合法或重复");
            int page=pageSize==null?256:pageSize, rows=maxRows==null?20_000:maxRows;
            long run=budgetMillis==null?900_000:budgetMillis, check=checkMillis==null?60_000:checkMillis;
            if(page<1||page>2_000||rows<1||rows>20_000||run<1||run>900_000||check<1||check>60_000)
                throw new IllegalArgumentException("预算只能在服务上限内收紧");
            return new Request(ids,page,rows,run,check,Boolean.TRUE.equals(failOnWarn));
        }
    }
    @FunctionalInterface interface Delay { void waitFor(ConsistencyBudget budget); }
    private final ConsistencySqlReader sql;
    private final ConsistencyRedisReader redis;
    private final GameNamespace namespace;
    private final RedisKeys keys;
    private final Delay delay;
    private final AtomicBoolean running = new AtomicBoolean();
    public ConsistencyService(ConsistencySqlReader sql, ConsistencyRedisReader redis, GameNamespace namespace, RedisKeys keys) {
        this(sql,redis,namespace,keys,ConsistencyBudget::confirmationDelay);
    }
    ConsistencyService(ConsistencySqlReader sql, ConsistencyRedisReader redis, GameNamespace namespace, RedisKeys keys, Delay delay) {
        this.sql=sql;this.redis=redis;this.namespace=namespace;this.keys=keys;this.delay=delay;
        if(!namespace.equals(keys.namespace()))throw new IllegalArgumentException("Redis命名空间不匹配");
    }
    public ConsistencyReport run(Request input) {
        Request request=(input==null?new Request(null,null,null,null,null,null):input).normalized();
        Instant start=Instant.now(); String id=UUID.randomUUID().toString();
        var results=new ArrayList<Result>();var executed=new ArrayList<String>();
        var salt=new byte[32];new SecureRandom().nextBytes(salt);
        boolean acquired=running.compareAndSet(false,true);
        try {
            Deadline deadline=Deadline.after(request.budgetMillis);
            for(String check:request.checks) {
                if(!acquired){results.add(Result.unknown(check,"local_busy"));continue;}
                if (!CORE.contains(check)) {
                    results.add(Result.unknown(check, ConsistencyExtraSqlPlans.unavailableReason(check)));
                    continue;
                }
                if(deadline.expired()){results.add(Result.unknown(check,"budget_exhausted"));continue;}
                executed.add(check);
                var progress=new Progress(check,salt);
                var budget=new ConsistencyBudget(deadline,request.checkMillis);
                try {
                    if(sql==null)throw new ConsistencyBudget.Failure("sql_not_configured");
                    if(check.equals("route.meta"))route(progress,budget);
                    else if(check.equals("zone.guild_rank"))rank(progress,budget,request);
                    else scan(progress,budget,request);
                    String coverageGap = ConsistencyExtraSqlPlans.coverageGap(check);
                    if (coverageGap != null) {
                        progress.unknown(coverageGap);
                    }
                } catch(ConsistencyBudget.Failure failure){progress.unknown(failure.getMessage());}
                catch(RuntimeException failure){progress.unknown("row_decode_failed");}
                results.add(progress.result());
            }
        } finally { if(acquired)running.set(false);java.util.Arrays.fill(salt,(byte)0); }
        var omitted=CATALOG.stream().filter(check->!request.checks.contains(check)).toList();
        return new ConsistencyReport(id,namespace.value(),start,Instant.now(),true,
                "bounded_read_committed_interval;concurrent_inserts_before_cursor_deferred;single_endpoint_redis;no_durable_run",
                request.checks,executed,omitted,results,ConsistencyReport.exitCode(results,request.failOnWarn));
    }
    private List<Map<String,Object>> query(Query query,int limit,ConsistencyBudget budget) {
        budget.check(); return sql.query(query.sql(),query.parameters(),limit,budget);
    }
    private void scan(Progress p,ConsistencyBudget budget,Request request) {
        long friendEdges=0,friendOrphans=0;
        List<Plan> plans = forCheck(p.id);
        if (plans.isEmpty()) {
            throw new ConsistencyBudget.Failure("not_implemented");
        }
        for (Plan plan : plans) {
            long scannedBefore=p.scanned,badBefore=p.bad;
            // 空源表也必须证明所有被引用表和列存在，不能在取upper为空时跳过依赖。
            query(plan.schema(),1,budget);
            var upperRows=query(plan.upper(),1,budget);
            if(upperRows.isEmpty())continue;
            Key upper=key(upperRows.getFirst()),cursor=null;p.upper=upper.toString();
            while(true) {
                budget.check();
                int size=(int)Math.min(request.pageSize,request.maxRows-p.scanned);
                if(size<1)throw new ConsistencyBudget.Failure("partial_coverage");
                var page=query(plan.page(cursor,upper,size),size,budget);
                if(page.isEmpty())break;
                var candidates=new ArrayList<Map<String,Object>>();
                for(var row:page) {
                    Key next=key(row);
                    if((cursor!=null&&next.compareTo(cursor)<=0)||next.compareTo(upper)>0)
                        throw new ConsistencyBudget.Failure("invalid_cursor");
                    p.scanned++;p.observe(row);cursor=next;p.cursor=plan.table()+":"+cursor;
                    Finding found=finding(plan,row);
                    if(found!=null) {
                        if(found.status()==Status.UNKNOWN)p.unknown(found.reason()); else candidates.add(row);
                    }
                }
                if(!candidates.isEmpty()) {
                    delay.waitFor(budget);
                    for(var before:candidates) {
                        var after=query(plan.point(key(before)),1,budget);
                        if(after.isEmpty()){p.transientCount++;continue;}
                        p.observe(after.getFirst());Finding found=finding(plan,after.getFirst());
                        if(found==null){p.transientCount++;continue;}
                        if(!stable(before,after.getFirst())){p.unknown("concurrent_change");continue;}
                        if(found.status()==Status.UNKNOWN){p.unknown(found.reason());continue;}
                        p.bad(plan.table()+":"+key(before),found);
                    }
                }
                if(cursor.compareTo(upper)>=0||page.size()<size)break;
            }
            if(p.id.equals("friend.orphan")&&plan.table().equals("friend")) {
                friendEdges=p.scanned-scannedBefore;friendOrphans=p.bad-badBefore;
            }
        }
        if(p.id.equals("friend.orphan")&&!p.unknown&&friendEdges>0&&friendOrphans*20>=friendEdges) {
            p.severity=Status.BLOCK;p.reasons.add("friend_orphan_ratio_at_least_5_percent");
        }
        p.finished=true;
    }
    private void route(Progress p,ConsistencyBudget budget) {
        Query query=new Query("SELECT id,generation,writable,fence_shards,"+NOW+" FROM route_meta ORDER BY id LIMIT 2",List.of());
        var before=query(query,2,budget);p.scanned=before.size();before.forEach(p::observe);
        Finding found=routeFinding(before);
        if(found!=null) {
            delay.waitFor(budget);var after=query(query,2,budget);after.forEach(p::observe);
            if(routeFinding(after)==null)p.transientCount++;
            else if(sameRows(before,after))p.bad("route-meta",found);
            else p.unknown("concurrent_change");
        }
        if(before.size()==2)p.unknown("partial_coverage");
        p.finished=true;
    }
    private static Finding routeFinding(List<Map<String,Object>> rows) {
        return rows.size()!=1?block("route_meta_cardinality"):ConsistencySqlPlans.route(rows.getFirst());
    }
    private static boolean sameRows(List<Map<String,Object>> a,List<Map<String,Object>> b) {
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!stable(a.get(i),b.get(i)))return false;
        return true;
    }
    static boolean stable(Map<String,Object> before,Map<String,Object> after) {
        var a=new java.util.HashMap<>(before);var b=new java.util.HashMap<>(after);a.remove("db_now");b.remove("db_now");
        if(!a.keySet().equals(b.keySet()))return false;
        return a.entrySet().stream().allMatch(entry->Objects.deepEquals(entry.getValue(),b.get(entry.getKey())));
    }
    private void rank(Progress p,ConsistencyBudget budget,Request request) {
        // Redis全空也必须证明SQL身份与依赖列，不能靠“未产生区号候选”跳过权威门禁。
        query(new Query("SELECT z.zone_id,m.zone_id AS retired_zone FROM zone_config z "
                + "LEFT JOIN zone_merged_into m ON m.zone_id=z.zone_id WHERE 1=0 LIMIT 1",List.of()),1,budget);
        if(redis==null)throw new ConsistencyBudget.Failure("redis_not_configured");
        if(!redis.completeTopology())throw new ConsistencyBudget.Failure("unsupported_topology");
        requireUnlocked(budget);
        var indexed=new LinkedHashSet<String>();var physical=new LinkedHashSet<String>();
        for(boolean index:new boolean[]{true,false}) {
            String cursor="0";int pages=0;
            do {
                if(++pages>request.maxRows||p.scanned>=request.maxRows)throw new ConsistencyBudget.Failure("partial_coverage");
                var page=index?redis.scanRankZones(cursor,request.pageSize,budget):redis.scanRankKeys(cursor,request.pageSize,budget);
                if(page.values().size()>request.maxRows-p.scanned)throw new ConsistencyBudget.Failure("partial_coverage");
                p.scanned+=page.values().size();(index?indexed:physical).addAll(page.values());
                cursor=page.cursor();p.cursor=(index?"index:":"keys:")+cursor;
            } while(!cursor.equals("0"));
        }
        String prefix=RedissonConsistencyReader.rankPrefix(keys);
        Set<String> candidates=new LinkedHashSet<>(physical);for(String zone:indexed)candidates.add(prefix+zone);
        for(String key:candidates) {
            budget.check();Finding before=rankFinding(key,indexed,physical,budget,p);
            if(before==null)continue;
            delay.waitFor(budget);requireUnlocked(budget);
            Finding after=rankFinding(key,indexed,physical,budget,p);
            if(after==null)p.transientCount++;
            else if(Objects.equals(before,after))p.bad(key,before);
            else p.unknown("concurrent_change");
        }
        requireUnlocked(budget);p.finished=true;
    }
    private Finding rankFinding(String key,Set<String> indexed,Set<String> physical,ConsistencyBudget budget,Progress p) {
        String prefix=RedissonConsistencyReader.rankPrefix(keys);
        if(!key.startsWith(prefix))throw new ConsistencyBudget.Failure("redis_decode_failed");
        String raw=key.substring(prefix.length());
        String initialType=redis.type(key,budget);
        if(initialType.equals("none")){
            p.transientCount++;
            if(indexed.contains(raw))p.unknown("rank_index_changed_or_missing");
            return null;
        }
        long zone;
        try {
            zone=Long.parseLong(raw);
            if(zone<1||zone>0xffff_ffffL||!Long.toString(zone).equals(raw))throw new NumberFormatException();
        } catch(NumberFormatException e) {return block("rank_zone_shape");}
        var zoneQuery=new Query("SELECT EXISTS(SELECT 1 FROM zone_config WHERE zone_id=?) AS known,"
                + "EXISTS(SELECT 1 FROM zone_merged_into WHERE zone_id=?) AS retired,"+NOW,List.of(zone,zone));
        var a=query(zoneQuery,1,budget);
        if(a.size()!=1)throw new ConsistencyBudget.Failure("row_decode_failed");
        String type=redis.type(key,budget);var b=query(zoneQuery,1,budget);
        if(b.size()!=1||!stable(a.getFirst(),b.getFirst()))throw new ConsistencyBudget.Failure("concurrent_change");
        p.observe(a.getFirst());p.observe(b.getFirst());
        if(type.equals("none")){p.transientCount++;return null;}
        if(!type.equals("zset"))return block("rank_type_invalid");
        if(!flag(b.getFirst(),"known")||flag(b.getFirst(),"retired"))return warn("rank_zone_orphan");
        // 本轮索引可能同时换榜；索引与实体不同仅报告未知，不据旧集合确认修复候选。
        if(!indexed.contains(raw)||!physical.contains(key))p.unknown("rank_index_changed_or_missing");
        return null;
    }
    private void requireUnlocked(ConsistencyBudget budget) {
        if(!redis.type(keys.guildRankLock(),budget).equals("none"))throw new ConsistencyBudget.Failure("concurrent_maintenance");
    }
    private static final class Progress {
        final String id;final byte[] salt;final Set<String> reasons=new LinkedHashSet<>();final List<Sample> samples=new ArrayList<>();
        long scanned,bad,transientCount;boolean finished,unknown;Status severity=Status.PASS;String cursor,upper;Long first,last;
        Progress(String id,byte[] salt){this.id=id;this.salt=salt;}
        void observe(Map<String,Object> row){long now=number(row,"db_now");if(first==null)first=now;last=now;}
        void unknown(String reason){unknown=true;reasons.add(reason);}
        void bad(String identity,Finding found){bad++;reasons.add(found.reason());if(found.status()==Status.BLOCK||severity==Status.PASS)severity=found.status();
            if(samples.size()<16)samples.add(new Sample(hash(identity),found.reason()));}
        String hash(String value){
            if(value==null)return null;
            try{MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(salt);return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));}
            catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        }
        Result result(){boolean complete=finished&&!unknown;return new Result(id,severity==Status.PASS&&!complete?Status.UNKNOWN:severity,
                complete?Coverage.COMPLETE:(scanned>0?Coverage.PARTIAL:Coverage.NONE),complete,scanned,bad,transientCount,
                List.copyOf(reasons),samples,hash(cursor),hash(upper),first,last);}
    }
}
