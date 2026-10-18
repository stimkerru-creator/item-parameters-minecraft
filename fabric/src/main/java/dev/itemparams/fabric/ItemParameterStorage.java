package dev.itemparams.fabric;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import net.minecraft.item.ItemStack;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * ItemStack adapter over the owner-independent ParameterNbtStorage codec.
 * One stack is a homogeneous batch; mutate live stacks on their owning server thread.
 */
public final class ItemParameterStorage {
    public static final String NBT_KEY = ParameterNbtStorage.NBT_KEY;
    public static final int FORMAT_VERSION = ParameterNbtStorage.FORMAT_VERSION;
    public static final int SCALE = ParameterNbtStorage.SCALE;
    public static final int MAX_FIELDS = ParameterNbtStorage.MAX_FIELDS;
    public static final int MAX_ID_LENGTH = ParameterNbtStorage.MAX_ID_LENGTH;
    private ItemParameterStorage() {}

    /** Pure read: absent data gives defaults without creating or changing ItemStack NBT. */
    public static ParameterValues read(ItemStack stack, ParameterSchema schema) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(schema, "schema");
        requireNonEmpty(stack);
        return ParameterNbtStorage.read(stack.getNbt(), schema);
    }

    /** One explicit commit after complete codec validation; errors leave the stack unchanged. */
    public static void write(ItemStack stack, ParameterValues values) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(values, "values");
        requireNonEmpty(stack);
        stack.setNbt(ParameterNbtStorage.write(stack.getNbt(), values));
    }

    /**
     * Server-side schema materialization. Unlike write(), this intentionally drops retired parameter IDs
     * from the active parameter payload while preserving every unrelated root NBT tag.
     */
    public static void writeCurrentSchema(ItemStack stack, ParameterValues values) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(values, "values");
        requireNonEmpty(stack);
        net.minecraft.nbt.NbtCompound root = stack.getNbt() == null
                ? new net.minecraft.nbt.NbtCompound() : stack.getNbt().copy();
        root.remove(NBT_KEY);
        // Detach *only active* fields. ParameterValues.read retains retired IDs for
        // compatibility; passing it directly to the generic codec would reintroduce
        // those IDs and force a pointless rewrite on every materialization scan.
        ParameterValues activeOnly = ParameterValues.defaults(values.schema());
        activeOnly.replaceRaw(values.copyRaw());
        stack.setNbt(ParameterNbtStorage.write(root, activeOnly));
    }

    /** Detached bulk editing; the callback must only change the supplied parameter values. */
    public static void edit(ItemStack stack, ParameterSchema schema, Consumer<ParameterValues> editor) {
        Objects.requireNonNull(editor, "editor");
        ParameterValues detached = read(stack, schema);
        editor.accept(detached);
        write(stack, detached);
    }

    private static void requireNonEmpty(ItemStack stack) {
        if (stack.isEmpty()) throw new IllegalArgumentException("Cannot attach parameters to an empty ItemStack");
    }
}
