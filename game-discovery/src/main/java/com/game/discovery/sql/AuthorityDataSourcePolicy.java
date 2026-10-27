package com.game.discovery.sql;

import com.game.common.RunMode;
import com.game.common.sql.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.support.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** 所有SQL池在init之前安装physical adapter；内部BPP最后执行，消费并清空已审init SQL。 */
@AutoConfiguration
public class AuthorityDataSourcePolicy {
    /** 非Spring工具在首次借连接之前调用；不改变Druid公开连接/holder/proxy类型。 */
    public static void protectDruid(com.alibaba.druid.pool.DruidDataSource pool,WriterAuthority boot,boolean required)throws SQLException{
        try{Installer installer=new Installer(null,null);installer.authority=Objects.requireNonNull(boot);installer.druid(pool,"normal-cli",required);}
        catch(ReflectiveOperationException invalid){throw new SQLException("CLI physical封口安装失败",invalid);}
    }
    @Bean static Installer normalWriterDataSourcePolicy(Environment environment,org.springframework.beans.factory.ObjectProvider<WriterAuthority> boots){return new Installer(environment,boots);}
    @Bean @org.springframework.context.annotation.Lazy
    WriterAuthority normalWriterBoot()throws java.io.IOException{return WriterAuthority.consumeEnvironment();}
    static final class Installer implements MergedBeanDefinitionPostProcessor,DestructionAwareBeanPostProcessor,Ordered {
        private final Environment environment;
        private final Set<Object> installed=Collections.newSetFromMap(new IdentityHashMap<>());
        private WriterAuthority authority;
        private final org.springframework.beans.factory.ObjectProvider<WriterAuthority> boots;
        Installer(Environment environment,org.springframework.beans.factory.ObjectProvider<WriterAuthority> boots){this.environment=environment;this.boots=boots;}
        synchronized WriterAuthority boot(){if(authority==null)authority=boots.getObject();return authority;}
        @Override public synchronized void postProcessBeforeDestruction(Object bean,String name){if(bean instanceof DataSource&&installed.contains(bean)&&authority!=null)authority.seal();}
        @Override public int getOrder(){return Ordered.LOWEST_PRECEDENCE;}
        @Override public void postProcessMergedBeanDefinition(RootBeanDefinition definition,Class<?> type,String name){}
        @Override public synchronized Object postProcessBeforeInitialization(Object bean,String name){
            if(!(bean instanceof DataSource)||installed.contains(bean))return bean;
            try{
                boolean required=environment.getProperty("game.sql-authority.require-tidb",Boolean.class,true);
                if(!required&&RunMode.parse(environment.getProperty("game.run-mode","prod"))==RunMode.PROD)
                    throw new IllegalStateException("生产normal连接不能关闭TiDB强读");
                boot();
                if(is(bean,"com.alibaba.druid.pool.DruidDataSource"))druid(bean,name,required);
                else if(is(bean,"com.zaxxer.hikari.HikariDataSource"))hikari(bean,required);
                else throw new IllegalStateException("未知DataSource不能证明physical接缝："+name);
                installed.add(bean);return bean;
            }catch(ReflectiveOperationException|SQLException failure){
                throw new IllegalStateException("normal DataSource启动原件或physical安装失败："+name,failure);
            }
        }
        private static boolean is(Object bean,String name){
            try{return Class.forName(name,false,bean.getClass().getClassLoader()).isInstance(bean);}
            catch(ClassNotFoundException absent){return false;}
        }
        @SuppressWarnings("unchecked")
        private void druid(Object bean,String name,boolean required)throws ReflectiveOperationException,SQLException{
            if((Boolean)get(bean,"isInited"))throw new IllegalStateException("Druid已连接，不能晚装writer gate");
            // 调用原policy安装原filter与固定SESSION_SQL，源码保持不变；随后一次消费完整等集。
            new StrongTiDbConnectionPolicy("normal-writer",required).postProcessBeforeInitialization(bean,name);
            Collection<String> configured=(Collection<String>)get(bean,"getConnectionInitSqls");
            List<String> init=AuthorityDataSource.checkedInitialization(configured==null?List.of():configured);
            String url=(String)get(bean,"getUrl"), driverClass=(String)get(bean,"getDriverClassName");
            AuthorityDataSource.validateUrl(url);
            Driver actual=(Driver)get(bean,"getDriver");
            if(actual==null){
                if(driverClass==null||driverClass.isBlank())driverClass=url.startsWith("jdbc:h2:")?"org.h2.Driver":"com.mysql.cj.jdbc.Driver";
                actual=(Driver)Class.forName(driverClass).getDeclaredConstructor().newInstance();
            }
            bean.getClass().getMethod("setDriver",Driver.class).invoke(bean,new DruidAuthorityDriver(actual,authority,required,init));
            bean.getClass().getMethod("setConnectionInitSqls",Collection.class).invoke(bean,List.of());
        }
        private void hikari(Object bean,boolean required)throws ReflectiveOperationException,SQLException{
            if(get(bean,"getHikariPoolMXBean")!=null)throw new IllegalStateException("Hikari已启动，必须以lazy构造后安装physical adapter");
            if(get(bean,"getDataSource")!=null)throw new IllegalStateException("Hikari已有未知physical DataSource");
            String url=(String)get(bean,"getJdbcUrl");
            Properties credentials=new Properties();credentials.putAll((Properties)get(bean,"getDataSourceProperties"));
            String user=(String)get(bean,"getUsername"), password=(String)get(bean,"getPassword");
            if(user!=null)credentials.setProperty("user",user);if(password!=null)credentials.setProperty("password",password);
            String initialization=(String)get(bean,"getConnectionInitSql");
            List<String> init=initialization==null?List.of():List.of(initialization);
            AuthorityDataSource physical=new AuthorityDataSource(url,credentials,authority,required,init);
            bean.getClass().getMethod("setDataSource",DataSource.class).invoke(bean,physical);
            bean.getClass().getMethod("setConnectionInitSql",String.class).invoke(bean,new Object[]{null});
        }
        private static Object get(Object target,String method)throws ReflectiveOperationException{
            try{return target.getClass().getMethod(method).invoke(target);}
            catch(InvocationTargetException failure){
                if(failure.getCause() instanceof RuntimeException runtime)throw runtime;throw failure;
            }
        }
    }
}

