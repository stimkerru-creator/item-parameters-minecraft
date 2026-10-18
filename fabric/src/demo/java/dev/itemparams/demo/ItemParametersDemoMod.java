package dev.itemparams.demo;

import dev.itemparams.catalog.ItemCatalogSnapshot;
import dev.itemparams.catalog.ItemParametersFoundation;
import dev.itemparams.fabric.machine.ProcessorRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Optional in-world integration test. All recipes use the same server catalog as the main library. */
public final class ItemParametersDemoMod implements ModInitializer {
    public static final String ID = "itemparams_demo";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);
    private static final List<String> EXAMPLES = List.of(
            "items/minecraft/glass_bottle.json",
            "items/minecraft/stick.json",
            "items/minecraft/bowl.json",
            "items/minecraft/chest.json",
            "recipes/drill.json",
            "recipes/choice_1.json",
            "recipes/choice_2.json",
            "recipes/choice_3.json",
            "recipes/polish.json",
            "recipes/stick_to_bowl.json",
            "recipes/bowl_to_chest.json",
            "recipes/assemble_chest.json",
            // Audit scenario 1: four fields -> six -> four -> four, with a root and a reset stage.
            "recipes/audit1_root_stage1.json",
            "recipes/audit1_recut_stage2.json",
            "recipes/audit1_reset_stage3.json",
            // Audit scenario 2: density * volume; remove two source fields, then add three output fields.
            "items/minecraft/iron_ingot.json",
            "items/minecraft/gold_ingot.json",
            "items/minecraft/diamond.json",
            "recipes/audit2_drop_fourth_volume.json",
            "recipes/audit2_add_three_fields.json",
            // Audit scenario 3: same four-field schema; 3 -> 1 -> 2 -> 4 input roles.
            "items/minecraft/iron_nugget.json",
            "items/minecraft/gold_nugget.json",
            "items/minecraft/feather.json",
            "items/minecraft/flint.json",
            "items/minecraft/compass.json",
            "items/minecraft/coal.json",
            "items/minecraft/emerald.json",
            "items/minecraft/quartz.json",
            "items/minecraft/leather.json",
            "items/minecraft/paper.json",
            "items/minecraft/lapis_lazuli.json",
            "recipes/audit3_three_inputs.json",
            "recipes/audit3_one_input.json",
            "recipes/audit3_two_inputs.json",
            "recipes/audit3_four_inputs.json",
            // Audit scenario 4: three + five fields -> six; six + two components -> four fields.
            "items/minecraft/apple.json",
            "items/minecraft/book.json",
            "items/minecraft/amethyst_shard.json",
            "items/minecraft/blaze_powder.json",
            "items/minecraft/prismarine_shard.json",
            "items/minecraft/clock.json",
            "recipes/audit4_three_plus_five.json",
            "recipes/audit4_six_plus_two.json");

    @Override public void onInitialize() {
        ProcessorRegistry.register();
        // Seed examples only for a completely empty catalog. Never overwrite administrator configuration.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                if (catalogHasNoJson()) {
                    for (String relative : EXAMPLES) copyExample(relative);
                    ItemCatalogSnapshot loaded = ItemParametersFoundation.reloadCatalog();
                    LOGGER.info("Installed demo examples: {} item definitions, {} recipes",
                            loaded.items().size(), loaded.recipes().size());
                }
            } catch (Exception failure) {
                throw new IllegalStateException("Could not initialize the optional processor demo", failure);
            }
        });
    }

    private static boolean catalogHasNoJson() throws IOException {
        Path root = ItemParametersFoundation.CATALOG;
        if (!Files.exists(root)) return true;
        try (Stream<Path> files = Files.walk(root)) {
            return files.noneMatch(path -> Files.isRegularFile(path) && path.toString().endsWith(".json"));
        }
    }

    private static void copyExample(String relative) throws IOException {
        Path target = ItemParametersFoundation.CATALOG.resolve(relative);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) return;
        try (InputStream input = ItemParametersDemoMod.class.getResourceAsStream("/itemparams-demo-catalog/" + relative)) {
            if (input == null) throw new IOException("Missing bundled demo configuration: " + relative);
            Files.copy(input, target);
        }
    }
}
