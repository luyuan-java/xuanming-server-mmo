package com.game.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.chat.PublishSystemWorldNoticeRequest;
import com.game.proto.chat.SystemWorldNoticeTemplate;
import org.junit.jupiter.api.Test;

/** 天墉生死状一期系统世界播报模板与合规校验单测。 */
class SystemWorldNoticeTest {

    @Test
    void rendersAllSevenWhitelistTemplatesAndSanitizesControlChars() {
        for (SystemWorldNoticeTemplate tmpl : new SystemWorldNoticeTemplate[] {
                SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_START_SILVER,
                SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_START_GOLD,
                SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_WIN_SILVER,
                SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_WIN_GOLD,
                SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_DRAW,
                SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_STREAK_MILESTONE,
                SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_STREAK_BROKEN
        }) {
            String rendered = SystemWorldNoticeTemplates.render(PublishSystemWorldNoticeRequest.newBuilder()
                    .setNoticeId("jh_duel:1:" + tmpl.getNumber())
                    .setTemplateId(tmpl)
                    .setBattleId(1001L)
                    .setActorAName("清风\n\r\t道人")
                    .setActorBName("墨渊\u200B剑修")
                    .setTotalRounds(8)
                    .setStreakCount(3)
                    .setTeamSize(3)
                    .build());
            assertThat(rendered).isNotBlank();
            assertThat(rendered).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
            assertThat(rendered).contains("清风道人").contains("墨渊剑修");
            assertThat(rendered).doesNotContain("杀人").doesNotContain("强杀").doesNotContain("监狱").doesNotContain("认怂");
        }
    }

    @Test
    void rejectsUnspecifiedTemplateOrBlankNoticeId() {
        assertThat(SystemWorldNoticeTemplates.render(PublishSystemWorldNoticeRequest.newBuilder()
                .setNoticeId("n1")
                .setTemplateId(SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_TEMPLATE_UNSPECIFIED)
                .build())).isNull();
        assertThat(SystemWorldNoticeTemplates.render(PublishSystemWorldNoticeRequest.newBuilder()
                .setNoticeId("   ")
                .setTemplateId(SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_START_SILVER)
                .build())).isNull();
        assertThat(SystemWorldNoticeTemplates.render(PublishSystemWorldNoticeRequest.newBuilder()
                .setNoticeId("n2")
                .setTemplateId(SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_STREAK_MILESTONE)
                .setActorAName("清风")
                .setActorBName("墨渊")
                .setStreakCount(2)
                .build())).isNull();
    }
}
