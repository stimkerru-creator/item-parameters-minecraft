package dev.itemparams.catalog;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import dev.itemparams.fabric.ItemParameterStorage;
import dev.itemparams.fabric.ParameterNbtStorage;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.item.ItemStack;
import java.util.Objects;

/** Server-owned catalog and convenience API; never receives arbitrary edit packets. */
public final class ConfiguredItemParameters {
    private static volatile ItemCatalogSnapshot current = ItemCatalogSnapshot.empty();
    private ConfiguredItemParameters() {}
    public static ItemCatalogSnapshot current() { return current; }
    /** Publish only a completely validated catalog. Called on the server lifecycle. */
    public static void install(ItemCatalogSnapshot complete) {
        current = Objects.requireNonNull(complete, "complete");
    }
    /** A configured item without NBT reads as its per-item defaults, without rewriting the stack. */
    public static ParameterValues read(ItemStack stack) { return read(current, stack); }
    public static ParameterValues read(ItemCatalogSnapshot catalog, ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("Cannot read empty stack");
        ParameterSchema schema = Objects.requireNonNull(catalog, "catalog").schema(stack.getItem());
        if (schema == null) throw new IllegalArgumentException("No configured fields for " + stack.getItem());
        return ItemParameterStorage.read(stack, schema);
    }
    /**
     * Materialize defaults on the trusted server so the vanilla ItemStack NBT sync makes them visible
     * to read-only tooltips. Returns true if the stack was changed. No client packet can write values.
     * The active schema is authoritative: retired field IDs are removed from the parameter payload.
     */
    public static boolean initializeIfMissing(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ParameterSchema schema = current.schema(stack.getItem());
        if (schema == null) return false;
        Map<String, Integer> stored = ParameterNbtStorage.storedRaw(stack.getNbt());
        Set<String> active = new HashSet<>();
        boolean complete = true;
        for (int i = 0; i < schema.size(); i++) {
            String id = schema.idAt(i);
            active.add(id);
            Integer raw = stored.get(id);
            if (raw == null) complete = false;
            else schema.validateRaw(i, raw);
        }
        if (complete && stored.keySet().equals(active)) return false;
        // Only materialize when the active schema is missing fields or contains retired IDs.
        ParameterValues values = ItemParameterStorage.read(stack, schema);
        ItemParameterStorage.writeCurrentSchema(stack, values);
        return true;
    }
    /** Explicit privileged server-side write; not exposed as command or network handler. */
    public static void write(ItemStack stack, ParameterValues values) {
        Objects.requireNonNull(stack, "stack");
        ParameterSchema schema = current.schema(stack.getItem());
        if (schema == null || values.schema() != schema)
            throw new IllegalArgumentException("Values do not match configured item schema");
        ItemParameterStorage.write(stack, values);
    }
}
