package dev.itemparams.fabric.machine;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import java.util.List;

/** Client sends only a bounded button index; recipe IDs/formulas/values are selected and validated server-side. */
public final class ProcessorScreenHandler extends ScreenHandler {
    private final Inventory inventory;
    private final PropertyDelegate properties;
    private final ProcessorBlockEntity processor;
    private String recipeId;
    private int recipeIndex;
    private int recipeCount;
    private List<String> inputRoles = List.of();
    private List<String> inputItemIds = List.of();
    private String outputItemId = "";

    public ProcessorScreenHandler(int syncId, PlayerInventory player) {
        this(syncId, player, new SimpleInventory(ProcessorBlockEntity.SLOT_COUNT),
                new ArrayPropertyDelegate(5), null, "", 0, 0);
    }

    /** Fabric extended-screen constructor. Opening data contains only display state, never parameter values/formulas. */
    public ProcessorScreenHandler(int syncId, PlayerInventory player, PacketByteBuf openingData) {
        this(syncId, player, new SimpleInventory(ProcessorBlockEntity.SLOT_COUNT),
                new ArrayPropertyDelegate(5), null, readOpeningState(openingData));
    }

    public ProcessorScreenHandler(int syncId, PlayerInventory player, Inventory inventory, PropertyDelegate properties) {
        this(syncId, player, inventory, properties,
                inventory instanceof ProcessorBlockEntity machine ? machine : null,
                inventory instanceof ProcessorBlockEntity machine ? machine.recipeId() : "",
                inventory instanceof ProcessorBlockEntity machine ? Math.max(0, machine.selectedRecipeIndex()) : 0,
                inventory instanceof ProcessorBlockEntity machine ? machine.recipeCount() : 0);
    }

    private ProcessorScreenHandler(int syncId, PlayerInventory player, Inventory inventory, PropertyDelegate properties,
                                   ProcessorBlockEntity processor, OpeningState state) {
        this(syncId, player, inventory, properties, processor, state.recipeId(), state.recipeIndex(), state.recipeCount(),
                state.inputRoles(), state.inputItemIds(), state.outputItemId());
    }

    ProcessorScreenHandler(int syncId, PlayerInventory player, Inventory inventory, PropertyDelegate properties,
                           ProcessorBlockEntity processor, String recipeId, int recipeIndex, int recipeCount) {
        this(syncId, player, inventory, properties, processor, recipeId, recipeIndex, recipeCount,
                processor == null ? List.of() : processor.inputRoleNames(),
                processor == null ? List.of() : processor.inputItemIds(),
                processor == null ? "" : processor.outputItemId());
    }

    private ProcessorScreenHandler(int syncId, PlayerInventory player, Inventory inventory, PropertyDelegate properties,
                                   ProcessorBlockEntity processor, String recipeId, int recipeIndex, int recipeCount,
                                   List<String> inputRoles, List<String> inputItemIds, String outputItemId) {
        super(ProcessorRegistry.SCREEN_HANDLER_TYPE, syncId);
        checkSize(inventory, ProcessorBlockEntity.SLOT_COUNT);
        checkDataCount(properties, 5);
        this.inventory = inventory;
        this.properties = properties;
        this.processor = processor;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.recipeIndex = Math.max(0, recipeIndex);
        this.recipeCount = Math.max(0, recipeCount);
        this.inputRoles = List.copyOf(inputRoles);
        this.inputItemIds = List.copyOf(inputItemIds);
        this.outputItemId = outputItemId == null ? "" : outputItemId;
        inventory.onOpen(player.player);

        for (int input = 0; input < ProcessorBlockEntity.MAX_INPUTS; input++) {
            final int slotIndex = input;
            int x = 10 + input * 22;
            addSlot(new Slot(inventory, slotIndex, x, 38) {
                @Override public boolean canInsert(ItemStack stack) { return ProcessorScreenHandler.this.canInsertIntoInput(slotIndex, stack); }
            });
        }
        addSlot(new Slot(inventory, ProcessorBlockEntity.OUTPUT_SLOT, 184, 38) {
            @Override public boolean canInsert(ItemStack stack) { return false; }
        });

        for (int row = 0; row < 3; row++) for (int column = 0; column < 9; column++)
            addSlot(new Slot(player, column + row * 9 + 9, 8 + column * 18, 122 + row * 18));
        for (int column = 0; column < 9; column++)
            addSlot(new Slot(player, column, 8 + column * 18, 180));
        addProperties(properties);
    }

    public int progress() { return properties.get(0); }
    public int status() { return properties.get(1); }
    public int selectedIndex() { return properties.get(2); }
    public boolean canChangeRecipe() { return properties.get(3) != 0; }
    public boolean canReconfigure() { return properties.get(4) != 0; }
    public String recipeId() { return recipeId; }
    public int recipeCount() { return recipeCount; }
    public List<String> inputRoles() { return inputRoles; }
    public String outputItemId() { return outputItemId; }

    public boolean canInsertIntoInput(int slot, ItemStack stack) {
        if (slot < 0 || slot >= ProcessorBlockEntity.MAX_INPUTS || stack == null || stack.isEmpty()) return false;
        if (processor != null) return processor.acceptsInput(slot, stack);
        if (slot >= inputItemIds.size()) return false;
        return Registries.ITEM.getId(stack.getItem()).toString().equals(inputItemIds.get(slot));
    }

    public void updateRecipeInfo(String id, int index, int count, List<String> roles,
                                 List<String> inputItemIds, String outputItemId) {
        this.recipeId = id == null ? "" : id;
        this.recipeIndex = Math.max(0, index);
        this.recipeCount = Math.max(0, count);
        this.inputRoles = List.copyOf(roles);
        this.inputItemIds = List.copyOf(inputItemIds);
        this.outputItemId = outputItemId == null ? "" : outputItemId;
    }

    private static OpeningState readOpeningState(PacketByteBuf buf) {
        String id = buf.readString(256);
        int index = buf.readVarInt();
        int count = buf.readVarInt();
        int roleCount = buf.readVarInt();
        if (roleCount < 0 || roleCount > ProcessorBlockEntity.MAX_INPUTS)
            throw new IllegalArgumentException("Invalid server recipe role count: " + roleCount);
        java.util.ArrayList<String> roles = new java.util.ArrayList<>(roleCount);
        java.util.ArrayList<String> itemIds = new java.util.ArrayList<>(roleCount);
        for (int i = 0; i < roleCount; i++) {
            roles.add(buf.readString(256));
            itemIds.add(buf.readString(256));
        }
        String output = buf.readString(256);
        return new OpeningState(id, index, count, List.copyOf(roles), List.copyOf(itemIds), output);
    }

    private record OpeningState(String recipeId, int recipeIndex, int recipeCount,
                                List<String> inputRoles, List<String> inputItemIds, String outputItemId) {}

    @Override public boolean onButtonClick(PlayerEntity player, int id) {
        if (!(player instanceof ServerPlayerEntity serverPlayer) || processor == null || !canUse(player)) return false;
        boolean accepted;
        if (id == 2) accepted = processor.reconfigure(player);
        else if (id == 0 || id == 1) accepted = processor.cycleRecipe(player, id == 0 ? -1 : 1);
        else return false;
        if (!accepted) return false;
        updateRecipeInfo(processor.recipeId(), Math.max(0, processor.selectedRecipeIndex()), processor.recipeCount(),
                processor.inputRoleNames(), processor.inputItemIds(), processor.outputItemId());
        PacketByteBuf payload = PacketByteBufs.create();
        payload.writeVarInt(syncId);
        payload.writeString(recipeId, 256);
        payload.writeVarInt(recipeIndex);
        payload.writeVarInt(recipeCount);
        payload.writeVarInt(inputRoles.size());
        for (int i = 0; i < inputRoles.size(); i++) {
            payload.writeString(inputRoles.get(i), 256);
            payload.writeString(inputItemIds.get(i), 256);
        }
        payload.writeString(outputItemId, 256);
        ServerPlayNetworking.send(serverPlayer, ProcessorRegistry.RECIPE_STATE_PACKET, payload);
        sendContentUpdates();
        return true;
    }

    @Override public boolean canUse(PlayerEntity player) { return inventory.canPlayerUse(player); }
    @Override public void onClosed(PlayerEntity player) { super.onClosed(player); inventory.onClose(player); }

    @Override public ItemStack quickMove(PlayerEntity player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasStack()) return ItemStack.EMPTY;
        ItemStack original = slot.getStack();
        ItemStack copy = original.copy();
        if (index < ProcessorBlockEntity.SLOT_COUNT) {
            if (!insertItem(original, ProcessorBlockEntity.SLOT_COUNT, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            if (!insertItem(original, 0, ProcessorBlockEntity.MAX_INPUTS, false)) return ItemStack.EMPTY;
        }
        if (original.isEmpty()) slot.setStack(ItemStack.EMPTY); else slot.markDirty();
        if (original.getCount() == copy.getCount()) return ItemStack.EMPTY;
        slot.onTakeItem(player, original);
        return copy;
    }
}
