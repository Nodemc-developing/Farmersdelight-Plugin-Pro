package com.huidu.farmersdelight.fluid;

import java.util.ArrayDeque;

/** Navigation preserves the exact list page when returning from a recipe. */
final class FluidRecipeBrowserState {
    record Frame(String type, String recipe, int page) {}
    private final ArrayDeque<Frame> history = new ArrayDeque<>();
    private Frame frame = new Frame(null, null, 0);
    Frame current() { return frame; }
    void type(String type) { history.push(frame); frame = new Frame(type, null, 0); }
    void recipe(String id) { history.push(frame); frame = new Frame(frame.type(), id, frame.page()); }
    void page(int page, int count, int pageSize) { frame = new Frame(frame.type(), null, Math.max(0, Math.min(page, Math.max(0, (count - 1) / pageSize)))); }
    boolean back() { if (history.isEmpty()) return false; frame = history.pop(); return true; }
}
