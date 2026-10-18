package dev.itemparams.catalog;

import dev.itemparams.core.ParameterSchema;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.Comparator;

/** Validated, immutable configuration generation. No per-value variant registry. */
public record ItemCatalogSnapshot(Map<Identifier, ItemDefinition> items,
                                  Map<String, ConfiguredItemRecipe> recipes) {
    public ItemCatalogSnapshot {
        items = Map.copyOf(Objects.requireNonNull(items));
        recipes = Map.copyOf(Objects.requireNonNull(recipes));
    }
    public ItemDefinition definition(Item item) {
        return items.get(Registries.ITEM.getId(Objects.requireNonNull(item)));
    }
    public ParameterSchema schema(Item item) {
        ItemDefinition def = definition(item);
        return def == null ? null : def.schema();
    }
    public ConfiguredItemRecipe recipe(String id) {
        ConfiguredItemRecipe recipe = recipes.get(id);
        if (recipe == null) throw new IllegalArgumentException("Unknown configured recipe: " + id);
        return recipe;
    }
    /** Stable server-defined recipe order for trusted GUI selection; never derived from client input. */
    public List<String> recipeIds(int maximumInputs) {
        if (maximumInputs < 1) throw new IllegalArgumentException("maximumInputs must be positive");
        return recipes.values().stream()
                .filter(recipe -> recipe.inputs().size() <= maximumInputs)
                .map(ConfiguredItemRecipe::id)
                .sorted(Comparator.naturalOrder())
                .toList();
    }
    public static ItemCatalogSnapshot empty() { return new ItemCatalogSnapshot(Map.of(), Map.of()); }
}
