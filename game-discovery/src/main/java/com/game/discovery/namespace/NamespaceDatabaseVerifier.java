package com.game.discovery.namespace;

import com.game.common.GameNamespace;
import com.game.common.NamespaceDatabaseCheck;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 连库的服务进程在启动期核对库名（与三个命令行工具同一套 {@link NamespaceDatabaseCheck}）：每个 {@link DataSource} bean 初始化完成、交给任何使用者
 * （{@code spring.sql.init} 建表、pbmysql 同步、MyBatis、业务存储）之前，取一条连接执行 {@code SELECT DATABASE()}，必须等于部署命名空间；
 * 不一致抛 {@link IllegalStateException} 拒启（由 Spring Boot 的缺省流程报告），一行表都不建、一行数据都不写。
 *
 * <p>防的是「命名空间与库分属两个部署」：例如照抄了别的部署的整串 JDBC URL，{@code GAME_NAMESPACE=t1} 的进程会拿 t1 的 Redis 与 Kafka 去配 game 的库，
 * player 行、{@code owner_epoch} 围栏、运维表都写进另一个部署。规格是库名恒等于命名空间、部署只覆盖 host / port（deploy-ci-spec §2.2）；库名偏离规格的
 * 存量部署用应急开关 {@value #EXPECT_DATABASE_PROPERTY}（环境变量 {@code GAME_MYSQL_EXPECT_DATABASE}，规范写法 {@code GAME_MYSQL_EXPECTDATABASE} 同样认）
 * 显式给出期望库名，放行时打 WARN。开关由 {@link GameMysqlProperties}（{@code @ConfigurationProperties} + {@code @Validated}）绑定校验：给了空值由
 * Spring Boot 的标准绑定校验报告拒启，本类拿到的要么是 null（没配）、要么是不含空白的库名。
 *
 * <p>只核 MySQL 系（数据库产品名含 MySQL / MariaDB）：别的库（测试用的 H2）记 DEBUG 跳过。连不上库照样拒启（与 Druid 的 {@code initial-size}
 * 同口径：这些进程本来就在启动期连库），报错只带 SQLState 与错误码（驱动的消息里可能带连接串）。由 {@link GameNamespaceAutoConfiguration}
 * 以静态 bean 登记；没有 DataSource 的进程不受影响。
 */
public class NamespaceDatabaseVerifier implements BeanPostProcessor {

    /** 应急开关的配置键（{@link GameMysqlProperties#expectDatabase()}）：库名偏离规格（≠ 命名空间）的存量部署显式给出期望库名。 */
    public static final String EXPECT_DATABASE_PROPERTY = "game.mysql.expect-database";
    /** 写进报错的应急开关写法。 */
    static final String OVERRIDE_HINT = EXPECT_DATABASE_PROPERTY + "（环境变量 GAME_MYSQL_EXPECT_DATABASE）";

    private static final Logger log = LoggerFactory.getLogger(NamespaceDatabaseVerifier.class);

    private final ObjectProvider<GameNamespace> namespace;
    private final ObjectProvider<GameMysqlProperties> mysql;

    /**
     * 两个参数都延迟取：本类是 BeanPostProcessor，要先于普通 bean 实例化。
     *
     * @param namespace 部署命名空间 bean（DataSource 经 depends-on 保证它已建好）
     * @param mysql     应急开关（{@link GameMysqlProperties}，第一次取时绑定校验）
     */
    public NamespaceDatabaseVerifier(ObjectProvider<GameNamespace> namespace, ObjectProvider<GameMysqlProperties> mysql) {
        this.namespace = namespace;
        this.mysql = mysql;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource) {
            verify(dataSource, beanName);
        }
        return bean;
    }

    /** 核对一个 DataSource；不一致或连不上抛 {@link IllegalStateException}（应急开关给了空值时，取开关就已经以绑定校验失败拒启）。 */
    void verify(DataSource dataSource, String beanName) {
        GameNamespace ns = namespace.getObject();
        String expected = mysql.getObject().expectDatabase();
        String product;
        String actual;
        String mismatch;
        try (Connection connection = dataSource.getConnection()) {
            if (connection == null) {
                throw new IllegalStateException("DataSource " + beanName + " 的库名核对拒启：getConnection() 返回 null");
            }
            product = connection.getMetaData().getDatabaseProductName();
            if (!isMysqlFamily(product)) {
                log.debug("库名核对跳过：DataSource {} 的数据库产品是 {}（只核 MySQL 系）", beanName, product);
                return;
            }
            actual = NamespaceDatabaseCheck.currentDatabase(connection);
            mismatch = NamespaceDatabaseCheck.mismatch(actual, expected, ns, OVERRIDE_HINT);
        } catch (SQLException e) {
            // 只报 SQLState / 错误码：驱动的消息可能带连接串
            throw new IllegalStateException("DataSource " + beanName + " 的库名核对连不上库（SQLState=" + e.getSQLState() + " code=" + e.getErrorCode()
                    + "），拒绝启动：确认库名之前不建表、不写库");
        }
        if (mismatch != null) {
            throw new IllegalStateException("DataSource " + beanName + " 的" + mismatch);
        }
        if (expected != null && !expected.equals(ns.value())) {
            log.warn("库名核对经应急开关放行：DataSource {} 的 DATABASE()={}，部署命名空间 {}={}（{} 偏离规格「库名 = 命名空间」）", beanName, actual,
                    GameNamespace.PROPERTY, ns, EXPECT_DATABASE_PROPERTY);
        } else {
            log.info("库名核对通过：DataSource {} 的 DATABASE()={}（部署命名空间 {}={}）", beanName, actual, GameNamespace.PROPERTY, ns);
        }
    }

    /** MySQL 系：产品名含 MySQL 或 MariaDB（大小写不敏感）。 */
    static boolean isMysqlFamily(String product) {
        if (product == null) {
            return false;
        }
        String p = product.toLowerCase(Locale.ROOT);
        return p.contains("mysql") || p.contains("mariadb");
    }
}
