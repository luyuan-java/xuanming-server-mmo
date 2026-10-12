package com.game.match.matcher;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.MatchProperties;
import org.junit.jupiter.api.Test;

/**
 * 凑单的评分容差曲线（match-spec §2.8 的表逐格；基线 {@code TestRatingToleranceCurve}、{@code TestStarvedAnchorsMatchAfterMaxWait} 末段的纯函数断言）：
 * 曲线的每一档、封顶、终态兜底、曲线到顶的秒数、0 / 漏配取缺省值；以及换算成 centi 之后的比较。
 */
class ToleranceTest {

    private final Tolerance defaults = Tolerance.defaults();

    @Test
    void 缺省曲线逐档_100起每5秒加100封顶1000() {
        long[][] table = {
                {0, 100}, {4, 100},
                {5, 200}, {9, 200},
                {10, 300}, {14, 300},
                {15, 400}, {19, 400},
                {20, 500}, {24, 500},
                {25, 600}, {29, 600},
                {30, 700}, {34, 700},
                {35, 800}, {39, 800},
                {40, 900}, {44, 900},
                {45, 1000}, {46, 1000}, {89, 1000}, {3600, 1000}};

        for (long[] row : table) {
            assertThat(defaults.curvePoints(row[0])).as("已等 %d s", row[0]).isEqualTo(row[1]);
        }
    }

    @Test
    void 负的等待按0_再大的等待也只是封顶不溢出() {
        assertThat(defaults.curvePoints(-3)).isEqualTo(100);
        assertThat(defaults.curvePoints(Long.MIN_VALUE)).isEqualTo(100);
        assertThat(defaults.curvePoints(Long.MAX_VALUE)).isEqualTo(1000);
    }

    @Test
    void 配置覆盖生效_封顶值不在档上时先算后封顶() {
        Tolerance custom = new Tolerance(50, 10, 25, 120, 90);

        assertThat(custom.curvePoints(0)).isEqualTo(50);
        assertThat(custom.curvePoints(9)).isEqualTo(50);
        assertThat(custom.curvePoints(10)).isEqualTo(75);
        assertThat(custom.curvePoints(20)).isEqualTo(100);
        assertThat(custom.curvePoints(29)).isEqualTo(100);
        assertThat(custom.curvePoints(30)).as("50 + 3 × 25 = 125，超过上限 120").isEqualTo(120);
        assertThat(custom.curvePoints(100)).isEqualTo(120);
    }

    @Test
    void 上限不高于起点时_曲线恒为上限() {
        Tolerance flat = new Tolerance(500, 5, 100, 300, 90);

        assertThat(flat.curvePoints(0)).as("同基线：先算 base + …，再按 max 封顶").isEqualTo(300);
        assertThat(flat.curvePoints(60)).isEqualTo(300);
        assertThat(flat.saturationSeconds()).isZero();
        assertThat(new Tolerance(300, 5, 100, 300, 90).curvePoints(0)).isEqualTo(300);
    }

    @Test
    void 锚点容差_评分模式走曲线并换算成centi_等满兜底秒数后无穷大() {
        assertThat(defaults.anchorCenti(true, 0)).isEqualTo(10_000);
        assertThat(defaults.anchorCenti(true, 10)).isEqualTo(30_000);
        assertThat(defaults.anchorCenti(true, 45)).isEqualTo(100_000);
        assertThat(defaults.anchorCenti(true, 89)).as("差 1 秒到兜底：仍是曲线上限").isEqualTo(100_000);
        assertThat(defaults.anchorCenti(true, 90)).isEqualTo(Tolerance.UNBOUNDED);
        assertThat(defaults.anchorCenti(true, 21_600)).isEqualTo(Tolerance.UNBOUNDED);
        assertThat(defaults.maxWaitSeconds()).isEqualTo(90);
    }

    @Test
    void 锚点容差_非评分模式恒为无穷大() {
        assertThat(defaults.anchorCenti(false, 0)).isEqualTo(Tolerance.UNBOUNDED);
        assertThat(defaults.anchorCenti(false, 89)).isEqualTo(Tolerance.UNBOUNDED);
    }

    @Test
    void 兜底秒数可配_上限调小后分差超过上限的人只等到兜底秒数() {
        Tolerance tight = new Tolerance(100, 5, 100, 300, 20);

        assertThat(tight.anchorCenti(true, 12)).as("曲线 10 s 就到顶 300").isEqualTo(30_000);
        assertThat(tight.anchorCenti(true, 19)).isEqualTo(30_000);
        assertThat(tight.anchorCenti(true, 20)).isEqualTo(Tolerance.UNBOUNDED);
        assertThat(tight.maxWaitSeconds()).isEqualTo(20);
    }

    @Test
    void 曲线到顶的秒数_缺省45_档数向上取整() {
        assertThat(defaults.saturationSeconds()).as("(1000 − 100) / 100 × 5").isEqualTo(45);
        assertThat(new Tolerance(100, 5, 100, 300, 90).saturationSeconds()).as("(300 − 100) / 100 × 5").isEqualTo(10);
        assertThat(new Tolerance(100, 5, 400, 1000, 90).saturationSeconds()).as("900 / 400 向上取整是 3 档").isEqualTo(15);
        assertThat(new Tolerance(100, 7, 300, 1000, 90).saturationSeconds()).as("恰好整除：3 档 × 7 s").isEqualTo(21);
        // 到顶秒数那一刻曲线确实到顶，前一秒还没有
        Tolerance uneven = new Tolerance(100, 5, 400, 1000, 90);
        assertThat(uneven.curvePoints(14)).isEqualTo(900);
        assertThat(uneven.curvePoints(15)).isEqualTo(1000);
    }

    @Test
    void 零与负数取缺省值_与缺省曲线逐值相同() {
        Tolerance zeros = new Tolerance(0, 0, 0, 0, 0);
        Tolerance negatives = new Tolerance(-1, -5, -100, -1000, -90);

        for (long wait : new long[] {0, 4, 5, 44, 45, 89, 90}) {
            assertThat(zeros.curvePoints(wait)).as("已等 %d s", wait).isEqualTo(defaults.curvePoints(wait));
            assertThat(zeros.anchorCenti(true, wait)).isEqualTo(defaults.anchorCenti(true, wait));
            assertThat(negatives.anchorCenti(true, wait)).isEqualTo(defaults.anchorCenti(true, wait));
        }
        assertThat(zeros.curvePoints(0)).as("0 不是合法容差").isEqualTo(100);
        assertThat(zeros.saturationSeconds()).isEqualTo(45);
        assertThat(zeros.maxWaitSeconds()).as("终态兜底不能关闭").isEqualTo(90);
    }

    @Test
    void 按配置建_漏配与0都是缺省值_配了的生效() {
        Tolerance fromDefaults = Tolerance.of(new MatchProperties.Tolerance(null, null, null, null, null));
        Tolerance fromZeros = Tolerance.of(new MatchProperties.Tolerance(0, 0, 0, 0, 0));
        Tolerance configured = Tolerance.of(new MatchProperties.Tolerance(50, 10, 25, 120, 30));

        assertThat(fromDefaults.anchorCenti(true, 44)).isEqualTo(90_000);
        assertThat(fromDefaults.anchorCenti(true, 90)).isEqualTo(Tolerance.UNBOUNDED);
        assertThat(fromZeros.anchorCenti(true, 44)).isEqualTo(90_000);
        assertThat(fromZeros.saturationSeconds()).isEqualTo(45);
        assertThat(configured.anchorCenti(true, 10)).isEqualTo(7_500);
        assertThat(configured.anchorCenti(true, 29)).isEqualTo(10_000);
        assertThat(configured.anchorCenti(true, 30)).isEqualTo(Tolerance.UNBOUNDED);
    }

    @Test
    void 分差与容差的比较_含边界_无穷大恒真_离谱的分数不溢出() {
        assertThat(Tolerance.distance(150_000, 180_000)).isEqualTo(30_000);
        assertThat(Tolerance.distance(180_000, 150_000)).isEqualTo(30_000);
        assertThat(Tolerance.distance(150_000, 150_000)).isZero();
        assertThat(Tolerance.within(150_000, 160_000, 10_000)).as("恰好等于容差：接受").isTrue();
        assertThat(Tolerance.within(150_000, 160_001, 10_000)).isFalse();
        assertThat(Tolerance.within(160_001, 150_000, 10_000)).isFalse();
        assertThat(Tolerance.within(0, 99_999_999, Tolerance.UNBOUNDED)).isTrue();
        assertThat(Tolerance.distance(Long.MIN_VALUE, Long.MAX_VALUE)).as("被人为改坏的镜像分：分差饱和，不会算成负数").isEqualTo(Long.MAX_VALUE);
        assertThat(Tolerance.within(Long.MIN_VALUE, Long.MAX_VALUE, 100_000)).isFalse();
        assertThat(Tolerance.within(Long.MIN_VALUE, Long.MAX_VALUE, Tolerance.UNBOUNDED)).isTrue();
    }
}
