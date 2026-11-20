package com.game.common.combatai;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 轻量可训练副本怪物效用打分器（纯 JDK 21，与 C++ {@code combat_ai::LinearUtilityModel} / Go {@code combatai} 逐位对齐）。
 *
 * <p>线上推理全部采用 {@code long} 定点数（万分比基准 {@link #UTILITY_SCALE} = 10000），零浮点误差、零随机数；
 * 离线训练器使用确定性全批量 Bradley-Terry 成对排序逻辑回归 SGD，舍入规则采用 half-away-from-zero
 * 与 C++ {@code std::llround} / Go {@code math.Round} 一致，保证跨语言产出相同量化权重。
 */
public final class MonsterUtilityAi {

    public static final int UTILITY_SCALE = 10_000;

    private MonsterUtilityAi() {
    }

    @FunctionalInterface
    public interface Scorer {
        long score(int[] features);
    }

    /** 线上线性打分模型。 */
    public static final class LinearModel implements Scorer {
        private final int[] weights;
        private final int bias;

        public LinearModel(int[] weights, int bias) {
            this.weights = Objects.requireNonNull(weights, "weights").clone();
            this.bias = bias;
        }

        public int[] weights() {
            return weights.clone();
        }

        public int bias() {
            return bias;
        }

        @Override
        public long score(int[] features) {
            long sum = (long) bias * UTILITY_SCALE;
            int n = Math.min(weights.length, features.length);
            for (int i = 0; i < n; i++) {
                sum += (long) weights[i] * (long) features[i];
            }
            return sum;
        }
    }

    /** 进阶 2 层量化 MLP 模型（全 int64 + ReLU）。 */
    public static final class QuantizedMlpModel implements Scorer {
        private final int[][] w1;
        private final int[] b1;
        private final int[] w2;
        private final int b2;

        public QuantizedMlpModel(int[][] w1, int[] b1, int[] w2, int b2) {
            Objects.requireNonNull(w1, "w1");
            this.w1 = new int[w1.length][];
            for (int i = 0; i < w1.length; i++) {
                this.w1[i] = w1[i].clone();
            }
            this.b1 = Objects.requireNonNull(b1, "b1").clone();
            this.w2 = Objects.requireNonNull(w2, "w2").clone();
            this.b2 = b2;
        }

        @Override
        public long score(int[] features) {
            long out = (long) b2 * UTILITY_SCALE;
            int hiddenCount = Math.min(w1.length, Math.min(b1.length, w2.length));
            for (int h = 0; h < hiddenCount; h++) {
                long hidden = (long) b1[h] * UTILITY_SCALE;
                int[] row = w1[h];
                int n = Math.min(row.length, features.length);
                for (int i = 0; i < n; i++) {
                    hidden += (long) row[i] * (long) features[i];
                }
                if (hidden > 0L) {
                    long activated = hidden / UTILITY_SCALE;
                    out += (long) w2[h] * activated;
                }
            }
            return out;
        }
    }

    /** 单个合法候选行动（actionId / formationSlot 按 uint32、targetId 按 uint64 无符号比较）。 */
    public record ActionCandidate(int actionId, long targetId, int formationSlot, int[] features) {
        public ActionCandidate {
            features = Objects.requireNonNull(features, "features").clone();
        }

        @Override
        public int[] features() {
            return features.clone();
        }

        int[] rawFeatures() {
            return features;
        }
    }

    public record Selection(int index, long score) {
    }

    /**
     * 确定性挑选最高分候选：分数降序 → 阵位升序（uint32）→ targetId 升序（uint64）→ actionId 升序（uint32），
     * 不消耗任何随机数。
     */
    public static Optional<Selection> selectBestCandidate(List<ActionCandidate> candidates, Scorer model) {
        if (candidates == null || candidates.isEmpty() || model == null) {
            return Optional.empty();
        }
        int bestIdx = 0;
        long bestScore = model.score(candidates.get(0).rawFeatures());

        for (int i = 1; i < candidates.size(); i++) {
            ActionCandidate cur = candidates.get(i);
            long score = model.score(cur.rawFeatures());
            ActionCandidate best = candidates.get(bestIdx);
            boolean better = score > bestScore;
            if (!better && score == bestScore) {
                int slotCmp = Integer.compareUnsigned(cur.formationSlot(), best.formationSlot());
                if (slotCmp < 0) {
                    better = true;
                } else if (slotCmp == 0) {
                    int targetCmp = Long.compareUnsigned(cur.targetId(), best.targetId());
                    if (targetCmp < 0) {
                        better = true;
                    } else if (targetCmp == 0 && Integer.compareUnsigned(cur.actionId(), best.actionId()) < 0) {
                        better = true;
                    }
                }
            }
            if (better) {
                bestIdx = i;
                bestScore = score;
            }
        }
        return Optional.of(new Selection(bestIdx, bestScore));
    }

    /** 一条成对偏好样本：chosen 比 rejected 更优。 */
    public record PairwiseSample(int[] chosen, int[] rejected, double sampleWeight) {
        public PairwiseSample {
            chosen = Objects.requireNonNull(chosen, "chosen").clone();
            rejected = Objects.requireNonNull(rejected, "rejected").clone();
        }
    }

    public record TrainConfig(int epochs, double learningRate, double l2Regularization, double maxAbsWeight) {
        public static TrainConfig defaults() {
            return new TrainConfig(200, 0.25, 0.001, 20.0);
        }
    }

    public record TrainMetrics(double initialLoss, double finalLoss, int accuracyPermyriad) {
    }

    public record TrainResult(LinearModel model, TrainMetrics metrics) {
    }

    /**
     * 确定性全批量 Bradley-Terry 成对排序逻辑回归 SGD：
     * 全程无随机数，与 C++ / Go 产出一致的 int32 量化权重。
     */
    public static TrainResult trainPairwiseLinearModel(int featureCount, List<PairwiseSample> samples,
                                                       TrainConfig config) {
        Objects.requireNonNull(config, "config");
        if (featureCount <= 0 || samples == null || samples.isEmpty() || config.epochs() <= 0) {
            return new TrainResult(new LinearModel(new int[Math.max(0, featureCount)], 0),
                    new TrainMetrics(0.0, 0.0, 0));
        }

        double invScale = 1.0 / UTILITY_SCALE;
        double[] w = new double[featureCount];
        double initialLoss = computeLoss(featureCount, samples, w, invScale);
        double[] grad = new double[featureCount];

        for (int epoch = 0; epoch < config.epochs(); epoch++) {
            Arrays.fill(grad, 0.0);
            double totalWeight = 0.0;

            for (PairwiseSample s : samples) {
                if (s.sampleWeight() <= 0.0) {
                    continue;
                }
                double margin = 0.0;
                for (int i = 0; i < featureCount; i++) {
                    double diff = (double) (s.chosen[i] - s.rejected[i]) * invScale;
                    margin += w[i] * diff;
                }
                double probWrong;
                if (margin >= 0.0) {
                    double e = Math.exp(-margin);
                    probWrong = e / (1.0 + e);
                } else {
                    probWrong = 1.0 / (1.0 + Math.exp(margin));
                }
                double factor = s.sampleWeight() * probWrong;
                for (int i = 0; i < featureCount; i++) {
                    double diff = (double) (s.chosen[i] - s.rejected[i]) * invScale;
                    grad[i] -= factor * diff;
                }
                totalWeight += s.sampleWeight();
            }

            if (totalWeight <= 0.0) {
                break;
            }

            for (int i = 0; i < featureCount; i++) {
                double g = (grad[i] / totalWeight) + config.l2Regularization() * w[i];
                double next = w[i] - config.learningRate() * g;
                if (next < -config.maxAbsWeight()) {
                    next = -config.maxAbsWeight();
                } else if (next > config.maxAbsWeight()) {
                    next = config.maxAbsWeight();
                }
                w[i] = next;
            }
        }

        int[] quantizedWeights = new int[featureCount];
        for (int i = 0; i < featureCount; i++) {
            quantizedWeights[i] = (int) roundHalfAwayFromZero(w[i] * UTILITY_SCALE);
        }
        LinearModel model = new LinearModel(quantizedWeights, 0);

        double finalLoss = computeLoss(featureCount, samples, w, invScale);
        long correct = 0;
        for (PairwiseSample s : samples) {
            if (model.score(s.chosen) > model.score(s.rejected)) {
                correct++;
            }
        }
        int accuracyPermyriad = (int) ((correct * UTILITY_SCALE) / samples.size());
        return new TrainResult(model, new TrainMetrics(initialLoss, finalLoss, accuracyPermyriad));
    }

    private static double computeLoss(int featureCount, List<PairwiseSample> samples, double[] weights, double invScale) {
        double totalLoss = 0.0;
        double totalWeight = 0.0;
        for (PairwiseSample s : samples) {
            if (s.sampleWeight() <= 0.0) {
                continue;
            }
            double margin = 0.0;
            for (int i = 0; i < featureCount; i++) {
                double diff = (double) (s.chosen[i] - s.rejected[i]) * invScale;
                margin += weights[i] * diff;
            }
            double sampleLoss = margin >= 0.0
                    ? Math.log1p(Math.exp(-margin))
                    : (-margin + Math.log1p(Math.exp(margin)));
            totalLoss += s.sampleWeight() * sampleLoss;
            totalWeight += s.sampleWeight();
        }
        return totalWeight > 0.0 ? (totalLoss / totalWeight) : 0.0;
    }

    /** 与 C++ {@code std::llround} / Go {@code math.Round} 一致：半值远离零舍入。 */
    private static long roundHalfAwayFromZero(double value) {
        return value >= 0.0 ? (long) Math.floor(value + 0.5) : (long) Math.ceil(value - 0.5);
    }
}
