package dev.itemparams.fabric.machine;

import dev.itemparams.catalog.ConfiguredItemParameters;
import dev.itemparams.catalog.ConfiguredItemRecipe;
import dev.itemparams.catalog.ItemCatalogSnapshot;
import dev.itemparams.catalog.ItemDefinition;
import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import dev.itemparams.fabric.ItemParameterStorage;
import dev.itemparams.formula.InputLayout;
import dev.itemparams.production.ParameterRecipe;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Integration contract for the catalog-backed demo processor. */
class ProcessorBlockEntityTest {
    @BeforeAll static void initializeMinecraftTypes() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        ProcessorRegistry.BLOCK_ENTITY_TYPE = BlockEntityType.Builder
                .create(ProcessorBlockEntity::new, ProcessorRegistry.BLOCK).build(null);
    }

    @AfterEach void clearCatalog() { ConfiguredItemParameters.install(ItemCatalogSnapshot.empty()); }

    @Test void configuredDefaultsAreMaterializedServerSideAndShownInNbt() {
        ParameterSchema schema = schema("test:x", "test:y");
        ItemDefinition stick = definition(Items.STICK, schema);
        ItemCatalogSnapshot catalog = catalog(Map.of(stick.id(), stick), Map.of());
        ConfiguredItemParameters.install(catalog);

        ItemStack stack = new ItemStack(Items.STICK);
        assertTrue(ConfiguredItemParameters.initializeIfMissing(stack));
        assertEquals(100, ItemParameterStorage.read(stack, schema).getRaw("test:x"));
        assertEquals(200, ItemParameterStorage.read(stack, schema).getRaw("test:y"));
        assertFalse(ConfiguredItemParameters.initializeIfMissing(stack));
    }

    @Test void removedSchemaFieldIsNoLongerActiveButUnrelatedNbtSurvives() {
        ParameterSchema oldSchema = ParameterSchema.builder().addRaw("test:x", 100).addRaw("test:retired", 200).build();
        ItemDefinition oldDefinition = definition(Items.STICK, oldSchema);
        ItemStack stack = values(Items.STICK, oldSchema, Map.of("test:x", 534, "test:retired", 777), 1);
        stack.getOrCreateNbt().putString("other:tag", "preserve");
        ConfiguredItemParameters.install(catalog(Map.of(oldDefinition.id(), oldDefinition), Map.of()));
        assertFalse(ConfiguredItemParameters.initializeIfMissing(stack));

        ParameterSchema newSchema = ParameterSchema.builder().addRaw("test:x", 100).build();
        ItemDefinition newDefinition = definition(Items.STICK, newSchema);
        ConfiguredItemParameters.install(catalog(Map.of(newDefinition.id(), newDefinition), Map.of()));
        assertTrue(ConfiguredItemParameters.initializeIfMissing(stack));
        assertEquals(Map.of("test:x", 534), dev.itemparams.fabric.ParameterNbtStorage.storedRaw(stack.getNbt()));
        assertEquals("preserve", stack.getNbt().getString("other:tag"));
        assertFalse(ConfiguredItemParameters.initializeIfMissing(stack),
                "Retired IDs must not be reintroduced and rewritten every 10 ticks");
    }

    @Test void oneInputRecipeCreatesNewItemWithCopiedChangedAndNewFields() {
        Fixture fixture = oneInputFixture("test:make", "${wood::test:x} + 1", "sqrt(16)");
        ConfiguredItemParameters.install(fixture.catalog);
        ProcessorBlockEntity machine = machine();
        ItemStack input = input(100, 3);
        machine.setStack(0, input);

        tick(machine, fixture.catalog, 20);

        assertEquals(2, machine.getStack(0).getCount());
        assertEquals(1, machine.getStack(ProcessorBlockEntity.OUTPUT_SLOT).getCount());
        assertTrue(machine.getStack(ProcessorBlockEntity.OUTPUT_SLOT).isOf(Items.GOLD_INGOT));
        ParameterValues result = ItemParameterStorage.read(machine.getStack(ProcessorBlockEntity.OUTPUT_SLOT), fixture.output.schema());
        assertEquals(200, result.getRaw("test:x"));
        assertEquals(200, result.getRaw("test:y"));
        assertEquals(400, result.getRaw("test:new"));
    }

    @Test void fixedOutputIsReusedAfterSaveLoadWithoutReevaluatingChangedFormula() {
        Fixture first = oneInputFixture("test:make", "${wood::test:x} + 1", "sqrt(16)");
        ConfiguredItemParameters.install(first.catalog);
        ProcessorBlockEntity original = machine();
        original.setStack(0, input(100, 4));
        tick(original, first.catalog, 1);
        NbtCompound saved = new NbtCompound();
        original.writeNbt(saved);
        assertTrue(saved.contains("InputProfiles"));
        assertTrue(saved.contains("CalculatedOutput"));

        Fixture changedFormula = oneInputFixture("test:make", "1 / (${wood::test:x} - 1)", "sqrt(16)");
        ConfiguredItemParameters.install(changedFormula.catalog);
        ProcessorBlockEntity loaded = machine();
        loaded.readNbt(saved);
        tick(loaded, changedFormula.catalog, 20);
        assertEquals(3, loaded.getStack(0).getCount());
        assertEquals(1, loaded.getStack(ProcessorBlockEntity.OUTPUT_SLOT).getCount());
        assertEquals(200, ItemParameterStorage.read(loaded.getStack(ProcessorBlockEntity.OUTPUT_SLOT), changedFormula.output.schema()).getRaw("test:x"));
    }

    @Test void differentNumericStateIsRejectedAfterFirstSuccessfulCalculation() {
        Fixture fixture = oneInputFixture("test:make", "${wood::test:x} + 1", "sqrt(16)");
        ConfiguredItemParameters.install(fixture.catalog);
        ProcessorBlockEntity machine = machine();
        machine.setStack(0, input(100, 2));
        tick(machine, fixture.catalog, 1);

        ItemStack changed = input(101, 1);
        ItemStack before = changed.copy();
        assertFalse(machine.acceptsInput(0, changed));
        machine.setStack(0, changed);
        assertTrue(ItemStack.areEqual(before, changed));
        assertEquals(2, machine.getStack(0).getCount());

        tick(machine, fixture.catalog, 19);
        assertEquals(1, machine.getStack(0).getCount());
        assertEquals(200, ItemParameterStorage.read(machine.getStack(ProcessorBlockEntity.OUTPUT_SLOT), fixture.output.schema()).getRaw("test:x"));
    }

    @Test void explicitReconfigureUnlocksSameRecipeOnlyAfterInputsAreRemoved() {
        Fixture fixture = oneInputFixture("test:make", "${wood::test:x} + 1", "sqrt(16)");
        ConfiguredItemParameters.install(fixture.catalog);
        ProcessorBlockEntity machine = machine();
        machine.setStack(0, input(100, 1));
        tick(machine, fixture.catalog, 1);
        assertTrue(machine.isInputLocked());
        assertFalse(machine.canReconfigure());

        machine.removeStack(0, 1);
        assertTrue(machine.canReconfigure());
        assertTrue(machine.reconfigure(null));
        assertFalse(machine.isInputLocked());
        assertTrue(machine.acceptsInput(0, input(101, 1)));
        machine.setStack(0, input(101, 1));
        tick(machine, fixture.catalog, ProcessorBlockEntity.OPERATION_TICKS);
        assertEquals(201, ItemParameterStorage.read(machine.getStack(ProcessorBlockEntity.OUTPUT_SLOT), fixture.output.schema()).getRaw("test:x"));
    }

    @Test void fullOutputDoesNotConsumeTheNextLockedInput() {
        Fixture fixture = oneInputFixture("test:make", "${wood::test:x} + 1", "sqrt(16)");
        ConfiguredItemParameters.install(fixture.catalog);
        ProcessorBlockEntity machine = machine();
        machine.setStack(0, input(100, 64));
        tick(machine, fixture.catalog, 64 * ProcessorBlockEntity.OPERATION_TICKS);
        assertEquals(64, machine.getStack(ProcessorBlockEntity.OUTPUT_SLOT).getCount());
        assertTrue(machine.getStack(0).isEmpty());

        ItemStack next = input(100, 1);
        machine.setStack(0, next);
        tick(machine, fixture.catalog, ProcessorBlockEntity.OPERATION_TICKS);
        assertEquals(1, machine.getStack(0).getCount());
        assertEquals(64, machine.getStack(ProcessorBlockEntity.OUTPUT_SLOT).getCount());
        assertEquals(ProcessorBlockEntity.OUTPUT_FULL, machine.status());
    }

    @Test void multipleInputRolesAreConsumedAtomicallyAndCreateOneOutput() {
        ParameterSchema boltSchema = schema("test:mass", "test:strength");
        ParameterSchema plateSchema = schema("test:mass", "test:quality");
        ParameterSchema outputSchema = ParameterSchema.builder()
                .addRaw("test:mass", 0).addRaw("test:strength", 0).addRaw("test:quality", 0).build();
        ItemDefinition bolt = definition(Items.IRON_INGOT, boltSchema);
        ItemDefinition plate = definition(Items.STICK, plateSchema);
        ItemDefinition output = definition(Items.GOLD_INGOT, outputSchema);
        Map<String, ItemDefinition> roles = new LinkedHashMap<>();
        roles.put("bolt", bolt);
        roles.put("plate", plate);
        Map<String, ParameterSchema> layouts = new LinkedHashMap<>();
        layouts.put("bolt", boltSchema);
        layouts.put("plate", plateSchema);
        ParameterRecipe formula = ParameterRecipe.builder("test:assemble", InputLayout.of(layouts), outputSchema)
                .assign("test:mass", "${bolt::test:mass} + ${plate::test:mass}")
                .assign("test:strength", "${bolt::test:strength}")
                .assign("test:quality", "${plate::test:quality}")
                .build();
        ConfiguredItemRecipe recipe = new ConfiguredItemRecipe("test:assemble", roles, output, 1, formula);
        ItemCatalogSnapshot catalog = catalog(Map.of(bolt.id(), bolt, plate.id(), plate, output.id(), output), Map.of(recipe.id(), recipe));
        ConfiguredItemParameters.install(catalog);

        ProcessorBlockEntity machine = machine();
        machine.setStack(0, values(Items.IRON_INGOT, boltSchema, Map.of("test:mass", 250, "test:strength", 500), 2));
        machine.setStack(1, values(Items.STICK, plateSchema, Map.of("test:mass", 75, "test:quality", 900), 2));
        tick(machine, catalog, 20);

        assertEquals(1, machine.getStack(0).getCount());
        assertEquals(1, machine.getStack(1).getCount());
        ParameterValues result = ItemParameterStorage.read(machine.getStack(ProcessorBlockEntity.OUTPUT_SLOT), outputSchema);
        assertEquals(325, result.getRaw("test:mass"));
        assertEquals(500, result.getRaw("test:strength"));
        assertEquals(900, result.getRaw("test:quality"));
    }

    @Test void unsupportedRecipeWithMoreInputsThanDemoSlotsIsNotSelected() {
        ParameterSchema schema = schema("test:x");
        ItemDefinition input = definition(Items.STICK, schema);
        ItemDefinition output = definition(Items.BOWL, schema);
        Map<String, ItemDefinition> roles = new LinkedHashMap<>();
        Map<String, ParameterSchema> layouts = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            String role = "part" + i;
            roles.put(role, input);
            layouts.put(role, schema);
        }
        ParameterRecipe formula = ParameterRecipe.builder("test:five_parts", InputLayout.of(layouts), schema)
                .assign("test:x", "${part0::test:x}").build();
        ConfiguredItemRecipe recipe = new ConfiguredItemRecipe("test:five_parts", roles, output, 1, formula);
        ConfiguredItemParameters.install(catalog(Map.of(input.id(), input, output.id(), output), Map.of(recipe.id(), recipe)));
        ProcessorBlockEntity machine = machine();
        machine.processTick(ConfiguredItemParameters.current());
        assertEquals(ProcessorBlockEntity.INVALID_RECIPE, machine.status());
    }

    @Test void invalidDirectInsertionIsRejectedWithoutThrowingOrMutatingCandidate() {
        Fixture fixture = oneInputFixture("test:make", "${wood::test:x} + 1", "sqrt(16)");
        ConfiguredItemParameters.install(fixture.catalog);
        ProcessorBlockEntity machine = machine();
        ItemStack wrong = new ItemStack(Items.DIAMOND, 7);
        ItemStack before = wrong.copy();
        machine.setStack(0, wrong);
        assertTrue(machine.getStack(0).isEmpty());
        assertTrue(ItemStack.areEqual(before, wrong));
    }

    @Test void hopperAndPlayerShareRecipeFilterButRespectSides() {
        Fixture fixture = oneInputFixture("test:make", "${wood::test:x} + 1", "sqrt(16)");
        ConfiguredItemParameters.install(fixture.catalog);
        ProcessorBlockEntity machine = machine();
        ItemStack valid = input(100, 1);
        assertTrue(machine.isValid(0, valid));
        assertTrue(machine.canInsert(0, valid, net.minecraft.util.math.Direction.UP));
        assertTrue(machine.canInsert(0, valid, net.minecraft.util.math.Direction.NORTH));
        assertFalse(machine.canInsert(0, valid, net.minecraft.util.math.Direction.DOWN));
        assertFalse(machine.canInsert(ProcessorBlockEntity.OUTPUT_SLOT, valid, net.minecraft.util.math.Direction.UP));
        assertFalse(machine.canInsert(0, new ItemStack(Items.DIAMOND), net.minecraft.util.math.Direction.UP));
        assertTrue(machine.canExtract(ProcessorBlockEntity.OUTPUT_SLOT, ItemStack.EMPTY,
                net.minecraft.util.math.Direction.DOWN));
        assertFalse(machine.canExtract(ProcessorBlockEntity.OUTPUT_SLOT, ItemStack.EMPTY,
                net.minecraft.util.math.Direction.NORTH));
        assertFalse(machine.canExtract(0, valid, net.minecraft.util.math.Direction.DOWN));
        machine.setStack(0, valid);
        tick(machine, fixture.catalog, 1); // confirm and lock 1.00 once
        assertFalse(machine.canInsert(0, input(101, 1), net.minecraft.util.math.Direction.UP));
        assertFalse(machine.isValid(0, input(101, 1)));
        assertTrue(machine.canInsert(0, input(100, 1), net.minecraft.util.math.Direction.NORTH));
    }

    @Test void modifiedInputInPlaceCannotBeConsumedAfterInitialConfirmation() {
        Fixture fixture = oneInputFixture("test:make", "${wood::test:x} + 1", "sqrt(16)");
        ConfiguredItemParameters.install(fixture.catalog);
        ProcessorBlockEntity machine = machine();
        machine.setStack(0, input(100, 2));
        tick(machine, fixture.catalog, 1);
        // Simulates an external mod mutating a live stack reference without setStack().
        ItemParameterStorage.edit(machine.getStack(0), fixture.input.schema(),
                params -> params.setRaw("test:x", 101));
        tick(machine, fixture.catalog, ProcessorBlockEntity.OPERATION_TICKS - 1);
        assertEquals(2, machine.getStack(0).getCount());
        assertTrue(machine.getStack(ProcessorBlockEntity.OUTPUT_SLOT).isEmpty());
        assertEquals(ProcessorBlockEntity.ERROR, machine.status());
    }

    @Test void changedOutputAfterReconfigurationWaitsForOldOutputToBeRemoved() {
        Fixture fixture = oneInputFixture("test:make", "${wood::test:x} + 1", "sqrt(16)");
        ConfiguredItemParameters.install(fixture.catalog);
        ProcessorBlockEntity machine = machine();
        machine.setStack(0, input(100, 1));
        tick(machine, fixture.catalog, ProcessorBlockEntity.OPERATION_TICKS);
        assertEquals(200, ItemParameterStorage.read(machine.getStack(ProcessorBlockEntity.OUTPUT_SLOT),
                fixture.output.schema()).getRaw("test:x"));
        assertTrue(machine.reconfigure(null));
        machine.setStack(0, input(101, 1));
        tick(machine, fixture.catalog, ProcessorBlockEntity.OPERATION_TICKS);
        assertEquals(ProcessorBlockEntity.OUTPUT_FULL, machine.status());
        assertEquals(1, machine.getStack(0).getCount());
        assertEquals(200, ItemParameterStorage.read(machine.getStack(ProcessorBlockEntity.OUTPUT_SLOT),
                fixture.output.schema()).getRaw("test:x"));
        machine.removeStack(ProcessorBlockEntity.OUTPUT_SLOT);
        tick(machine, fixture.catalog, 1);
        assertTrue(machine.getStack(0).isEmpty());
        assertEquals(201, ItemParameterStorage.read(machine.getStack(ProcessorBlockEntity.OUTPUT_SLOT),
                fixture.output.schema()).getRaw("test:x"));
    }

    private static ProcessorBlockEntity machine() {
        return new ProcessorBlockEntity(BlockPos.ORIGIN, ProcessorRegistry.BLOCK.getDefaultState());
    }

    private static void tick(ProcessorBlockEntity machine, ItemCatalogSnapshot catalog, int count) {
        for (int i = 0; i < count; i++) machine.processTick(catalog);
    }

    private static ParameterSchema schema(String... ids) {
        ParameterSchema.Builder builder = ParameterSchema.builder();
        for (int i = 0; i < ids.length; i++)
            builder.addRaw(ids[i], (i + 1) * 100, Integer.MIN_VALUE, Integer.MAX_VALUE);
        return builder.build();
    }

    private static ItemDefinition definition(Item item, ParameterSchema schema) {
        return new ItemDefinition(Registries.ITEM.getId(item), item, schema);
    }

    private static ItemStack input(int rawX, int count) {
        ParameterSchema schema = ParameterSchema.builder().addRaw("test:x", 100).addRaw("test:y", 200).build();
        return values(Items.STICK, schema, Map.of("test:x", rawX, "test:y", 200), count);
    }

    private static ItemStack values(Item item, ParameterSchema schema, Map<String, Integer> raw, int count) {
        ItemStack stack = new ItemStack(item, count);
        ParameterValues values = schema.defaults();
        raw.forEach(values::setRaw);
        ItemParameterStorage.write(stack, values);
        return stack;
    }

    private static Fixture oneInputFixture(String recipeId, String strengthFormula, String newFormula) {
        ParameterSchema inputSchema = ParameterSchema.builder()
                .addRaw("test:x", 100).addRaw("test:y", 200).build();
        ParameterSchema outputSchema = ParameterSchema.builder()
                .addRaw("test:x", 0).addRaw("test:y", 0).addRaw("test:new", 0).build();
        ItemDefinition input = definition(Items.STICK, inputSchema);
        ItemDefinition output = definition(Items.GOLD_INGOT, outputSchema);
        Map<String, ItemDefinition> roles = Map.of("wood", input);
        ParameterRecipe formula = ParameterRecipe.builder(recipeId,
                        InputLayout.of(Map.of("wood", inputSchema)), outputSchema)
                .copyCommonFrom("wood")
                .assign("test:x", strengthFormula)
                .assign("test:new", newFormula)
                .build();
        ConfiguredItemRecipe recipe = new ConfiguredItemRecipe(recipeId, roles, output, 1, formula);
        return new Fixture(catalog(Map.of(input.id(), input, output.id(), output), Map.of(recipeId, recipe)), input, output);
    }

    private static ItemCatalogSnapshot catalog(Map<Identifier, ItemDefinition> items,
                                               Map<String, ConfiguredItemRecipe> recipes) {
        return new ItemCatalogSnapshot(items, recipes);
    }

    private record Fixture(ItemCatalogSnapshot catalog, ItemDefinition input, ItemDefinition output) {}
}
