package dev.itemparams.fabric;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import dev.itemparams.production.ProductionException;
import dev.itemparams.production.ProductionRecipe;
import dev.itemparams.production.ProductionOperation;
import dev.itemparams.production.CarryPolicy;
import dev.itemparams.formula.FormulaCompiler;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StorageProductionTest {
    private static final ParameterSchema SCHEMA = ParameterSchema.builder()
            .addRaw("test:x", 100).addRaw("test:y", 200).build();

    @BeforeAll static void bootstrapMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test void missingPayloadReadIsPureAndDetached() {
        ItemStack item = new ItemStack(Items.IRON_INGOT);
        ParameterValues values = ItemParameterStorage.read(item, SCHEMA);
        assertNull(item.getNbt());
        assertEquals(100, values.getRaw("test:x"));
        values.setRaw("test:x", 123);
        assertEquals(100, ItemParameterStorage.read(item, SCHEMA).getRaw("test:x"));
        ItemParameterStorage.write(item, values);
        assertEquals(123, ItemParameterStorage.read(item, SCHEMA).getRaw("test:x"));
    }

    @Test void independentItemsCopyAndMinecraftSerializationRoundTrip() {
        ItemStack first = new ItemStack(Items.IRON_INGOT);
        ItemStack second = new ItemStack(Items.IRON_INGOT);
        ParameterValues values = SCHEMA.defaults();
        values.setRaw("test:x", Integer.MIN_VALUE);
        values.setRaw("test:y", Integer.MAX_VALUE);
        ItemParameterStorage.write(first, values);
        assertEquals(100, ItemParameterStorage.read(second, SCHEMA).getRaw("test:x"));
        ItemStack copy = first.copy();
        ItemStack loaded = ItemStack.fromNbt(first.writeNbt(new NbtCompound()));
        assertEquals(Integer.MIN_VALUE, ItemParameterStorage.read(loaded, SCHEMA).getRaw("test:x"));
        assertEquals(Integer.MAX_VALUE, ItemParameterStorage.read(loaded, SCHEMA).getRaw("test:y"));
        values.setRaw("test:x", 90);
        ItemParameterStorage.write(first, values);
        assertEquals(Integer.MIN_VALUE, ItemParameterStorage.read(copy, SCHEMA).getRaw("test:x"));
    }

    @Test void remapsReorderedFieldsAddsDefaultsAndPreservesRemovedFields() {
        ItemStack item = new ItemStack(Items.IRON_INGOT);
        ParameterValues old = SCHEMA.defaults();
        old.setRaw("test:x", 1234);
        old.setRaw("test:y", -56);
        ItemParameterStorage.write(item, old);
        ParameterSchema next = ParameterSchema.builder().addRaw("test:y", 900)
                .addRaw("test:z", 345).build();
        ParameterValues remapped = ItemParameterStorage.read(item, next);
        assertEquals(-56, remapped.getRaw("test:y"));
        assertEquals(345, remapped.getRaw("test:z"));
        remapped.setRaw("test:y", 700);
        ItemParameterStorage.write(item, remapped);
        assertEquals(1234, ItemParameterStorage.read(item, SCHEMA).getRaw("test:x"));
        assertEquals(700, ItemParameterStorage.read(item, SCHEMA).getRaw("test:y"));
        // A caller creating defaults still cannot discard fields absent from its schema.
        ItemParameterStorage.write(item, next.defaults());
        assertEquals(1234, ItemParameterStorage.read(item, SCHEMA).getRaw("test:x"));
    }

    @Test void preservesUnrelatedAndForwardMetadataWhenWriting() {
        ItemStack item = validItem();
        item.getOrCreateNbt().putString("othermod:data", "preserved");
        payload(item).putString("extension", "preserved");
        ItemParameterStorage.write(item, SCHEMA.defaults());
        assertEquals("preserved", item.getNbt().getString("othermod:data"));
        assertEquals("preserved", payload(item).getString("extension"));
    }

    @Test void canonicalIdsAllowEquivalentStacksToCombineDespiteDifferentSchemaHistory() {
        ItemStack first = validItem();
        ItemStack second = new ItemStack(Items.IRON_INGOT);
        ParameterSchema reversed = ParameterSchema.builder().addRaw("test:y", 200).addRaw("test:x", 100).build();
        ItemParameterStorage.write(second, reversed.defaults());
        assertEquals(first.getNbt(), second.getNbt());
        assertTrue(ItemStack.canCombine(first, second));
    }

    @Test void detachedBulkEditCommitsOnlyAfterSuccessfulCallback() {
        ItemStack item = validItem();
        NbtCompound before = item.getNbt().copy();
        assertThrows(IllegalStateException.class, () -> ItemParameterStorage.edit(item, SCHEMA, values -> {
            values.setRaw("test:x", 700);
            throw new IllegalStateException("cancel operation");
        }));
        assertEquals(before, item.getNbt());
        ItemParameterStorage.edit(item, SCHEMA, values -> {
            values.setRaw("test:x", 700);
            values.setRaw("test:y", 800);
        });
        assertEquals(700, ItemParameterStorage.read(item, SCHEMA).getRaw("test:x"));
        assertEquals(800, ItemParameterStorage.read(item, SCHEMA).getRaw("test:y"));
    }

    @Test void rejectsFutureVersionsWrongTypesAndScaleWithoutMutatingSource() {
        ItemStack item = validItem();
        payload(item).putInt("version", 2);
        assertRejectedWithoutMutation(item);
        item = validItem();
        payload(item).putShort("version", (short) 1);
        assertRejectedWithoutMutation(item);
        item = validItem();
        payload(item).putInt("scale", 1000);
        assertRejectedWithoutMutation(item);
        item = validItem();
        payload(item).putString("values", "incorrect type");
        assertRejectedWithoutMutation(item);
        item = validItem();
        item.getNbt().putString(ItemParameterStorage.NBT_KEY, "incorrect root type");
        assertRejectedWithoutMutation(item);
    }

    @Test void tightenedConfiguredBoundsRejectHistoricalValuesWithoutRewritingThem() {
        ItemStack item = validItem();
        ParameterSchema restricted = ParameterSchema.builder().addRaw("test:x", 0, 0, 50)
                .addRaw("test:y", 200).build();
        NbtCompound before = item.getNbt().copy();
        assertThrows(IllegalArgumentException.class, () -> ItemParameterStorage.read(item, restricted));
        assertThrows(IllegalArgumentException.class, () -> ItemParameterStorage.write(item, restricted.defaults()));
        assertEquals(before, item.getNbt());
    }

    @Test void rejectsDuplicateIdsInvalidIdsAndArrayMismatch() {
        ItemStack item = validItem();
        NbtList ids = new NbtList();
        ids.add(NbtString.of("test:x"));
        ids.add(NbtString.of("test:x"));
        payload(item).put("ids", ids);
        assertRejectedWithoutMutation(item);
        item = validItem();
        ids = new NbtList();
        ids.add(NbtString.of("NOT_VALID"));
        ids.add(NbtString.of("test:y"));
        payload(item).put("ids", ids);
        assertRejectedWithoutMutation(item);
        item = validItem();
        payload(item).putIntArray("values", new int[]{100});
        assertRejectedWithoutMutation(item);
    }

    @Test void productionUsesSameSnapshotAndCreatesIndependentChainableOutput() {
        Map<String, String> assignments = new LinkedHashMap<>();
        assignments.put("test:x", "${test:y}+1");
        assignments.put("test:y", "${test:x}+1");
        ProductionRecipe recipe = ProductionRecipe.compile("test:smelt", Items.IRON_INGOT,
                Items.GOLD_INGOT, 1, SCHEMA, assignments);
        ItemStack input = validItem();
        input.getOrCreateNbt().putString("othermod:input-only", "input");
        NbtCompound before = input.getNbt().copy();
        ItemStack output = recipe.execute(input);
        assertNotSame(input, output);
        assertEquals(before, input.getNbt());
        assertFalse(output.getNbt().contains("othermod:input-only"));
        ParameterValues result = ItemParameterStorage.read(output, SCHEMA);
        assertEquals(300, result.getRaw("test:x"));
        assertEquals(200, result.getRaw("test:y"));
        ProductionRecipe followup = ProductionRecipe.compile("test:second", Items.GOLD_INGOT,
                Items.DIAMOND, 1, SCHEMA, Map.of("test:x", "${test:x}*2"));
        assertEquals(600, ItemParameterStorage.read(followup.execute(output), SCHEMA).getRaw("test:x"));
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("test:y", assignments.get("test:y"));
        reversed.put("test:x", assignments.get("test:x"));
        ProductionRecipe reordered = ProductionRecipe.compile("test:reordered", Items.IRON_INGOT,
                Items.GOLD_INGOT, 1, SCHEMA, reversed);
        assertEquals(result.toRawMap(), ItemParameterStorage.read(reordered.execute(input), SCHEMA).toRawMap());
    }

    @Test void failingLastFormulaAndWrongInputLeaveOriginalUntouched() {
        Map<String, String> assignments = new LinkedHashMap<>();
        assignments.put("test:x", "42");
        assignments.put("test:y", "1/(${test:y}-2)");
        ProductionRecipe recipe = ProductionRecipe.compile("test:invalid", Items.IRON_INGOT,
                Items.GOLD_INGOT, 1, SCHEMA, assignments);
        ItemStack input = validItem();
        NbtCompound before = input.getNbt().copy();
        assertThrows(ProductionException.class, () -> recipe.execute(input));
        assertEquals(before, input.getNbt());
        assertEquals(1, input.getCount());
        assertThrows(IllegalArgumentException.class, () -> recipe.execute(new ItemStack(Items.STICK)));
    }

    @Test void copyInputCarriesUnassignedAndUnknownParametersDefaultsPolicyResetsThem() {
        ItemStack input = new ItemStack(Items.IRON_INGOT);
        ItemParameterStorage.write(input, ParameterValues.fromRawMap(SCHEMA,
                Map.of("test:x", 100, "test:y", 321, "extension:retained", 789)));
        ProductionRecipe carry = ProductionRecipe.compile("test:carry", Items.IRON_INGOT,
                Items.GOLD_INGOT, 1, SCHEMA, Map.of("test:x", "5"));
        ParameterValues carried = ItemParameterStorage.read(carry.execute(input), SCHEMA);
        assertEquals(321, carried.getRaw("test:y"));
        assertEquals(789, carried.toRawMap().get("extension:retained"));
        ProductionRecipe reset = ProductionRecipe.compile("test:reset", Items.IRON_INGOT,
                Items.GOLD_INGOT, 1, SCHEMA, Map.of("test:x", "5"),
                expression -> FormulaCompiler.compile(SCHEMA, expression), CarryPolicy.DEFAULTS);
        ParameterValues defaults = ItemParameterStorage.read(reset.execute(input), SCHEMA);
        assertEquals(200, defaults.getRaw("test:y"));
        assertFalse(defaults.toRawMap().containsKey("extension:retained"));
    }

    @Test void inventoryPlanChecksCapacityBeforeConsumingAndDoesNotAliasSources() {
        ProductionRecipe recipe = ProductionRecipe.compile("test:inventory", Items.IRON_INGOT,
                Items.GOLD_INGOT, 1, SCHEMA, Map.of("test:x", "${test:x}+1"));
        ItemStack input = validItem();
        input.setCount(2);
        ItemStack output = recipe.execute(input);
        output.setCount(64);
        NbtCompound inputBefore = input.getNbt().copy();
        NbtCompound outputBefore = output.getNbt().copy();
        assertTrue(ProductionOperation.prepare(input, output, recipe, recipe.newWorkspace(), 64).isEmpty());
        assertEquals(2, input.getCount());
        assertEquals(64, output.getCount());
        assertEquals(inputBefore, input.getNbt());
        assertEquals(outputBefore, output.getNbt());
        output.setCount(10);
        ProductionOperation.Prepared prepared = ProductionOperation.prepare(input, output, recipe,
                recipe.newWorkspace(), 64).orElseThrow();
        assertEquals(1, prepared.inputAfter().getCount());
        assertEquals(11, prepared.outputAfter().getCount());
        assertNotSame(input, prepared.inputAfter());
        assertNotSame(output, prepared.outputAfter());
        prepared.inputAfter().getOrCreateNbt().putString("test:changed", "detached");
        assertEquals(inputBefore, input.getNbt());
        ItemStack incompatible = new ItemStack(Items.GOLD_INGOT);
        assertTrue(ProductionOperation.prepare(input, incompatible, recipe, recipe.newWorkspace(), 64).isEmpty());
    }

    private static ItemStack validItem() {
        ItemStack item = new ItemStack(Items.IRON_INGOT);
        ItemParameterStorage.write(item, SCHEMA.defaults());
        return item;
    }

    private static NbtCompound payload(ItemStack item) {
        return item.getNbt().getCompound(ItemParameterStorage.NBT_KEY);
    }

    private static void assertRejectedWithoutMutation(ItemStack item) {
        NbtCompound before = item.getNbt().copy();
        assertThrows(ParameterStorageException.class, () -> ItemParameterStorage.read(item, SCHEMA));
        assertEquals(before, item.getNbt());
        assertThrows(ParameterStorageException.class, () -> ItemParameterStorage.write(item, SCHEMA.defaults()));
        assertEquals(before, item.getNbt());
    }
}
