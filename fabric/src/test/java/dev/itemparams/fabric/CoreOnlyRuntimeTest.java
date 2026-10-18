package dev.itemparams.fabric;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.production.ProductionRecipe;
import java.util.Map;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Fails if optional editor/demo classes are present: run through coreRuntimeTest. */
@Tag("core-only")
class CoreOnlyRuntimeTest {
    @Test void formulaWorksWithoutDesignerToolsOrDemo() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("dev.itemparams.demo.ItemParametersDemoMod"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("dev.itemparams.fabric.machine.ProcessorBlockEntity"));
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        ParameterSchema schema = ParameterSchema.builder().addRaw("designer:mass", 1250).build();
        // A host mechanism executes a designer-defined formula in ordinary runtime.
        ProductionRecipe operation = ProductionRecipe.compile("designer:rolling", Items.DIRT, Items.STONE,
                1, schema, Map.of("designer:mass", "${designer:mass} / 3 * 2"));
        ItemStack input = new ItemStack(Items.DIRT, 32);
        ItemParameterStorage.write(input, schema.defaults());
        var workspace = operation.newWorkspace();
        assertEquals(833, ItemParameterStorage.read(operation.execute(input, workspace), schema).getRaw("designer:mass"));
        for (int i = 0; i < 3; i++) {
            assertEquals(833, ItemParameterStorage.read(operation.execute(input, workspace), schema).getRaw("designer:mass"));
        }
        assertEquals(32, input.getCount());
    }
}
