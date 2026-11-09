package com.game.scene;

import com.game.common.RunMode;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 仅为隔离开发环境消费冻结节庆导航；默认不开，生产模式显式开启也拒绝。 */
@ConfigurationProperties("game.scene.festival-test")
public record FestivalTestProperties(@DefaultValue("false") boolean enabled,
                                     @DefaultValue("") String navigationRoot,
                                     @DefaultValue("") String currentNavigationRoot) {
    public Path festivalRoot(RunMode runMode) {
        if (!enabled) return null;
        if (runMode == null || !runMode.allowsGmCommands()) {
            throw new IllegalStateException("节庆联调只允许 game.run-mode=dev/test");
        }
        if (navigationRoot == null || navigationRoot.isBlank()) {
            throw new IllegalArgumentException("节庆联调必须配置 game.scene.festival-test.navigation-root");
        }
        return Path.of(navigationRoot).toAbsolutePath().normalize();
    }

    public Path currentRoot(Path defaultRoot) {
        if (!enabled || currentNavigationRoot == null || currentNavigationRoot.isBlank()) return defaultRoot;
        return Path.of(currentNavigationRoot).toAbsolutePath().normalize();
    }
}
