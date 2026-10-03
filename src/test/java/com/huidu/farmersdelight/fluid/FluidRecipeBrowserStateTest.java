package com.huidu.farmersdelight.fluid;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidRecipeBrowserStateTest {
    @Test void detailCancellationReturnsToTheExactListAndCategoryCancellationExits() {
        var state = new FluidRecipeBrowserState(); state.type("soaking"); state.page(2, 85, 28); state.recipe("test:recipe");
        assertTrue(state.back()); assertEquals(new FluidRecipeBrowserState.Frame("soaking", null, 2), state.current());
        assertTrue(state.back()); assertEquals(new FluidRecipeBrowserState.Frame(null, null, 0), state.current()); assertFalse(state.back());
    }
    @Test void emptyAndShrunkListsCannotRetainAnInvalidPage() {
        var state = new FluidRecipeBrowserState(); state.type("fluid_filling"); state.page(99, 29, 28); assertEquals(1, state.current().page());
        state.page(1, 0, 28); assertEquals(0, state.current().page()); state.page(-10, 100, 28); assertEquals(0, state.current().page());
    }
}
