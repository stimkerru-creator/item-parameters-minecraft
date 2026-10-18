package dev.itemparams.production;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.formula.InputLayout;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executed by numericCoreTest with Minecraft, Fabric and their test loader absent. */
@Tag("numeric-only")
class NumericBoundaryTest {
    @Test void rootsAndFractionalPowersRetainIntermediatePrecision() {
        ParameterSchema input = ParameterSchema.builder().addRaw("test:a", 100).build();
        ParameterSchema output = ParameterSchema.builder()
                .addRaw("test:sqrt_product", 0)
                .addRaw("test:cbrt_four", 0)
                .addRaw("test:fractional_power", 0)
                .addRaw("test:cbrt_product", 0)
                .addRaw("test:power_two_thirds", 0)
                .addRaw("test:one_third_times_three", 0)
                .build();
        InputLayout layout = InputLayout.of(Map.of("input", input));
        ParameterRecipe recipe = ParameterRecipe.compile("test:roots", layout, output, Map.of(
                "test:sqrt_product", "sqrt(2) * sqrt(8)",
                "test:cbrt_four", "root(4,3)",
                "test:fractional_power", "pow(4,1/3)",
                "test:cbrt_product", "root(2,3) * root(4,3)",
                "test:power_two_thirds", "pow(27,2/3)",
                "test:one_third_times_three", "(1/3)*3"));
        var result = recipe.execute(layout.bind(Map.of("input", input.defaults())));
        assertEquals(400, result.getRaw("test:sqrt_product"));
        assertEquals(158, result.getRaw("test:cbrt_four"));
        assertEquals(158, result.getRaw("test:fractional_power"));
        assertEquals(200, result.getRaw("test:cbrt_product"));
        assertEquals(900, result.getRaw("test:power_two_thirds"));
        assertEquals(100, result.getRaw("test:one_third_times_three"));
    }

    @Test void multiInputNumericRecipeRunsOutsideMinecraft() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("net.minecraft.item.ItemStack"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("net.fabricmc.loader.api.FabricLoader"));
        ParameterSchema bolt = ParameterSchema.builder().addRaw("part:mass", 20).build();
        ParameterSchema plate = ParameterSchema.builder().addRaw("part:mass", 400).build();
        ParameterSchema output = ParameterSchema.builder().addRaw("part:mass", 0).build();
        InputLayout layout = InputLayout.of(Map.of("bolt", bolt, "plate", plate));
        ParameterRecipe recipe = ParameterRecipe.compile("example:assembly", layout, output,
                Map.of("part:mass", "${bolt::part:mass}+${plate::part:mass}"));
        var inputs = layout.bind(Map.of("plate", plate.defaults(), "bolt", bolt.defaults()));
        var workspace = recipe.newWorkspace();
        assertEquals(420, recipe.execute(inputs, workspace).getRaw("part:mass"));
        assertEquals(420, recipe.execute(inputs, workspace).getRaw("part:mass"));
    }
}
