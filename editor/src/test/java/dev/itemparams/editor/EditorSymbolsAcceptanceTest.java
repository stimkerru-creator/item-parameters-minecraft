package dev.itemparams.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorSymbolsAcceptanceTest {
  @TempDir Path temporary;

  @Test
  void symbolsCaseGreekAndWordsReadTheSameParametersAsPositions() {
    var p = EditorProject.sample();
    var r = p.recipes.get(0);
    String[] aliases = {"M", "m", "ρ", "масса"};
    for (int i = 0; i < aliases.length; i++)
      r.inputs.get(0).symbols.put(p.items.get(0).fields.get(i).id, aliases[i]);
    r.outputSymbols.put("factory:hardness", "прочность");
    r.equations = p.bind(r, "M + m + ρ + масса = прочность");
    assertEquals(0, new BigDecimal("111").compareTo(p.preview(r).values().get("factory:hardness")));
    assertTrue(p.display(r).contains("прочность"));
    r.equations = p.bind(r, "1 + 2 + 3 + 4 = 3");
    assertEquals(0, new BigDecimal("111").compareTo(p.preview(r).values().get("factory:hardness")));
  }

  @Test
  void repeatedItemRolesHaveIndependentSymbolsAndSampleValues() {
    var p = EditorProject.sample();
    var r = p.recipes.get(0);
    r.inputs.add(new EditorProject.Component("second", "Другая руда", "minecraft:raw_iron"));
    r.inputs.get(0).symbols.put("factory:mass", "M1");
    r.inputs.get(1).symbols.put("factory:mass", "M2");
    r.inputs.get(1).sampleValues.put("factory:mass", "20");
    r.outputSymbols.put("factory:mass", "M");
    r.equations = p.bind(r, "M1 + M2 = M");
    assertEquals(0, new BigDecimal("30").compareTo(p.preview(r).values().get("factory:mass")));
    r.inputs.get(1).symbols.put("factory:mass", "M1");
    assertThrows(IllegalArgumentException.class, p::validate);
  }

  @Test
  void renameAndReorderRemainIdBoundAfterSaveLoadAndExport() throws Exception {
    var p = EditorProject.physicsSample();
    var r = p.recipes.get(0);
    var before = p.preview(r).values();
    r.inputs.get(0).symbols.put("factory:mass", "масса");
    r.outputSymbols.put("factory:hardness", "σ");
    Collections.reverse(p.items.get(0).fields);
    Collections.reverse(p.items.get(1).fields);
    Path saved = temporary.resolve("physics.ipproject");
    p.save(saved);
    var loaded = EditorProject.load(saved);
    var recipe = loaded.recipes.get(0);
    assertEquals(before, loaded.preview(recipe).values());
    assertTrue(loaded.display(recipe).contains("масса"));
    assertTrue(loaded.display(recipe).contains("σ"));
    Path export = temporary.resolve("itemparams");
    loaded.exportCatalog(export);
    var imported = EditorProject.importCatalog(export);
    assertEquals(before, imported.preview(imported.recipes.get(0)).values());
    assertFalse(Files.readString(export.resolve("recipes/editor-recipes.json")).contains("масса"));
  }

  @Test
  void oldNumericProjectMigratesWithoutChangingItsEquations() throws Exception {
    Path saved = temporary.resolve("old.ipproject");
    try (var resource = getClass().getResourceAsStream("/editor/project-v1.ipproject")) {
      assertNotNull(resource);
      Files.copy(resource, saved);
    }
    var loaded = EditorProject.load(saved);
    assertEquals(2, loaded.editorVersion);
    assertEquals("1*2=3\n3+2=2", loaded.display(loaded.recipes.get(0)).replace(" ", ""));
    assertEquals(
        0,
        new BigDecimal("40")
            .compareTo(loaded.preview(loaded.recipes.get(0)).values().get("factory:hardness")));
  }

  @Test
  void actualTableSymbolEditCommitsPendingDraftAndPreservesMeaning() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var project = EditorProject.sample();
          var panel = new ArrayEditor.EditorPanel(project);
          var tables = controls(panel, JTable.class);
          var formulas = controls(panel, JTextArea.class).get(0);
          formulas.setText("1 + [5] = 3");
          assertTrue(tables.get(0).editCellAt(0, 0));
          ((JTextField) tables.get(0).getEditorComponent()).setText("M");
          controls(panel, JButton.class).stream()
              .filter(b -> b.getText().equals("Рассчитать"))
              .findFirst()
              .orElseThrow()
              .doClick();
          assertTrue(formulas.getText().contains("M"));
          assertEquals("15.00", tables.get(1).getValueAt(2, 2));
          tables.get(1).setValueAt("H", 2, 0);
          assertTrue(formulas.getText().contains("= H"));
          tables.get(0).setValueAt("", 0, 0);
          assertTrue(formulas.getText().contains("1"));
          assertFalse(project.recipes.get(0).inputs.get(0).symbols.containsKey("factory:mass"));
        });
  }

  @Test
  void conflictingTableAliasIsRejectedWithoutChangingExistingBindings() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var project = EditorProject.physicsSample();
          var panel = new ArrayEditor.EditorPanel(project);
          var input = controls(panel, JTable.class).get(0);
          input.setValueAt("M", 1, 0);
          assertEquals("ρ", input.getValueAt(1, 0));
          assertEquals("M", input.getValueAt(0, 0));
          assertDoesNotThrow(project::validate);
        });
  }

  private static <T> List<T> controls(Container root, Class<T> type) {
    List<T> result = new ArrayList<>();
    for (Component child : root.getComponents()) {
      if (type.isInstance(child)) result.add(type.cast(child));
      if (child instanceof Container container) result.addAll(controls(container, type));
    }
    return result;
  }
}
