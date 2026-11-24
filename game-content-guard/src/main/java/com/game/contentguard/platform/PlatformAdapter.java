package com.game.contentguard.platform;

import com.game.proto.content_guard.TextCategory;
import com.game.proto.content_guard.TextScene;
import java.time.Duration;

/**
 * 外部平台内容审核策略接口（GoF Strategy 模式：微信 msgSecCheck v2、抖音 antidirt 或商业第三方审核各成实现）。
 */
public interface PlatformAdapter {

    String PROVIDER_WECHAT = "wechat";
    String PROVIDER_DOUYIN = "douyin";

    enum Outcome {
        SKIP,
        PASS,
        REJECT,
        UNAVAILABLE,
        PENDING
    }

    record ReviewResult(Outcome outcome, TextCategory category, String provider) {
        public static ReviewResult skip() {
            return new ReviewResult(Outcome.SKIP, TextCategory.TEXT_CATEGORY_UNSPECIFIED, "");
        }

        public static ReviewResult skip(String provider) {
            return new ReviewResult(Outcome.SKIP, TextCategory.TEXT_CATEGORY_UNSPECIFIED, provider);
        }

        public static ReviewResult pass(String provider) {
            return new ReviewResult(Outcome.PASS, TextCategory.TEXT_CATEGORY_UNSPECIFIED, provider);
        }

        public static ReviewResult reject(TextCategory category, String provider) {
            return new ReviewResult(Outcome.REJECT, category == null ? TextCategory.TEXT_CATEGORY_OTHER : category, provider);
        }

        public static ReviewResult unavailable(String provider) {
            return new ReviewResult(Outcome.UNAVAILABLE, TextCategory.TEXT_CATEGORY_UNSPECIFIED, provider);
        }

        public static ReviewResult pending(String provider) {
            return new ReviewResult(Outcome.PENDING, TextCategory.TEXT_CATEGORY_UNSPECIFIED, provider);
        }
    }

    String provider();

    ReviewResult check(TextScene scene, String openId, String text, Duration timeout);
}
