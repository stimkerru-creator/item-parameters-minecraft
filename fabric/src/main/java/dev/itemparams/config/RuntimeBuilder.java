package dev.itemparams.config;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.Numeric;
import dev.itemparams.production.ProductionRecipe;
import dev.itemparams.production.CarryPolicy;
import net.minecraft.item.Item;
import java.math.BigDecimal;
import java.util.*;
import dev.itemparams.formula.*;

/** Configuration extension surface; decimal values use item units, raw values never enter here. */
public final class RuntimeBuilder {
    public static final int MAX_RECIPES = 20000;
    public static final int MAX_UNIQUE_FORMULA_CHARS = 16 * 1024 * 1024;
    public static final int MAX_COMPILED_INSTRUCTIONS = 2_000_000;
    public static final int MAX_ASSIGNMENTS = 1_000_000;
    private final ParameterSchema.Builder fields = ParameterSchema.builder();
    private final Map<String, RecipeDefinition> recipes = new LinkedHashMap<>();
    private int assignmentCount;
    public RuntimeBuilder field(String id, BigDecimal defaultValue) {
        fields.addRaw(id, Numeric.encodeExact(defaultValue)); return this;
    }
    public RuntimeBuilder field(String id, BigDecimal defaultValue, BigDecimal min, BigDecimal max) {
        fields.add(id, defaultValue, min, max); return this;
    }
    public RuntimeBuilder recipe(String id, Item input, Item output, int count, Map<String, String> assignments) {
        return recipe(id, input, output, count, assignments, CarryPolicy.COPY_INPUT);
    }
    public RuntimeBuilder recipe(String id, Item input, Item output, int count, Map<String, String> assignments,
                                 CarryPolicy carryPolicy) {
        ParameterSchema.validateId(id);
        if (recipes.size() >= MAX_RECIPES) throw new IllegalArgumentException("Too many recipes; maximum " + MAX_RECIPES);
        Objects.requireNonNull(assignments, "assignments");
        if (assignments.isEmpty() || assignments.size() > ParameterSchema.MAX_FIELDS)
            throw new IllegalArgumentException("Recipe assignment count must be 1.." + ParameterSchema.MAX_FIELDS);
        if ((long)assignmentCount + assignments.size() > MAX_ASSIGNMENTS)
            throw new IllegalArgumentException("Total recipe assignment budget exceeded");
        if (recipes.putIfAbsent(id, new RecipeDefinition(input, output, count, Map.copyOf(assignments), carryPolicy)) != null)
            throw new IllegalArgumentException("Duplicate recipe: " + id);
        assignmentCount += assignments.size();
        return this;
    }
    public ConfigurationSnapshot build() {
        ParameterSchema schema = fields.build();
        Map<String, ProductionRecipe> compiled = new LinkedHashMap<>();
        Map<String, CompiledExpression> cache = new HashMap<>();
        Set<String> sources = new HashSet<>();
        long characters = 0;
        for (RecipeDefinition definition : recipes.values()) for (String source : definition.assignments.values()) {
            if (source == null || source.length() > FormulaCompiler.MAX_SOURCE_LENGTH) throw new IllegalArgumentException("Formula exceeds source limit");
            if (sources.add(source)) characters += source.length();
            if (characters > MAX_UNIQUE_FORMULA_CHARS) throw new IllegalArgumentException("Unique formula source budget exceeded");
        }
        int[] instructions = {0};
        java.util.function.Function<String, CompiledExpression> compiler = source -> cache.computeIfAbsent(source, text -> {
            CompiledExpression result = FormulaCompiler.compile(schema, text);
            if ((long)instructions[0] + result.instructionCount() > MAX_COMPILED_INSTRUCTIONS)
                throw new IllegalArgumentException("Compiled instruction budget exceeded");
            instructions[0] += result.instructionCount();
            return result;
        });
        recipes.forEach((id, def) -> compiled.put(id, ProductionRecipe.compile(id, def.input, def.output,
                def.count, schema, def.assignments, compiler, def.carryPolicy)));
        return new ConfigurationSnapshot(schema, compiled);
    }
    private record RecipeDefinition(Item input, Item output, int count, Map<String, String> assignments, CarryPolicy carryPolicy) {}
}
