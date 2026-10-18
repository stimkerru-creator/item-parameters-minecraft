package dev.itemparams.production;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import dev.itemparams.formula.FormulaCompiler;
import dev.itemparams.formula.InputLayout;
import dev.itemparams.formula.InputValues;
import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent acceptance cases for the multi-component API, without Minecraft classes. */
class MultiComponentAcceptanceTest {
    private static final ParameterSchema BOLT = ParameterSchema.builder()
            .addRaw("part:mass", 0).addRaw("part:strength", 0).addRaw("part:unused", 730).build();
    private static final ParameterSchema BARREL = ParameterSchema.builder()
            .addRaw("part:mass", 0).addRaw("part:strength", 0).addRaw("part:energy", 0).build();
    private static final ParameterSchema OUTPUT = ParameterSchema.builder()
            .addRaw("product:mass", 100).addRaw("product:strength", 200)
            .addRaw("product:untouched", 999).addRaw("product:base", 0)
            .addRaw("part:mass", 501).addRaw("part:strength", 502).build();

    @Test void oneInputProducesASeparateOutputFromItsOwnSchemaAndBase() {
        InputLayout layout = InputLayout.of(Map.of("bolt", BOLT));
        ParameterValues bolt = bolt();
        ParameterRecipe recipe = ParameterRecipe.builder("test:single", layout, OUTPUT)
                .base("product:base", new BigDecimal("0.29"))
                .assign("product:mass", "${bolt::part:mass} * 2")
                .build();
        ParameterValues result = recipe.execute(layout.bind(Map.of("bolt", bolt)));
        assertSame(OUTPUT, result.schema());
        assertEquals(300, result.getRaw("product:mass"));
        assertEquals(29, result.getRaw("product:base"));
        assertEquals(999, result.getRaw("product:untouched"));
        assertEquals(501, result.getRaw("part:mass"), "Unassigned output must use its own base");
        assertEquals(150, bolt.getRaw("part:mass"));
    }

    @Test void twoInputsContributeToOneFormulaWithoutFieldIdCollisions() {
        Fixture fixture = fixture();
        ParameterRecipe recipe = massRecipe(fixture.layout);
        ParameterValues result = recipe.execute(fixture.inputs());
        assertEquals(400, result.getRaw("product:mass"));
        assertEquals(150, fixture.bolt.getRaw("part:mass"));
        assertEquals(250, fixture.barrel.getRaw("part:mass"));
    }

    @Test void fourComponentsImplementTheRequestedBoltPlateHingeSuspensionExample() {
        ParameterSchema boltSchema = ParameterSchema.builder().addRaw("part:mass", 20).build();
        ParameterSchema plateSchema = ParameterSchema.builder().addRaw("part:mass", 400).build();
        ParameterSchema hingeSchema = ParameterSchema.builder().addRaw("part:strength", 2500).build();
        ParameterSchema suspensionSchema = ParameterSchema.builder().addRaw("part:elasticity", 4000).build();
        ParameterSchema product = ParameterSchema.builder().addRaw("product:mass", 0)
                .addRaw("product:strength", 0).addRaw("product:elasticity", 0).build();
        InputLayout layout = InputLayout.of(Map.of("bolt", boltSchema, "plate", plateSchema,
                "hinge", hingeSchema, "suspension", suspensionSchema));
        ParameterRecipe recipe = ParameterRecipe.builder("test:four", layout, product)
                .assign("product:mass", "${bolt::part:mass} + ${plate::part:mass}")
                .assign("product:strength", "${hinge::part:strength} * 1.2")
                .transfer("product:elasticity", "suspension", "part:elasticity").build();
        ParameterValues result = recipe.execute(layout.bind(Map.of("bolt", boltSchema.defaults(),
                "plate", plateSchema.defaults(), "hinge", hingeSchema.defaults(),
                "suspension", suspensionSchema.defaults())));
        assertEquals(420, result.getRaw("product:mass"));
        assertEquals(3000, result.getRaw("product:strength"));
        assertEquals(4000, result.getRaw("product:elasticity"));
    }

    @Test void distinctInputAndOutputSchemasNeedNoSharedIndicesOrFieldNames() {
        ParameterSchema left = ParameterSchema.builder().addRaw("a:irrelevant", 700)
                .addRaw("a:length", 125).build();
        ParameterSchema right = ParameterSchema.builder().addRaw("b:width", 240).build();
        ParameterSchema product = ParameterSchema.builder().addRaw("c:area", 0).build();
        InputLayout layout = InputLayout.of(Map.of("left", left, "right", right));
        ParameterRecipe recipe = ParameterRecipe.compile("test:area", layout, product,
                Map.of("c:area", "${left::a:length} * ${right::b:width}"));
        ParameterValues result = recipe.execute(layout.bind(Map.of("left", left.defaults(), "right", right.defaults())));
        assertSame(product, result.schema());
        assertEquals(300, result.getRaw("c:area"));
    }

    @Test void changingOneComponentChangesItsNextResultUsingTheSameWorkspace() {
        Fixture fixture = fixture();
        ParameterRecipe recipe = massRecipe(fixture.layout);
        ParameterRecipe.Workspace workspace = recipe.newWorkspace();
        InputValues inputs = fixture.inputs();
        assertEquals(400, recipe.execute(inputs, workspace).getRaw("product:mass"));
        fixture.bolt.setRaw("part:mass", 151);
        assertEquals(401, recipe.execute(inputs, workspace).getRaw("product:mass"));
        fixture.barrel.setRaw("part:mass", 300);
        assertEquals(451, recipe.execute(inputs, workspace).getRaw("product:mass"));
    }

    @Test void namedRolesIgnoreInputMapInsertionOrder() {
        Fixture fixture = fixture();
        ParameterRecipe recipe = ParameterRecipe.compile("test:ordered", fixture.layout, OUTPUT,
                Map.of("product:mass", "${bolt::part:mass} * 10 + ${barrel::part:mass}"));
        Map<String, ParameterValues> reversed = new LinkedHashMap<>();
        reversed.put("barrel", fixture.barrel); reversed.put("bolt", fixture.bolt);
        assertEquals(1750, recipe.execute(fixture.layout.bind(reversed)).getRaw("product:mass"));
        assertEquals(recipe.execute(fixture.inputs()).toRawMap(),
                recipe.execute(fixture.layout.bind(reversed)).toRawMap());
    }

    @Test void missingFieldsAndRolesArePredictableAndSavedAbsenceUsesSchemaDefault() {
        Fixture fixture = fixture();
        assertThrows(IllegalArgumentException.class, () -> ParameterRecipe.compile("test:missing", fixture.layout,
                OUTPUT, Map.of("product:mass", "${bolt::part:missing}")));
        assertThrows(IllegalArgumentException.class, () -> ParameterRecipe.compile("test:role", fixture.layout,
                OUTPUT, Map.of("product:mass", "${unknown::part:mass}")));
        assertThrows(IllegalArgumentException.class, () -> fixture.layout.bind(Map.of("bolt", fixture.bolt)));
        assertThrows(IllegalArgumentException.class, () -> fixture.layout.bind(
                Map.of("bolt", fixture.bolt, "wrong", fixture.barrel)));
        ParameterSchema withDefault = ParameterSchema.builder().addRaw("part:mass", 123).build();
        ParameterValues absent = ParameterValues.fromRawMap(withDefault, Map.of());
        InputLayout layout = InputLayout.of(Map.of("part", withDefault));
        ParameterRecipe recipe = ParameterRecipe.compile("test:default", layout, OUTPUT,
                Map.of("product:mass", "${part::part:mass}"));
        assertEquals(123, recipe.execute(layout.bind(Map.of("part", absent))).getRaw("product:mass"));
    }

    @Test void invalidFormulaAndRuntimeDomainFailureNeverMutateAnyInput() {
        Fixture fixture = fixture();
        Map<String, Integer> boltBefore = fixture.bolt.toRawMap();
        Map<String, Integer> barrelBefore = fixture.barrel.toRawMap();
        assertThrows(IllegalArgumentException.class, () -> ParameterRecipe.compile("test:grammar", fixture.layout,
                OUTPUT, Map.of("product:mass", "${bolt::part:mass} +")));
        ParameterRecipe recipe = ParameterRecipe.builder("test:failure", fixture.layout, OUTPUT)
                .assign("product:mass", "${bolt::part:mass} * 2")
                .assign("product:strength", "1 / (${barrel::part:mass} - 2.5)").build();
        assertThrows(IllegalArgumentException.class, () -> recipe.execute(fixture.inputs(), recipe.newWorkspace()));
        assertEquals(boltBefore, fixture.bolt.toRawMap());
        assertEquals(barrelBefore, fixture.barrel.toRawMap());
    }

    @Test void legacySingleInputFormulaAndArrayApiRemainUsable() {
        ParameterValues input = bolt();
        assertEquals(550, FormulaCompiler.compile(BOLT, "${part:mass} + ${part:strength}").evaluateRaw(input));
        ParameterValues copy = input.copy();
        copy.setRaw("part:mass", 1);
        assertEquals(150, input.getRaw("part:mass"));
        assertEquals(1, copy.getRaw("part:mass"));
    }

    @Test void baseCommonCopyExplicitTransferAndFormulaHaveDeclaredPrecedence() {
        Fixture fixture = fixture();
        ParameterRecipe recipe = ParameterRecipe.builder("test:precedence", fixture.layout, OUTPUT)
                .assign("part:mass", "${bolt::part:mass} + ${barrel::part:mass}")
                .transfer("part:mass", "barrel", "part:mass")
                .transfer("part:strength", "barrel", "part:strength")
                .copyCommonFrom("bolt")
                .baseRaw("part:mass", 999).baseRaw("product:untouched", 123).build();
        ParameterValues result = recipe.execute(fixture.inputs());
        assertEquals(400, result.getRaw("part:mass"), "Formula overrides transfer/common/base");
        assertEquals(200, result.getRaw("part:strength"), "Explicit transfer overrides common copy");
        assertEquals(123, result.getRaw("product:untouched"));
        assertEquals(OUTPUT.size(), result.toRawMap().size());
        assertFalse(result.toRawMap().containsKey("old:retained"), "Unknown input data is not implicit output carry");
        assertFalse(result.toRawMap().containsKey("part:unused"), "Common copy means the known schema intersection");
    }

    @Test void assignmentOrderCannotChangeTheSourceSnapshot() {
        Fixture fixture = fixture();
        Map<String, String> first = new LinkedHashMap<>();
        first.put("part:mass", "${bolt::part:mass} + 1");
        first.put("product:mass", "${bolt::part:mass} * 2");
        Map<String, String> reverse = new LinkedHashMap<>();
        reverse.put("product:mass", first.get("product:mass")); reverse.put("part:mass", first.get("part:mass"));
        ParameterValues a = ParameterRecipe.compile("test:first", fixture.layout, OUTPUT, first).execute(fixture.inputs());
        ParameterValues b = ParameterRecipe.compile("test:reverse", fixture.layout, OUTPUT, reverse).execute(fixture.inputs());
        assertEquals(250, a.getRaw("part:mass"));
        assertEquals(300, a.getRaw("product:mass"));
        assertEquals(a.toRawMap(), b.toRawMap());
        assertEquals(150, fixture.bolt.getRaw("part:mass"));
    }

    @Test void resultCanBeAComponentOfTheNextRecipeWithAnIndependentOutputSchema() {
        Fixture fixture = fixture();
        ParameterValues assembly = massRecipe(fixture.layout).execute(fixture.inputs());
        ParameterSchema coating = ParameterSchema.builder().addRaw("coat:mass", 100).build();
        ParameterSchema finalSchema = ParameterSchema.builder().addRaw("final:mass", 0).build();
        InputLayout nextLayout = InputLayout.of(Map.of("assembly", OUTPUT, "coating", coating));
        ParameterRecipe next = ParameterRecipe.compile("test:coating", nextLayout, finalSchema,
                Map.of("final:mass", "${assembly::product:mass} + ${coating::coat:mass}"));
        ParameterValues finalResult = next.execute(nextLayout.bind(Map.of("assembly", assembly, "coating", coating.defaults())));
        assertEquals(500, finalResult.getRaw("final:mass"));
        assertEquals(400, assembly.getRaw("product:mass"));
    }

    @Test void returnedValuesAndEquivalentBindingsCannotAliasCachedState() {
        Fixture fixture = fixture();
        ParameterRecipe recipe = massRecipe(fixture.layout);
        ParameterRecipe.Workspace workspace = recipe.newWorkspace();
        ParameterValues first = recipe.execute(fixture.inputs(), workspace);
        first.setRaw("product:mass", -999);
        InputValues equivalent = fixture.layout.bind(Map.of("bolt", fixture.bolt.copy(), "barrel", fixture.barrel.copy()));
        ParameterValues second = recipe.execute(equivalent, workspace);
        assertNotSame(first, second);
        assertEquals(400, second.getRaw("product:mass"));
        second.replaceRaw(new int[OUTPUT.size()]);
        assertEquals(400, recipe.execute(fixture.inputs(), workspace).getRaw("product:mass"));
        workspace.clear();
        assertEquals(400, recipe.execute(fixture.inputs(), workspace).getRaw("product:mass"));
    }

    @Test void failedCombinationDoesNotBreakThePreviousSuccessfulCombination() {
        Fixture fixture = fixture();
        ParameterRecipe recipe = ParameterRecipe.compile("test:division", fixture.layout, OUTPUT,
                Map.of("product:mass", "${bolt::part:mass} / ${barrel::part:mass}"));
        ParameterRecipe.Workspace workspace = recipe.newWorkspace();
        InputValues inputs = fixture.inputs();
        assertEquals(60, recipe.execute(inputs, workspace).getRaw("product:mass"));
        fixture.barrel.setRaw("part:mass", 0);
        assertThrows(IllegalArgumentException.class, () -> recipe.execute(inputs, workspace));
        fixture.barrel.setRaw("part:mass", 250);
        assertEquals(60, recipe.execute(inputs, workspace).getRaw("product:mass"));
    }

    @Test void equivalentButDifferentSchemaLayoutAndWorkspaceOwnersAreRejected() {
        Fixture fixture = fixture();
        ParameterRecipe recipe = massRecipe(fixture.layout);
        ParameterSchema equivalentBolt = ParameterSchema.builder().addRaw("part:mass", 0)
                .addRaw("part:strength", 0).addRaw("part:unused", 730).build();
        assertThrows(IllegalArgumentException.class, () -> fixture.layout.bind(
                Map.of("bolt", equivalentBolt.defaults(), "barrel", fixture.barrel)));
        InputLayout anotherLayout = InputLayout.of(Map.of("bolt", BOLT, "barrel", BARREL));
        assertThrows(IllegalArgumentException.class, () -> recipe.execute(
                anotherLayout.bind(Map.of("bolt", fixture.bolt, "barrel", fixture.barrel))));
        ParameterRecipe otherRecipe = massRecipe(fixture.layout);
        assertThrows(IllegalArgumentException.class,
                () -> otherRecipe.execute(fixture.inputs(), recipe.newWorkspace()));
    }

    @Test void bindingCopiesRoleReferencesSoLaterMapEditsCannotSwapParts() {
        Fixture fixture = fixture();
        Map<String, ParameterValues> roles = new LinkedHashMap<>();
        roles.put("bolt", fixture.bolt); roles.put("barrel", fixture.barrel);
        InputValues bound = fixture.layout.bind(roles);
        roles.put("bolt", BOLT.defaults()); roles.remove("barrel");
        assertSame(fixture.bolt, bound.get("bolt"));
        assertSame(fixture.barrel, bound.get("barrel"));
        assertEquals(400, massRecipe(fixture.layout).execute(bound).getRaw("product:mass"));
    }

    @Test void optimizerMustNotTreatEqualFieldIdsOnDifferentRolesAsTheSameOperand() {
        Fixture fixture = fixture();
        ParameterRecipe recipe = ParameterRecipe.compile("test:coordinates", fixture.layout, OUTPUT, Map.of(
                "product:mass", "${bolt::part:mass} - ${barrel::part:mass}",
                "product:strength", "${bolt::part:mass} / ${barrel::part:mass}"));
        ParameterValues result = recipe.execute(fixture.inputs());
        assertEquals(-100, result.getRaw("product:mass"));
        assertEquals(60, result.getRaw("product:strength"));
    }

    @Test void overwrittenTransferDoesNotRejectAValidFinalOutputRange() {
        Fixture fixture = fixture();
        ParameterSchema constrained = ParameterSchema.builder().addRaw("part:mass", 0, 0, 100).build();
        ParameterRecipe recipe = ParameterRecipe.builder("test:final_range", fixture.layout, constrained)
                .copyCommonFrom("bolt").transfer("part:mass", "bolt", "part:mass")
                .assign("part:mass", "${bolt::part:mass} / 2").build();
        assertEquals(75, recipe.execute(fixture.inputs()).getRaw("part:mass"));
    }

    @Test void cacheRetainsExactlyOneSuccessfulSnapshotAndDoesNotRecopyOnHits() throws Exception {
        Fixture fixture = fixture();
        ParameterRecipe recipe = massRecipe(fixture.layout);
        ParameterRecipe.Workspace workspace = recipe.newWorkspace();
        InputValues inputs = fixture.inputs();
        recipe.execute(inputs, workspace);
        Object firstSnapshots = retained(workspace, "snapshots");
        Object firstOutput = retained(workspace, "output");
        recipe.execute(inputs, workspace);
        assertSame(firstSnapshots, retained(workspace, "snapshots"));
        assertSame(firstOutput, retained(workspace, "output"));
        recipe.execute(fixture.layout.bind(Map.of("bolt", fixture.bolt.copy(), "barrel", fixture.barrel.copy())), workspace);
        assertSame(firstSnapshots, retained(workspace, "snapshots"), "Semantic hit must not clone inputs");

        fixture.bolt.setRaw("part:mass", 151);
        assertEquals(401, recipe.execute(inputs, workspace).getRaw("product:mass"));
        Object secondSnapshots = retained(workspace, "snapshots");
        Object secondOutput = retained(workspace, "output");
        assertNotSame(firstSnapshots, secondSnapshots);
        assertNotSame(firstOutput, secondOutput);
        fixture.bolt.setRaw("part:mass", Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> recipe.execute(inputs, workspace));
        assertSame(secondSnapshots, retained(workspace, "snapshots"));
        assertSame(secondOutput, retained(workspace, "output"));
        fixture.bolt.setRaw("part:mass", 151);
        assertEquals(401, recipe.execute(inputs, workspace).getRaw("product:mass"));
        assertSame(secondSnapshots, retained(workspace, "snapshots"));
        workspace.clear();
        assertNull(retained(workspace, "snapshots"));
        assertNull(retained(workspace, "output"));
    }

    /** Inspect retention solely to verify the promised no-copy hit path, not to alter it. */
    private static Object retained(ParameterRecipe.Workspace workspace, String name) throws Exception {
        Field field = workspace.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(workspace);
    }

    private static ParameterRecipe massRecipe(InputLayout layout) {
        return ParameterRecipe.compile("test:mass", layout, OUTPUT,
                Map.of("product:mass", "${bolt::part:mass} + ${barrel::part:mass}"));
    }
    private static ParameterValues bolt() {
        return ParameterValues.fromRawMap(BOLT, Map.of("part:mass", 150, "part:strength", 400,
                "part:unused", 730, "old:retained", 123));
    }
    private static Fixture fixture() {
        return new Fixture(InputLayout.of(Map.of("bolt", BOLT, "barrel", BARREL)), bolt(),
                ParameterValues.fromRawMap(BARREL, Map.of("part:mass", 250, "part:strength", 200, "part:energy", 300)));
    }
    private record Fixture(InputLayout layout, ParameterValues bolt, ParameterValues barrel) {
        InputValues inputs() { return layout.bind(Map.of("bolt", bolt, "barrel", barrel)); }
    }
}
