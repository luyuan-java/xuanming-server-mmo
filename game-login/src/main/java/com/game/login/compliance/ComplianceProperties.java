package com.game.login.compliance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 实名防沉迷与账号注销配置（{@code game.login.compliance.*}）。
 * 默认关闭防沉迷（便于本地开发切片与机器人回归），生产开启时强制校验 {@code NPPA_SECRET_KEY} 环境变量与日历。
 */
@ConfigurationProperties(prefix = "game.login.compliance")
public class ComplianceProperties {

    public static final String NPPA_SECRET_KEY_ENV = "NPPA_SECRET_KEY";

    private AntiAddictionProps antiAddiction = new AntiAddictionProps();
    private DeletionProps deletion = new DeletionProps();

    public AntiAddictionProps getAntiAddiction() {
        return antiAddiction;
    }

    public void setAntiAddiction(AntiAddictionProps antiAddiction) {
        this.antiAddiction = antiAddiction;
    }

    public DeletionProps getDeletion() {
        return deletion;
    }

    public void setDeletion(DeletionProps deletion) {
        this.deletion = deletion;
    }

    public static class AntiAddictionProps {
        private boolean enabled;
        private String nppaAppId = "";
        private String nppaBizId = "";
        private String nppaCheckUrl = "";
        private String nppaQueryUrl = "";
        private String nppaLoginoutUrl = "";
        private List<String> platformManagedAuthTypes = new ArrayList<>();
        private List<String> extraPlayDays = new ArrayList<>();
        private int dailySubmitLimit = 5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getNppaAppId() { return nppaAppId; }
        public void setNppaAppId(String nppaAppId) { this.nppaAppId = nppaAppId; }
        public String getNppaBizId() { return nppaBizId; }
        public void setNppaBizId(String nppaBizId) { this.nppaBizId = nppaBizId; }
        public String getNppaCheckUrl() { return nppaCheckUrl; }
        public void setNppaCheckUrl(String nppaCheckUrl) { this.nppaCheckUrl = nppaCheckUrl; }
        public String getNppaQueryUrl() { return nppaQueryUrl; }
        public void setNppaQueryUrl(String nppaQueryUrl) { this.nppaQueryUrl = nppaQueryUrl; }
        public String getNppaLoginoutUrl() { return nppaLoginoutUrl; }
        public void setNppaLoginoutUrl(String nppaLoginoutUrl) { this.nppaLoginoutUrl = nppaLoginoutUrl; }
        public List<String> getPlatformManagedAuthTypes() { return platformManagedAuthTypes; }
        public void setPlatformManagedAuthTypes(List<String> platformManagedAuthTypes) { this.platformManagedAuthTypes = platformManagedAuthTypes; }
        public List<String> getExtraPlayDays() { return extraPlayDays; }
        public void setExtraPlayDays(List<String> extraPlayDays) { this.extraPlayDays = extraPlayDays; }
        public int getDailySubmitLimit() { return dailySubmitLimit; }
        public void setDailySubmitLimit(int dailySubmitLimit) { this.dailySubmitLimit = dailySubmitLimit; }
    }

    public static class DeletionProps {
        private Duration coolingOff = Duration.ofDays(7);
        private boolean executorEnabled = true;

        public Duration getCoolingOff() { return coolingOff; }
        public void setCoolingOff(Duration coolingOff) { this.coolingOff = coolingOff; }
        public boolean isExecutorEnabled() { return executorEnabled; }
        public void setExecutorEnabled(boolean executorEnabled) { this.executorEnabled = executorEnabled; }
    }
}
