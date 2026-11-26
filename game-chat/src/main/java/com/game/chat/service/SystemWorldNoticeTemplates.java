package com.game.chat.service;

import com.game.proto.chat.PublishSystemWorldNoticeRequest;
import com.game.proto.chat.SystemWorldNoticeTemplate;

/**
 * 天墉生死状一期系统世界频道白名单模板渲染器（与 Go {@code chat/internal/authority/system_notice.go} 严格逐字一致）。
 *
 * <p>安全与过审红线：绝不接受客户端自由文本；所有展示名清除控制字符并限长；文案仅使用合规仙侠术语，零暴力/辱骂/外链。
 */
public final class SystemWorldNoticeTemplates {

    private static final int MAX_NAME_CODEPOINTS = 24;

    private SystemWorldNoticeTemplates() {
    }

    public static String sanitizeActorName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "无名道友";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        int count = 0;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || cp == '\u200B' || cp == '\uFEFF') {
                continue;
            }
            sb.appendCodePoint(cp);
            if (++count >= MAX_NAME_CODEPOINTS) {
                break;
            }
        }
        String trimmed = sb.toString().trim();
        return trimmed.isEmpty() ? "无名道友" : trimmed;
    }

    /** 按严格白名单枚举渲染世界公告正文；非法枚举返回 null。 */
    public static String render(PublishSystemWorldNoticeRequest req) {
        if (req == null || req.getNoticeId().isBlank()) {
            return null;
        }
        String a = sanitizeActorName(req.getActorAName());
        String b = sanitizeActorName(req.getActorBName());
        int teamSize = req.getTeamSize() <= 0 ? 1 : req.getTeamSize();
        if (teamSize > 5) {
            return null;
        }
        int rounds = Math.max(1, req.getTotalRounds());
        int streak = Math.max(0, req.getStreakCount());
        SystemWorldNoticeTemplate tmpl = req.getTemplateId();
        return switch (tmpl) {
            case SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_START_SILVER -> teamSize <= 1
                    ? String.format("【天墉生死擂】%s 向 %s 下达江湖战书，点击观战！", a, b)
                    : String.format("【天墉生死擂】%s 向 %s 下达江湖战书（%dV%d），点击观战！", a, b, teamSize, teamSize);
            case SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_START_GOLD -> teamSize <= 1
                    ? String.format("【金帖生死状】%s 向 %s 掷出金帖生死状，名动天墉，点击观战！", a, b)
                    : String.format("【金帖生死状】%s 向 %s 掷出金帖生死状（%dV%d），名动天墉，点击观战！", a, b, teamSize, teamSize);
            case SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_WIN_SILVER ->
                    String.format("【天墉生死擂】%s 历经 %d 回合击败 %s，略胜一筹！", a, rounds, b);
            case SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_WIN_GOLD ->
                    String.format("【金帖生死状】%s 历经 %d 回合大破 %s，快意恩仇！", a, rounds, b);
            case SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_DRAW ->
                    String.format("【天墉生死擂】%s 与 %s 大战 %d 回合难分胜负，各自收剑！", a, b, rounds);
            case SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_STREAK_MILESTONE -> streak < 3 ? null
                    : String.format("【宿敌连胜】%s 再度力克宿敌 %s，豪取 %d 连胜！", a, b, streak);
            case SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_STREAK_BROKEN -> streak < 3 ? null
                    : String.format("【快意雪耻】%s 终结了宿敌 %s 的 %d 连胜，扬眉吐气！", a, b, streak);
            default -> null;
        };
    }
}
