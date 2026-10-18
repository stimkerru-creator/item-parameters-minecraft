package dev.itemparams.fabric;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import dev.itemparams.production.ProductionOperation;
import dev.itemparams.production.ProductionRecipe;
import java.util.Map;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeWorkspaceTest {
    private static final ParameterSchema SCHEMA;
    static {
        ParameterSchema.Builder builder = ParameterSchema.builder();
        for (int i = 0; i < 20; i++) builder.addRaw("test:p" + i, (i + 1) * 100);
        SCHEMA = builder.build();
    }
    @BeforeAll static void bootstrap() {
        SharedConstants.createGameVersion(); Bootstrap.initialize();
    }
    private static ProductionRecipe recipe() {
        return ProductionRecipe.compile("test:press", Items.DIRT, Items.STONE, 1, SCHEMA,
                Map.of("test:p3", "${test:p3}/3*2", "test:p7", "sqrt(${test:p7}*2)"));
    }
    private static ItemStack sample() {
        ItemStack stack = new ItemStack(Items.DIRT, 10);
        ItemParameterStorage.write(stack, SCHEMA.defaults()); return stack;
    }
    @Test void transformsTwoFieldsAndCarriesTheOtherEighteenWithoutConsumingInput() {
        ItemStack input = sample(); NbtCompound before = input.writeNbt(new NbtCompound());
        ProductionRecipe recipe = recipe(); var workspace = recipe.newWorkspace();
        for (int call = 0; call < 3; call++) {
            ItemStack output = recipe.execute(input, workspace);
            assertTrue(output.isOf(Items.STONE)); assertEquals(1, output.getCount());
            ParameterValues values = ItemParameterStorage.read(output, SCHEMA);
            for (int i = 0; i < 20; i++) assertEquals(i == 3 ? 266 : i == 7 ? 400 : (i + 1) * 100,
                    values.getRaw("test:p" + i), "field " + i);
        }
        assertEquals(before, input.writeNbt(new NbtCompound()));
    }
    @Test void countAndUnrelatedNbtDoNotAffectNumericOutput() {
        ProductionRecipe recipe = recipe(); var workspace = recipe.newWorkspace(); ItemStack input = sample();
        ItemStack first = recipe.execute(input, workspace);
        input.setCount(64); input.getOrCreateNbt().putString("other:label", "new");
        assertEquals(first.getNbt(), recipe.execute(input, workspace).getNbt());
        assertEquals(64, input.getCount());
    }
    @Test void changedInputsRecalculateAndUnusedFieldsStillCarryTheirNewValues() {
        ProductionRecipe recipe = recipe(); var workspace = recipe.newWorkspace(); ItemStack input = sample();
        recipe.execute(input, workspace);
        ItemParameterStorage.edit(input, SCHEMA, v -> { v.setRaw("test:p3", 600); v.setRaw("test:p19", 2222); });
        ParameterValues output = ItemParameterStorage.read(recipe.execute(input, workspace), SCHEMA);
        assertEquals(400, output.getRaw("test:p3")); assertEquals(2222, output.getRaw("test:p19"));
        assertThrows(IllegalArgumentException.class, () -> recipe.execute(ItemStack.EMPTY, workspace));
        assertThrows(IllegalArgumentException.class, () -> recipe.execute(new ItemStack(Items.STONE), workspace));
    }
    @Test void noMutableAliasExistsBetweenSampleOutputsAndInternalCache() {
        ProductionRecipe recipe = recipe(); var workspace = recipe.newWorkspace(); ItemStack input = sample();
        ItemStack first = recipe.execute(input, workspace); first.setNbt(null); first.setCount(30);
        assertEquals(266, ItemParameterStorage.read(recipe.execute(input, workspace), SCHEMA).getRaw("test:p3"));
        ItemParameterStorage.edit(input, SCHEMA, v -> v.setRaw("test:p3", 900));
        assertEquals(600, ItemParameterStorage.read(recipe.execute(input, workspace), SCHEMA).getRaw("test:p3"));
        assertEquals(1, recipe.execute(input, workspace).getCount());
    }
    @Test void equivalentIdOrderAndAbsentDefaultsHaveTheSameMeaning() {
        ProductionRecipe recipe = recipe(); var workspace = recipe.newWorkspace();
        NbtCompound expected = recipe.execute(sample(), workspace).getNbt();
        assertEquals(expected, recipe.execute(new ItemStack(Items.DIRT), workspace).getNbt());
        ItemStack reordered = sample(); NbtCompound p = reordered.getNbt().getCompound(ItemParameterStorage.NBT_KEY);
        NbtList ids = p.getList("ids", 8); int[] raw = p.getIntArray("values");
        NbtList reversed = new NbtList(); int[] values = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            reversed.add(NbtString.of(ids.getString(raw.length - i - 1))); values[i] = raw[raw.length - i - 1];
        }
        p.put("ids", reversed); p.putIntArray("values", values);
        assertEquals(expected, recipe.execute(reordered, workspace).getNbt());
    }
    @Test void unknownFieldsParticipateInCacheIdentityAndRemainPreserved() {
        ParameterSchema extra = ParameterSchema.builder().addRaw("other:variant", 300).build();
        ProductionRecipe recipe = recipe(); var workspace = recipe.newWorkspace(); ItemStack input = sample();
        ItemParameterStorage.write(input, extra.defaults()); recipe.execute(input, workspace);
        ItemParameterStorage.edit(input, extra, v -> v.setRaw("other:variant", 301));
        assertEquals(301, ItemParameterStorage.read(recipe.execute(input, workspace), extra).getRaw("other:variant"));
    }
    @Test void invalidInputCannotPoisonAPreviousSuccessfulResult() {
        ProductionRecipe recipe = recipe(); var workspace = recipe.newWorkspace(); ItemStack valid = sample();
        NbtCompound expected = recipe.execute(valid, workspace).getNbt(); ItemStack invalid = sample();
        invalid.getNbt().getCompound(ItemParameterStorage.NBT_KEY).putInt("version", 2);
        NbtCompound before = invalid.getNbt().copy();
        assertThrows(IllegalArgumentException.class, () -> recipe.execute(invalid, workspace));
        assertEquals(before, invalid.getNbt()); assertEquals(expected, recipe.execute(valid, workspace).getNbt());
        workspace.clear(); assertEquals(expected, recipe.execute(valid, workspace).getNbt());
    }
    @Test void independentOwnersAndCapacityPlanningUseCurrentStacks() {
        ProductionRecipe recipe = recipe(); var a = recipe.newWorkspace(); var b = recipe.newWorkspace();
        ItemStack input = sample(); recipe.execute(input, a);
        ItemStack other = sample(); ItemParameterStorage.edit(other, SCHEMA, v -> v.setRaw("test:p3", 900));
        assertEquals(600, ItemParameterStorage.read(recipe.execute(other, b), SCHEMA).getRaw("test:p3"));
        assertEquals(266, ItemParameterStorage.read(recipe.execute(input, a), SCHEMA).getRaw("test:p3"));
        input.setCount(32); ItemStack full = recipe.execute(input, a); full.setCount(64);
        assertTrue(ProductionOperation.prepare(input, full, recipe, a, 64).isEmpty());
        var changes = ProductionOperation.prepare(input, ItemStack.EMPTY, recipe, a, 64).orElseThrow();
        assertEquals(31, changes.inputAfter().getCount()); assertEquals(32, input.getCount());
    }
}
