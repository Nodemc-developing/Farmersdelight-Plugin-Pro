package com.huidu.farmersdelight.visual;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionPacketBudgetTest {
    @Test void rapidMailboxDrainsShareOneLimitAndWindowsResetIndependently() {
        var first = new ConnectionPacketBudget(200L);
        var second = new ConnectionPacketBudget(200L);
        assertTrue(first.acquire(10, 2));
        assertTrue(first.acquire(11, 2));
        assertFalse(first.acquire(199, 2));
        assertTrue(second.acquire(199, 2));
        assertTrue(first.acquire(200, 2));
    }
}
