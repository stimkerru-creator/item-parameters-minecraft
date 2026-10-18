package dev.itemparams.production;

import net.minecraft.item.ItemStack;

import java.util.Objects;
import java.util.Optional;

/** Pure inventory planning. Commit both detached replacements on the owning server thread. */
public final class ProductionOperation {
    private ProductionOperation() {}

    /**
     * Computes everything before consumption. Empty means blocked output; formula/storage
     * errors propagate. Neither source stack is changed, including on failure.
     */
    public static Optional<Prepared> prepare(ItemStack input, ItemStack existingOutput,
                                             ProductionRecipe recipe, ProductionRecipe.Workspace workspace,
                                             int outputSlotLimit) {
        Objects.requireNonNull(existingOutput, "existingOutput");
        if (outputSlotLimit < 1) throw new IllegalArgumentException("Output slot limit must be positive");
        ItemStack created = recipe.execute(input, workspace);
        return planOutput(input, existingOutput, created, outputSlotLimit, true);
    }

    /** Reuses an already computed output while the same input/schema snapshot remains valid. */
    public static Optional<Prepared> prepareOutput(ItemStack input, ItemStack existingOutput,
                                                   ItemStack created, int outputSlotLimit) {
        return planOutput(input, existingOutput, created, outputSlotLimit, false);
    }

    private static Optional<Prepared> planOutput(ItemStack input, ItemStack existingOutput,
                                                 ItemStack created, int outputSlotLimit,
                                                 boolean ownsCreated) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(existingOutput, "existingOutput");
        Objects.requireNonNull(created, "created");
        if (input.isEmpty() || created.isEmpty()) throw new IllegalArgumentException("Prepared stacks must be nonempty");
        if (outputSlotLimit < 1) throw new IllegalArgumentException("Output slot limit must be positive");
        int limit = Math.min(outputSlotLimit, created.getMaxCount());
        if (created.getCount() > limit || !existingOutput.isEmpty() &&
                (!ItemStack.canCombine(existingOutput, created)
                        || (long) existingOutput.getCount() + created.getCount() > limit)) {
            return Optional.empty();
        }
        ItemStack nextInput = input.copy();
        nextInput.decrement(1);
        ItemStack nextOutput = ownsCreated ? created : created.copy();
        if (!existingOutput.isEmpty()) {
            nextOutput = existingOutput.copy();
            nextOutput.increment(created.getCount());
        }
        return Optional.of(new Prepared(nextInput.isEmpty() ? ItemStack.EMPTY : nextInput, nextOutput));
    }

    /** These detached mutable stacks become owned by the inventory when committed. */
    public record Prepared(ItemStack inputAfter, ItemStack outputAfter) {}
}
