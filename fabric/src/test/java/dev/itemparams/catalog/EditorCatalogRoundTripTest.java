package dev.itemparams.catalog;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemparams.editor.EditorProject;
import dev.itemparams.fabric.ItemParameterStorage;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Run editor exports through the actual Minecraft catalog loader and ItemStack adapter. */
class EditorCatalogRoundTripTest {
  @Test
  void clearTutorialKeepsRawIronAndIngotDefaultsSeparateAndWritesCalculatedOutput()
      throws Exception {
    var project = EditorProject.tutorialSample();
    Path exported = directory.resolve("clear-tutorial");
    project.exportCatalog(exported);
    var catalog = ItemCatalogLoader.load(exported);
    assertEquals(100000, catalog.schema(Items.RAW_IRON).defaults().getRaw("factory:mass"));
    assertEquals(8000, catalog.schema(Items.RAW_IRON).defaults().getRaw("factory:purity"));
    assertEquals(0, catalog.schema(Items.IRON_INGOT).defaults().getRaw("factory:mass"));
    var input = new ItemStack(Items.RAW_IRON);
    var result = catalog.recipe(project.recipes.get(0).id).execute(Map.of("ore", input));
    assertTrue(result.isOf(Items.IRON_INGOT));
    var actual = ItemParameterStorage.read(result, catalog.schema(Items.IRON_INGOT));
    assertEquals(76000, actual.getRaw("factory:mass"));
    assertEquals(9000, actual.getRaw("factory:purity"));
    assertEquals(2500, actual.getRaw("factory:hardness"));
    assertFalse(input.hasNbt(), "Calculation must not rewrite the input stack");
  }

  @Test
  void greekAndPhysicalSymbolsExportToTheExistingMinecraftRecipeEngine() throws Exception {
    var project = EditorProject.physicsSample();
    var recipe = project.recipes.get(0);
    recipe.equations = project.bind(recipe, "M * ρ = H\nH + ρ = ρ");
    Path exported = directory.resolve("symbols");
    project.exportCatalog(exported);
    var catalog = ItemCatalogLoader.load(exported);
    var output = catalog.recipe(recipe.id).execute(Map.of("ore", new ItemStack(Items.RAW_IRON)));
    var values = ItemParameterStorage.read(output, catalog.schema(Items.IRON_INGOT));
    assertEquals(4000, values.getRaw("factory:hardness"));
    assertEquals(1100, values.getRaw("factory:density"));
  }

  @TempDir Path directory;

  @BeforeAll
  static void bootstrap() {
    SharedConstants.createGameVersion();
    Bootstrap.initialize();
  }

  @Test
  void numberedExampleCreatesRealOutputNbtWithoutEditorAtProductionTime() throws Exception {
    EditorProject project = EditorProject.sample();
    var recipe = project.recipes.get(0);
    Path export = directory.resolve("itemparams");
    project.exportCatalog(export);
    var catalog = ItemCatalogLoader.load(export);
    var produced = catalog.recipe(recipe.id).execute(Map.of("ore", new ItemStack(Items.RAW_IRON)));
    assertTrue(produced.isOf(Items.IRON_INGOT));
    var actual = ItemParameterStorage.read(produced, catalog.schema(Items.IRON_INGOT));
    assertEquals(1000, actual.getRaw("factory:mass"));
    assertEquals(1100, actual.getRaw("factory:density"));
    assertEquals(4000, actual.getRaw("factory:hardness"));
    assertEquals(9000, actual.getRaw("factory:purity"));
    assertNotNull(produced.getNbt());
  }

  @Test
  void identicalInputAndOutputTypesCanUseDifferentSavedFormulas() throws Exception {
    EditorProject project = EditorProject.sample();
    var first = project.recipes.get(0);
    var second = new EditorProject.Recipe("Другая обработка той же руды");
    second.inputs.add(new EditorProject.Component("ore", "Руда", "minecraft:raw_iron"));
    second.outputItemId = first.outputItemId;
    second.copyCommonFrom = "ore";
    second.equations = project.bind(second, "1 * [0.5] = 1");
    project.recipes.add(second);
    Path saved = directory.resolve("project.iparrays");
    project.save(saved);
    project = EditorProject.load(saved);
    Path export = directory.resolve("export");
    project.exportCatalog(export);
    var catalog = ItemCatalogLoader.load(export);
    var input = Map.of("ore", new ItemStack(Items.RAW_IRON));
    var a = catalog.recipe(first.id).execute(input);
    var b = catalog.recipe(second.id).execute(input);
    assertTrue(a.isOf(Items.IRON_INGOT));
    assertTrue(b.isOf(Items.IRON_INGOT));
    assertEquals(
        1000,
        ItemParameterStorage.read(a, catalog.schema(Items.IRON_INGOT)).getRaw("factory:mass"));
    assertEquals(
        500, ItemParameterStorage.read(b, catalog.schema(Items.IRON_INGOT)).getRaw("factory:mass"));
    assertFalse(ItemStack.canCombine(a, b));
  }

  @Test
  void twoIndependentComponentsUseTheirActualStackValues() throws Exception {
    EditorProject project = EditorProject.sample();
    var recipe = project.recipes.get(0);
    recipe.inputs.add(new EditorProject.Component("other", "Вторая руда", "minecraft:raw_iron"));
    recipe.equations = project.bind(recipe, "1 + 5 = 1");
    Path export = directory.resolve("multi");
    project.exportCatalog(export);
    var catalog = ItemCatalogLoader.load(export);
    var second = new ItemStack(Items.RAW_IRON);
    var values = catalog.schema(Items.RAW_IRON).defaults();
    values.set("factory:mass", new BigDecimal("3"));
    ItemParameterStorage.write(second, values);
    var output =
        catalog
            .recipe(recipe.id)
            .execute(Map.of("ore", new ItemStack(Items.RAW_IRON), "other", second));
    assertEquals(
        1300,
        ItemParameterStorage.read(output, catalog.schema(Items.IRON_INGOT)).getRaw("factory:mass"));
  }
}
