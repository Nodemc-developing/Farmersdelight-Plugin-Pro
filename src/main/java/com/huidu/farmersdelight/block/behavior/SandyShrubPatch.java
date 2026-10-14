package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.random.RandomGenerator;

final class SandyShrubPatch {
    record Options(int attempts, int horizontalRange, int verticalRange) {
        Options {
            if (attempts < 1 || attempts > 256 || horizontalRange < 0 || horizontalRange > 8
                    || verticalRange < 0 || verticalRange > 8) {
                throw new IllegalArgumentException("shrub patch requires 1..256 attempts and 0..8 ranges");
            }
        }
        static Options parse(Map<String, Object> arguments) {
            return new Options(BehaviorArgParser.getInt(arguments, "patch.attempts", 32),
                    BehaviorArgParser.getInt(arguments, "patch.horizontal-range", 2),
                    BehaviorArgParser.getInt(arguments, "patch.vertical-range", 3));
        }
    }
    record Offset(int x, int y, int z) { }
    record Change<S>(OwnedBlockPairTransaction.Cell<S> cell, S before, S after) { }
    private SandyShrubPatch() { }

    static <S> List<Change<S>> prepare(Options options, RandomGenerator random,
                                     Function<Offset, Change<S>> inspect) {
        Map<Offset, Change<S>> unique = new LinkedHashMap<>();
        for (int n = 0; n < options.attempts; n++) {
            Offset offset = new Offset(sample(random, options.horizontalRange),
                    1 + sample(random, options.verticalRange), sample(random, options.horizontalRange));
            if (unique.containsKey(offset)) continue;
            Change<S> change = inspect.apply(offset);
            unique.put(offset, change);
        }
        return unique.values().stream().filter(Objects::nonNull).toList();
    }

    private static int sample(RandomGenerator random, int range) {
        return random.nextInt(range + 1) - random.nextInt(range + 1);
    }

    static <S> OwnedBlockPairTransaction.Outcome commit(List<Change<S>> changes, BooleanSupplier valid) {
        if (changes.isEmpty() || !valid.getAsBoolean()) return new OwnedBlockPairTransaction.Outcome(false, true);
        for (Change<S> change : changes) if (!matches(change, change.before)) return new OwnedBlockPairTransaction.Outcome(false, true);
        List<Change<S>> attempted = new ArrayList<>(changes.size());
        try {
            for (Change<S> change : changes) {
                if (!valid.getAsBoolean() || !matches(change, change.before)) return rollback(attempted);
                attempted.add(change);
                if (!change.cell.write(change.after) || !matches(change, change.after)) return rollback(attempted);
            }
            if (!valid.getAsBoolean()) return rollback(attempted);
            for (Change<S> change : changes) if (!matches(change, change.after)) return rollback(attempted);
            return new OwnedBlockPairTransaction.Outcome(true, true);
        } catch (RuntimeException | Error failure) {
            try { rollback(attempted); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static <S> boolean matches(Change<S> change, S state) {
        return change.cell.resident() && Objects.equals(change.cell.read(), state);
    }
    private static <S> OwnedBlockPairTransaction.Outcome rollback(List<Change<S>> attempted) {
        boolean restored = true;
        Throwable failure = null;
        for (int n = attempted.size() - 1; n >= 0; n--) {
            Change<S> change = attempted.get(n);
            try { restored &= OwnedBlockPairTransaction.restore(change.cell, change.after, change.before); }
            catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
        }
        if (failure instanceof RuntimeException problem) throw problem;
        if (failure instanceof Error problem) throw problem;
        return new OwnedBlockPairTransaction.Outcome(false, restored);
    }
}
