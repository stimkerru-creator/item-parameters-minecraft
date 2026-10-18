package dev.itemparams.production;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.formula.InputLayout;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Checks 10k independent value combinations with twenty fields, no Minecraft item registration. */
class UniqueVariantScaleTest {
    @Test void tenThousandUniqueTwentyFieldOutputsRequireNoCatalogEntries() {
        var inputBuilder = ParameterSchema.builder();
        var outputBuilder = ParameterSchema.builder();
        for (int i = 0; i < 20; i++) {
            inputBuilder.addRaw("test:field" + i, 0);
            outputBuilder.addRaw("test:field" + i, 0);
        }
        var inputSchema = inputBuilder.build();
        var outputSchema = outputBuilder.build();
        var layout = InputLayout.of(Map.of("part", inputSchema));
        var recipe = ParameterRecipe.builder("test:copy", layout, outputSchema)
                .copyCommonFrom("part").build();
        var workspace = recipe.newWorkspace();
        for (int i = 1; i <= 10000; i++) {
            var source = inputSchema.defaults();
            source.setRaw("test:field0", i);
            source.setRaw("test:field19", 20000 - i);
            var result = recipe.execute(layout.bind(Map.of("part", source)), workspace);
            assertEquals(20, result.toRawMap().size());
            assertEquals(i, result.getRaw("test:field0"));
            assertEquals(20000 - i, result.getRaw("test:field19"));
        }
    }
}
