package com.game.contentguard.wordlist;

import com.game.contentguard.textnorm.TextNorm;
import com.game.contentguard.textnorm.TextNorm.FoldedText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 压平数组字典树匹配器（对齐 Go {@code wordlist.Matcher}，编译后完全不可变、多线程无锁安全）。
 *
 * <p>节点出边按码点升序存于原始数组 {@code int[] first / labels / next}，避免每节点一个 {@code HashMap} 带来的对象与 GC 压力。
 */
public final class WordMatcher {

    /** 内置防冒充官方底线词（不依赖外部词库、永远生效）。 */
    static final List<Lexicon.Entry> BUILTIN_DENY = List.of(
            new Lexicon.Entry("管理员", WordCategory.IMPERSONATION, MatchMode.SUB),
            new Lexicon.Entry("客服", WordCategory.IMPERSONATION, MatchMode.SUB),
            new Lexicon.Entry("官方", WordCategory.IMPERSONATION, MatchMode.SUB),
            new Lexicon.Entry("系统", WordCategory.IMPERSONATION, MatchMode.SUB),
            new Lexicon.Entry("运营", WordCategory.IMPERSONATION, MatchMode.SUB),
            new Lexicon.Entry("gm", WordCategory.IMPERSONATION, MatchMode.PREFIX));

    public record CompileReport(int denyWords, int allowWords, int dropped, int duplicates) {
    }

    public record Compiled(WordMatcher matcher, CompileReport report) {
    }

    /** 命中区间（原文码点闭区间 {@code [startOrig, endOrig]}）。 */
    public record Hit(int startOrig, int endOrig, WordCategory category) {
    }

    /** 原文码点闭区间（供外围联系方式检测做白名单覆盖豁免）。 */
    public record OrigSpan(int startOrig, int endOrig) {
    }

    private record FoldSpan(int start, int end) {
    }

    private final FlatTrie deny;
    private final FlatTrie allow;

    private WordMatcher(FlatTrie deny, FlatTrie allow) {
        this.deny = deny;
        this.allow = allow;
    }

    /** 编译词库；{@code lex} 为 null 时仅包含内置防冒充词。 */
    public static Compiled compile(Lexicon lex) {
        BuildNode denyRoot = new BuildNode();
        BuildNode allowRoot = new BuildNode();
        int[] stats = new int[4]; // denyWords, allowWords, dropped, duplicates

        for (Lexicon.Entry e : BUILTIN_DENY) {
            if (insert(denyRoot, e.word(), e.mode(), e.category(), stats)) {
                stats[0]++;
            }
        }
        if (lex != null) {
            for (Lexicon.Entry e : lex.deny()) {
                if (insert(denyRoot, e.word(), e.mode(), e.category(), stats)) {
                    stats[0]++;
                }
            }
            for (String word : lex.allow()) {
                if (insert(allowRoot, word, MatchMode.SUB, WordCategory.OTHER, stats)) {
                    stats[1]++;
                }
            }
        }
        return new Compiled(
                new WordMatcher(FlatTrie.flatten(denyRoot), FlatTrie.flatten(allowRoot)),
                new CompileReport(stats[0], stats[1], stats[2], stats[3]));
    }

    private static boolean insert(BuildNode root, String word, MatchMode mode, WordCategory category, int[] stats) {
        if (mode == null || category == null || category == WordCategory.UNSPECIFIED) {
            stats[2]++;
            return false;
        }
        int[] folded = TextNorm.foldWord(word);
        if (folded.length == 0) {
            stats[2]++;
            return false;
        }
        BuildNode node = root;
        for (int cp : folded) {
            node = node.child(cp);
        }
        int idx = mode.index();
        if (node.term[idx] != 0) {
            stats[3]++;
            return false;
        }
        node.term[idx] = (byte) category.code();
        return true;
    }

    /** 返回文本中全部未被白名单完全覆盖的命中，按起点升序。 */
    public List<Hit> find(FoldedText elems) {
        int firstReal = -1;
        for (int i = 0; i < elems.length(); i++) {
            if (!elems.skipAt(i)) {
                firstReal = i;
                break;
            }
        }
        if (firstReal < 0) {
            return List.of();
        }
        List<FoldSpan> allowed = allowSpans(elems);
        List<Hit> hits = new ArrayList<>();
        int[] reported = new int[MatchMode.COUNT];
        for (int start = firstReal; start < elems.length(); start++) {
            if (elems.skipAt(start)) {
                continue;
            }
            int s = start;
            int fr = firstReal;
            deny.walk(elems, start, (end, term, contiguous) -> {
                if (covered(allowed, s, end)) {
                    return;
                }
                int count = 0;
                int sub = term[MatchMode.SUB.index()];
                if (sub != 0) {
                    reported[count++] = sub;
                    hits.add(new Hit(elems.origAt(s), elems.origAt(end), WordCategory.fromCode(sub)));
                }
                int prefix = term[MatchMode.PREFIX.index()];
                if (prefix != 0 && s == fr && !containsCode(reported, count, prefix)) {
                    reported[count++] = prefix;
                    hits.add(new Hit(elems.origAt(s), elems.origAt(end), WordCategory.fromCode(prefix)));
                }
                int token = term[MatchMode.TOKEN.index()];
                if (token != 0 && contiguous && tokenBounded(elems, s, end) && !containsCode(reported, count, token)) {
                    hits.add(new Hit(elems.origAt(s), elems.origAt(end), WordCategory.fromCode(token)));
                }
            });
        }
        return hits;
    }

    public List<OrigSpan> allowOrigSpans(FoldedText elems) {
        if (elems.length() == 0) {
            return List.of();
        }
        List<FoldSpan> spans = allowSpans(elems);
        if (spans.isEmpty()) {
            return List.of();
        }
        List<OrigSpan> out = new ArrayList<>(spans.size());
        for (FoldSpan s : spans) {
            out.add(new OrigSpan(elems.origAt(s.start()), elems.origAt(s.end())));
        }
        return out;
    }

    public static boolean origCovered(List<OrigSpan> spans, int startOrig, int endOrig) {
        for (OrigSpan s : spans) {
            if (s.startOrig() <= startOrig && endOrig <= s.endOrig()) {
                return true;
            }
        }
        return false;
    }

    private List<FoldSpan> allowSpans(FoldedText elems) {
        if (allow.labels.length == 0) {
            return List.of();
        }
        List<FoldSpan> spans = new ArrayList<>();
        for (int start = 0; start < elems.length(); start++) {
            if (elems.skipAt(start)) {
                continue;
            }
            int s = start;
            allow.walk(elems, start, (end, term, contiguous) -> spans.add(new FoldSpan(s, end)));
        }
        return spans;
    }

    private static boolean covered(List<FoldSpan> spans, int start, int end) {
        for (FoldSpan s : spans) {
            if (s.start() <= start && end <= s.end()) {
                return true;
            }
        }
        return false;
    }

    private static boolean tokenBounded(FoldedText elems, int start, int end) {
        if (start > 0 && TextNorm.isAsciiAlnum(elems.codePointAt(start - 1))) {
            return false;
        }
        return end + 1 >= elems.length() || !TextNorm.isAsciiAlnum(elems.codePointAt(end + 1));
    }

    private static boolean containsCode(int[] arr, int count, int code) {
        for (int i = 0; i < count; i++) {
            if (arr[i] == code) {
                return true;
            }
        }
        return false;
    }

    private static final class BuildNode {
        Map<Integer, BuildNode> children;
        final byte[] term = new byte[MatchMode.COUNT];

        BuildNode child(int cp) {
            if (children == null) {
                children = new HashMap<>(4);
            }
            return children.computeIfAbsent(cp, ignored -> new BuildNode());
        }
    }

    @FunctionalInterface
    private interface WalkVisitor {
        void visit(int end, byte[] term, boolean contiguous);
    }

    private static final class FlatTrie {
        final int[] first;
        final int[] labels;
        final int[] next;
        final byte[][] term;
        final boolean[] hasTerm;

        FlatTrie(int[] first, int[] labels, int[] next, byte[][] term, boolean[] hasTerm) {
            this.first = first;
            this.labels = labels;
            this.next = next;
            this.term = term;
            this.hasTerm = hasTerm;
        }

        static FlatTrie flatten(BuildNode root) {
            List<BuildNode> queue = new ArrayList<>();
            queue.add(root);
            int totalEdges = 0;
            for (int i = 0; i < queue.size(); i++) {
                BuildNode n = queue.get(i);
                if (n.children != null) {
                    totalEdges += n.children.size();
                    int[] keys = new int[n.children.size()];
                    int k = 0;
                    for (Integer cp : n.children.keySet()) {
                        keys[k++] = cp;
                    }
                    Arrays.sort(keys);
                    for (int cp : keys) {
                        queue.add(n.children.get(cp));
                    }
                }
            }
            int nodeCount = queue.size();
            int[] first = new int[nodeCount + 1];
            int[] labels = new int[totalEdges];
            int[] next = new int[totalEdges];
            byte[][] term = new byte[nodeCount][];
            boolean[] hasTerm = new boolean[nodeCount];

            int edgePos = 0;
            int childId = 1;
            for (int i = 0; i < nodeCount; i++) {
                BuildNode n = queue.get(i);
                first[i] = edgePos;
                term[i] = n.term;
                hasTerm[i] = n.term[0] != 0 || n.term[1] != 0 || n.term[2] != 0;
                if (n.children != null && !n.children.isEmpty()) {
                    int[] keys = new int[n.children.size()];
                    int k = 0;
                    for (Integer cp : n.children.keySet()) {
                        keys[k++] = cp;
                    }
                    Arrays.sort(keys);
                    for (int cp : keys) {
                        labels[edgePos] = cp;
                        next[edgePos] = childId++;
                        edgePos++;
                    }
                }
            }
            first[nodeCount] = edgePos;
            return new FlatTrie(first, labels, next, term, hasTerm);
        }

        int child(int node, int cp) {
            int lo = first[node];
            int hi = first[node + 1];
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                int label = labels[mid];
                if (label == cp) {
                    return next[mid];
                }
                if (label < cp) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return -1;
        }

        void walk(FoldedText elems, int start, WalkVisitor visitor) {
            int node = 0;
            boolean contiguous = true;
            boolean skipped = false;
            for (int i = start; i < elems.length(); i++) {
                if (elems.skipAt(i)) {
                    skipped = true;
                    continue;
                }
                int nextNode = child(node, elems.codePointAt(i));
                if (nextNode < 0) {
                    return;
                }
                if (skipped) {
                    contiguous = false;
                }
                node = nextNode;
                if (hasTerm[node]) {
                    visitor.visit(i, term[node], contiguous);
                }
            }
        }
    }
}
