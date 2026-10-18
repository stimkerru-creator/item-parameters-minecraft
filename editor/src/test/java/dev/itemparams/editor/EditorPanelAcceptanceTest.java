package dev.itemparams.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.junit.jupiter.api.Test;

/** Acceptance at real laptop sizes; a one-row list is a regression, even if math passes. */
class EditorPanelAcceptanceTest {
  @Test
  void laptopAndScaledLaptopKeepListsAndThreeFormulaLinesVisible() throws Exception {
    onEdt(
        () -> {
          for (var size :
              List.of(
                  new Dimension(1366, 660), new Dimension(1024, 600), new Dimension(900, 560))) {
            var panel = new ArrayEditor.EditorPanel(EditorProject.tutorialSample());
            panel.setSize(size);
            layout(panel);
            for (JTable table : controls(panel, JTable.class)) {
              assertEquals(3, table.getColumnCount());
              assertTrue(
                  table.getParent().getHeight() >= 90, "At least three rows must fit: " + size);
              assertTrue(table.getWidth() >= 250);
            }
            assertTrue(
                controls(panel, JTextArea.class).get(0).getHeight() >= 65,
                "Three formula lines must fit");
            long buttons =
                controls(panel, JButton.class).stream()
                    .filter(b -> b.isVisible() && !b.getText().isBlank())
                    .count();
            assertTrue(buttons <= 8, "Routine toolbar must not obscure the lists");
          }
        });
  }

  @Test
  void everyInputHasItsOwnVisibleListAndSameFieldsStayIndependent() throws Exception {
    onEdt(
        () -> {
          var p = EditorProject.tutorialSample();
          var r = p.recipes.get(0);
          r.inputs.add(new EditorProject.Component("plate", "Пластина", p.items.get(0).id));
          r.inputs.get(1).symbols.put("factory:mass", "M2");
          r.inputs.get(1).symbols.put("factory:purity", "P2");
          r.equations = p.bind(r, "M + M2 = M\n[25] = H");
          var panel = new ArrayEditor.EditorPanel(p);
          panel.setSize(1366, 660);
          layout(panel);
          var tables = controls(panel, JTable.class);
          assertEquals(3, tables.size(), "two input lists and one output");
          assertEquals("M", tables.get(0).getValueAt(0, 0));
          assertEquals("M2", tables.get(1).getValueAt(0, 0));
          tables.get(1).setValueAt("200", 0, 2);
          calculate(panel);
          assertEquals("1200.00", tables.get(2).getValueAt(0, 2));
          assertEquals("1000", tables.get(0).getValueAt(0, 2));
          assertEquals("1000", p.items.get(0).fields.get(0).value);
        });
  }

  @Test
  void changingTrialInputClearsOldPreviewAndDoesNotChangeDefault() throws Exception {
    onEdt(
        () -> {
          var p = EditorProject.tutorialSample();
          var panel = new ArrayEditor.EditorPanel(p);
          var tables = controls(panel, JTable.class);
          assertEquals("760.00", tables.get(1).getValueAt(0, 2));
          tables.get(0).setValueAt("2000", 0, 2);
          assertEquals("1000", p.items.get(0).fields.get(0).value);
          assertEquals("—", tables.get(1).getValueAt(0, 2));
          calculate(panel);
          assertEquals("1520.00", tables.get(1).getValueAt(0, 2));
        });
  }

  @Test
  void invalidFormulaNeverShowsPreviousSuccessfulOutput() throws Exception {
    onEdt(
        () -> {
          var panel = new ArrayEditor.EditorPanel(EditorProject.tutorialSample());
          var output = controls(panel, JTable.class).get(1);
          var text = controls(panel, JTextArea.class).get(0);
          text.setText("M / [0] = M");
          assertEquals("—", output.getValueAt(0, 2));
          calculate(panel);
          assertEquals("—", output.getValueAt(0, 2));
          text.setText("M + [5] = M");
          calculate(panel);
          assertEquals("1005.00", output.getValueAt(0, 2));
        });
  }

  @Test
  void rowContextActionsKeepBindingsWhenRowsMove() throws Exception {
    onEdt(
        () -> {
          var panel = new ArrayEditor.EditorPanel(EditorProject.sample());
          var input = controls(panel, JTable.class).get(0);
          input.setRowSelectionInterval(0, 0);
          click(input.getComponentPopupMenu(), "Переместить выбранное поле вниз");
          assertEquals(
              "2*1=3\n3+1=2", controls(panel, JTextArea.class).get(0).getText().replace(" ", ""));
          var output = controls(panel, JTable.class).get(1);
          assertEquals("40.00", output.getValueAt(2, 2));
          output.setRowSelectionInterval(2, 2);
          click(output.getComponentPopupMenu(), "Переместить выбранное поле вверх");
          assertEquals("40.00", controls(panel, JTable.class).get(1).getValueAt(1, 2));
        });
  }

  @Test
  void selectedRecipeAndUnfinishedDraftAreNeverSilentlyReplaced() throws Exception {
    onEdt(
        () -> {
          var p = EditorProject.tutorialSample();
          var original = p.recipes.get(0);
          var alternative = new EditorProject.Recipe("Вторая обработка");
          alternative.outputItemId = original.outputItemId;
          alternative.inputs.add(new EditorProject.Component("ore", "Руда", p.items.get(0).id));
          p.recipes.add(alternative);
          var panel = new ArrayEditor.EditorPanel(p);
          JComboBox<?> selector =
              controls(panel, JComboBox.class).stream()
                  .filter(
                      c -> c.getItemCount() > 0 && c.getItemAt(0) instanceof EditorProject.Recipe)
                  .findFirst()
                  .orElseThrow();
          var text = controls(panel, JTextArea.class).get(0);
          text.setText("M + = M");
          selector.setSelectedItem(alternative);
          assertSame(original, selector.getSelectedItem());
          assertEquals("M + = M", text.getText());
        });
  }

  @Test
  void manyParametersUseTheirOwnScrollWithoutShrinkingTheViewport() throws Exception {
    onEdt(
        () -> {
          var p = EditorProject.tutorialSample();
          for (int n = 0; n < 120; n++)
            p.items.get(0).fields.add(new EditorProject.Field("test:f" + n, "Поле " + n, "0"));
          var panel = new ArrayEditor.EditorPanel(p);
          panel.setSize(1024, 600);
          layout(panel);
          var input = controls(panel, JTable.class).get(0);
          assertEquals(122, input.getRowCount());
          assertTrue(input.getParent().getHeight() >= 120);
          assertTrue(input.getHeight() > input.getParent().getHeight());
        });
  }

  static void calculate(Container p) {
    controls(p, JButton.class).stream()
        .filter(b -> b.getText().equals("Рассчитать"))
        .findFirst()
        .orElseThrow()
        .doClick();
  }

  static void click(JPopupMenu p, String text) {
    for (var c : p.getComponents())
      if (c instanceof JMenuItem item && item.getText().equals(text)) {
        item.doClick();
        return;
      }
    throw new AssertionError(text);
  }

  static void layout(Container c) {
    c.doLayout();
    for (var child : c.getComponents()) if (child instanceof Container nested) layout(nested);
  }

  static <T> List<T> controls(Container root, Class<T> type) {
    var found = new ArrayList<T>();
    for (var c : root.getComponents()) {
      if (type.isInstance(c)) found.add(type.cast(c));
      if (c instanceof Container nested) found.addAll(controls(nested, type));
    }
    return found;
  }

  static void onEdt(Runnable action) throws Exception {
    System.setProperty("java.awt.headless", "true");
    SwingUtilities.invokeAndWait(action);
  }
}
