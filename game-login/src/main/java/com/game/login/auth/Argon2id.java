package com.game.login.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id 口令哈希（同 mmorpg {@code password_provider.go} 的 PHC 串格式与参数策略），纯函数、线程安全：
 * <ul>
 *   <li>格式 {@code $argon2id$v=19$m=<KiB>,t=<迭代>,p=<并行>$<salt>$<digest>}，salt / digest 是无填充的标准 Base64
 *       （与 Go {@code base64.RawStdEncoding} 逐字节相同，两版的哈希串可以互认）；</li>
 *   <li>生成用 m = 64 MiB、t = 3、p = 2、salt 16 字节、digest 32 字节；</li>
 *   <li>校验时参数有上下界（m ∈ [19 MiB, 256 MiB]、t ∈ [2, 10]、p ∈ [1, 16]、salt / digest 16–64 字节）：
 *       既拒绝弱哈希，也防止被污染的库记录诱导进程分配不受控的内存或跑很久；比较用常数时间。</li>
 * </ul>
 * 每次计算占 m KiB 内存，调用方必须限制并发（{@link ProductionPasswordAuthenticator}）。
 */
public final class Argon2id {

    static final int MEMORY_KIB = 64 * 1024;
    static final int ITERATIONS = 3;
    static final int PARALLELISM = 2;
    static final int SALT_BYTES = 16;
    static final int KEY_BYTES = 32;
    /** 口令字节上限（同基线 passwordMaxBytes）。 */
    public static final int PASSWORD_MAX_BYTES = 1024;

    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();

    private Argon2id() {
    }

    /** 解析出的参数与 salt / digest。 */
    record Params(int memoryKib, int iterations, int parallelism, byte[] salt, byte[] digest) {
    }

    /**
     * 生成可直接写进 {@code account.password_hash} 的 PHC 串（只给受控的管理工具用，登录本身不写口令）。
     *
     * @throws IllegalArgumentException 口令为空或超过 1024 字节
     */
    public static String hash(String password, SecureRandom random) {
        byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            throw new IllegalArgumentException("口令不能为空");
        }
        if (bytes.length > PASSWORD_MAX_BYTES) {
            throw new IllegalArgumentException("口令超过 " + PASSWORD_MAX_BYTES + " 字节");
        }
        return hash(bytes, MEMORY_KIB, ITERATIONS, PARALLELISM, SALT_BYTES, KEY_BYTES, random);
    }

    static String hash(byte[] password, int memoryKib, int iterations, int parallelism, int saltBytes, int keyBytes,
                       SecureRandom random) {
        byte[] salt = new byte[saltBytes];
        random.nextBytes(salt);
        byte[] digest = derive(password, salt, memoryKib, iterations, parallelism, keyBytes);
        return "$argon2id$v=19$m=" + memoryKib + ",t=" + iterations + ",p=" + parallelism + "$"
                + B64.encodeToString(salt) + "$" + B64.encodeToString(digest);
    }

    /**
     * 校验口令。
     *
     * @return true = 匹配
     * @throws IllegalArgumentException 哈希串格式不对或参数超出策略（调用方按认证失败处理）
     */
    public static boolean verify(String password, String encoded) {
        Params params = parse(encoded);
        byte[] actual = derive(password.getBytes(StandardCharsets.UTF_8), params.salt(), params.memoryKib(),
                params.iterations(), params.parallelism(), params.digest().length);
        return MessageDigest.isEqual(actual, params.digest());
    }

    static byte[] derive(byte[] password, byte[] salt, int memoryKib, int iterations, int parallelism, int keyBytes) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] out = new byte[keyBytes];
        generator.generateBytes(password, out);
        return out;
    }

    /** 同基线 parseArgon2id：结构、版本、三个参数各出现一次、上下界、salt / digest 长度。 */
    static Params parse(String encoded) {
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 6 || !parts[0].isEmpty() || !"argon2id".equals(parts[1]) || !"v=19".equals(parts[2])) {
            throw new IllegalArgumentException("不支持的口令哈希格式");
        }
        String[] fields = parts[3].split(",", -1);
        if (fields.length != 3) {
            throw new IllegalArgumentException("Argon2id 参数不对");
        }
        Map<String, Long> values = new HashMap<>();
        for (String field : fields) {
            int eq = field.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Argon2id 参数不对");
            }
            // 同 Go strconv.ParseUint(s, 10, 32)：只认 ASCII 数字（Long.parseLong 会接受正负号与其它文字的数字）
            String text = field.substring(eq + 1);
            if (text.isEmpty() || !text.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("Argon2id 参数值不对");
            }
            String significant = text.replaceFirst("^0+(?=.)", "");
            long value = significant.length() > 10 ? Long.MAX_VALUE : Long.parseLong(significant);
            if (value > 0xFFFF_FFFFL) {
                throw new IllegalArgumentException("Argon2id 参数值不对");
            }
            if (values.put(field.substring(0, eq), value) != null) {
                throw new IllegalArgumentException("Argon2id 参数重复");
            }
        }
        Long memory = values.get("m");
        Long iterations = values.get("t");
        Long parallelism = values.get("p");
        if (memory == null || iterations == null || parallelism == null) {
            throw new IllegalArgumentException("Argon2id 参数缺失");
        }
        if (memory < 19 * 1024 || memory > 256 * 1024 || iterations < 2 || iterations > 10 || parallelism < 1
                || parallelism > 16) {
            throw new IllegalArgumentException("Argon2id 参数超出策略");
        }
        byte[] salt = decode(parts[4], "salt");
        byte[] digest = decode(parts[5], "digest");
        return new Params(memory.intValue(), iterations.intValue(), parallelism.intValue(), salt, digest);
    }

    private static byte[] decode(String text, String what) {
        if (text.indexOf('=') >= 0) {
            // 同 Go RawStdEncoding：不接受填充
            throw new IllegalArgumentException("Argon2id " + what + " 不对");
        }
        byte[] bytes;
        try {
            bytes = B64_DECODER.decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Argon2id " + what + " 不对");
        }
        if (bytes.length < 16 || bytes.length > 64) {
            throw new IllegalArgumentException("Argon2id " + what + " 不对");
        }
        return bytes;
    }
}
