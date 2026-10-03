package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.gui.recipebook.RecipeBookGui;

/** Keeps the coupled recipe GUI caches and open views in sync after a reload. */
public final class GuiCacheInvalidator {

    private GuiCacheInvalidator() {
    }

    /** Invalidates recipe previews without initializing a menu or creating Bukkit items. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static void clearRecipeDisplayCaches() {
        RecipeViewCache.clearDisplay();
        ToolPreviewRenderer.clearToolPreviewCache();
    }

    public static void clearConfigCaches() {
        RecipeViewGui.clearConfigCache();
        RecipeBookGui.clearConfigCache();
    }

    public static void clearConfigCachesAndCloseOpenGuis() {
        clearConfigCaches();
        CookingPotGui.closeAllOpenGuis();
        RecipeViewGui.closeAllOpenGuis();
        RecipeBookGui.closeAllOpenWindows();
    }
}
