package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HostCommonTagOverlayTest {
    private final Map<Key, List<UniqueKey>> vanilla = new LinkedHashMap<>(), custom = new LinkedHashMap<>();
    private final Map<Key, Set<Key>> reverse = new LinkedHashMap<>();
    private final HostCommonTagOverlay overlay = new HostCommonTagOverlay(vanilla, custom, reverse);

    @Test void mixedGroupPublishesToTheCorrectTablesAndClosesWithoutLeavingStaticVanillaMembers() {
        Key tag = Key.of("c:foods/fixture"), nativeTag = Key.of("minecraft:native_fixture");
        UniqueKey stick = item("minecraft:stick"), cabbage = item("farmersdelight:cabbage");
        Set<Key> original = Set.of(nativeTag); reverse.put(stick.key(), original);
        overlay.publish(Map.of(tag, members(List.of(stick), List.of(cabbage))));
        assertEquals(List.of(stick), vanilla.get(tag)); assertEquals(List.of(cabbage), custom.get(tag));
        assertEquals(Set.of("minecraft:stick", "farmersdelight:cabbage"), overlay.existingMembers(tag));
        assertEquals(Set.of(nativeTag, tag), reverse.get(stick.key()));
        overlay.close();
        assertFalse(vanilla.containsKey(tag)); assertFalse(custom.containsKey(tag));
        assertSame(original, reverse.get(stick.key()));
        assertThrows(IllegalStateException.class, () -> overlay.publish(Map.of(tag, members(List.of(stick), List.of()))));
    }

    @Test void completeNativeGroupsKeepTheirExactListIdentityAndNeverGainDefaultMembers() {
        Key vanillaTag = Key.of("minecraft:fixture"), customTag = Key.of("food:fixture");
        List<UniqueKey> existingVanilla = List.of(item("minecraft:carrot"));
        List<UniqueKey> existingCustom = List.of(item("external:cabbage"));
        vanilla.put(vanillaTag, existingVanilla); custom.put(customTag, existingCustom);
        overlay.publish(Map.of(vanillaTag, members(List.of(item("minecraft:stick")), List.of()),
                customTag, members(List.of(), List.of(item("farmersdelight:cabbage")))));
        assertSame(existingVanilla, vanilla.get(vanillaTag)); assertSame(existingCustom, custom.get(customTag));
        assertFalse(custom.containsKey(vanillaTag)); assertFalse(vanilla.containsKey(customTag));
        overlay.clear();
        assertSame(existingVanilla, vanilla.get(vanillaTag)); assertSame(existingCustom, custom.get(customTag));
    }

    @Test void anExplicitEmptyGroupDoesNotManufactureAnIngredientOrEraseNativeDefinitions() {
        Key empty = Key.of("food:empty"), nativeTag = Key.of("food:native");
        List<UniqueKey> original = List.of(item("external:leaf")); custom.put(nativeTag, original);
        overlay.publish(Map.of(empty, members(List.of(), List.of()), nativeTag, members(List.of(), List.of())));
        assertNull(overlay.existingMembers(empty)); assertSame(original, custom.get(nativeTag));
        assertFalse(vanilla.containsKey(empty)); assertFalse(custom.containsKey(empty));
    }

    @Test void multipleGroupsRestoreTheOriginalReverseSetAndPreexistingEmptyForwardValueExactly() {
        Key first = Key.of("c:first"), second = Key.of("c:second"), nativeTag = Key.of("minecraft:native");
        UniqueKey stick = item("minecraft:stick");
        List<UniqueKey> originalEmpty = new java.util.ArrayList<>(); vanilla.put(first, originalEmpty);
        Set<Key> originalReverse = new java.util.LinkedHashSet<>(Set.of(nativeTag)); reverse.put(stick.key(), originalReverse);
        overlay.publish(Map.of(first, members(List.of(stick), List.of()), second, members(List.of(stick), List.of())));
        assertEquals(Set.of(nativeTag, first, second), reverse.get(stick.key()));
        overlay.clear();
        assertSame(originalEmpty, vanilla.get(first)); assertFalse(vanilla.containsKey(second));
        assertSame(originalReverse, reverse.get(stick.key()));
    }

    @Test void laterHostWritesWinOverCleanupAndAreNotRemovedOnTheNextPublication() {
        Key tag = Key.of("food:fixture"), replacementTag = Key.of("external:replacement");
        UniqueKey stick = item("minecraft:stick");
        overlay.publish(Map.of(tag, members(List.of(stick), List.of(item("food:old")))));
        List<UniqueKey> replacementVanilla = List.of(item("minecraft:carrot"));
        List<UniqueKey> replacementCustom = List.of(item("external:new"));
        Set<Key> replacementReverse = Set.of(replacementTag);
        vanilla.put(tag, replacementVanilla); custom.put(tag, replacementCustom); reverse.put(stick.key(), replacementReverse);
        overlay.publish(Map.of(tag, members(List.of(stick), List.of(item("food:next")))));
        assertSame(replacementVanilla, vanilla.get(tag)); assertSame(replacementCustom, custom.get(tag));
        assertSame(replacementReverse, reverse.get(stick.key()));
        overlay.close();
        assertSame(replacementVanilla, vanilla.get(tag)); assertSame(replacementCustom, custom.get(tag));
        assertSame(replacementReverse, reverse.get(stick.key()));
    }

    @Test void newGenerationRemovesEveryOwnedOldGroupRatherThanAccumulatingMembership() {
        Key first = Key.of("food:old"), second = Key.of("food:new");
        UniqueKey stick = item("minecraft:stick");
        overlay.publish(Map.of(first, members(List.of(stick), List.of())));
        overlay.publish(Map.of(second, members(List.of(stick), List.of())));
        assertNull(overlay.existingMembers(first)); assertEquals(Set.of("minecraft:stick"), overlay.existingMembers(second));
        assertEquals(Set.of(second), reverse.get(stick.key()));
        overlay.clear(); assertTrue(vanilla.isEmpty()); assertTrue(custom.isEmpty()); assertTrue(reverse.isEmpty());
    }

    private static UniqueKey item(String id) { return UniqueKey.create(Key.of(id)); }
    private static HostCommonTagOverlay.Members members(List<UniqueKey> vanilla, List<UniqueKey> custom) {
        return new HostCommonTagOverlay.Members(vanilla, custom);
    }
}
