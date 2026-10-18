package dev.itemparams.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ItemDefinitionEditorTest {
  @Test
  void parametersCanBeDefinedWithoutAnyRecipeAndCallerCopiesStayDetached() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var project = new EditorProject();
          var item = new EditorProject.Item("minecraft:diamond", "Алмаз");
          project.items.add(item);
          var panel = new ArrayEditor.EditorPanel(project);
          var mass = new EditorProject.Field("test:mass", "Масса", "12.25");
          panel.applyItemFields(item, List.of(mass));
          assertTrue(project.recipes.isEmpty());
          assertEquals(1225, project.schema(item).defaultRawAt(0));
          assertEquals("test:mass", item.fields.get(0).id);
          mass.value = "99";
          assertEquals("12.25", item.fields.get(0).value);
          assertDoesNotThrow(project::validate);
        });
  }

  @Test
  void invalidDefaultOrRemovedUsedFieldCannotPartiallyChangeTheItem() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var project = EditorProject.physicsSample();
          var ore = project.items.get(0);
          var panel = new ArrayEditor.EditorPanel(project);
          var invalid = ore.fields.get(0).copy();
          invalid.value = "20";
          invalid.maximum = "5";
          assertThrows(
              IllegalArgumentException.class, () -> panel.applyItemFields(ore, List.of(invalid)));
          assertEquals(4, ore.fields.size());
          assertEquals("10", ore.fields.get(0).value);
          assertThrows(
              IllegalArgumentException.class,
              () -> panel.applyItemFields(ore, List.of(ore.fields.get(3).copy())));
          assertEquals(4, ore.fields.size());
          assertEquals("M", project.recipes.get(0).inputs.get(0).symbols.get("factory:mass"));
        });
  }

  @Test
  void editingHumanNamesAndDefaultsPreservesStableFormulaBindings() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var project = EditorProject.physicsSample();
          var ore = project.items.get(0);
          var panel = new ArrayEditor.EditorPanel(project);
          var edited = ore.fields.stream().map(EditorProject.Field::copy).toList();
          edited.get(0).name = "Масса сырья";
          edited.get(0).value = "12";
          panel.applyItemFields(ore, edited);
          assertEquals("factory:mass", ore.fields.get(0).id);
          assertEquals("Масса сырья", ore.fields.get(0).name);
          assertEquals(
              "48.00",
              project
                  .preview(project.recipes.get(0))
                  .values()
                  .get("factory:hardness")
                  .toPlainString());
        });
  }
}
