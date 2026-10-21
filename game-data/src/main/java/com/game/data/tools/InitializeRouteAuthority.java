package com.game.data.tools;

import com.game.common.player.RouteAuthorityInitializer;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import java.sql.DriverManager;
import java.util.ArrayList;

/** 停写维护窗口的显式初始化工具；不在服务启动调用，不读取Redis，不自动生成世代或判断SQL恢复真实性。 */
public final class InitializeRouteAuthority {
    private InitializeRouteAuthority(){}
    public static void main(String[] args)throws Exception{
        if(args.length!=7||!args[0].equals("--initialize-from-sql-player")||!args[1].equals("--generation")||!args[3].equals("--zones")||!args[5].equals("--expect-database"))throw new IllegalArgumentException("需要 --initialize-from-sql-player --generation <独立记录的非零UUID> --zones <逗号分隔uint32区号> --expect-database <真实业务库>");
        var zones=new ArrayList<Integer>();for(String value:args[4].split(",",-1)){long zone=Long.parseUnsignedLong(value);if(zone<=0||zone>0xffff_ffffL)throw new IllegalArgumentException("配置zone越界");zones.add((int)zone);}
        String url=System.getenv("GAME_ROUTE_INIT_JDBC"),user=System.getenv("GAME_MYSQL_USER"),password=System.getenv().getOrDefault("GAME_MYSQL_PASSWORD","");
        if(url==null||url.isBlank()||user==null||user.isBlank()||!args[6].matches("[a-zA-Z0-9_]{1,64}"))throw new IllegalArgumentException("必须显式提供JDBC、账号环境及预期数据库；密码只读环境，不打印连接信息");
        try(var connection=DriverManager.getConnection(url,user,password)){
            try(var statement=connection.createStatement()){statement.setQueryTimeout(3);statement.execute(StrongTiDbConnectionPolicy.SESSION_SQL);}StrongTiDbConnectionPolicy.verify(connection,true);
            var namespace=com.game.discovery.namespace.GameNamespaceBinder.bind(System.getProperties(),System.getenv()).namespace();
            String mismatch=com.game.common.NamespaceDatabaseCheck.verify(connection,args[6],namespace,"--expect-database <真实业务库>");if(mismatch!=null)throw new IllegalStateException(mismatch);
            RouteAuthorityInitializer.initialize(connection,args[2],zones);
        }
        System.out.println("SQL路由原件初始化完成；未执行合区、撤销合区或历史恢复真实性认证");
    }
}
