package dev.itemparams.fabric;

import com.sun.management.ThreadMXBean;
import dev.itemparams.core.ParameterSchema;
import dev.itemparams.production.ProductionRecipe;
import java.lang.management.ManagementFactory;
import java.util.Map;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Smoke benchmark; separate from correctness tests, no TPS guarantee. */
@Tag("benchmark")
class ProductionMeasurementTest {
    private static volatile ItemStack sink;
    private static volatile long checksum;
    @Test void compareRepeatedOperationWithIndependentCalculation() {
        SharedConstants.createGameVersion(); Bootstrap.initialize();
        ParameterSchema.Builder b = ParameterSchema.builder();
        for (int i = 0; i < 20; i++) b.addRaw("bench:p" + i, (i + 1) * 100);
        ParameterSchema schema = b.build();
        ProductionRecipe recipe = ProductionRecipe.compile("bench:press", Items.DIRT, Items.STONE, 1, schema,
                Map.of("bench:p3", "${bench:p3}/3*2", "bench:p7", "sqrt(${bench:p7}*2)"));
        ItemStack input = new ItemStack(Items.DIRT, 64); ItemParameterStorage.write(input, schema.defaults());
        var workspace = recipe.newWorkspace();
        for (int i = 0; i < 10000; i++) { sink = recipe.execute(input, workspace); sink = recipe.execute(input); }
        measure("repeat_same_input", 50000, () -> sink = recipe.execute(input, workspace));
        measure("independent_calculation", 10000, () -> sink = recipe.execute(input));
        System.out.println("PRODUCTION fields=20 assignments=2 checksum=" + checksum);
    }
    private static void measure(String label, int count, Runnable action) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        boolean supported = bean.isThreadAllocatedMemorySupported();
        if (supported && !bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId(), bytes = supported ? bean.getThreadAllocatedBytes(thread) : 0;
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) { action.run(); checksum += sink.getCount(); }
        long elapsed = System.nanoTime() - start;
        double allocated = supported ? (bean.getThreadAllocatedBytes(thread) - bytes) / (double) count : -1;
        System.out.printf(java.util.Locale.ROOT, "PRODUCTION %s n=%d ns/op=%.1f bytes/op=%.1f%n",
                label, count, elapsed / (double) count, allocated);
    }
}
