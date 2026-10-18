package dev.itemparams.production;

import com.sun.management.ThreadMXBean;
import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import dev.itemparams.formula.InputLayout;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Standalone numeric smoke benchmark. No Minecraft, Fabric or JUnit on the runtime classpath. */
public final class MultiInputBenchmark {
    private static volatile ParameterValues sink;
    private static volatile long checksum;
    private MultiInputBenchmark() {}

    public static void main(String[] args) throws Exception {
        try {
            Class.forName("net.minecraft.item.ItemStack");
            throw new AssertionError("Benchmark must run without Minecraft");
        } catch (ClassNotFoundException expected) { /* numeric-only runtime */ }
        System.out.println("MULTI Java=" + System.getProperty("java.version") + " warmup=10000 paired operations; not JMH");
        run(1, 20); run(4, 20); run(16, 120);
    }

    private static void run(int count, int fields) {
        ParameterSchema.Builder schemaBuilder = ParameterSchema.builder();
        for (int i = 0; i < fields; i++) schemaBuilder.addRaw("bench:f" + i, 100);
        ParameterSchema schema = schemaBuilder.build();
        Map<String, ParameterSchema> schemas = new LinkedHashMap<>();
        Map<String, ParameterValues> values = new LinkedHashMap<>();
        StringBuilder expression = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String role = "part" + i;
            schemas.put(role, schema); values.put(role, schema.defaults());
            if (i > 0) expression.append('+');
            expression.append("${").append(role).append("::bench:f0}");
        }
        long compileStart = System.nanoTime();
        InputLayout layout = InputLayout.of(schemas);
        ParameterRecipe recipe = ParameterRecipe.builder("bench:assembly", layout, schema)
                .copyCommonFrom("part0").assign("bench:f0", expression.toString()).build();
        long compileNs = System.nanoTime() - compileStart;
        var inputs = layout.bind(values);
        var workspace = recipe.newWorkspace();
        ParameterValues changing = values.get("part0");
        for (int i = 0; i < 10000; i++) {
            changing.setRaw(0, 100 + (i & 1));
            sink = recipe.execute(inputs, workspace);
            sink = recipe.execute(inputs, workspace);
        }
        String label = "inputs=" + count + " fields/input=" + fields + " output_fields=" + fields;
        System.out.println("MULTI " + label + " compile_ns=" + compileNs);
        measure("hit " + label, 50000, () -> sink = recipe.execute(inputs, workspace));
        int[] iteration = {0};
        measure("miss " + label, 20000, () -> {
            changing.setRaw(0, 100 + ((iteration[0]++) & 1));
            sink = recipe.execute(inputs, workspace);
        });
        if (sink.getRaw(0) != count * 100 + 1) throw new AssertionError("Incorrect benchmark result");
    }

    private static void measure(String label, int iterations, Runnable operation) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        boolean supported = bean.isThreadAllocatedMemorySupported();
        if (supported && !bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        long bytes = supported ? bean.getThreadAllocatedBytes(thread) : 0;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) { operation.run(); checksum += sink.getRaw(0); }
        long elapsed = System.nanoTime() - start;
        double allocation = supported ? (bean.getThreadAllocatedBytes(thread) - bytes) / (double) iterations : -1;
        System.out.printf(Locale.ROOT, "MULTI %s n=%d ns/op=%.1f bytes/op=%.1f checksum=%d%n",
                label, iterations, elapsed / (double) iterations, allocation, checksum);
    }
}
