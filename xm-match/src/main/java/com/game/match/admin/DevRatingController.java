package com.game.match.admin;

import com.game.common.RunMode;
import com.game.match.rating.RatingStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * dev / test 专用的读评分接口 {@code GET /admin/match/dev/rating/{pid}}（match-spec §9.10，Java 独有；robot 的 battle-smoke / match-5v5 用它断言
 * 评分变化）。挂在管理端口（缺省 18113，只绑本机）；令牌与操作人的鉴权、每次调用一行审计在 {@code /admin/*} 的过滤器里，本类只管自己这一层：
 * <ol>
 *   <li>运行模式不是 dev / test → <b>403</b>（先于解析路径参数、不碰库；过滤器之外的第二道闸，prod 下接口照样注册、只回 403）；</li>
 *   <li>{@code pid} 不是 1 – 2^64−1 的无符号十进制 → 400；</li>
 *   <li>读库失败 → 500（<b>不</b>回落 1500：robot 拿它做断言，回落会把库故障读成「评分没变」）；</li>
 *   <li>其余 200，JSON {@code {"player_id":"…","rating":"1516.00","games":1}}：uint64 的玩家号输出为十进制<b>字符串</b>（JSON 数字过不了 2^53），
 *       评分是两位小数的字符串，没有行的玩家是 {@code "1500.00"} / 0。</li>
 * </ol>
 * 在 Tomcat 线程上直接读库（平台线程；只在 dev / test 生效、低频）。
 */
@RestController
public class DevRatingController {

    /** 路径前缀（过滤器按它归类指标的 op 标签）。 */
    public static final String PATH_PREFIX = "/admin/match/dev/rating";
    public static final String PATH = PATH_PREFIX + "/{pid}";

    private static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
    private static final Logger log = LoggerFactory.getLogger(DevRatingController.class);

    private final RatingStore store;
    private final RunMode runMode;

    public DevRatingController(RatingStore store, RunMode runMode) {
        this.store = store;
        this.runMode = runMode;
    }

    @GetMapping(PATH)
    public ResponseEntity<Object> rating(@PathVariable("pid") String pid) {
        if (runMode != RunMode.DEV && runMode != RunMode.TEST) {
            return text(HttpStatus.FORBIDDEN, "读评分接口只在运行模式 dev / test 下开放（当前 " + runMode + "）");
        }
        long playerId = parsePlayerId(pid);
        if (playerId == 0) {
            return text(HttpStatus.BAD_REQUEST, "pid 必须是 1 – 18446744073709551615 的无符号十进制");
        }
        RatingStore.Rating row;
        try {
            row = store.findOrFresh(playerId);
        } catch (RatingStore.StoreException e) {
            log.warn("[rating] dev 读评分失败 player={}: {}", Long.toUnsignedString(playerId), String.valueOf(e.getCause()));
            return text(HttpStatus.INTERNAL_SERVER_ERROR, "读评分失败（数据库故障）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("player_id", Long.toUnsignedString(row.playerId()));
        body.put("rating", formatRating(row.ratingCenti()));
        body.put("games", row.games());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** centi → 两位小数的十进制串（151600 → {@code "1516.00"}）。 */
    static String formatRating(long ratingCenti) {
        return BigDecimal.valueOf(ratingCenti, 2).toPlainString();
    }

    /** 无符号十进制 → uint64 的位模式；不合法（空、带符号、非数字、前导零、越界）或为 0 时返回 0。 */
    static long parsePlayerId(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 20 || raw.charAt(0) == '0') {
            return 0;
        }
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch < '0' || ch > '9') {
                return 0;
            }
        }
        try {
            return Long.parseUnsignedLong(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static ResponseEntity<Object> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(TEXT).body(message);
    }
}
