package com.game.contentguard.engine;

import com.game.contentguard.contact.ContactDetector;
import com.game.contentguard.textnorm.TextNorm;
import com.game.contentguard.textnorm.TextNorm.FoldedText;
import com.game.contentguard.wordlist.Lexicon;
import com.game.contentguard.wordlist.WordCategory;
import com.game.contentguard.wordlist.WordMatcher;
import com.game.contentguard.wordlist.WordMatcher.CompileReport;
import com.game.contentguard.wordlist.WordMatcher.Compiled;
import com.game.contentguard.wordlist.WordMatcher.Hit;
import com.game.contentguard.wordlist.WordMatcher.OrigSpan;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地内容安全判定引擎（对齐 Go {@code content_guard/internal/engine}）。
 *
 * <p>采用不可变快照 + {@link AtomicReference} 无锁热切换：新词库在调用线程完成编译后一次性原子替换，
 * 在途判定继续持有旧快照，由 JVM GC 自动回收，零锁竞争、无半份词库可见性问题。
 */
public final class ContentGuardEngine {

    /** 场景动作大类。 */
    public enum SceneClass {
        /** 名字（角色/帮会/宠物/寄售标题）：任意敏感词与联系方式广告均拒绝。 */
        NAME,
        /** 公告（帮会公告）：忽略冒充官方类，其余敏感词与引流广告均拒绝。 */
        NOTICE,
        /** 聊天（世界/队伍/私聊）：忽略冒充官方类，辱骂打码放行，其余敏感词与引流广告拒绝。 */
        CHAT,
        /** 仅填写者本人可见的私有标签：走词库拒绝规则，不做联系方式广告启发式拦截。 */
        PRIVATE_LABEL
    }

    public enum Verdict {
        PASS,
        MASKED,
        REJECT
    }

    private enum Action {
        IGNORE,
        MASK,
        REJECT
    }

    public record CheckResult(Verdict verdict, String maskedText, WordCategory category, long lexiconVersion) {
    }

    public record Snapshot(long version, WordMatcher matcher, CompileReport report, Instant loadedAt) {
    }

    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    /** 构造只带内置防冒充词的引擎（版本 0）。 */
    public ContentGuardEngine() {
        Compiled compiled = WordMatcher.compile(null);
        current.set(new Snapshot(0L, compiled.matcher(), compiled.report(), Instant.now()));
    }

    /** 编译并原子切换词库快照；{@code version} 必须非 0（0 保留给仅内置词）。 */
    public CompileReport swap(long version, Lexicon lexicon) {
        if (version == 0L) {
            throw new IllegalArgumentException("词库版本号不能为 0");
        }
        Compiled compiled = WordMatcher.compile(lexicon);
        current.set(new Snapshot(version, compiled.matcher(), compiled.report(), Instant.now()));
        return compiled.report();
    }

    public Snapshot current() {
        return current.get();
    }

    public CheckResult check(SceneClass sceneClass, String text) {
        Snapshot snapshot = current.get();
        if (text == null || text.isEmpty()) {
            return new CheckResult(Verdict.PASS, "", WordCategory.UNSPECIFIED, snapshot.version());
        }
        FoldedText folded = TextNorm.fold(text);
        List<Hit> hits = snapshot.matcher().find(folded);
        boolean masked = false;
        WordCategory firstMaskCategory = WordCategory.UNSPECIFIED;
        int[] orig = null;
        for (Hit hit : hits) {
            Action action = actionFor(sceneClass, hit.category());
            if (action == Action.REJECT) {
                return new CheckResult(Verdict.REJECT, "", hit.category(), snapshot.version());
            }
            if (action == Action.MASK) {
                if (!masked) {
                    masked = true;
                    firstMaskCategory = hit.category();
                    orig = folded.copyOrigCodePoints();
                }
                maskSpan(orig, hit.startOrig(), hit.endOrig());
            }
        }
        if (sceneClass != SceneClass.PRIVATE_LABEL) {
            List<OrigSpan> allowSpans = snapshot.matcher().allowOrigSpans(folded);
            if (ContactDetector.containsAd(text, folded,
                    (startOrig, endOrig) -> WordMatcher.origCovered(allowSpans, startOrig, endOrig))) {
                return new CheckResult(Verdict.REJECT, "", WordCategory.AD, snapshot.version());
            }
        }
        if (masked) {
            return new CheckResult(Verdict.MASKED, new String(orig, 0, orig.length), firstMaskCategory, snapshot.version());
        }
        return new CheckResult(Verdict.PASS, "", WordCategory.UNSPECIFIED, snapshot.version());
    }

    private static Action actionFor(SceneClass sceneClass, WordCategory category) {
        if (sceneClass == SceneClass.CHAT) {
            if (category == WordCategory.IMPERSONATION) {
                return Action.IGNORE;
            }
            if (category == WordCategory.ABUSE) {
                return Action.MASK;
            }
            return Action.REJECT;
        }
        if (sceneClass == SceneClass.NOTICE) {
            return category == WordCategory.IMPERSONATION ? Action.IGNORE : Action.REJECT;
        }
        // NAME、PRIVATE_LABEL 及任何未知大类：fail-closed 一律拒绝
        return Action.REJECT;
    }

    private static void maskSpan(int[] orig, int startOrig, int endOrig) {
        int last = endOrig;
        while (last + 1 < orig.length && TextNorm.isMark(orig[last + 1])) {
            last++;
        }
        for (int i = startOrig; i <= last && i < orig.length; i++) {
            orig[i] = '*';
        }
    }
}
