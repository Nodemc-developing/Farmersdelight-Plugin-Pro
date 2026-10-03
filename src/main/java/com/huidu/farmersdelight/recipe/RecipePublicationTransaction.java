package com.huidu.farmersdelight.recipe;

/** Restores the complete captured recipe state when one publication stage fails. */
public final class RecipePublicationTransaction {
    private RecipePublicationTransaction() { }

    public static void run(Runnable publication, Runnable... restore) {
        RuntimeSnapshotPublication.transaction(publication, restore);
    }
}
