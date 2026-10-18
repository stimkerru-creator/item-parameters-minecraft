package dev.itemparams.fabric.machine;

import dev.itemparams.catalog.ConfiguredItemRecipe;
import dev.itemparams.catalog.ConfiguredItemParameters;
import dev.itemparams.catalog.ItemCatalogSnapshot;
import dev.itemparams.core.ParameterSchema;
import dev.itemparams.fabric.ItemInputFilter;
import dev.itemparams.fabric.ItemParameterStorage;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Optional test processor. All formulas and schemas come from the same server catalog as the core API. */
public final class ProcessorBlockEntity extends BlockEntity implements SidedInventory, ExtendedScreenHandlerFactory {
    public static final int OPERATION_TICKS = 20;
    public static final int MAX_INPUTS = 4;
    public static final int OUTPUT_SLOT = MAX_INPUTS;
    public static final int SLOT_COUNT = MAX_INPUTS + 1;
    public static final int IDLE = 0, WORKING = 1, OUTPUT_FULL = 2, INVALID_RECIPE = 3, ERROR = 4;
    // Immutable, reusable slot lists: hoppers need only probe the active roles.
    private static final int[][] ACTIVE_INPUT_SLOTS = {
            {}, {0}, {0, 1}, {0, 1, 2}, {0, 1, 2, 3}
    };
    private static final int[] OUTPUT_SLOTS = {OUTPUT_SLOT};

    private final DefaultedList<ItemStack> items = DefaultedList.ofSize(SLOT_COUNT, ItemStack.EMPTY);
    private final DefaultedList<ItemStack> inputProfiles = DefaultedList.ofSize(MAX_INPUTS, ItemStack.EMPTY);
    private String recipeId = "";
    private String lastError = "";
    private int progress;
    private int status;
    private ItemCatalogSnapshot cachedCatalog;
    private ConfiguredItemRecipe cachedRecipe;
    private List<String> recipeIds = List.of();
    private List<String> inputRoles = List.of();
    private List<ItemInputFilter> inputFilters = List.of();
    private ItemStack calculatedOutput = ItemStack.EMPTY;
    private boolean invalidSavedProduction;
    private String bindingError = "";
    private List<ItemStack> failedInputSamples = List.of();
    private String failedCalculationError = "";
    // No NBT polling during ordinary progress. A blocked output is retried after a change or once per second.
    private int blockedOutputPollTicks;
    private int failedInputPollTicks;

    private final PropertyDelegate properties = new PropertyDelegate() {
        @Override public int get(int index) {
            return switch (index) {
                case 0 -> progress;
                case 1 -> status;
                case 2 -> Math.max(0, selectedRecipeIndex());
                case 3 -> canChangeRecipe() ? 1 : 0;
                case 4 -> canReconfigure() ? 1 : 0;
                default -> 0;
            };
        }
        @Override public void set(int index, int value) {
            // Client-sent property values are never used to change server state.
            if (world == null || world.isClient) {
                if (index == 0) progress = value;
                else if (index == 1) status = value;
            }
        }
        @Override public int size() { return 5; }
    };

    public ProcessorBlockEntity(BlockPos pos, BlockState state) {
        super(ProcessorRegistry.BLOCK_ENTITY_TYPE, pos, state);
    }

    public static void tick(World world, BlockPos pos, BlockState state, ProcessorBlockEntity machine) {
        if (!world.isClient) machine.processTick(ConfiguredItemParameters.current());
    }

    /**
     * Server tick path. Regular ticks only inspect the occupied input slots and advance
     * progress. Full NBT/parameter validation occurs on admission and immediately before
     * a production commit, never once per tick for a previously locked input.
     * A formula is evaluated only when there is no prepared output (after a reset).
     */
    void processTick(ItemCatalogSnapshot runtime) {
        Objects.requireNonNull(runtime, "runtime");
        try {
            bindRuntime(runtime);
            if (cachedRecipe == null) {
                status = INVALID_RECIPE;
                lastError = bindingError.isEmpty() ? "No configured recipe selected" : bindingError;
                return;
            }
            if (!bindingError.isEmpty()) {
                progress = 0;
                status = ERROR;
                lastError = bindingError;
                return;
            }

            // The common fast path allocates no lists, copies or NBT objects.
            for (int i = 0; i < inputRoles.size(); i++) {
                if (items.get(i).isEmpty()) {
                    progress = 0;
                    status = IDLE;
                    lastError = "Waiting for all recipe inputs";
                    return;
                }
            }

            if (calculatedOutput.isEmpty()) {
                // A failed calculation with unchanged input is retried at most once
                // per second (or as soon as an Inventory mutation is reported).
                if (!failedInputSamples.isEmpty() && ++failedInputPollTicks < OPERATION_TICKS) return;
                failedInputPollTicks = 0;
                if (sameInputsAsFailed()) {
                    status = ERROR;
                    lastError = failedCalculationError;
                    return;
                }
                if (!inputsMatch()) {
                    progress = 0;
                    status = ERROR;
                    lastError = "Input does not match the selected recipe or locked numeric parameters";
                    return;
                }
                Map<String, ItemStack> namedInputs = new LinkedHashMap<>();
                for (int i = 0; i < inputRoles.size(); i++) namedInputs.put(inputRoles.get(i), items.get(i));
                try {
                    ItemStack created = cachedRecipe.execute(namedInputs);
                    for (int i = 0; i < inputRoles.size(); i++) {
                        inputFilters.get(i).lock(items.get(i));
                        inputProfiles.set(i, inputFilters.get(i).sample());
                    }
                    calculatedOutput = created;
                    failedInputSamples = List.of();
                    failedCalculationError = "";
                    markDirty();
                } catch (RuntimeException failure) {
                    List<ItemStack> samples = new ArrayList<>(inputRoles.size());
                    for (int i = 0; i < inputRoles.size(); i++) {
                        samples.add(items.get(i).copyWithCount(1));
                    }
                    failedInputSamples = List.copyOf(samples);
                    failedInputPollTicks = 0;
                    failedCalculationError = failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage();
                    throw failure;
                }
            }

            // A full/incompatible output does not trigger NBT comparisons every tick.
            // Normal slot mutations wake it immediately. Periodic fallback covers an
            // external mod mutating ItemStack in place without notifying Inventory.
            if (status == OUTPUT_FULL && ++blockedOutputPollTicks < OPERATION_TICKS) return;
            blockedOutputPollTicks = 0;
            if (progress < OPERATION_TICKS) progress++;
            if (progress < OPERATION_TICKS) {
                status = WORKING;
                lastError = "";
                return;
            }

            // Recheck real server-owned contents at the actual commit boundary.
            // This also catches in-place mutations by other mods during progress.
            if (!inputsMatch()) {
                progress = 0;
                status = ERROR;
                lastError = "Input does not match the selected recipe or locked numeric parameters";
                return;
            }

            ItemStack output = items.get(OUTPUT_SLOT);
            ItemStack afterOutput;
            if (output.isEmpty()) {
                afterOutput = calculatedOutput.copy();
            } else {
                if (!ItemStack.canCombine(output, calculatedOutput)) {
                    status = OUTPUT_FULL;
                    lastError = "Output slot contains a different item or parameters";
                    return;
                }
                int maximum = Math.min(getMaxCountPerStack(),
                        Math.min(output.getMaxCount(), calculatedOutput.getMaxCount()));
                if (output.getCount() + calculatedOutput.getCount() > maximum) {
                    status = OUTPUT_FULL;
                    lastError = "Output slot is full";
                    return;
                }
                afterOutput = output.copy();
                afterOutput.increment(calculatedOutput.getCount());
            }

            // Prepare all resulting input stacks before mutating any inventory slot.
            List<ItemStack> afterInputs = new ArrayList<>(inputRoles.size());
            for (int i = 0; i < inputRoles.size(); i++) {
                ItemStack remaining = items.get(i).copy();
                remaining.decrement(1);
                afterInputs.add(remaining);
            }
            for (int i = 0; i < inputRoles.size(); i++) items.set(i, afterInputs.get(i));
            items.set(OUTPUT_SLOT, afterOutput);
            progress = 0;
            status = WORKING;
            lastError = "";
            markDirty();
        } catch (RuntimeException failure) {
            progress = 0;
            status = ERROR;
            lastError = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
        }
    }

    /** Full parameter checks, called on initial confirmation and production commit. */
    private boolean inputsMatch() {
        for (int i = 0; i < inputRoles.size(); i++) {
            if (!inputFilters.get(i).matches(items.get(i))) return false;
        }
        return true;
    }

    private boolean sameInputsAsFailed() {
        if (failedInputSamples.size() != inputRoles.size()) return false;
        try {
            for (int i = 0; i < inputRoles.size(); i++) {
                ItemStack current = items.get(i), failed = failedInputSamples.get(i);
                if (!current.isOf(failed.getItem())) return false;
                var schema = cachedRecipe.inputs().get(inputRoles.get(i)).schema();
                if (!ItemParameterStorage.read(current, schema).toRawMap()
                        .equals(ItemParameterStorage.read(failed, schema).toRawMap())) return false;
            }
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void inventorySlotChanged(int slot) {
        if (slot == OUTPUT_SLOT) {
            blockedOutputPollTicks = OPERATION_TICKS;
        } else if (slot >= 0 && slot < MAX_INPUTS) {
            failedInputPollTicks = OPERATION_TICKS;
        }
    }

    private void bindRuntime(ItemCatalogSnapshot runtime) {
        if (cachedCatalog == runtime) return;
        cachedCatalog = runtime;
        recipeIds = runtime.recipeIds(MAX_INPUTS);
        cachedRecipe = null;
        inputRoles = List.of();
        inputFilters = List.of();
        bindingError = "";
        failedInputSamples = List.of();
        failedCalculationError = "";

        boolean hasPreparedState = !calculatedOutput.isEmpty()
                || inputProfiles.stream().anyMatch(stack -> !stack.isEmpty());
        if (recipeId.isBlank() && hasPreparedState) {
            bindingError = "Saved production has no recipe ID; select a recipe again";
            invalidSavedProduction = true;
            return;
        }
        if (recipeId.isBlank() && !recipeIds.isEmpty()) {
            recipeId = recipeIds.get(0);
            markDirty();
        }
        if (recipeId.isBlank()) return;
        ConfiguredItemRecipe recipe = runtime.recipes().get(recipeId);
        if (recipe == null) {
            bindingError = "Recipe is not present in the server catalog: " + recipeId;
            return;
        }
        if (recipe.inputs().size() > MAX_INPUTS) {
            bindingError = "Demo processor supports at most " + MAX_INPUTS + " input roles";
            return;
        }
        cachedRecipe = recipe;
        inputRoles = List.copyOf(recipe.inputs().keySet());
        List<ItemInputFilter> filters = new ArrayList<>(inputRoles.size());
        for (String role : inputRoles) {
            var definition = recipe.inputs().get(role);
            filters.add(ItemInputFilter.of(definition.item(), definition.schema()));
        }
        inputFilters = List.copyOf(filters);

        int savedProfiles = 0;
        while (savedProfiles < MAX_INPUTS && !inputProfiles.get(savedProfiles).isEmpty()) savedProfiles++;
        boolean hole = false;
        for (int i = savedProfiles; i < MAX_INPUTS; i++) if (!inputProfiles.get(i).isEmpty()) hole = true;
        boolean hasAnyProfile = savedProfiles > 0 || hole;
        if (invalidSavedProduction) {
            bindingError = "Invalid saved production; select a recipe again";
            return;
        }
        if (hasAnyProfile || !calculatedOutput.isEmpty()) {
            try {
                if (hole || calculatedOutput.isEmpty() || savedProfiles != inputRoles.size()
                        || !calculatedOutput.isOf(recipe.output().item())
                        || calculatedOutput.getCount() != recipe.outputCount()) {
                    throw new IllegalArgumentException("Saved input/output profile does not match selected recipe");
                }
                for (int i = 0; i < savedProfiles; i++) {
                    if (!inputProfiles.get(i).isOf(recipe.inputs().get(inputRoles.get(i)).item()))
                        throw new IllegalArgumentException("Saved input item does not match role " + inputRoles.get(i));
                    inputFilters.get(i).lock(inputProfiles.get(i));
                }
                // Validate the saved output without recalculating the formula.
                ItemParameterStorage.read(calculatedOutput, recipe.output().schema());
            } catch (IllegalArgumentException invalid) {
                bindingError = "Saved production is incompatible: " + invalid.getMessage();
            }
        }
    }

    public String recipeId() { return recipeId; }
    public String lastError() { return lastError; }
    public int status() { return status; }
    public int progress() { return progress; }
    public int selectedRecipeIndex() { return recipeIds.indexOf(recipeId); }
    public int recipeCount() { return recipeIds.size(); }
    public List<String> inputRoleNames() { return inputRoles; }
    public List<String> inputItemIds() {
        if (cachedRecipe == null) return List.of();
        return inputRoles.stream().map(role -> cachedRecipe.inputs().get(role).id().toString()).toList();
    }
    public String outputItemId() { return cachedRecipe == null ? "" : cachedRecipe.output().id().toString(); }
    public boolean canChangeRecipe() {
        for (int i = 0; i < MAX_INPUTS; i++) if (!items.get(i).isEmpty()) return false;
        return !recipeIds.isEmpty();
    }
    public boolean canReconfigure() { return canChangeRecipe() && isInputLocked(); }
    public PropertyDelegate propertyDelegate() { return properties; }
    public boolean isInputLocked() { return inputProfiles.stream().anyMatch(stack -> !stack.isEmpty()) || invalidSavedProduction; }

    public boolean acceptsInput(int slot, ItemStack stack) {
        if (slot < 0 || slot >= MAX_INPUTS || stack == null || stack.isEmpty()) return false;
        bindRuntime(ConfiguredItemParameters.current());
        return bindingError.isEmpty() && cachedRecipe != null && slot < inputRoles.size()
                && inputFilters.get(slot).matches(stack);
    }

    /** Called only by the server ScreenHandler after a bounded GUI button click. */
    boolean cycleRecipe(PlayerEntity player, int direction) {
        if (direction != -1 && direction != 1) return false;
        if (world == null || world.isClient || !canPlayerUse(player) || !canChangeRecipe()) return false;
        bindRuntime(ConfiguredItemParameters.current());
        if (recipeIds.isEmpty()) return false;
        int current = recipeIds.indexOf(recipeId);
        if (current < 0) current = 0;
        int next = Math.floorMod(current + direction, recipeIds.size());
        if (recipeIds.get(next).equals(recipeId)) return true;
        recipeId = recipeIds.get(next);
        resetPreparedProduction();
        bindRuntime(ConfiguredItemParameters.current());
        markDirty();
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_LISTENERS);
        return true;
    }

    /** Clear the locked input/output profile for the currently selected recipe, without changing the formula. */
    boolean reconfigure(PlayerEntity player) {
        if (world != null && (world.isClient || !canPlayerUse(player))) return false;
        if (world == null && player != null) return false;
        if (!canChangeRecipe() || !isInputLocked()) return false;
        resetPreparedProduction();
        bindRuntime(ConfiguredItemParameters.current());
        markDirty();
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_LISTENERS);
        return true;
    }

    private void resetPreparedProduction() {
        cachedCatalog = null;
        cachedRecipe = null;
        for (int i = 0; i < MAX_INPUTS; i++) inputProfiles.set(i, ItemStack.EMPTY);
        calculatedOutput = ItemStack.EMPTY;
        invalidSavedProduction = false;
        bindingError = "";
        failedInputSamples = List.of();
        failedCalculationError = "";
        progress = 0;
        status = IDLE;
        lastError = "";
        blockedOutputPollTicks = 0;
        failedInputPollTicks = 0;
    }

    @Override protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        Inventories.writeNbt(nbt, items);
        nbt.putString("Recipe", recipeId);
        NbtList profiles = new NbtList();
        int count = 0;
        for (int i = 0; i < MAX_INPUTS; i++) {
            ItemStack profile = inputProfiles.get(i);
            if (profile.isEmpty()) break;
            profiles.add(profile.writeNbt(new NbtCompound()));
            count++;
        }
        if (count > 0) nbt.put("InputProfiles", profiles); else nbt.remove("InputProfiles");
        if (!calculatedOutput.isEmpty()) nbt.put("CalculatedOutput", calculatedOutput.writeNbt(new NbtCompound()));
        else nbt.remove("CalculatedOutput");
        nbt.putBoolean("InvalidProduction", invalidSavedProduction);
    }

    @Override public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        items.clear();
        Inventories.readNbt(nbt, items);
        if (nbt.contains("Recipe", NbtElement.STRING_TYPE)) {
            String saved = nbt.getString("Recipe");
            if (saved.length() <= ParameterSchema.MAX_ID_LENGTH) recipeId = saved;
        }
        for (int i = 0; i < MAX_INPUTS; i++) inputProfiles.set(i, ItemStack.EMPTY);
        calculatedOutput = ItemStack.EMPTY;
        boolean malformedProfiles = false;
        if (nbt.contains("InputProfiles")) {
            if (!nbt.contains("InputProfiles", NbtElement.LIST_TYPE)) malformedProfiles = true;
            else {
                NbtList list = nbt.getList("InputProfiles", NbtElement.COMPOUND_TYPE);
                if (list.size() > MAX_INPUTS) malformedProfiles = true;
                else for (int i = 0; i < list.size(); i++) {
                    ItemStack profile = ItemStack.fromNbt(list.getCompound(i));
                    if (profile.isEmpty() || profile.getCount() != 1) malformedProfiles = true;
                    inputProfiles.set(i, profile);
                }
            }
        }
        boolean hasOutput = nbt.contains("CalculatedOutput");
        if (hasOutput && nbt.contains("CalculatedOutput", NbtElement.COMPOUND_TYPE))
            calculatedOutput = ItemStack.fromNbt(nbt.getCompound("CalculatedOutput"));
        invalidSavedProduction = nbt.getBoolean("InvalidProduction") || malformedProfiles
                || hasOutput != inputProfiles.stream().anyMatch(stack -> !stack.isEmpty())
                || hasOutput && calculatedOutput.isEmpty();
        cachedCatalog = null;
        cachedRecipe = null;
        inputRoles = List.of();
        inputFilters = List.of();
        recipeIds = List.of();
        progress = 0;
        status = IDLE;
        lastError = "";
        failedInputSamples = List.of();
        failedCalculationError = "";
        blockedOutputPollTicks = 0;
        failedInputPollTicks = 0;
    }

    @Override public int size() { return SLOT_COUNT; }
    @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getStack(int slot) { return items.get(slot); }
    @Override public ItemStack removeStack(int slot, int amount) {
        ItemStack removed = Inventories.splitStack(items, slot, amount);
        if (!removed.isEmpty()) { inventorySlotChanged(slot); markDirty(); }
        return removed;
    }
    @Override public ItemStack removeStack(int slot) {
        ItemStack removed = Inventories.removeStack(items, slot);
        if (!removed.isEmpty()) { inventorySlotChanged(slot); markDirty(); }
        return removed;
    }
    @Override public void setStack(int slot, ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (slot < 0 || slot >= SLOT_COUNT) throw new IndexOutOfBoundsException("slot " + slot);
        if (slot == OUTPUT_SLOT && !stack.isEmpty()) return;
        if (slot < MAX_INPUTS && !stack.isEmpty() && !acceptsInput(slot, stack)) return;
        items.set(slot, stack);
        if (stack.getCount() > Math.min(getMaxCountPerStack(), stack.getMaxCount()))
            stack.setCount(Math.min(getMaxCountPerStack(), stack.getMaxCount()));
        inventorySlotChanged(slot);
        markDirty();
    }
    @Override public boolean canPlayerUse(PlayerEntity player) {
        return world == null || world.getBlockEntity(pos) == this
                && player.squaredDistanceTo(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64;
    }
    @Override public void clear() { items.clear(); progress = 0; inventorySlotChanged(OUTPUT_SLOT);
        for (int i = 0; i < MAX_INPUTS; i++) inventorySlotChanged(i); markDirty(); }
    @Override public boolean isValid(int slot, ItemStack stack) {
        return slot == OUTPUT_SLOT ? false : acceptsInput(slot, stack);
    }
    @Override public int[] getAvailableSlots(Direction side) {
        if (side == Direction.DOWN) return OUTPUT_SLOTS;
        if (side == null) return ACTIVE_INPUT_SLOTS[0];
        bindRuntime(ConfiguredItemParameters.current());
        return ACTIVE_INPUT_SLOTS[inputRoles.size()];
    }
    @Override public boolean canInsert(int slot, ItemStack stack, Direction direction) {
        // Same admission filter for GUI and hopper; direction only restricts exposed slots.
        return direction != null && direction != Direction.DOWN && isValid(slot, stack);
    }
    @Override public boolean canExtract(int slot, ItemStack stack, Direction direction) {
        return direction == Direction.DOWN && slot == OUTPUT_SLOT;
    }
    @Override public Text getDisplayName() { return Text.translatable("container.itemparams_demo.processor"); }

    @Override public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity player) {
        bindRuntime(ConfiguredItemParameters.current());
        return new ProcessorScreenHandler(syncId, inventory, this, properties);
    }

    @Override public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        bindRuntime(ConfiguredItemParameters.current());
        buf.writeString(recipeId, ParameterSchema.MAX_ID_LENGTH);
        buf.writeVarInt(Math.max(0, selectedRecipeIndex()));
        buf.writeVarInt(recipeCount());
        buf.writeVarInt(inputRoles.size());
        List<String> itemIds = inputItemIds();
        for (int i = 0; i < inputRoles.size(); i++) {
            buf.writeString(inputRoles.get(i), ParameterSchema.MAX_ID_LENGTH);
            buf.writeString(itemIds.get(i), ParameterSchema.MAX_ID_LENGTH);
        }
        buf.writeString(outputItemId(), ParameterSchema.MAX_ID_LENGTH);
    }
}
