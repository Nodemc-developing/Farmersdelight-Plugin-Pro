package com.huidu.farmersdelight.manager;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class HandheldFlipStateTest {
    @Test void oneJumpProducesExactlyOneFlipOnlyAfterLanding() {
        var state = new HandheldFlipState();
        assertFalse(state.sample(false));
        assertFalse(state.sample(true));
        state.jump();
        assertFalse(state.sample(true));
        assertFalse(state.sample(false));
        state.jump();
        assertFalse(state.sample(false));
        assertTrue(state.sample(true));
        assertTrue(state.flipped());
        assertFalse(state.sample(true));
        state.jump(); state.sample(false);
        assertTrue(state.sample(true));
        assertFalse(state.flipped());
    }
    @Test void generatedFaceRotationDoesNotModifyTheSourceModel() {
        var source = JsonParser.parseString("{\"elements\":[{\"faces\":{\"up\":{\"texture\":\"#food\"},\"down\":{\"texture\":\"#food\",\"rotation\":90}}}]}").getAsJsonObject();
        var flipped = HandheldCookingModelPack.flippedOverlay("demo:food", "demo:beef", Map.of("demo:food", source));
        var faces = flipped.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces");
        assertEquals(270, faces.getAsJsonObject("up").get("rotation").getAsInt());
        assertEquals(180, faces.getAsJsonObject("down").get("rotation").getAsInt());
        assertFalse(source.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("up").has("rotation"));
    }
}
