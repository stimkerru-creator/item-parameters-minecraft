package dev.itemparams.catalog;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server-owned configuration and default materialization. No client-edit protocol is registered. */
public final class ItemParametersFoundation implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("itemparams");
    public static final Path CATALOG = FabricLoader.getInstance().getConfigDir().resolve("itemparams");
    private static final int MATERIALIZE_INTERVAL_TICKS = 10;

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                reloadCatalog();
            } catch (IOException failure) {
                throw new IllegalStateException("Invalid server-side Item Parameters catalog", failure);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server ->
                ConfiguredItemParameters.install(ItemCatalogSnapshot.empty()));

        // The server materializes defaults into authoritative ItemStack NBT. Vanilla inventory sync then
        // displays those values in tooltips. The client never sends parameter values or edits back.
        int[] ticks = {0};
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks[0] % MATERIALIZE_INTERVAL_TICKS != 0) return;
            for (var player : server.getPlayerManager().getPlayerList()) {
                ScreenHandler handler = player.currentScreenHandler;
                boolean cursorChanged = false;
                for (Slot slot : handler.slots) {
                    ItemStack stack = slot.getStack();
                    if (stack.isEmpty()) continue;
                    try {
                        if (ConfiguredItemParameters.initializeIfMissing(stack)) slot.markDirty();
                    } catch (RuntimeException invalidExistingData) {
                        // Fail closed: malformed NBT is not overwritten or trusted.
                    }
                }
                ItemStack cursor = handler.getCursorStack();
                if (!cursor.isEmpty()) {
                    try { cursorChanged = ConfiguredItemParameters.initializeIfMissing(cursor); }
                    catch (RuntimeException invalidExistingData) { /* fail closed */ }
                }
                if (cursorChanged) handler.sendContentUpdates();
            }
        });
    }

    /** Validate the complete server catalog before atomically publishing it. */
    public static ItemCatalogSnapshot reloadCatalog() throws IOException {
        Files.createDirectories(CATALOG.resolve("items"));
        Files.createDirectories(CATALOG.resolve("recipes"));
        ItemCatalogSnapshot validated = ItemCatalogLoader.load(CATALOG);
        ConfiguredItemParameters.install(validated);
        LOGGER.info("Item Parameters loaded {} item definitions and {} formulas",
                validated.items().size(), validated.recipes().size());
        return validated;
    }
}
