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
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Inventory admission contract: only configured input types/states enter; output slot is not insertable. */
class ProcessorScreenHandlerAdmissionTest {
    @BeforeAll static void initializeTypes() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        ProcessorRegistry.BLOCK_ENTITY_TYPE = BlockEntityType.Builder
                .create(ProcessorBlockEntity::new, ProcessorRegistry.BLOCK).build(null);
        ProcessorRegistry.SCREEN_HANDLER_TYPE = new ScreenHandlerType<>(ProcessorScreenHandler::new,
                FeatureFlags.VANILLA_FEATURES);
    }

    @AfterEach void clearCatalog() { ConfiguredItemParameters.install(ItemCatalogSnapshot.empty()); }

    @Test void serverSlotsRejectWrongTypesAndChangedStatsWithoutMutatingCandidate() {
        Fixture fixture = fixture();
        ConfiguredItemParameters.install(fixture.catalog);
        ProcessorBlockEntity machine = new ProcessorBlockEntity(BlockPos.ORIGIN, ProcessorRegistry.BLOCK.getDefaultState());
        machine.processTick(fixture.catalog);
        PlayerInventory player = new PlayerInventory(null);
        ProcessorScreenHandler handler = new ProcessorScreenHandler(1, player, machine, machine.propertyDelegate());

        ItemStack wrong = new ItemStack(Items.DIAMOND, 7);
        ItemStack wrongBefore = wrong.copy();
        assertFalse(handler.getSlot(0).canInsert(wrong));
        assertFalse(machine.canInsert(0, wrong, Direction.UP));
        assertFalse(machine.canInsert(ProcessorBlockEntity.OUTPUT_SLOT, wrong, Direction.UP));
        handler.getSlot(0).insertStack(wrong);
        assertTrue(ItemStack.areEqual(wrongBefore, wrong));
        assertTrue(machine.getStack(0).isEmpty());

        ItemStack valid = values(100);
        assertTrue(handler.getSlot(0).canInsert(valid));
        handler.getSlot(0).insertStack(valid);
        assertEquals(2, machine.getStack(0).getCount());
        machine.processTick(fixture.catalog);

        ItemStack changed = values(101);
        ItemStack changedBefore = changed.copy();
        assertFalse(handler.getSlot(0).canInsert(changed));
        assertFalse(machine.canInsert(0, changed, Direction.UP));
        handler.getSlot(0).insertStack(changed);
        assertTrue(ItemStack.areEqual(changedBefore, changed));
        assertEquals(2, machine.getStack(0).getCount());
        assertFalse(handler.getSlot(ProcessorBlockEntity.OUTPUT_SLOT).canInsert(values(100)));
    }

    @Test void clientGuiRejectsWrongItemTypeUsingServerSentAllowlistOnly() {
        PlayerInventory player = new PlayerInventory(null);
        PacketByteBuf opening = PacketByteBufs.create();
        opening.writeString("test:recipe", 256);
        opening.writeVarInt(0);
        opening.writeVarInt(1);
        opening.writeVarInt(1);
        opening.writeString("input", 256);
        opening.writeString("minecraft:iron_ingot", 256);
        opening.writeString("minecraft:gold_ingot", 256);
        try {
            ProcessorScreenHandler clientHandler = new ProcessorScreenHandler(2, player, opening);
            assertTrue(clientHandler.getSlot(0).canInsert(new ItemStack(Items.IRON_INGOT)));
            assertFalse(clientHandler.getSlot(0).canInsert(new ItemStack(Items.DIAMOND)));
        } finally {
            opening.release();
        }
    }

    @Test void slotLayoutSupportsFourInputSlotsAndRejectsUnconfiguredRoles() {
        Fixture fixture = fixture();
        ConfiguredItemParameters.install(fixture.catalog);
        ProcessorBlockEntity machine = new ProcessorBlockEntity(BlockPos.ORIGIN, ProcessorRegistry.BLOCK.getDefaultState());
        machine.processTick(fixture.catalog);
        PlayerInventory player = new PlayerInventory(null);
        ProcessorScreenHandler handler = new ProcessorScreenHandler(1, player, machine, machine.propertyDelegate());
        assertEquals(ProcessorBlockEntity.SLOT_COUNT + 36, handler.slots.size());
        assertFalse(handler.getSlot(1).canInsert(new ItemStack(Items.STICK)));
        assertTrue(handler.getSlot(0).canInsert(values(100)));
    }

    private static Fixture fixture() {
        ParameterSchema inputSchema = ParameterSchema.builder().addRaw("test:x", 100).build();
        ParameterSchema outputSchema = ParameterSchema.builder().addRaw("test:x", 0).addRaw("test:new", 0).build();
        ItemDefinition input = new ItemDefinition(Registries.ITEM.getId(Items.IRON_INGOT), Items.IRON_INGOT, inputSchema);
        ItemDefinition output = new ItemDefinition(Registries.ITEM.getId(Items.GOLD_INGOT), Items.GOLD_INGOT, outputSchema);
        ParameterRecipe numeric = ParameterRecipe.builder("test:recipe", InputLayout.of(Map.of("input", inputSchema)), outputSchema)
                .copyCommonFrom("input").assign("test:x", "${input::test:x}+1").assign("test:new", "sqrt(16)").build();
        ConfiguredItemRecipe recipe = new ConfiguredItemRecipe("test:recipe", Map.of("input", input), output, 1, numeric);
        ItemCatalogSnapshot catalog = new ItemCatalogSnapshot(
                Map.of(input.id(), input, output.id(), output), Map.of(recipe.id(), recipe));
        return new Fixture(catalog, inputSchema);
    }

    private static ItemStack values(int raw) {
        ParameterSchema schema = ParameterSchema.builder().addRaw("test:x", 100).build();
        ItemStack stack = new ItemStack(Items.IRON_INGOT, 2);
        ParameterValues values = schema.defaults();
        values.setRaw("test:x", raw);
        ItemParameterStorage.write(stack, values);
        return stack;
    }

    private record Fixture(ItemCatalogSnapshot catalog, ParameterSchema inputSchema) {}
}
