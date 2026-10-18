package dev.itemparams.catalog;

import dev.itemparams.core.ParameterValues;
import dev.itemparams.fabric.ItemParameterStorage;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ItemCatalogIntegrationTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { SharedConstants.createGameVersion(); Bootstrap.initialize(); }

    private ItemCatalogSnapshot prepared() throws Exception {
        Files.createDirectories(directory.resolve("items/minecraft"));
        Files.createDirectories(directory.resolve("recipes"));
        Files.writeString(directory.resolve("items/minecraft/stick.json"), """
            {"item":"minecraft:stick","fields":[
              {"id":"test:mass","default":2},
              {"id":"test:strength","default":19},
              {"id":"test:quality","default":20},
              {"id":"test:wear","default":4}
            ]}
            """);
        Files.writeString(directory.resolve("items/minecraft/bowl.json"), """
            {"item":"minecraft:bowl","fields":[
              "test:mass","test:strength","test:quality","test:wear",
              {"id":"test:coating","default":10},
              "test:precision"
            ]}
            """);
        Files.writeString(directory.resolve("recipes/wood.json"), """
            {"id":"test:assemble","inputs":{"wood":"minecraft:stick"},
             "output":{"item":"minecraft:bowl"},"copy_common_from":"wood",
             "assignments":{"test:precision":"sqrt(16)"}}
            """);
        return ItemCatalogLoader.load(directory);
    }

    @Test void itemSchemasIndependentAndFourFieldsBecomeSix() throws Exception {
        var catalog = prepared();
        assertEquals(4, catalog.schema(Items.STICK).size());
        assertEquals(6, catalog.schema(Items.BOWL).size());
        ItemStack stick = new ItemStack(Items.STICK);
        var input = ConfiguredItemParameters.read(catalog, stick);
        assertNull(stick.getNbt(), "Lazy defaults must not mutate vanilla stacks");
        input.set("test:mass", new java.math.BigDecimal("5.34"));
        ItemParameterStorage.write(stick, input);
        ItemStack bowl = catalog.recipe("test:assemble").execute(Map.of("wood", stick));
        assertTrue(bowl.isOf(Items.BOWL));
        var values = ItemParameterStorage.read(bowl, catalog.schema(Items.BOWL));
        assertEquals(534, values.getRaw("test:mass"));
        assertEquals(1900, values.getRaw("test:strength"));
        assertEquals(2000, values.getRaw("test:quality"));
        assertEquals(400, values.getRaw("test:wear"));
        assertEquals(1000, values.getRaw("test:coating"));
        assertEquals(400, values.getRaw("test:precision"));
        assertEquals(-1, catalog.schema(Items.STICK).indexOf("test:coating"));
        assertNotNull(bowl.getNbt(), "Computed output MUST carry NBT");
        assertEquals(6, values.toRawMap().size());
    }

    @Test void tenThousandDifferentNumericOutputsNeedNoNewFilesOrIds() throws Exception {
        var catalog = prepared();
        var formula = catalog.recipe("test:assemble");
        ItemStack stick = new ItemStack(Items.STICK);
        int produced = 0;
        for (int i = 1; i <= 10000; i++) {
            var input = catalog.schema(Items.STICK).defaults();
            input.setRaw("test:mass", i); // 0.01 .. 100.00
            ItemParameterStorage.write(stick, input);
            ItemStack result = formula.execute(Map.of("wood", stick));
            assertEquals(i, ItemParameterStorage.read(result, catalog.schema(Items.BOWL)).getRaw("test:mass"));
            produced++;
        }
        assertEquals(10000, produced);
        assertEquals(1, catalog.recipes().size());
        assertEquals(2, catalog.items().size());
    }

    @Test void tenThousandUniqueVariantsWithTwentyFieldsUseOneDefinitionAndOneRecipe() throws Exception {
        Path items = directory.resolve("items");
        Path recipes = directory.resolve("recipes");
        Files.createDirectories(items);
        Files.createDirectories(recipes);
        String fields = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> "{\"id\":\"test:p" + i + "\",\"default\":0}")
                .collect(java.util.stream.Collectors.joining(","));
        Files.writeString(items.resolve("stick.json"), "{\"item\":\"minecraft:stick\",\"fields\":[" + fields + "]}");
        Files.writeString(items.resolve("bowl.json"), "{\"item\":\"minecraft:bowl\",\"fields\":[" + fields + "]}");
        Files.writeString(recipes.resolve("stick_to_bowl.json"), """
                {"id":"test:twenty_fields","inputs":{"wood":"minecraft:stick"},
                 "output":{"item":"minecraft:bowl"},"copy_common_from":"wood",
                 "assignments":{"test:p0":"${wood::test:p0} + 1"}}
                """);
        var catalog = ItemCatalogLoader.load(directory);
        var recipe = catalog.recipe("test:twenty_fields");
        ItemStack input = new ItemStack(Items.STICK);
        java.util.Set<Integer> outputs = new java.util.HashSet<>();
        for (int i = 1; i <= 10000; i++) {
            ParameterValues values = catalog.schema(Items.STICK).defaults();
            values.setRaw("test:p0", i);
            ItemParameterStorage.write(input, values);
            ItemStack output = recipe.execute(Map.of("wood", input));
            ParameterValues result = ItemParameterStorage.read(output, catalog.schema(Items.BOWL));
            assertEquals(i + 100, result.getRaw("test:p0"));
            assertEquals(20, result.toRawMap().size());
            outputs.add(result.getRaw("test:p0"));
        }
        assertEquals(10000, outputs.size());
        assertEquals(2, catalog.items().size());
        assertEquals(1, catalog.recipes().size());
    }

    @Test void separateValuesAreNotCombinedAndNbtSurvivesCopy() throws Exception {
        var catalog = prepared();
        var recipe = catalog.recipe("test:assemble");
        ItemStack a = new ItemStack(Items.STICK);
        ItemStack b = new ItemStack(Items.STICK);
        var av = catalog.schema(Items.STICK).defaults();
        var bv = catalog.schema(Items.STICK).defaults();
        av.setRaw("test:mass", 534);
        bv.setRaw("test:mass", 612);
        ItemParameterStorage.write(a, av);
        ItemParameterStorage.write(b, bv);
        var outputA = recipe.execute(Map.of("wood", a));
        var outputB = recipe.execute(Map.of("wood", b));
        assertFalse(ItemStack.canCombine(outputA, outputB));
        assertEquals(534, ItemParameterStorage.read(outputA.copy(), catalog.schema(Items.BOWL)).getRaw("test:mass"));
        assertTrue(ItemStack.canCombine(outputA, outputA.copy()));
    }

    @Test void rejectUnregisteredItemAndDuplicateDeclarations() throws Exception {
        prepared();
        Files.writeString(directory.resolve("items/duplicates.json"), """
                {"item":"minecraft:stick","fields":["test:mass"]}
                """);
        assertThrows(IOException.class, () -> ItemCatalogLoader.load(directory));
        Files.delete(directory.resolve("items/duplicates.json"));
        Files.writeString(directory.resolve("items/bad.json"), """
                {"item":"test:item_not_registered","fields":["test:mass"]}
                """);
        assertThrows(IOException.class, () -> ItemCatalogLoader.load(directory));
    }

    @Test void multiInputUsesIndependentValuesAndNoNewOutputItemRegistration() throws Exception {
        var catalog = prepared();
        Files.writeString(directory.resolve("recipes/combine.json"), """
            {"id":"test:combine","inputs":{"left":"minecraft:stick","right":"minecraft:stick"},
             "output":{"item":"minecraft:bowl"},
             "assignments":{"test:mass":"${left::test:mass} + ${right::test:mass}"}}
            """);
        catalog = ItemCatalogLoader.load(directory);
        ItemStack left = new ItemStack(Items.STICK);
        ItemStack right = new ItemStack(Items.STICK);
        var a = catalog.schema(Items.STICK).defaults();
        var b = catalog.schema(Items.STICK).defaults();
        a.setRaw("test:mass", 534);
        b.setRaw("test:mass", 100);
        ItemParameterStorage.write(left, a);
        ItemParameterStorage.write(right, b);
        ItemStack result = catalog.recipe("test:combine").execute(Map.of("left", left, "right", right));
        assertEquals(634, ItemParameterStorage.read(result, catalog.schema(Items.BOWL)).getRaw("test:mass"));
        assertEquals(1, catalog.items().values().stream().filter(def -> def.item() == Items.BOWL).count());
    }

    @Test void rejectRecipeWithoutInputs() throws Exception {
        prepared();
        Files.writeString(directory.resolve("recipes/no-inputs.json"), """
            {"id":"test:empty","inputs":{},"output":{"item":"minecraft:bowl"},
             "assignments":{"test:mass":"1"}}
            """);
        assertThrows(IOException.class, () -> ItemCatalogLoader.load(directory));
    }

    @Test void immutablePublishedGenerationAfterFailedReload() throws Exception {
        var first = prepared();
        ConfiguredItemParameters.install(first);
        try {
            Files.writeString(directory.resolve("recipes/wood.json"), """
                {"id":"test:assemble","inputs":{"wood":"minecraft:stick"},
                 "output":{"item":"minecraft:bowl"},"assignments":{"test:mass":"${wood::test:absent}"}}
                """);
            assertThrows(IOException.class, () -> ItemCatalogLoader.load(directory));
            assertSame(first, ConfiguredItemParameters.current());
        } finally {
            ConfiguredItemParameters.install(ItemCatalogSnapshot.empty());
        }
    }
}
