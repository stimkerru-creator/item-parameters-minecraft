package dev.itemparams.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent author workflow checks, with no Minecraft classes on the classpath. */
class EditorProjectAcceptanceTest {
  @TempDir Path temporary;

  @Test
  void exampleProducesRequestedValuesAndCopiesUntouchedCharacteristics() {
    EditorProject project = EditorProject.sample();
    Map<String, BigDecimal> result = project.preview(project.recipes.get(0)).values();
    number("40", result.get("factory:hardness"));
    number("11", result.get("factory:density"));
    number("10", result.get("factory:mass"));
    number("90", result.get("factory:purity"));
  }

  @Test
  void saveReopenRenameAppendAndReorderPreserveEquationsByStableIdentity() throws Exception {
    EditorProject project = EditorProject.sample();
    Map<String, BigDecimal> original = project.preview(project.recipes.get(0)).values();
    EditorProject.Item ore = project.items.get(0);
    EditorProject.Item ingot = project.items.get(1);
    ore.fields.get(0).name = "Масса заготовки";
    Collections.rotate(ore.fields, 1);
    Collections.reverse(ingot.fields);
    ore.fields.add(new EditorProject.Field("factory:new_input", "Новая характеристика", "123"));
    ingot.fields.add(new EditorProject.Field("factory:new_output", "Новое выходное поле", "45"));
    project.recipes.get(0).name = "Новое понятное название обработки";

    Path file = temporary.resolve("Производство.itemparams-project.json");
    project.save(file);
    EditorProject reopened = EditorProject.load(file);
    Map<String, BigDecimal> actual = reopened.preview(reopened.recipes.get(0)).values();
    original.forEach((id, value) -> assertEquals(0, value.compareTo(actual.get(id)), id));
    number("45", actual.get("factory:new_output"));
    assertEquals(
        "Масса заготовки",
        reopened.items.get(0).fields.stream()
            .filter(f -> f.id.equals("factory:mass"))
            .findFirst()
            .orElseThrow()
            .name);
    assertNotEquals(
        "1*2=3\n3+2=2",
        reopened.display(reopened.recipes.get(0)).replace(" ", ""),
        "Displayed row numbers should follow the new row order");
  }

  @Test
  void deletingAReferencedFieldDoesNotRebindToItsNewNeighbour() {
    EditorProject project = EditorProject.sample();
    project.items.get(0).fields.remove(0);
    assertThrows(IllegalArgumentException.class, project::validate);
    assertThrows(IllegalArgumentException.class, () -> project.preview(project.recipes.get(0)));
  }

  @Test
  void twoRecipesWithSameItemsCanProduceDifferentOutputsWithoutSharingState() {
    EditorProject project = EditorProject.sample();
    EditorProject.Recipe original = project.recipes.get(0);
    EditorProject.Recipe alternative = new EditorProject.Recipe("Альтернативная обработка");
    alternative.inputs.add(new EditorProject.Component("ore", "Руда", "minecraft:raw_iron"));
    alternative.outputItemId = original.outputItemId;
    alternative.copyCommonFrom = "ore";
    alternative.equations = project.bind(alternative, "1 * 2 * [0.95] = 3\n[25] = 2");
    project.recipes.add(alternative);
    project.validate();
    number("40", project.preview(original).values().get("factory:hardness"));
    number("38", project.preview(alternative).values().get("factory:hardness"));
    number("25", project.preview(alternative).values().get("factory:density"));
    number("40", project.preview(original).values().get("factory:hardness"));
  }

  @Test
  void exportProducesOrdinaryCatalogAndImportKeepsItsNumericMeaning() throws Exception {
    EditorProject project = EditorProject.sample();
    Path catalog = temporary.resolve("catalog");
    project.exportCatalog(catalog);
    assertTrue(Files.isRegularFile(catalog.resolve("items/editor-items.json")));
    assertTrue(Files.isRegularFile(catalog.resolve("recipes/editor-recipes.json")));
    EditorProject imported = EditorProject.importCatalog(catalog);
    assertEquals(
        project.preview(project.recipes.get(0)).values(),
        imported.preview(imported.recipes.get(0)).values());
  }

  @Test
  void exportRefusesExistingFilesAndDoesNotModifyThem() throws Exception {
    EditorProject project = EditorProject.sample();
    Path directory = temporary.resolve("existing");
    Files.createDirectory(directory);
    Path unrelated = directory.resolve(".keep");
    Files.writeString(unrelated, "existing user file", StandardCharsets.UTF_8);
    assertThrows(IOException.class, () -> project.exportCatalog(directory));
    assertEquals("existing user file", Files.readString(unrelated));
    assertFalse(Files.exists(directory.resolve("items")));
  }

  @Test
  void failedProjectValidationLeavesPreviousSavedProjectUntouched() throws Exception {
    EditorProject project = EditorProject.sample();
    Path file = temporary.resolve("saved.json");
    project.save(file);
    byte[] before = Files.readAllBytes(file);
    project.items.get(0).fields.get(0).value = "1000000000000";
    assertThrows(IllegalArgumentException.class, () -> project.save(file));
    assertArrayEquals(before, Files.readAllBytes(file));
    assertDoesNotThrow(() -> EditorProject.load(file));
  }

  @Test
  void aFutureProjectVersionIsRejectedInsteadOfSilentlyRewritten() throws Exception {
    EditorProject project = EditorProject.sample();
    Path file = temporary.resolve("future.json");
    project.save(file);
    String content =
        Files.readString(file).replace("\"editorVersion\": 2", "\"editorVersion\": 999");
    Files.writeString(file, content);
    assertThrows(IOException.class, () -> EditorProject.load(file));
    assertEquals(content, Files.readString(file));
  }

  @Test
  void importedUnknownRuntimeRulesAreRejectedWithoutChangingOriginalFiles() throws Exception {
    Path catalog = temporary.resolve("unsupported");
    EditorProject.sample().exportCatalog(catalog);
    Path recipes = catalog.resolve("recipes/editor-recipes.json");
    String before =
        Files.readString(recipes)
            .replace("\"output\": {", "\"unrecognized_rule\": true, \"output\": {");
    Files.writeString(recipes, before);
    assertThrows(IOException.class, () -> EditorProject.importCatalog(catalog));
    assertEquals(before, Files.readString(recipes));
  }

  private static void number(String expected, BigDecimal actual) {
    assertNotNull(actual);
    assertEquals(0, new BigDecimal(expected).compareTo(actual));
  }
}
