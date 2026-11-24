package com.game.contentguard.service;

import com.game.api.ContentGuardService;
import com.game.api.DubboGroups;
import com.game.contentguard.ContentGuardProperties;
import com.game.contentguard.engine.ContentGuardEngine;
import com.game.contentguard.engine.ContentGuardEngine.CheckResult;
import com.game.contentguard.engine.ContentGuardEngine.SceneClass;
import com.game.contentguard.platform.PlatformAdapter.Outcome;
import com.game.contentguard.platform.PlatformAdapter.ReviewResult;
import com.game.contentguard.platform.PlatformReviewer;
import com.game.contentguard.wordlist.WordCategory;
import com.game.contract.generated.service.ContentGuardHandler;
import com.game.proto.content_guard.CheckTextRequest;
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextCategory;
import com.game.proto.content_guard.TextScene;
import com.game.proto.content_guard.TextVerdict;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 内容安全服务 Dubbo 入口（同时实现编译期生成的 {@link ContentGuardHandler}）。
 *
 * <p>被检文字绝不写入日志、异常消息或指标 label。
 */
@DubboService(group = DubboGroups.CONTENT_GUARD)
public final class ContentGuardRpcService implements ContentGuardService, ContentGuardHandler<Void> {

    private record SceneMapping(SceneClass sceneClass, String metricLabel) {
    }

    private final ContentGuardEngine engine;
    private final PlatformReviewer reviewer;
    private final boolean requireLexicon;
    private final int maxTextBytes;
    private final MeterRegistry meters;

    @Autowired
    public ContentGuardRpcService(
            ContentGuardEngine engine,
            PlatformReviewer reviewer,
            ContentGuardProperties props,
            MeterRegistry meters) {
        this(engine, reviewer, props.requireLexicon(), props.maxTextBytes(), meters);
    }

    public ContentGuardRpcService(
            ContentGuardEngine engine,
            PlatformReviewer reviewer,
            boolean requireLexicon,
            int maxTextBytes,
            MeterRegistry meters) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.reviewer = reviewer;
        this.requireLexicon = requireLexicon;
        this.maxTextBytes = maxTextBytes;
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    public boolean isReady() {
        return !requireLexicon || engine.current().version() != 0L;
    }

    @Override
    public CheckTextResponse checkText(Void context, CheckTextRequest request) {
        return checkText(request);
    }

    @Override
    public CheckTextResponse checkPlatformText(Void context, CheckTextRequest request) {
        return checkPlatformText(request);
    }

    @Override
    public CheckTextResponse checkText(CheckTextRequest request) {
        return evaluate(request, false);
    }

    @Override
    public CheckTextResponse checkPlatformText(CheckTextRequest request) {
        return evaluate(request, true);
    }

    private CheckTextResponse evaluate(CheckTextRequest request, boolean syncPlatformOnly) {
        Objects.requireNonNull(request, "request");
        Timer.Sample sample = Timer.start(meters);
        TextScene scene = request.getScene();
        SceneMapping mapping = mapScene(scene);
        if (mapping == null) {
            observe(sample, "unknown", "invalid", "none");
            throw new IllegalArgumentException("unknown text scene: " + scene);
        }
        String text = request.getText();
        int utf8Bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes > maxTextBytes) {
            observe(sample, mapping.metricLabel(), "invalid", "none");
            throw new IllegalArgumentException("text exceeds max bytes: " + utf8Bytes + " > " + maxTextBytes);
        }
        if (!isReady()) {
            observe(sample, mapping.metricLabel(), "unavailable", "none");
            throw new IllegalStateException("content guard lexicon not loaded");
        }

        CheckResult local = engine.check(mapping.sceneClass(), text);
        CheckTextResponse.Builder out = CheckTextResponse.newBuilder()
                .setCategory(local.category().proto())
                .setLexiconVersion(local.lexiconVersion());
        String verdictLabel;
        switch (local.verdict()) {
            case PASS -> {
                out.setVerdict(TextVerdict.TEXT_VERDICT_PASS);
                verdictLabel = "pass";
            }
            case MASKED -> {
                out.setVerdict(TextVerdict.TEXT_VERDICT_MASKED).setMaskedText(local.maskedText());
                verdictLabel = "masked";
            }
            default -> {
                out.setVerdict(TextVerdict.TEXT_VERDICT_REJECT);
                verdictLabel = "reject";
            }
        }

        if (out.getVerdict() != TextVerdict.TEXT_VERDICT_REJECT && reviewer != null && reviewer.hasAdapters()) {
            ReviewResult ext = syncPlatformOnly
                    ? reviewer.reviewSync(scene, request.getPlayerId(), request.getAccount(), text)
                    : reviewer.review(scene, request.getPlayerId(), request.getAccount(), text, request.getAsyncPlatform());
            if (ext.outcome() == Outcome.REJECT) {
                out.setVerdict(TextVerdict.TEXT_VERDICT_REJECT).clearMaskedText();
                if (ext.category() != TextCategory.TEXT_CATEGORY_UNSPECIFIED) {
                    out.setCategory(ext.category());
                }
                verdictLabel = "reject";
            } else if (ext.outcome() == Outcome.PENDING) {
                out.setPendingPlatformReview(true);
            } else if (ext.outcome() == Outcome.UNAVAILABLE) {
                observe(sample, mapping.metricLabel(), "unavailable", "none");
                throw new IllegalStateException("external content reviewer unavailable");
            }
        }

        observe(sample, mapping.metricLabel(), verdictLabel, WordCategory.fromProto(out.getCategory()).label());
        return out.build();
    }

    static SceneMapping mapScene(TextScene scene) {
        if (scene == null) {
            return null;
        }
        return switch (scene) {
            case TEXT_SCENE_ROLE_NAME -> new SceneMapping(SceneClass.NAME, "role_name");
            case TEXT_SCENE_GUILD_NAME -> new SceneMapping(SceneClass.NAME, "guild_name");
            case TEXT_SCENE_PET_NAME -> new SceneMapping(SceneClass.NAME, "pet_name");
            case TEXT_SCENE_TRADE_TITLE -> new SceneMapping(SceneClass.NAME, "trade_title");
            case TEXT_SCENE_PRIVATE_LABEL -> new SceneMapping(SceneClass.PRIVATE_LABEL, "private_label");
            case TEXT_SCENE_GUILD_ANNOUNCEMENT -> new SceneMapping(SceneClass.NOTICE, "guild_announcement");
            case TEXT_SCENE_CHAT_WORLD -> new SceneMapping(SceneClass.CHAT, "chat_world");
            case TEXT_SCENE_CHAT_TEAM -> new SceneMapping(SceneClass.CHAT, "chat_team");
            case TEXT_SCENE_CHAT_PRIVATE -> new SceneMapping(SceneClass.CHAT, "chat_private");
            case TEXT_SCENE_UNSPECIFIED, UNRECOGNIZED -> null;
        };
    }

    private void observe(Timer.Sample sample, String scene, String verdict, String category) {
        sample.stop(Timer.builder("game.content.guard.checks")
                .tag("scene", scene)
                .tag("verdict", verdict)
                .tag("category", category)
                .register(meters));
    }
}
