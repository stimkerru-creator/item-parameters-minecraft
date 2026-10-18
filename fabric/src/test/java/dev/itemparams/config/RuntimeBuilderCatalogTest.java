package dev.itemparams.config;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.fabric.ItemParameterStorage;
import dev.itemparams.production.ProductionRecipe;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeBuilderCatalogTest {
    @BeforeAll static void bootstrap() { SharedConstants.createGameVersion(); Bootstrap.initialize(); }
    @Test void tenThousandRecipesRetainIndependentDefinitionsAgainstOneSchema() {
        RuntimeBuilder builder = new RuntimeBuilder();
        for (int field = 0; field < 20; field++) builder.field("test:f" + field, BigDecimal.valueOf(100));
        for (int recipe = 0; recipe < 10000; recipe++) {
            Map<String, String> assignments = new LinkedHashMap<>();
            for (int field = 0; field < 20; field++) assignments.put("test:f" + field,
                    field == 0 ? "${test:f0} + " + recipe + "/10000" : "${test:f" + field + "}");
            builder.recipe("test:r" + recipe, Items.GLASS_BOTTLE, Items.GLASS_BOTTLE, 1, assignments);
        }
        ConfigurationSnapshot snapshot = builder.build();
        assertEquals(10000, snapshot.recipes().size()); assertEquals(20, snapshot.schema().size());
        ItemStack input = new ItemStack(Items.GLASS_BOTTLE);
        ItemStack first = snapshot.recipe("test:r0").execute(input);
        ItemStack last = snapshot.recipe("test:r9999").execute(input);
        assertEquals(10000, ItemParameterStorage.read(first, snapshot.schema()).getRaw("test:f0"));
        assertEquals(10099, ItemParameterStorage.read(last, snapshot.schema()).getRaw("test:f0"));
        assertEquals(10000, ItemParameterStorage.read(last, snapshot.schema()).getRaw("test:f19"));
        assertNull(input.getNbt());
    }
    @Test void snapshotCannotMixSchemaGenerationsOrRecipeKeys() {
        ParameterSchema first = ParameterSchema.builder().addRaw("test:x", 100).build();
        ParameterSchema second = ParameterSchema.builder().addRaw("test:x", 100).build();
        ProductionRecipe recipe = ProductionRecipe.compile("test:r", Items.STICK, Items.STICK, 1, first,
                Map.of("test:x", "${test:x}"));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationSnapshot(second, Map.of("test:r", recipe)));
        assertThrows(IllegalArgumentException.class, () -> new ConfigurationSnapshot(first, Map.of("test:wrong", recipe)));
    }
    @Test void configurationDefaultsDoNotSilentlyTruncateFractionalHundredths() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeBuilder().field("test:x", new BigDecimal("0.009")));
    }
}
