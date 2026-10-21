package com.game.data.tools;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.NamespaceDatabaseCheck;
import com.game.data.merge.ZoneMergeStore;
import com.game.discovery.namespace.GameNamespaceBinder;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Set;

/** 显式离线维护工具；不启动服务、不改Redis、不自动循环、不自动解冻。用法见zone-merge-spec维护节。 */
public final class MergeZone {
    private MergeZone(){}
    public static void main(String[] args)throws Exception{
        if(args.length<3||args.length%2!=1)throw new IllegalArgumentException("需要 <freeze|plan|approve|apply|finish|abort|release|read> --run <UUID> 及对应参数");
        String command=args[0];if(!Set.of("freeze","plan","approve","apply","finish","abort","release","read").contains(command))throw new IllegalArgumentException("未知维护命令");
        var values=new HashMap<String,String>();for(int i=1;i<args.length;i+=2){String key=args[i];if(!Set.of("--run","--source","--target","--expected-players","--operator","--original-run","--hash","--generation","--expect-database").contains(key)||values.putIfAbsent(key,args[i+1])!=null)throw new IllegalArgumentException("未知或重复参数");}
        var allowed=new java.util.HashSet<>(Set.of("--run","--expect-database"));
        allowed.addAll(switch(command){case "freeze"->Set.of("--source","--target","--expected-players","--operator","--original-run");case "approve","abort"->Set.of("--hash","--operator");case "apply","finish"->Set.of("--hash");case "release"->Set.of("--hash","--generation","--operator");default->Set.<String>of();});
        if(!allowed.containsAll(values.keySet()))throw new IllegalArgumentException("当前命令携带了不属于该意图的参数");
        String key=required(values,"--run");
        var namespace=GameNamespaceBinder.bind(System.getProperties(),System.getenv()).namespace();
        String url=System.getenv("GAME_ZONE_MERGE_JDBC"),user=System.getenv("GAME_MYSQL_USER");
        if(url==null||url.isBlank()||user==null||user.isBlank()||url.matches("(?is).*[?&](user|password|token)=.*"))throw new IllegalArgumentException("必须显式设置GAME_ZONE_MERGE_JDBC/GAME_MYSQL_USER，秘密只读独立环境，连接串不得夹带秘密");
        try(var pool=new DruidDataSource()){
            pool.setUrl(url);pool.setUsername(user);pool.setPassword(System.getenv().getOrDefault("GAME_MYSQL_PASSWORD",""));pool.setMaxActive(1);pool.setMaxWait(3000);
            new StrongTiDbConnectionPolicy("显式SQL合区维护",true).postProcessBeforeInitialization(pool,"zoneMergeMaintenance");pool.init();
            try(var c=pool.getConnection()){StrongTiDbConnectionPolicy.verify(c,true);String mismatch=NamespaceDatabaseCheck.verify(c,values.get("--expect-database"),namespace,"--expect-database <真实业务库>");if(mismatch!=null)throw new IllegalStateException(mismatch);}
            var store=new ZoneMergeStore(pool);
            var result=switch(command){
                case "freeze"->store.freeze(key,zone(required(values,"--source")),zone(required(values,"--target")),Long.parseLong(required(values,"--expected-players")),required(values,"--operator"),values.getOrDefault("--original-run",""));
                case "plan"->store.plan(key);
                case "approve"->store.approve(key,required(values,"--hash"),required(values,"--operator"));
                case "apply"->store.apply(key,required(values,"--hash"));
                case "finish"->store.finish(key,required(values,"--hash"));
                case "abort"->store.abort(key,required(values,"--hash"),required(values,"--operator"));
                case "release"->store.release(key,required(values,"--hash"),required(values,"--generation"),required(values,"--operator"));
                default->store.read(key);
            };
            // 不输出玩家账号、原件payload、连接串或秘密；每次只推进一批，操作者可据永久阶段续跑。
            System.out.printf("run=%s state=%d phase=%d cursor=%s items=%s hash=%s generation=%s%n",result.getRunId(),result.getState(),result.getPhase(),Long.toUnsignedString(result.getCursorId()),Long.toUnsignedString(result.getItemCount()),HexFormat.of().formatHex(result.getPlanHash().toByteArray()),result.getGeneration());
        }
    }
    private static String required(java.util.Map<String,String> values,String key){String value=values.get(key);if(value==null||value.isBlank())throw new IllegalArgumentException("缺少必要参数 "+key);return value;}
    private static int zone(String value){long n=Long.parseUnsignedLong(value);if(n==0||n>0xffff_ffffL)throw new IllegalArgumentException("区号必须为uint32非零");return (int)n;}
}
