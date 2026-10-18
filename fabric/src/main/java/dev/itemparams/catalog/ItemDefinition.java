package dev.itemparams.catalog;

import dev.itemparams.core.ParameterSchema;
import net.minecraft.item.Item;
import net.minecraft.util.Identifier;
import java.util.Objects;

/** Immutable field layout for one Minecraft item type, NOT for one value combination. */
public record ItemDefinition(Identifier id, Item item, ParameterSchema schema) {
    public ItemDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(schema, "schema");
    }
}
