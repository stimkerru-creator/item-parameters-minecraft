package dev.itemparams.config;

import dev.itemparams.core.ParameterValues;
import dev.itemparams.fabric.ItemParameterStorage;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Repeatable smoke measurement, not JMH and not a promise about server TPS. */
public final class CatalogBenchmark {
    public static void main(String[] args) {
        SharedConstants.createGameVersion(); Bootstrap.initialize();
        run(0); run(1); run(2);
    }
    private static void run(int mode) {
        long start = System.nanoTime();
        RuntimeBuilder builder = new RuntimeBuilder();
        for (int field = 0; field < 20; field++) builder.field("bench:f" + field, BigDecimal.valueOf(100));
        for (int recipe = 0; recipe < 10000; recipe++) {
            Map<String, String> assignments = new LinkedHashMap<>();
            for (int field = 0; field < 20; field++) assignments.put("bench:f" + field,
                    "${bench:f" + field + "} + " + (mode == 2 ? (recipe * 20 + field) + "/10000"
                        : mode == 1 && field == 0 ? recipe + "/10000" : "0.29"));
            builder.recipe("bench:r" + recipe, Items.GLASS_BOTTLE, Items.GLASS_BOTTLE, 1, assignments);
        }
        ConfigurationSnapshot snapshot = builder.build();
        long built = System.nanoTime();
        if (snapshot.recipes().size() != 10000) throw new AssertionError("Catalog lost recipes");
        ItemStack input = new ItemStack(Items.GLASS_BOTTLE);
        ParameterValues values = snapshot.schema().defaults();
        ItemParameterStorage.write(input, values);
        var recipe = snapshot.recipe("bench:r9999");
        var workspace = recipe.newWorkspace();
        long checksum = 0;
        for (int i = 0; i < 3000; i++) checksum += recipe.execute(input, workspace).getCount();
        long evaluationStart = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            ItemStack output = recipe.execute(input, workspace);
            checksum += ItemParameterStorage.read(output, snapshot.schema()).getRaw(0);
        }
        long end = System.nanoTime();
        if (checksum == 0) throw new AssertionError("Unused benchmark result");
        long lookupStart = System.nanoTime();
        for (int i = 0; i < 100000; i++) checksum += snapshot.recipe("bench:r9999").assignmentCount();
        long lookupEnd = System.nanoTime();
        System.out.printf("Catalog %s: recipes=10000 fields=20 build=%.3f ms fullOperation=%.1f ns lookup=%.1f ns checksum=%d%n",
                mode == 2 ? "all-200000-distinct" : mode == 1 ? "10019-distinct" : "20-shared", (built-start)/1e6, (end-evaluationStart)/10000.0,
                (lookupEnd-lookupStart)/100000.0, checksum);
    }
}
