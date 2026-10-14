package com.huidu.farmersdelight.listener;

import java.util.Objects;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

final class CakeSliceTransaction {
    interface Cell<S> { boolean resident(); S read(); boolean write(S value); }
    interface Output { boolean deliver(); boolean retract(); }
    record Outcome(boolean committed, boolean rollbackComplete) { }
    private CakeSliceTransaction() { }

    static <S> Outcome commit(Cell<S> cell, S before, S after, BooleanSupplier valid, Output output) {
        if (!valid.getAsBoolean() || !matches(cell, before)) return new Outcome(false, true);
        boolean attempted = false;
        try {
            attempted = true;
            if (!cell.write(after) || !matches(cell, after) || !valid.getAsBoolean()) return rollback(cell, before, after, output);
            if (!output.deliver() || !matches(cell, after) || !valid.getAsBoolean()) return rollback(cell, before, after, output);
            return new Outcome(true, true);
        } catch (RuntimeException | Error failure) {
            if (attempted) {
                try { rollback(cell, before, after, output); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }
    private static <S> boolean matches(Cell<S> cell, S expected) {
        return cell.resident() && Objects.equals(cell.read(), expected);
    }
    private static <S> Outcome rollback(Cell<S> cell, S before, S after, Output output) {
        boolean retracted = false;
        boolean restored = false;
        Throwable failure = null;
        try { retracted = output.retract(); }
        catch (RuntimeException | Error error) { failure = error; }
        try { restored = restore(cell, before, after); }
        catch (RuntimeException | Error error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        return new Outcome(false, restored && retracted);
    }
    private static <S> boolean restore(Cell<S> cell, S before, S after) {
        if (!cell.resident()) return false;
        S current = cell.read();
        if (Objects.equals(current, before) || !Objects.equals(current, after)) return true;
        return cell.write(before) && matches(cell, before);
    }
    static <T> boolean retractAll(List<T> outputs, Function<T, Boolean> retract) {
        boolean complete = true;
        Throwable failure = null;
        for (T output : outputs) {
            try { complete &= retract.apply(output); }
            catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else if (failure != problem) failure.addSuppressed(problem);
            }
        }
        if (failure instanceof RuntimeException problem) throw problem;
        if (failure instanceof Error problem) throw problem;
        return complete;
    }
}
