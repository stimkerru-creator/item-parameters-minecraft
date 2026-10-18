package dev.itemparams.fabric.machine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroupEntries;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import org.junit.jupiter.api.Test;

class ProcessorCreativeInventoryTest {
  @Test
  void functionalGroupCallbackAddsMachineToBothTabAndCreativeSearch() {
    SharedConstants.createGameVersion();
    Bootstrap.initialize();
    ProcessorRegistry.registerCreativeEntry();
    var visible = new ArrayList<ItemStack>();
    var search = new ArrayList<ItemStack>();
    var context = new ItemGroup.DisplayContext(FeatureFlags.VANILLA_FEATURES, false, null);
    var entries = new FabricItemGroupEntries(context, visible, search);
    ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).invoker().modifyEntries(entries);
    assertTrue(visible.stream().anyMatch(stack -> stack.isOf(ProcessorRegistry.ITEM)));
    assertTrue(search.stream().anyMatch(stack -> stack.isOf(ProcessorRegistry.ITEM)));
  }
}
