package com.game.data.admin;

import com.game.data.gainblock.GainBlockStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 运维：全服产出封禁（紧急止血——名单上的币种 / 物品所有人都不得再获得，基线 GainBlockService 的全服名单）。鉴权见
 * {@link AdminAuthFilter}；封禁与解封都必填原因，每次改动另记一行运维审计 {@code xm.audit.admin}（带原因）。
 * 名单在 Redis，改动后全部 scene 节点秒级生效（变更通知 + 周期重读）。Redis 不可用时回 503。
 */
@RestController
public class GainBlockController {

    public static final String PATH = "/admin/gain-blocks";
    static final int MAX_REASON = 256;

    private static final Logger log = LoggerFactory.getLogger(GainBlockController.class);
    private static final Logger audit = LoggerFactory.getLogger(AdminAuthFilter.AUDIT_LOGGER);

    private final GainBlockStore store;
    private final Clock clock;

    public GainBlockController(GainBlockStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** 全部封禁，按类别：{@code {"currency":[{"id":1,"operator":"..","timeMs":..,"reason":".."}],"item":[...]}}。 */
    @GetMapping(PATH)
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String category : GainBlockStore.CATEGORIES) {
            out.put(category, redis(() -> store.list(category)).stream().map(GainBlockController::view).toList());
        }
        return out;
    }

    /**
     * 封禁（幂等；再封覆盖原因与操作人）。{@code category} 是 currency（币种号）或 item（物品配置号）；
     * {@code reason} 必填，1–256 字符、不含控制字符。
     */
    @PutMapping(PATH + "/{category}/{id}")
    public Map<String, Object> block(@PathVariable("category") String category, @PathVariable("id") String id,
                                     @RequestParam("reason") String reason, HttpServletRequest request) {
        String checkedCategory = checkCategory(category);
        int value = parseId(id);
        checkReason(reason);
        String operator = AdminAuthFilter.operator(request.getHeader(AdminAuthFilter.OPERATOR_HEADER));
        GainBlockStore.Entry entry = redis(() -> store.block(checkedCategory, value, operator, clock.millis(), reason));
        audit.warn("gain-block block category={} id={} operator={} reason={}", checkedCategory, value, operator, reason);
        return view(entry);
    }

    /** 解除封禁（幂等）：{@code {"removed":true|false}}。参数同封禁，原因必填。 */
    @DeleteMapping(PATH + "/{category}/{id}")
    public Map<String, Object> unblock(@PathVariable("category") String category, @PathVariable("id") String id,
                                       @RequestParam("reason") String reason, HttpServletRequest request) {
        String checkedCategory = checkCategory(category);
        int value = parseId(id);
        checkReason(reason);
        boolean removed = redis(() -> store.unblock(checkedCategory, value));
        audit.warn("gain-block unblock category={} id={} operator={} removed={} reason={}", checkedCategory, value,
                AdminAuthFilter.operator(request.getHeader(AdminAuthFilter.OPERATOR_HEADER)), removed, reason);
        return Map.of("removed", removed);
    }

    private static String checkCategory(String category) {
        if (!GainBlockStore.CATEGORIES.contains(category)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "类别只有 " + GainBlockStore.CATEGORIES);
        }
        return category;
    }

    private static void checkReason(String reason) {
        if (reason.isBlank() || reason.codePointCount(0, reason.length()) > MAX_REASON
                || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason 必填，1–" + MAX_REASON + " 字符、不含控制字符");
        }
    }

    private static int parseId(String id) {
        try {
            int value = Integer.parseInt(id);
            if (value >= 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // 落到下面
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 必须是 0–2147483647 的十进制整数");
    }

    /**
     * Redis 侧的失败（连不上、超时、懒创建客户端失败）记 WARN 并回 503；别的异常照常抛出（500，容器记堆栈），
     * 免得把程序错误伪装成 Redis 故障。
     */
    private static <T> T redis(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RedisException | BeanCreationException e) {
            log.warn("全服产出封禁：Redis 调用失败", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Redis 不可用", e);
        }
    }

    private static Map<String, Object> view(GainBlockStore.Entry e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.id());
        out.put("operator", e.operator());
        out.put("timeMs", e.timeMs());
        out.put("reason", e.reason());
        return out;
    }
}
