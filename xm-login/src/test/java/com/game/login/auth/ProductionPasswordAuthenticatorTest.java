package com.game.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.player.store.AccountPassword;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 生产口令认证：规范账号、时序 dummy、坏哈希、库故障、KDF 并发槽。KDF 用假实现（真 Argon2 另有一条往返用例）。 */
class ProductionPasswordAuthenticatorTest {

    private static final String DUMMY = "$dummy";

    private final Map<String, AccountPassword> rows = new HashMap<>();
    private final List<String> verified = new ArrayList<>();

    /** 假 KDF：哈希串是 "$fake$" + 口令；DUMMY 恒不匹配；其余串当格式坏。 */
    private boolean fakeVerify(String password, String encoded) {
        synchronized (verified) {
            verified.add(encoded);
        }
        if (encoded.equals(DUMMY)) {
            return false;
        }
        if (!encoded.startsWith("$fake$")) {
            throw new IllegalArgumentException("不支持的口令哈希格式");
        }
        return encoded.equals("$fake$" + password);
    }

    private ProductionPasswordAuthenticator auth() {
        return new ProductionPasswordAuthenticator(a -> Optional.ofNullable(rows.get(a)), 2, Duration.ofMillis(500),
                this::fakeVerify, DUMMY);
    }

    @Test
    void 口令对_返回库里的规范账号() {
        rows.put("robot_0001", new AccountPassword("Robot_0001", "$fake$secret"));
        assertThat(auth().authenticate("robot_0001", "secret")).as("不回显输入").contains("Robot_0001");
        assertThat(verified).containsExactly("$fake$secret");
        rows.put("robot_0002", new AccountPassword(null, "$fake$secret"));
        assertThat(auth().authenticate("robot_0002", "secret")).as("库里账号空按失败").isEmpty();
    }

    @Test
    void 口令认证里的线程到上限_再来的不等槽立即失败() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        rows.put("robot_0001", new AccountPassword("robot_0001", "$fake$secret"));
        ProductionPasswordAuthenticator auth = new ProductionPasswordAuthenticator(a -> Optional.ofNullable(rows.get(a)),
                1, Duration.ofSeconds(5), (password, encoded) -> {
                    inside.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return encoded.equals("$fake$" + password);
                }, DUMMY);
        // 1 个在算、2 个在等槽：门（3 × 1）满
        List<CompletableFuture<Optional<String>>> holders = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(ProductionPasswordAuthenticator.ADMISSION_PER_SLOT);
        for (int i = 0; i < ProductionPasswordAuthenticator.ADMISSION_PER_SLOT; i++) {
            holders.add(CompletableFuture.supplyAsync(() -> auth.authenticate("robot_0001", "secret"), pool));
            if (i == 0) {
                assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            }
        }
        Thread.sleep(200);
        long start = System.nanoTime();
        assertThat(auth.authenticate("robot_0001", "secret")).as("门满").isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("不等槽").isLessThan(Duration.ofMillis(200));
        release.countDown();
        for (CompletableFuture<Optional<String>> holder : holders) {
            assertThat(holder.get(10, TimeUnit.SECONDS)).contains("robot_0001");
        }
        pool.shutdownNow();
    }

    @Test
    void 口令错_账号不存在_没有哈希_都失败且都跑一次KDF() {
        rows.put("robot_0001", new AccountPassword("robot_0001", "$fake$secret"));
        rows.put("robot_0002", new AccountPassword("robot_0002", null));
        ProductionPasswordAuthenticator auth = auth();
        assertThat(auth.authenticate("robot_0001", "wrong")).isEmpty();
        assertThat(auth.authenticate("robot_9999", "secret")).isEmpty();
        assertThat(auth.authenticate("robot_0002", "secret")).isEmpty();
        assertThat(verified).containsExactly("$fake$secret", DUMMY, DUMMY);
    }

    @Test
    void 不合法的输入也跑一次dummy() {
        ProductionPasswordAuthenticator auth = auth();
        assertThat(auth.authenticate("", "secret")).isEmpty();
        assertThat(auth.authenticate(" robot_0001", "secret")).isEmpty();
        assertThat(auth.authenticate("a".repeat(65), "secret")).isEmpty();
        assertThat(auth.authenticate("robot_0001", "")).isEmpty();
        assertThat(auth.authenticate("robot_0001", "x".repeat(1025))).isEmpty();
        assertThat(auth.authenticate("bad\uD800", "secret")).as("孤立代理对").isEmpty();
        assertThat(verified).hasSize(6).containsOnly(DUMMY);
    }

    @Test
    void 库里的哈希坏了_失败并补一次dummy() {
        rows.put("robot_0001", new AccountPassword("robot_0001", "$argon2id$garbage"));
        assertThat(auth().authenticate("robot_0001", "secret")).isEmpty();
        assertThat(verified).containsExactly("$argon2id$garbage", DUMMY);
    }

    @Test
    void 库故障_按认证失败() {
        ProductionPasswordAuthenticator auth = new ProductionPasswordAuthenticator(a -> {
            throw new IllegalStateException("db down");
        }, 2, Duration.ofMillis(500), this::fakeVerify, DUMMY);
        assertThat(auth.authenticate("robot_0001", "secret")).isEmpty();
    }

    @Test
    void KDF并发槽占满_等不到槽按失败() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        rows.put("robot_0001", new AccountPassword("robot_0001", "$fake$secret"));
        ProductionPasswordAuthenticator auth = new ProductionPasswordAuthenticator(a -> Optional.ofNullable(rows.get(a)),
                1, Duration.ofMillis(100), (password, encoded) -> {
                    inside.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return encoded.equals("$fake$" + password);
                }, DUMMY);
        CompletableFuture<Optional<String>> holder = CompletableFuture.supplyAsync(
                () -> auth.authenticate("robot_0001", "secret"));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        long start = System.nanoTime();
        assertThat(auth.authenticate("robot_0001", "secret")).as("槽被占着").isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(90));
        release.countDown();
        assertThat(holder.get(5, TimeUnit.SECONDS)).contains("robot_0001");
    }

    @Test
    void 并发槽与等槽上限越界拒绝构造() {
        assertThatThrownBy(() -> new ProductionPasswordAuthenticator(a -> Optional.empty(), 0, Duration.ofMillis(500),
                this::fakeVerify, DUMMY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductionPasswordAuthenticator(a -> Optional.empty(), 9, Duration.ofMillis(500),
                this::fakeVerify, DUMMY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductionPasswordAuthenticator(a -> Optional.empty(), 2, Duration.ZERO,
                this::fakeVerify, DUMMY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductionPasswordAuthenticator(a -> Optional.empty(), 2, Duration.ofSeconds(6),
                this::fakeVerify, DUMMY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 真Argon2往返() {
        String encoded = Argon2id.hash("correct horse", new SecureRandom());
        rows.put("dev_0001", new AccountPassword("dev_0001", encoded));
        ProductionPasswordAuthenticator real = new ProductionPasswordAuthenticator(a -> Optional.ofNullable(rows.get(a)),
                2, Duration.ofMillis(500));
        assertThat(real.authenticate("dev_0001", "correct horse")).contains("dev_0001");
        assertThat(real.authenticate("dev_0001", "correct horsE")).isEmpty();
    }
}
