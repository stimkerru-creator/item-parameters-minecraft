package dev.itemparams.config;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationManagerReloadTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { SharedConstants.createGameVersion(); Bootstrap.initialize(); }
    @Test void readerDoesNotCreateDemoSettingsForAConsumer() {
        Path file = directory.resolve("consumer-settings.json");
        assertThrows(IOException.class, () -> new ConfigurationManager(file).reload());
        assertFalse(Files.exists(file));
    }
    @Test void failedExpressionCompilationLeavesTheWholeGenerationActive() throws Exception {
        Path file = directory.resolve("itemparams.json");
        Files.writeString(file, main("${test:x}"));
        ConfigurationManager manager = new ConfigurationManager(file);
        ConfigurationSnapshot first = manager.reload();
        Files.writeString(file, main("${test:missing}"));
        assertThrows(IOException.class, manager::reload);
        assertSame(first, manager.current());
        assertSame(first.schema(), manager.current().recipe("test:copy").schema());
    }
    @Test void modularCatalogLoadsTogetherAndRejectsDuplicateIdsAcrossFiles() throws Exception {
        Path file = directory.resolve("itemparams.json");
        Files.writeString(file, main("${test:x}"));
        Path catalogDirectory = directory.resolve("itemparams-recipes");
        Files.createDirectories(catalogDirectory);
        Path catalog = catalogDirectory.resolve("extra.json");
        Files.writeString(catalog, "{\"version\":1,\"recipes\":[" + recipe("test:extra", "${test:x}+1") + "]}");
        ConfigurationManager manager = new ConfigurationManager(file);
        ConfigurationSnapshot first = manager.reload();
        assertEquals(2, first.recipes().size());
        Files.writeString(catalog, "{\"version\":1,\"recipes\":[" + recipe("test:copy", "2") + "]}");
        assertThrows(IOException.class, manager::reload);
        assertSame(first, manager.current());
    }
    private static String main(String expression) {
        return "{\"version\":1,\"fields\":[{\"id\":\"test:x\",\"default\":1,\"min\":0,\"max\":100}],\"recipes\":["
                + recipe("test:copy", expression) + "]}";
    }
    private static String recipe(String id, String expression) {
        return "{\"id\":\"" + id + "\",\"input\":\"minecraft:stick\",\"output\":{\"item\":\"minecraft:stick\"},\"assignments\":{\"test:x\":\""
                + expression + "\"}}";
    }
}
