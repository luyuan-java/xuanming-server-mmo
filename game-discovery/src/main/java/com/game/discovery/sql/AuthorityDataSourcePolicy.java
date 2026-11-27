package com.game.discovery.sql;

import com.game.common.RunMode;
import com.game.common.sql.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.support.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** 所有SQL池在init之前安装physical adapter；内部BPP最后执行，消费并清空已审init SQL。 */
@AutoConfiguration
public class AuthorityDataSourcePolicy {
    /** 对齐主仓 {@code writer_gate_seconds} 的 12 档指数桶（0.2ms ～ 409.6ms）。 */
    static final Duration[] GATE_BUCKETS = {
            Duration.ofNanos(200_000), Duration.ofNanos(400_000), Duration.ofNanos(800_000),
            Duration.ofNanos(1_600_000), Duration.ofNanos(3_200_000), Duration.ofNanos(6_400_000),
            Duration.ofNanos(12_800_000), Duration.ofNanos(25_600_000), Duration.ofNanos(51_200_000),
            Duration.ofNanos(102_400_000), Duration.ofNanos(204_800_000), Duration.ofNanos(409_600_000)
    };

    /** 将 {@link WriterAuthority} 写者闸门阶段耗时绑定到 Micrometer（Prometheus 导出为 {@code writer_gate_seconds}）。 */
    public static WriterAuthority.GateObserver micrometerGateObserver(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Map<String, Timer> timers = new ConcurrentHashMap<>();
        return (logicalDb, phase, durationNanos) -> {
            String key = logicalDb + '\0' + phase;
            Timer timer = timers.computeIfAbsent(key, ignored -> Timer.builder("writer.gate")
                    .description("写者闸门各阶段耗时（before_begin / enter / read / pin / revalidate）")
                    .tag("logical_db", logicalDb)
                    .tag("phase", phase)
                    .serviceLevelObjectives(GATE_BUCKETS)
                    .register(registry));
            timer.record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
        };
    }

    /** 非Spring工具在首次借连接之前调用；签名只暴露JDBC类型，避免装配反射提前链接可选Druid；真实池与holder/proxy类型保持不变。 */
    public static void protectDruid(DataSource pool,WriterAuthority boot,boolean required)throws SQLException{
        if(pool==null||!Installer.is(pool,"com.alibaba.druid.pool.DruidDataSource"))throw new SQLException("CLI physical封口仅支持真实Druid池");
        try{Installer installer=new Installer(null,null);installer.authority=Objects.requireNonNull(boot);installer.druid(pool,"normal-cli",required);}
        catch(ReflectiveOperationException invalid){throw new SQLException("CLI physical封口安装失败",invalid);}
    }
    @Bean static Installer normalWriterDataSourcePolicy(Environment environment,ObjectProvider<WriterAuthority> boots){return new Installer(environment,boots);}
    @Bean Object writerGateMetricsBinder(ObjectProvider<MeterRegistry> meters) {
        MeterRegistry registry = meters.getIfAvailable();
        if (registry != null) {
            WriterAuthority.setGateObserver(micrometerGateObserver(registry));
        }
        return new Object();
    }
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
            // J2：把 Druid 空闲驱逐与探活周期收紧到秒级，TiDB 重启后无需等待缺省 60s 才摘掉陈旧物理连接
            bean.getClass().getMethod("setTimeBetweenEvictionRunsMillis",long.class).invoke(bean,5_000L);
            bean.getClass().getMethod("setMinEvictableIdleTimeMillis",long.class).invoke(bean,30_000L);
            bean.getClass().getMethod("setTestWhileIdle",boolean.class).invoke(bean,true);
            bean.getClass().getMethod("setValidationQuery",String.class).invoke(bean,"SELECT 1");
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

