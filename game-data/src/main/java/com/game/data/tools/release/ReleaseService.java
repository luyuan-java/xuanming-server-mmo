package com.game.data.tools.release;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** 发布单位是 Java Boot 服务；robot 是验收工具，不计入常驻服务完整性。 */
public enum ReleaseService {
    SCENE_MANAGER("game-scene-manager"), LOGIN("game-login"), FRIEND("game-friend"),
    CHAT("game-chat"), MAIL("game-mail"), RANK("game-rank"), MATCH("game-match"),
    TEAM("game-team"), GUILD("game-guild"), MALL("game-mall"), TRADE("game-trade"),
    DATA("game-data"), INBOX("game-inbox"), SCENE("game-scene"), BATTLE("game-battle"),
    GATE("game-gate"), GATEWAY("game-gateway");

    static final String ROBOT = "game-robot";
    private final String module;
    ReleaseService(String module) { this.module = module; }
    public String module() { return module; }
    boolean usesSql() { return this != GATE && this != BATTLE; }
    static Set<String> modules() {
        return Arrays.stream(values()).map(ReleaseService::module).collect(Collectors.toCollection(TreeSet::new));
    }
    static Set<String> sqlModules() {
        return Arrays.stream(values()).filter(ReleaseService::usesSql).map(ReleaseService::module)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
