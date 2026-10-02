package com.huidu.farmersdelight.block.behavior;

/** Semantic support state shared by string and integer content-pack properties. */
public final class CookingPotSupportState {
    public static String fromInteger(Integer value) {
        if (value == null) return null;
        return switch (value) {
            case 0 -> "none";
            case 1 -> "tray";
            case 2 -> "handle";
            default -> null;
        };
    }

    public static int toInteger(String state) {
        return switch (state) {
            case "none" -> 0;
            case "tray" -> 1;
            case "handle" -> 2;
            default -> throw new IllegalArgumentException("Unknown cooking pot support state: " + state);
        };
    }

    private CookingPotSupportState() { }
}
