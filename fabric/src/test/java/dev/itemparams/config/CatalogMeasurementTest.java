package dev.itemparams.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Executes through Fabric Loader JUnit/Knot so Minecraft transformations are applied. */
@Tag("benchmark")
class CatalogMeasurementTest {
    @Test void measureSharedAndDistinctTenThousandRecipeCatalogs() {
        CatalogBenchmark.main(new String[0]);
    }
}
