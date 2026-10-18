package dev.itemparams.fabric;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;

import java.util.Map;
import java.util.Objects;

/**
 * Reusable admission rule for an input slot. Before locking it admits valid numeric
 * states of one item type. After locking it requires the entire same numeric state,
 * including retained fields unknown to the active schema. Count and unrelated NBT
 * never participate. The host controls when processing starts and calls lock then.
 *
 * Reading never changes a stack. Locking is atomic and cannot replace an established
 * state; no caller receives an alias to that state. Inventory changes and decisions
 * about admission/consumption must still run on the owning server thread.
 */
public final class ItemInputFilter {
    private final Item inputItem;
    private final ParameterSchema schema;
    private volatile LockedState locked;

    private ItemInputFilter(Item inputItem, ParameterSchema schema) {
        this.inputItem = inputItem;
        this.schema = schema;
    }

    public static ItemInputFilter of(Item inputItem, ParameterSchema schema) {
        Objects.requireNonNull(inputItem, "inputItem");
        Objects.requireNonNull(schema, "schema");
        if (new ItemStack(inputItem).isEmpty()) {
            throw new IllegalArgumentException("An input filter requires a nonempty item type");
        }
        return new ItemInputFilter(inputItem, schema);
    }

    /** Returns false for absent, wrong, malformed, unsupported or out-of-range input. */
    public boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isOf(inputItem)) return false;
        LockedState state = locked;
        if (state != null && Objects.equals(state.payload, payload(stack))) return true;
        try {
            ParameterValues parameters = ItemParameterStorage.read(stack, schema);
            return state == null || state.parameters.equals(parameters.toRawMap());
        } catch (IllegalArgumentException invalidParameters) {
            return false;
        }
    }

    /**
     * Locks a validated input once. An equivalent later call is harmless; a different
     * or invalid state throws without replacing the established state or changing input.
     */
    public synchronized void lock(ItemStack stack) {
        if (locked != null) {
            if (!matches(stack)) throw new IllegalArgumentException("Input does not match the locked numeric state");
            return;
        }
        if (stack == null || stack.isEmpty() || !stack.isOf(inputItem)) {
            throw new IllegalArgumentException("Cannot lock an absent or different input item");
        }
        ParameterValues parameters = ItemParameterStorage.read(stack, schema);
        Map<String, Integer> raw = parameters.toRawMap();
        ItemStack sample = new ItemStack(inputItem, 1);
        ItemParameterStorage.write(sample, parameters);
        // All validation/allocation finishes before publishing the one immutable state.
        // The private sample is never mutated; its payload can safely share that owner.
        locked = new LockedState(raw, sample, payload(sample));
    }

    public boolean isLocked() { return locked != null; }

    /** A detached count-one numeric-only stack, or EMPTY before locking. */
    public ItemStack sample() {
        LockedState state = locked;
        return state == null ? ItemStack.EMPTY : state.sample.copy();
    }

    private static NbtElement payload(ItemStack stack) {
        NbtCompound root = stack.getNbt();
        return root == null ? null : root.get(ItemParameterStorage.NBT_KEY);
    }

    private record LockedState(Map<String, Integer> parameters, ItemStack sample, NbtElement payload) {}
}
