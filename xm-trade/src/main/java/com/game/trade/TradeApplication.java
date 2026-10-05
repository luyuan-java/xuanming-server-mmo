package com.game.trade;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * xm-trade 进程入口（批次 4.7「聚宝斋只读面」，T1）：对外提供 Dubbo 服务 {@code ClientMessageService}（group {@code trade}，Triple，缺省端口 20887），
 * gate 把 {@code trade.ClientPlayerJubaozhai} 的 4 个消息号（浏览 196 / 详情 197 / 收藏 198 / 货架 200）转到这里；管理端口（缺省 18111，只绑本机）上
 * 另有 actuator 与 dev 播种接口 {@code POST /admin/trade/seed-listing}。全服一份、可多副本、进程无状态（权威数据在 MySQL {@code xm_java} 的
 * {@code trade_listing} / {@code trade_favorite}，经 xm-pbmysql 建表；只读 {@code xm_java.player.zone_id} 作归属区；Redis 只用于发号租约与热关停规则）。
 *
 * <p>启动即接客前提（trade-spec §5.10，照基线 trade.go:127-291；任何一步失败进程都起不来）：校验 {@code xm.trade.*}（scope 必填且合法、页长、预算）与
 * {@code XM_DUBBO_SECRET} → xm-pbmysql 建表 / 只扩不缩地同步两张表 → 数据库版本与会话参数检查 → 申领 listing_id 雪花租约（{@code NodeTypes.TRADE}，
 * 作用域 0；失败拒启，Q5 / T13）→ 启动横幅 → 暴露 Dubbo。关闭时 Dubbo 先注销并等在途调用，再排空工作线程池，最后释放租约、关 Redis 与连接池。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDubbo(scanBasePackages = "com.game.trade")
public class TradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeApplication.class, args);
    }
}
