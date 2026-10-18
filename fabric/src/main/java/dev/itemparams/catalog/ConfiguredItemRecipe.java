package dev.itemparams.catalog;

import dev.itemparams.core.ParameterValues;
import dev.itemparams.fabric.ItemParameterStorage;
import dev.itemparams.formula.InputLayout;
import dev.itemparams.production.ParameterRecipe;
import net.minecraft.item.ItemStack;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** ItemStack adapter; numerical recipe remains Minecraft-independent. */
public final class ConfiguredItemRecipe {
    private final String id;
    private final Map<String, ItemDefinition> inputs;
    private final ItemDefinition output;
    private final int count;
    private final ParameterRecipe formula;

    public ConfiguredItemRecipe(String id, Map<String, ItemDefinition> inputs,
                                ItemDefinition output, int count, ParameterRecipe formula) {
        this.id = Objects.requireNonNull(id, "id");
        this.inputs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.output = Objects.requireNonNull(output, "output");
        this.formula = Objects.requireNonNull(formula, "formula");
        if (count < 1 || count > output.item().getMaxCount())
            throw new IllegalArgumentException("Output count exceeds item stack limit");
        this.count = count;
        InputLayout layout = formula.inputLayout();
        if (layout.size() != inputs.size()) throw new IllegalArgumentException("Input count mismatch");
        for (int i = 0; i < layout.size(); i++) {
            ItemDefinition definition = inputs.get(layout.nameAt(i));
            if (definition == null || definition.schema() != layout.schemaAt(i))
                throw new IllegalArgumentException("Input schema mismatch: " + layout.nameAt(i));
        }
        if (formula.outputSchema() != output.schema())
            throw new IllegalArgumentException("Output schema mismatch");
    }

    public String id() { return id; }
    public Map<String, ItemDefinition> inputs() { return inputs; }
    public ItemDefinition output() { return output; }
    public int outputCount() { return count; }
    public ParameterRecipe numericRecipe() { return formula; }
    public ParameterRecipe.Workspace newWorkspace() { return formula.newWorkspace(); }

    /** A calculation can create any representable output values. No value variant registration. */
    public ItemStack execute(Map<String, ItemStack> stacks) { return execute(stacks, null); }

    /** Optional per-production-line last-result cache. Not shared between lines. */
    public ItemStack execute(Map<String, ItemStack> stacks, ParameterRecipe.Workspace workspace) {
        Objects.requireNonNull(stacks, "stacks");
        if (!stacks.keySet().equals(inputs.keySet()))
            throw new IllegalArgumentException("Recipe requires exactly the named input roles");
        Map<String, ParameterValues> values = new LinkedHashMap<>();
        for (var entry : inputs.entrySet()) {
            ItemStack stack = Objects.requireNonNull(stacks.get(entry.getKey()), "missing input stack");
            ItemDefinition definition = entry.getValue();
            if (stack.isEmpty() || !stack.isOf(definition.item()))
                throw new IllegalArgumentException("Wrong item for input " + entry.getKey());
            values.put(entry.getKey(), ItemParameterStorage.read(stack, definition.schema()));
        }
        var bound = formula.inputLayout().bind(values);
        ParameterValues computed = workspace == null ? formula.execute(bound) : formula.execute(bound, workspace);
        ItemStack outputStack = new ItemStack(output.item(), count);
        ItemParameterStorage.write(outputStack, computed);
        return outputStack;
    }
}
