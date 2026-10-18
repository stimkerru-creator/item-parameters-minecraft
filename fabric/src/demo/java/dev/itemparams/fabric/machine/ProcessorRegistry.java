package dev.itemparams.fabric.machine;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;

/** Called once during common mod initialization, before any world or screen exists. */
public final class ProcessorRegistry {
  public static final Identifier ID = new Identifier("itemparams_demo", "processor");
  public static final Identifier RECIPE_STATE_PACKET =
      new Identifier("itemparams_demo", "recipe_state");
  public static final ProcessorBlock BLOCK =
      new ProcessorBlock(AbstractBlock.Settings.copy(Blocks.IRON_BLOCK));
  public static final Item ITEM = new BlockItem(BLOCK, new Item.Settings());
  public static BlockEntityType<ProcessorBlockEntity> BLOCK_ENTITY_TYPE;
  public static ScreenHandlerType<ProcessorScreenHandler> SCREEN_HANDLER_TYPE;

  private ProcessorRegistry() {}

  public static void register() {
    Registry.register(Registries.BLOCK, ID, BLOCK);
    Registry.register(Registries.ITEM, ID, ITEM);
    registerCreativeEntry();
    BLOCK_ENTITY_TYPE =
        Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            ID,
            FabricBlockEntityTypeBuilder.create(ProcessorBlockEntity::new, BLOCK).build());
    SCREEN_HANDLER_TYPE = ScreenHandlerRegistry.registerExtended(ID, ProcessorScreenHandler::new);
  }

  static void registerCreativeEntry() {
    ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL)
        .register(entries -> entries.add(ITEM));
  }
}
