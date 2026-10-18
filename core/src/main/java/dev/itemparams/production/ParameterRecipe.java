package dev.itemparams.production;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import dev.itemparams.formula.CompiledExpression;
import dev.itemparams.formula.EvaluationWorkspace;
import dev.itemparams.formula.FormulaCompiler;
import dev.itemparams.formula.InputLayout;
import dev.itemparams.formula.InputValues;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable numeric recipe: named independent inputs produce one output schema.
 * This class has no Minecraft dependencies. Transfers and assignments always read
 * original inputs; the returned output is detached. Inputs must remain confined
 * to the calling thread for the duration of execution.
 */
public final class ParameterRecipe {
    private final String id;
    private final InputLayout inputs;
    private final ParameterValues base;
    private final int[] transferTargets;
    private final int[] transferInputs;
    private final int[] transferFields;
    private final int[] assignmentTargets;
    private final String[] assignmentIds;
    private final CompiledExpression[] expressions;
    private final int stackSize;

    private ParameterRecipe(Builder builder, Map<String, Source> transfers,
                            CompiledExpression[] expressions) {
        id = builder.id;
        inputs = builder.inputs;
        base = builder.base.copy();
        transferTargets = new int[transfers.size()];
        transferInputs = new int[transfers.size()];
        transferFields = new int[transfers.size()];
        int cursor = 0;
        for (Map.Entry<String, Source> entry : transfers.entrySet()) {
            transferTargets[cursor] = base.schema().requireIndex(entry.getKey());
            transferInputs[cursor] = entry.getValue().input;
            transferFields[cursor++] = entry.getValue().field;
        }
        this.expressions = expressions;
        assignmentTargets = new int[expressions.length];
        assignmentIds = builder.assignments.keySet().toArray(String[]::new);
        int required = 1;
        for (int i = 0; i < expressions.length; i++) {
            assignmentTargets[i] = base.schema().requireIndex(assignmentIds[i]);
            required = Math.max(required, expressions[i].requiredWorkspaceSize());
        }
        stackSize = required;
    }

    public static Builder builder(String id, InputLayout inputs, ParameterSchema outputSchema) {
        return new Builder(id, inputs, outputSchema);
    }

    /** Shortcut for schema defaults plus formula assignments. */
    public static ParameterRecipe compile(String id, InputLayout inputs, ParameterSchema outputSchema,
                                          Map<String, String> assignments) {
        Builder builder = builder(id, inputs, outputSchema);
        Objects.requireNonNull(assignments, "assignments").forEach(builder::assign);
        return builder.build();
    }

    /** Independent calculation without retaining copies of the input arrays for caching. */
    public ParameterValues execute(InputValues values) {
        validate(values);
        return calculate(values, new EvaluationWorkspace(stackSize));
    }

    /** Reuse one workspace per owner; it remembers at most one successful combination. */
    public ParameterValues execute(InputValues values, Workspace workspace) {
        validate(values);
        Objects.requireNonNull(workspace, "workspace");
        if (workspace.owner != this) throw new IllegalArgumentException("Workspace belongs to another parameter recipe");
        if (workspace.matches(values)) return workspace.output.copy();

        ParameterValues result = calculate(values, workspace.evaluation);
        // No cache publication until every transfer, assignment and range check succeeds.
        workspace.remember(values, result);
        return result;
    }

    public Workspace newWorkspace() { return new Workspace(this); }
    public String id() { return id; }
    public InputLayout inputLayout() { return inputs; }
    public ParameterSchema outputSchema() { return base.schema(); }
    public int assignmentCount() { return expressions.length; }
    public int transferCount() { return transferTargets.length; }

    private void validate(InputValues values) {
        Objects.requireNonNull(values, "values");
        if (values.layout() != inputs) throw new IllegalArgumentException("Input values belong to another input layout");
    }

    private ParameterValues calculate(InputValues values, EvaluationWorkspace evaluation) {
        ParameterValues result = base.copy();
        for (int i = 0; i < transferTargets.length; i++) {
            try {
                result.setRaw(transferTargets[i], values.valueAt(transferInputs[i]).getRaw(transferFields[i]));
            } catch (IllegalArgumentException failure) {
                throw new ProductionException("Recipe " + id + ", transfer to "
                        + base.schema().idAt(transferTargets[i]) + ": " + failure.getMessage(), failure);
            }
        }
        for (int i = 0; i < expressions.length; i++) {
            try {
                result.setRaw(assignmentTargets[i], expressions[i].evaluateRawInputs(values, evaluation));
            } catch (IllegalArgumentException failure) {
                throw new ProductionException("Recipe " + id + ", output " + assignmentIds[i]
                        + ": " + failure.getMessage(), failure);
            }
        }
        return result;
    }

    /** Builder order does not alter precedence: base, common copy, explicit transfers, formulas. */
    public static final class Builder {
        private final String id;
        private final InputLayout inputs;
        private final ParameterValues base;
        private final Map<String, Source> transfers = new LinkedHashMap<>();
        private final Map<String, String> assignments = new LinkedHashMap<>();
        private String commonSource;

        private Builder(String id, InputLayout inputs, ParameterSchema outputSchema) {
            ParameterSchema.validateId(id);
            this.id = id;
            this.inputs = Objects.requireNonNull(inputs, "inputs");
            base = Objects.requireNonNull(outputSchema, "outputSchema").defaults();
        }

        public Builder base(String outputField, BigDecimal value) {
            base.set(outputField, value);
            return this;
        }

        public Builder baseRaw(String outputField, int value) {
            base.setRaw(outputField, value);
            return this;
        }

        /** Copy active fields common to this input and the output. Unknown saved fields are not copied. */
        public Builder copyCommonFrom(String input) {
            inputs.requireIndex(input);
            if (commonSource != null) throw new IllegalArgumentException("Common-copy input is already configured");
            commonSource = input;
            return this;
        }

        /** Exact transfer of stored hundredths; formula assignments may override its target. */
        public Builder transfer(String outputField, String input, String inputField) {
            base.schema().requireIndex(outputField);
            int role = inputs.requireIndex(input);
            int field = inputs.schemaAt(role).requireIndex(inputField);
            if (transfers.putIfAbsent(outputField, new Source(role, field)) != null)
                throw new IllegalArgumentException("Duplicate transfer target: " + outputField);
            return this;
        }

        public Builder assign(String outputField, String expression) {
            base.schema().requireIndex(outputField);
            if (assignments.putIfAbsent(outputField, Objects.requireNonNull(expression, "expression")) != null)
                throw new IllegalArgumentException("Duplicate assignment target: " + outputField);
            return this;
        }

        public ParameterRecipe build() {
            Map<String, Source> resolved = new LinkedHashMap<>();
            if (commonSource != null) {
                int role = inputs.requireIndex(commonSource);
                ParameterSchema source = inputs.schemaAt(role);
                for (int i = 0; i < base.schema().size(); i++) {
                    String target = base.schema().idAt(i);
                    int field = source.indexOf(target);
                    if (field >= 0) resolved.put(target, new Source(role, field));
                }
            }
            resolved.putAll(transfers);
            // Assignments replace transfers. Do not range-check a temporary value
            // that is never part of the final output (e.g. mass before milling).
            assignments.keySet().forEach(resolved::remove);
            CompiledExpression[] compiled = new CompiledExpression[assignments.size()];
            Map<String, CompiledExpression> shared = new LinkedHashMap<>();
            int index = 0;
            for (String source : assignments.values())
                compiled[index++] = shared.computeIfAbsent(source, text -> FormulaCompiler.compileInputs(inputs, text));
            return new ParameterRecipe(this, resolved, compiled);
        }
    }

    private record Source(int input, int field) {}

    /**
     * One owner, one last successful combination; no global cache. Snapshots are
     * retained only on a miss, not copied for each expression or repeated call.
     */
    public static final class Workspace {
        private final ParameterRecipe owner;
        private final EvaluationWorkspace evaluation;
        private final ParameterValues[] references;
        private final long[] versions;
        private int[][] snapshots;
        private ParameterValues output;

        private Workspace(ParameterRecipe owner) {
            this.owner = owner;
            evaluation = new EvaluationWorkspace(owner.stackSize);
            references = new ParameterValues[owner.inputs.size()];
            versions = new long[references.length];
        }

        private boolean matches(InputValues values) {
            if (output == null) return false;
            boolean unchanged = true;
            for (int i = 0; i < references.length; i++) {
                ParameterValues value = values.valueAt(i);
                if (references[i] != value || versions[i] != value.modificationVersion()) {
                    unchanged = false;
                    break;
                }
            }
            if (unchanged) return true;
            // New object instances with the same values are still the same numeric combination.
            for (int i = 0; i < references.length; i++) {
                ParameterValues value = values.valueAt(i);
                int[] raw = snapshots[i];
                for (int field = 0; field < raw.length; field++)
                    if (raw[field] != value.getRaw(field)) return false;
            }
            observe(values);
            return true;
        }

        private void remember(InputValues values, ParameterValues result) {
            int[][] next = new int[references.length][];
            for (int i = 0; i < next.length; i++) next[i] = values.valueAt(i).copyRaw();
            ParameterValues savedOutput = result.copy();
            snapshots = next;
            output = savedOutput;
            observe(values);
        }

        private void observe(InputValues values) {
            for (int i = 0; i < references.length; i++) {
                references[i] = values.valueAt(i);
                versions[i] = references[i].modificationVersion();
            }
        }

        /** Release retained input snapshots/output. The next call calculates normally. */
        public void clear() {
            java.util.Arrays.fill(references, null);
            java.util.Arrays.fill(versions, 0);
            snapshots = null;
            output = null;
            evaluation.clear();
        }
    }
}
