package com.game.data.admin;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.store.WhitelistRow;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 运维：区服白名单（同 mmorpg AdminWhitelistController）。{@code GET /admin/whitelist/{zoneId}}；
 * {@code POST /admin/whitelist {zone_id, account, note?}} 幂等加入（备注以最后一次为准），缺字段 400；
 * {@code DELETE /admin/whitelist/{zoneId}/{account}} 恒 204。按 Java 版的字符串账号存（基线 account_id BIGINT 对不上账号体系）。
 *
 * <p>同基线：<b>准入不读它</b>——预告区一律 503 {@code zone_not_open}。assign-gate 的 account 未经认证，按它放行等于谁都能进；
 * 要做预告区内测，得在认证之后的环节消费它。
 */
@RestController
public class WhitelistAdminController {

    public static final String PATH = "/admin/whitelist";

    private static final Logger audit = LoggerFactory.getLogger(AdminAuthFilter.AUDIT_LOGGER);

    private final GatewayStore store;

    public WhitelistAdminController(GatewayStore store) {
        this.store = store;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WhitelistBody(Long zoneId, String account, String note) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WhitelistView(int zoneId, String account, String note) {

        static WhitelistView of(WhitelistRow w) {
            return new WhitelistView(w.zoneId(), w.account(), w.note());
        }
    }

    @GetMapping(PATH + "/{zoneId}")
    public List<WhitelistView> list(@PathVariable("zoneId") int zoneId) {
        return store.whitelist(zoneId).stream().map(WhitelistView::of).toList();
    }

    @PostMapping(PATH)
    public WhitelistView add(@RequestBody WhitelistBody body, HttpServletRequest request) {
        if (body.zoneId() == null || body.account() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "zone_id 与 account 必填");
        }
        if (body.zoneId() <= 0 || body.zoneId() > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "zone_id 须在 1–2147483647");
        }
        WhitelistRow stored = ZoneAdminController.checked(
                () -> store.addWhitelist(body.zoneId().intValue(), body.account(), body.note()));
        audit.warn("whitelist add zone_id={} account={} operator={}", stored.zoneId(), stored.account(),
                ZoneAdminController.operator(request));
        return WhitelistView.of(stored);
    }

    @DeleteMapping(PATH + "/{zoneId}/{account}")
    public ResponseEntity<Void> remove(@PathVariable("zoneId") int zoneId, @PathVariable("account") String account,
                                       HttpServletRequest request) {
        ZoneAdminController.checked(() -> {
            store.removeWhitelist(zoneId, account);
            return null;
        });
        audit.warn("whitelist remove zone_id={} account={} operator={}", zoneId, account,
                ZoneAdminController.operator(request));
        return ResponseEntity.noContent().build();
    }
}
