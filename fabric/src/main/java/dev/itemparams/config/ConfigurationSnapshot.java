package dev.itemparams.config;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.production.ProductionRecipe;
import java.util.Map;
import java.util.Objects;

/** An entire validated generation; callers retain one snapshot for the whole operation. */
public record ConfigurationSnapshot(ParameterSchema schema, Map<String, ProductionRecipe> recipes) {
    public ConfigurationSnapshot {
        Objects.requireNonNull(schema, "schema");
        recipes = Map.copyOf(recipes);
        for (Map.Entry<String, ProductionRecipe> entry : recipes.entrySet()) {
            ParameterSchema.validateId(entry.getKey());
            if (entry.getValue().schema() != schema || !entry.getKey().equals(entry.getValue().id()))
                throw new IllegalArgumentException("Recipe map must use matching IDs and one schema instance");
        }
    }
    public ProductionRecipe recipe(String id) {
        ProductionRecipe recipe = recipes.get(id);
        if (recipe == null) throw new IllegalArgumentException("Unknown recipe: " + id);
        return recipe;
    }
}
