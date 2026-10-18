package dev.itemparams.production;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import dev.itemparams.fabric.ItemParameterStorage;
import dev.itemparams.formula.CompiledExpression;
import dev.itemparams.formula.FormulaCompiler;
import dev.itemparams.formula.EvaluationWorkspace;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Immutable recipe compiled against one exact schema instance. All assignments read
 * the same detached input snapshot. The owner-confined workspace remembers only
 * the last successful numeric input/output; repeated execution returns a copy.
 * The caller owns inventory capacity checks and consumption.
 */
public final class ProductionRecipe {
    private final String id;
    private final Item inputItem;
    private final Item outputItem;
    private final int outputCount;
    private final ParameterSchema schema;
    private final int[] outputIndices;
    private final String[] outputIds;
    private final CompiledExpression[] formulas;
    private final int workspaceSize;
    private final CarryPolicy carryPolicy;

    private ProductionRecipe(String id, Item inputItem, Item outputItem, int outputCount,
                             ParameterSchema schema, int[] outputIndices,
                             String[] outputIds, CompiledExpression[] formulas, CarryPolicy carryPolicy) {
        this.id = id;
        this.inputItem = inputItem;
        this.outputItem = outputItem;
        this.outputCount = outputCount;
        this.schema = schema;
        this.outputIndices = outputIndices;
        this.outputIds = outputIds;
        this.formulas = formulas;
        this.carryPolicy = carryPolicy;
        int required = 1;
        for (CompiledExpression formula : formulas) required = Math.max(required, formula.requiredWorkspaceSize());
        workspaceSize = required;
    }

    public static ProductionRecipe compile(String id, Item inputItem, Item outputItem,
                                           int outputCount, ParameterSchema schema,
                                           Map<String, String> assignments) {
        return compile(id, inputItem, outputItem, outputCount, schema, assignments,
                expression -> FormulaCompiler.compile(schema, expression));
    }

    /** Allows a generation-scoped compiler cache to share identical immutable expressions. */
    public static ProductionRecipe compile(String id, Item inputItem, Item outputItem,
                                           int outputCount, ParameterSchema schema,
                                           Map<String, String> assignments, CarryPolicy carryPolicy) {
        return compile(id, inputItem, outputItem, outputCount, schema, assignments,
                expression -> FormulaCompiler.compile(schema, expression), carryPolicy);
    }

    /** Allows a generation-scoped compiler cache to share identical immutable expressions. */
    public static ProductionRecipe compile(String id, Item inputItem, Item outputItem,
                                           int outputCount, ParameterSchema schema,
                                           Map<String, String> assignments,
                                           Function<String, CompiledExpression> compiler) {
        return compile(id, inputItem, outputItem, outputCount, schema, assignments, compiler, CarryPolicy.COPY_INPUT);
    }

    public static ProductionRecipe compile(String id, Item inputItem, Item outputItem,
                                           int outputCount, ParameterSchema schema,
                                           Map<String, String> assignments,
                                           Function<String, CompiledExpression> compiler,
                                           CarryPolicy carryPolicy) {
        ParameterSchema.validateId(id);
        Objects.requireNonNull(inputItem, "inputItem");
        Objects.requireNonNull(outputItem, "outputItem");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(assignments, "assignments");
        Objects.requireNonNull(compiler, "compiler");
        Objects.requireNonNull(carryPolicy, "carryPolicy");
        if (outputCount < 1 || outputCount > outputItem.getMaxCount()) {
            throw new IllegalArgumentException("Recipe output count must be 1.." + outputItem.getMaxCount());
        }
        if (new ItemStack(inputItem).isEmpty() || new ItemStack(outputItem).isEmpty()) {
            throw new IllegalArgumentException("Recipe input and output must be nonempty items");
        }
        if (assignments.isEmpty() || assignments.size() > ParameterSchema.MAX_FIELDS) {
            throw new IllegalArgumentException("Recipe requires 1.." + ParameterSchema.MAX_FIELDS + " assignments");
        }
        int[] indices = new int[assignments.size()];
        String[] ids = new String[assignments.size()];
        CompiledExpression[] compiled = new CompiledExpression[assignments.size()];
        int index = 0;
        for (Map.Entry<String, String> assignment : assignments.entrySet()) {
            ids[index] = Objects.requireNonNull(assignment.getKey(), "assignment target");
            indices[index] = schema.requireIndex(ids[index]);
            compiled[index] = Objects.requireNonNull(compiler.apply(
                    Objects.requireNonNull(assignment.getValue(), "assignment expression")), "compiled expression");
            if (compiled[index].schema() != schema) {
                throw new IllegalArgumentException("Compiler returned an expression for another schema");
            }
            index++;
        }
        return new ProductionRecipe(id, inputItem, outputItem, outputCount, schema, indices, ids, compiled, carryPolicy);
    }

    /** One independent calculation. Reuse a workspace for repeated production operations. */
    public ItemStack execute(ItemStack input) {
        return execute(input, newWorkspace());
    }

    /** Reusable owner-bound evaluation state and last-result cache; never use concurrently. */
    public Workspace newWorkspace() { return new Workspace(this, new EvaluationWorkspace(workspaceSize)); }

    public ItemStack execute(ItemStack input, Workspace workspace) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(workspace, "workspace");
        if (workspace.owner != this) throw new IllegalArgumentException("Workspace belongs to another recipe generation");
        if (input.isEmpty() || !input.isOf(inputItem)) {
            throw new IllegalArgumentException("Wrong input item for recipe " + id);
        }
        NbtCompound root = input.getNbt();
        NbtElement payload = root == null ? null : root.get(ItemParameterStorage.NBT_KEY);
        if (workspace.cachedOutput != null && Objects.equals(workspace.inputPayload, payload)) {
            return workspace.cachedOutput.copy();
        }
        ParameterValues snapshot = ItemParameterStorage.read(input, schema);
        Map<String, Integer> inputParameters = snapshot.toRawMap();
        if (workspace.cachedOutput != null && workspace.inputParameters.equals(inputParameters)) {
            // Equivalent field ordering/default encodings get the same result. Remember
            // the validated representation so subsequent calls use the no-decode path.
            workspace.inputPayload = payload == null ? null : payload.copy();
            return workspace.cachedOutput.copy();
        }
        ParameterValues result = carryPolicy == CarryPolicy.COPY_INPUT ? snapshot.copy() : schema.defaults();
        for (int index = 0; index < formulas.length; index++) {
            try {
                result.setRaw(outputIndices[index], formulas[index].evaluateRaw(snapshot, workspace.evaluation));
            } catch (IllegalArgumentException failure) {
                throw new ProductionException("Recipe " + id + ", output " + outputIds[index]
                        + ": " + failure.getMessage(), failure);
            }
        }
        ItemStack output = new ItemStack(outputItem, outputCount);
        ItemParameterStorage.write(output, result);
        // Publish the internal cache only after every assignment and NBT write succeeds.
        // Count and unrelated NBT are not formula inputs. No caller owns these copies.
        workspace.inputPayload = payload == null ? null : payload.copy();
        workspace.inputParameters = inputParameters;
        workspace.cachedOutput = output.copy();
        return output;
    }

    public String id() { return id; }
    public Item inputItem() { return inputItem; }
    public Item outputItem() { return outputItem; }
    public int outputCount() { return outputCount; }
    public ParameterSchema schema() { return schema; }
    public int assignmentCount() { return formulas.length; }
    public CarryPolicy carryPolicy() { return carryPolicy; }

    public static final class Workspace {
        private final ProductionRecipe owner;
        private final EvaluationWorkspace evaluation;
        private NbtElement inputPayload;
        private Map<String, Integer> inputParameters;
        private ItemStack cachedOutput;
        private Workspace(ProductionRecipe owner, EvaluationWorkspace evaluation) {
            this.owner = owner;
            this.evaluation = evaluation;
        }
        /** Releases the last result and decoded fields; the next call calculates normally. */
        public void clear() {
            inputPayload = null;
            inputParameters = null;
            cachedOutput = null;
            evaluation.clear();
        }
    }
}
