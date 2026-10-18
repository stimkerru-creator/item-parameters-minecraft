package dev.itemparams.editor;

import dev.itemparams.authoring.BoundEquation;
import dev.itemparams.authoring.InputField;
import dev.itemparams.authoring.NumberedFormulas;
import dev.itemparams.editor.EditorProject.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;

/** Standalone Swing authoring application. No Minecraft classes and no alternative calculator. */
public final class ArrayEditor {
  private ArrayEditor() {}

  public static void main(String[] args) throws Exception {
    if ((args.length == 2 || args.length == 4) && args[0].equals("--render-preview")) {
      int width = args.length == 4 ? Integer.parseInt(args[2]) : 1440;
      int height = args.length == 4 ? Integer.parseInt(args[3]) : 980;
      if (width < 900 || height < 560 || width > 3840 || height > 2160)
        throw new IllegalArgumentException("Preview size outside supported range");
      System.setProperty("java.awt.headless", "true");
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              configureLook();
              EditorPanel panel = new EditorPanel(EditorProject.tutorialSample());
              panel.setSize(width, height);
              layoutAll(panel);
              BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
              Graphics2D g = image.createGraphics();
              panel.printAll(g);
              g.dispose();
              Path path = Path.of(args[1]).toAbsolutePath();
              Files.createDirectories(path.getParent());
              ImageIO.write(image, "png", path.toFile());
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
      return;
    }
    if (args.length == 2 && args[0].equals("--self-test")) {
      Path folder = Path.of(args[1]);
      Files.createDirectories(folder);
      EditorProject p = EditorProject.tutorialSample();
      p.save(folder.resolve("example.ipproject"));
      EditorProject restored = EditorProject.load(folder.resolve("example.ipproject"));
      var result = restored.preview(restored.recipes.get(0)).values();
      if (result.get("factory:hardness").intValueExact() != 25
          || result.get("factory:mass").intValueExact() != 760)
        throw new IllegalStateException("Preview differs");
      restored.exportCatalog(folder.resolve("itemparams"));
      EditorProject imported = EditorProject.importCatalog(folder.resolve("itemparams"));
      if (!result.equals(imported.preview(imported.recipes.get(0)).values()))
        throw new IllegalStateException("Export differs");
      System.out.println("PASS: preview, stable project save/load, catalog export/import");
      return;
    }
    SwingUtilities.invokeLater(
        () -> {
          configureLook();
          EditorPanel panel = new EditorPanel(EditorProject.tutorialSample());
          JFrame frame = new JFrame("Параметры предметов 1.6.1 — учебный пример");
          frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
          frame.setContentPane(panel);
          Rectangle usable =
              GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
          frame.setSize(Math.min(1360, usable.width - 32), Math.min(900, usable.height - 32));
          frame.setMinimumSize(
              new Dimension(Math.min(900, frame.getWidth()), Math.min(560, frame.getHeight())));
          frame.setLocationRelativeTo(null);
          frame.addWindowListener(
              new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                  if (panel.mayDiscard()) frame.dispose();
                }
              });
          frame.setVisible(true);
          if (args.length == 1) panel.open(Path.of(args[0]));
        });
  }

  private static void configureLook() {
    try {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    } catch (Exception ignored) {
    }
    Font font = new Font("Dialog", Font.PLAIN, 14);
    for (Object key : Collections.list(UIManager.getDefaults().keys()))
      if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource) UIManager.put(key, font);
    UIManager.put("OptionPane.yesButtonText", "Да");
    UIManager.put("OptionPane.noButtonText", "Нет");
    UIManager.put("OptionPane.cancelButtonText", "Отмена");
  }

  private static void layoutAll(Container container) {
    container.doLayout();
    for (java.awt.Component c : container.getComponents())
      if (c instanceof Container child) layoutAll(child);
  }

  public static final class EditorPanel extends JPanel {
    private EditorProject project;
    private Recipe recipe;
    private Path document;
    private boolean dirty, refreshing;
    private final JComboBox<Recipe> recipeChoice = new JComboBox<>();
    private final JComboBox<EditorProject.Component> componentChoice = new JComboBox<>();
    private final JComboBox<Item> inputItem = new JComboBox<>(), outputItem = new JComboBox<>();
    private final JComboBox<String> carry = new JComboBox<>();
    private final JSpinner count = new JSpinner(new SpinnerNumberModel(1, 1, 64, 1));
    private final JTextArea equations = new JTextArea();
    private final JLabel status = new JLabel(" "), recipeHint = new JLabel(" ");
    private final InputModel inputModel = new InputModel();
    private final OutputModel outputModel = new OutputModel();
    private final JTable inputTable = new JTable(inputModel), outputTable = new JTable(outputModel);
    private Map<String, java.math.BigDecimal> preview = Map.of();
    private List<InputRow> displayedInputs = List.of();
    private boolean editingFormula;
    private final CardStrip cardStrip = new CardStrip();
    private final List<JTable> compactInputTables = new ArrayList<>();
    private final JLabel projectSource = new JLabel();

    public EditorPanel(EditorProject project) {
      super(new BorderLayout(8, 8));
      this.project = project;
      recipe = project.recipes.isEmpty() ? null : project.recipes.get(0);
      setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
      setBackground(new Color(246, 248, 251));
      JPanel top = new JPanel(new BorderLayout(0, 5));
      top.setOpaque(false);
      top.add(mainMenu(), BorderLayout.NORTH);
      JPanel choice = new JPanel(new BorderLayout(10, 0));
      choice.setOpaque(false);
      choice.add(new JLabel("Рецепт:"), BorderLayout.WEST);
      recipeChoice.setPreferredSize(new Dimension(360, 30));
      choice.add(recipeChoice, BorderLayout.CENTER);
      choice.add(button("Передать в Minecraft…", this::installCatalog), BorderLayout.EAST);
      top.add(choice, BorderLayout.CENTER);
      projectSource.setFont(new Font("Dialog", Font.PLAIN, 12));
      top.add(projectSource, BorderLayout.SOUTH);
      add(top, BorderLayout.NORTH);

      configureTable(inputTable);
      configureTable(outputTable);
      // Defaults and bounds belong to the item dialog. This list shows only the result.
      for (int modelColumn : new int[] {4, 3, 2})
        outputTable.removeColumn(outputTable.getColumnModel().getColumn(modelColumn));
      outputTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
      outputTable.setName("output-parameters");
      JPanel center = new JPanel(new BorderLayout(0, 8));
      center.setOpaque(false);
      JScrollPane cards =
          new JScrollPane(
              cardStrip,
              JScrollPane.VERTICAL_SCROLLBAR_NEVER,
              JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
      cards.setBorder(null);
      center.add(cards, BorderLayout.CENTER);
      JPanel formulas = formulaPanel();
      formulas.setPreferredSize(new Dimension(900, 185));
      center.add(formulas, BorderLayout.SOUTH);
      add(center, BorderLayout.CENTER);
      status.setBorder(BorderFactory.createEmptyBorder(3, 4, 0, 0));
      add(status, BorderLayout.SOUTH);
      inputModel.addTableModelListener(
          event -> {
            for (JTable table : compactInputTables)
              ((AbstractTableModel) table.getModel()).fireTableDataChanged();
          });
      equations
          .getDocument()
          .addDocumentListener(
              new DocumentListener() {
                public void insertUpdate(DocumentEvent e) {
                  changed();
                }

                public void removeUpdate(DocumentEvent e) {
                  changed();
                }

                public void changedUpdate(DocumentEvent e) {
                  changed();
                }

                private void changed() {
                  if (!refreshing) {
                    editingFormula = true;
                    markDirty();
                    clearPreview();
                    status.setForeground(new Color(85, 96, 110));
                    status.setText(
                        "Формулы изменены. Нажмите «Рассчитать», чтобы проверить результат.");
                  }
                }
              });
      recipeChoice.addActionListener(
          e -> {
            if (!refreshing) switchRecipe((Recipe) recipeChoice.getSelectedItem());
          });
      componentChoice.addActionListener(
          e -> {
            if (!refreshing) refreshComponent();
          });
      inputItem.addActionListener(
          e -> {
            if (!refreshing) changeInputItem();
          });
      outputItem.addActionListener(
          e -> {
            if (!refreshing) changeOutputItem();
          });
      carry.addActionListener(
          e -> {
            if (!refreshing && recipe != null)
              guarded(
                  () -> {
                    commit();
                    int i = carry.getSelectedIndex();
                    recipe.copyCommonFrom = i <= 0 ? null : recipe.inputs.get(i - 1).role;
                    markDirty();
                    calculate();
                  });
          });
      count.addChangeListener(
          e -> {
            if (!refreshing && recipe != null) {
              recipe.count = (int) count.getValue();
              markDirty();
            }
          });
      refreshAll();
      calculate();
    }

    private JMenuBar mainMenu() {
      JMenuBar bar = new JMenuBar();
      JMenu file = new JMenu("Файл");
      menu(file, "Новый учебный проект", this::newProject);
      menu(file, "Открыть проект…", () -> chooseOpen(false));
      menu(file, "Сохранить", () -> save(false));
      menu(file, "Сохранить как…", () -> save(true));
      file.addSeparator();
      menu(file, "Импорт игрового каталога…", () -> chooseOpen(true));
      menu(file, "Экспорт в пустую папку…", this::export);
      bar.add(file);
      JMenu recipes = new JMenu("Рецепт");
      menu(recipes, "Добавить рецепт…", this::addRecipe);
      menu(recipes, "Копия текущего", this::duplicateRecipe);
      menu(recipes, "Переименовать…", this::renameRecipe);
      menu(recipes, "Удалить…", this::deleteRecipe);
      menu(
          recipes,
          "Показать ID для станка",
          () -> {
            if (recipe != null)
              JOptionPane.showMessageDialog(
                  this, recipe.id, "Рецепт в игре", JOptionPane.INFORMATION_MESSAGE);
          });
      bar.add(recipes);
      JMenu inputs = new JMenu("Входы");
      menu(inputs, "Добавить входной предмет…", this::addComponent);
      menu(inputs, "Переименовать вход…", () -> withChosenInput(this::renameComponent));
      menu(inputs, "Удалить вход…", () -> withChosenInput(this::removeComponent));
      bar.add(inputs);
      JMenu output = new JMenu("Выход");
      menu(output, "Поля из входа…", () -> withChosenInput(this::copyFields));
      menu(output, "Начальные характеристики…", () -> editItemFields(fieldItem(true)));
      bar.add(output);
      JMenu items = new JMenu("Предметы");
      menu(items, "Предметы и начальные характеристики…", this::manageItems);
      bar.add(items);
      JMenu help = new JMenu("Справка");
      menu(help, "Как задать формулы", this::help);
      menu(
          help,
          "Что означают числа",
          () ->
              JOptionPane.showMessageDialog(
                  this,
                  "Слева — числа для пробного расчёта. Они не меняют предметы в игре.\n"
                      + "Справа — вычисленный выход. Начальные значения задаются в «Предметы».\n"
                      + "Рассчитать не устанавливает настройки в игру. Для этого нажмите «Передать"
                      + " в Minecraft…».\n"
                      + "Обычная печь эти формулы не применяет: используется demo-станок или"
                      + " адаптер другого мода.",
                  "Числа и игра",
                  JOptionPane.INFORMATION_MESSAGE));
      bar.add(help);
      return bar;
    }

    private void menu(JMenu parent, String text, Runnable action) {
      JMenuItem item = new JMenuItem(text);
      item.addActionListener(event -> guarded(action));
      parent.add(item);
    }

    private void withChosenInput(Runnable action) {
      commit();
      if (recipe == null) throw new IllegalArgumentException("Сначала выберите рецепт");
      EditorProject.Component chosen;
      if (recipe.inputs.size() == 1) chosen = recipe.inputs.get(0);
      else {
        Object selected =
            JOptionPane.showInputDialog(
                this,
                "С каким входным предметом выполнить действие?",
                "Выберите вход",
                JOptionPane.QUESTION_MESSAGE,
                null,
                recipe.inputs.toArray(),
                componentChoice.getSelectedItem());
        if (selected == null) return;
        chosen = (EditorProject.Component) selected;
      }
      componentChoice.setSelectedItem(chosen);
      action.run();
    }

    private static final class CardStrip extends JPanel implements Scrollable {
      CardStrip() {
        super(new GridLayout(1, 0, 10, 0));
        setOpaque(false);
      }

      private int minimumWidth() {
        return Math.max(1, getComponentCount()) * 300;
      }

      public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(1000, 360);
      }

      public Dimension getPreferredSize() {
        return new Dimension(minimumWidth(), 360);
      }

      public boolean getScrollableTracksViewportWidth() {
        return getParent() != null && getParent().getWidth() >= minimumWidth();
      }

      public boolean getScrollableTracksViewportHeight() {
        return true;
      }

      public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
        return 30;
      }

      public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
        return Math.max(30, r.width - 30);
      }
    }

    private void friendlyItems(JComboBox<Item> combo) {
      combo.setRenderer(
          new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                JList<?> list, Object value, int i, boolean selected, boolean focus) {
              JLabel label =
                  (JLabel) super.getListCellRendererComponent(list, value, i, selected, focus);
              if (value instanceof Item item) {
                label.setText(item.name);
                label.setToolTipText(item.id);
              }
              return label;
            }
          });
    }

    private void rebuildCards() {
      compactInputTables.clear();
      cardStrip.removeAll();
      if (recipe != null) {
        for (int index = 0; index < recipe.inputs.size(); index++) {
          EditorProject.Component component = recipe.inputs.get(index);
          JPanel panel = card("ВХОД " + (index + 1) + " · " + component.name);
          JPanel head = new JPanel(new BorderLayout(4, 0));
          head.setOpaque(false);
          JComboBox<Item> itemChoice = new JComboBox<>(project.items.toArray(Item[]::new));
          friendlyItems(itemChoice);
          itemChoice.setSelectedItem(project.item(component.itemId));
          itemChoice.addActionListener(
              e -> {
                if (refreshing) return;
                componentChoice.setSelectedItem(component);
                inputItem.setSelectedItem(itemChoice.getSelectedItem());
                boolean previous = refreshing;
                refreshing = true;
                try {
                  itemChoice.setSelectedItem(project.item(component.itemId));
                } finally {
                  refreshing = previous;
                }
              });
          head.add(itemChoice, BorderLayout.CENTER);
          JButton more = button("Поля…", () -> editItemFields(project.item(component.itemId)));
          more.setToolTipText("Названия, начальные значения и диапазоны параметров");
          head.add(more, BorderLayout.EAST);
          panel.add(head, BorderLayout.NORTH);
          AbstractTableModel model =
              new AbstractTableModel() {
                public int getRowCount() {
                  return project.item(component.itemId).fields.size();
                }

                public int getColumnCount() {
                  return 3;
                }

                public String getColumnName(int c) {
                  return new String[] {"Символ", "Параметр", "Значение"}[c];
                }

                public boolean isCellEditable(int row, int col) {
                  return true;
                }

                private int global(int row) {
                  String id = project.item(component.itemId).fields.get(row).id;
                  for (int n = 0; n < displayedInputs.size(); n++)
                    if (displayedInputs.get(n).component() == component
                        && displayedInputs.get(n).field().id.equals(id)) return n;
                  throw new IllegalStateException("Входное поле не найдено");
                }

                public Object getValueAt(int row, int col) {
                  return inputModel.getValueAt(global(row), new int[] {0, 2, 6}[col]);
                }

                public void setValueAt(Object value, int row, int col) {
                  inputModel.setValueAt(value, global(row), new int[] {0, 2, 6}[col]);
                }
              };
          JTable table = new JTable(model);
          configureTable(table);
          table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
          table.setName("input-" + component.role);
          widths(table, new int[] {65, 130, 90});
          table
              .getSelectionModel()
              .addListSelectionListener(
                  e -> {
                    int local = table.getSelectedRow();
                    if (local < 0) return;
                    componentChoice.setSelectedItem(component);
                    String id = project.item(component.itemId).fields.get(local).id;
                    for (int n = 0; n < displayedInputs.size(); n++)
                      if (displayedInputs.get(n).component() == component
                          && displayedInputs.get(n).field().id.equals(id))
                        inputTable.setRowSelectionInterval(n, n);
                  });
          JPopupMenu rows = new JPopupMenu();
          popup(rows, "Переместить выбранное поле вверх", () -> moveField(false, -1));
          popup(rows, "Переместить выбранное поле вниз", () -> moveField(false, 1));
          popup(rows, "Удалить выбранное поле", () -> deleteField(false));
          table.setComponentPopupMenu(rows);
          compactInputTables.add(table);
          panel.add(tableScroll(table), BorderLayout.CENTER);
          panel.add(
              note("Пробные числа. Начальные значения — кнопка «Поля…»."), BorderLayout.SOUTH);
          cardStrip.add(panel);
        }
        cardStrip.add(outputPanel());
      } else {
        cardStrip.add(
            note("В меню «Рецепт» добавьте рецепт. Параметры без рецепта — в меню «Предметы»."));
      }
      cardStrip.revalidate();
      cardStrip.repaint();
    }

    private void popup(JPopupMenu parent, String text, Runnable action) {
      JMenuItem entry = new JMenuItem(text);
      entry.addActionListener(e -> guarded(action));
      parent.add(entry);
    }

    private JPanel outputPanel() {
      JPanel panel = card("ВЫХОД · рассчитанные параметры");
      JPanel head = new JPanel(new BorderLayout(4, 0));
      head.setOpaque(false);
      friendlyItems(outputItem);
      head.add(outputItem, BorderLayout.CENTER);
      count.setPreferredSize(new Dimension(52, 28));
      count.setToolTipText("Количество выходных предметов");
      head.add(count, BorderLayout.EAST);
      panel.add(head, BorderLayout.NORTH);
      panel.add(tableScroll(outputTable), BorderLayout.CENTER);
      JPopupMenu options = new JPopupMenu();
      popup(options, "Начальные характеристики предмета…", () -> editItemFields(fieldItem(true)));
      popup(options, "Добавить выходное поле…", () -> addField(true));
      popup(options, "Взять поля выбранного входа", this::copyFields);
      popup(options, "Переместить выбранное поле вверх", () -> moveField(true, -1));
      popup(options, "Переместить выбранное поле вниз", () -> moveField(true, 1));
      popup(options, "Удалить выбранное поле", () -> deleteField(true));
      outputTable.setComponentPopupMenu(options);
      JPanel bottom = new JPanel(new BorderLayout(3, 3));
      bottom.setOpaque(false);
      bottom.add(new JLabel("Поля без формул:"), BorderLayout.NORTH);
      bottom.add(carry, BorderLayout.CENTER);
      JButton more = button("Поля…", () -> editItemFields(fieldItem(true)));
      more.setToolTipText("Выходные поля и начальные значения");
      bottom.add(more, BorderLayout.EAST);
      panel.add(bottom, BorderLayout.SOUTH);
      return panel;
    }

    private JPanel formulaPanel() {
      JPanel panel = card("ФОРМУЛЫ · выражение = символ поля выхода");
      panel.add(
          note(
              "Например: M * [0.95] = M. Постоянные числа — [25]. Корни: sqrt(M). Каждое назначение"
                  + " с новой строки."),
          BorderLayout.NORTH);
      equations.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 17));
      equations.setMargin(new Insets(6, 8, 6, 8));
      equations.setRows(4);
      equations.setName("recipe-formulas");
      panel.add(new JScrollPane(equations), BorderLayout.CENTER);
      JPanel actions = new JPanel(new BorderLayout(5, 0));
      actions.setOpaque(false);
      actions.add(
          new JLabel(
              "Расчёт здесь — предварительный просмотр. В игру передаётся отдельной кнопкой."),
          BorderLayout.CENTER);
      JButton calculate = button("Рассчитать", this::calculate);
      calculate.setFont(calculate.getFont().deriveFont(Font.BOLD));
      actions.add(calculate, BorderLayout.EAST);
      panel.add(actions, BorderLayout.SOUTH);
      JPopupMenu insert = new JPopupMenu();
      popup(insert, "Вставить выбранное входное поле", this::insertInput);
      popup(insert, "Вставить число…", this::insertConstant);
      popup(insert, "Вставить = выходное поле", this::insertOutput);
      equations.setComponentPopupMenu(insert);
      return panel;
    }

    /** Replace only the exported catalog, with a reversible sibling backup. */
    private void installCatalog() {
      commit();
      project.validate();
      JFileChooser chooser = chooser(true);
      chooser.setDialogTitle("Выберите папку игры Minecraft — в ней должна быть папка mods");
      if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
      Path game = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
      if (!Files.isDirectory(game.resolve("mods")))
        throw new IllegalArgumentException(
            "Выберите папку игры с папкой mods, а не папку проекта или мира");
      if (JOptionPane.showConfirmDialog(
              this,
              "Установить настройки в "
                  + game
                  + "?\n"
                  + "Закройте мир перед установкой. Старый каталог будет сохранён как резервная"
                  + " копия.\n"
                  + "Это копия настроек, а не мира. Удалённые поля могут потерять значения у старых"
                  + " предметов.\n"
                  + "Для продолжения существующей настройки откройте свой проект или импортируйте"
                  + " игровой каталог.\n"
                  + "После установки откройте мир снова. Обычная печь рецепты не применяет.",
              "Передача в Minecraft",
              JOptionPane.OK_CANCEL_OPTION)
          != JOptionPane.OK_OPTION) return;
      try {
        EditorCatalogInstaller.Result installed = EditorCatalogInstaller.install(project, game);
        Path target = installed.catalog();
        status.setText(
            "Установлено в " + target + ". Заново откройте мир; выберите рецепт в demo-станке.");
        status.setToolTipText(status.getText());
        JOptionPane.showMessageDialog(
            this,
            "Настройки установлены.\n"
                + target
                + "\n"
                + "Заново откройте мир. Станок: «Параметрический станок» во вкладке функциональных"
                + " блоков.\n"
                + "Рецепт для выбора в станке: "
                + (recipe == null ? "нет" : recipe.id)
                + "\nДля ранее закреплённой линии: уберите входы и нажмите «Сброс».",
            "Minecraft",
            JOptionPane.INFORMATION_MESSAGE);
      } catch (IOException failure) {
        throw new IllegalArgumentException(
            "Каталог не установлен: " + failure.getMessage(), failure);
      }
    }

    private static JPanel card(String title) {
      JPanel p = new JPanel(new BorderLayout(4, 6));
      p.setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createTitledBorder(
                  BorderFactory.createLineBorder(new Color(213, 220, 230)), title),
              BorderFactory.createEmptyBorder(8, 10, 8, 10)));
      return p;
    }

    private static JPanel row() {
      JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 4));
      p.setOpaque(false);
      return p;
    }

    private static JLabel note(String text) {
      JLabel label = new JLabel("<html>" + text + "</html>");
      label.setAlignmentX(LEFT_ALIGNMENT);
      label.setFont(new Font("Dialog", Font.PLAIN, 12));
      label.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
      return label;
    }

    private static void alignLeft(JPanel panel) {
      for (java.awt.Component child : panel.getComponents())
        if (child instanceof JComponent component) component.setAlignmentX(LEFT_ALIGNMENT);
    }

    private static JScrollPane tableScroll(JTable table) {
      JScrollPane scroll = new JScrollPane(table);
      scroll.setColumnHeaderView(table.getTableHeader());
      table
          .getTableHeader()
          .setToolTipText("Дважды нажмите на символ, имя или входное число для изменения");
      return scroll;
    }

    private static String shownBound(String value, boolean minimum) {
      return value.equals(minimum ? "-21474836.48" : "21474836.47") ? "—" : value;
    }

    private static String enteredBound(String value, boolean minimum) {
      return value.isEmpty() || value.equals("—")
          ? (minimum ? "-21474836.48" : "21474836.47")
          : value;
    }

    private JButton button(String name, Runnable action) {
      JButton b = new JButton(name);
      b.addActionListener(e -> guarded(action));
      return b;
    }

    private void configureTable(JTable table) {
      table.setRowHeight(29);
      table.setFillsViewportHeight(true);
      table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
      table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      table.getTableHeader().setReorderingAllowed(false);
      table.putClientProperty("terminateEditOnFocusLost", true);
    }

    private void guarded(Runnable action) {
      try {
        action.run();
      } catch (Exception e) {
        error(e);
      }
    }

    private void error(Exception e) {
      String text = friendlyError(e);
      System.err.println("Редактор параметров: " + e);
      status.setForeground(new Color(170, 35, 35));
      status.setText("Не выполнено: " + text);
      if (!GraphicsEnvironment.isHeadless())
        JOptionPane.showMessageDialog(
            this, text, "Проверьте настройку", JOptionPane.WARNING_MESSAGE);
    }

    private String friendlyError(Exception error) {
      String raw = error.getMessage() == null ? "" : error.getMessage();
      StringBuilder details = new StringBuilder(raw);
      for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause())
        details.append(' ').append(cause.getMessage());
      String lower = details.toString().toLowerCase(Locale.ROOT);
      String reason = null;
      if (lower.contains("division by zero") || lower.contains("divide by zero"))
        reason = "Деление на ноль: проверьте выражение после знака /.";
      else if (lower.contains("square root requires") || lower.contains("negative radicand"))
        reason = "Для этого корня недопустимо отрицательное число под корнем.";
      else if (lower.contains("outside configured range")
          || lower.contains("outside signed int32")
          || lower.contains("exceeds signed int32"))
        reason =
            "Значение выходит за разрешённые границы. Проверьте минимум, максимум и результат"
                + " формулы.";
      else if (lower.contains("configuration value must fit"))
        reason =
            "Значение предмета должно иметь не больше двух знаков после запятой и помещаться в"
                + " диапазон −21 474 836.48 … 21 474 836.47.";
      else if (lower.contains("exponent exceeds") || lower.contains("resource limit"))
        reason =
            "Слишком большая степень или слишком сложное выражение. Уменьшите показатель степени.";
      else if (lower.contains("root degree cannot be zero"))
        reason = "Степень корня не может быть равна нулю.";
      else if (lower.contains("zero requires a positive")
          || lower.contains("zero cannot have a negative"))
        reason = "Ноль нельзя возводить в такую степень: возникает деление на ноль.";
      else if (lower.contains("negative base requires"))
        reason =
            "Для отрицательного основания нужен целый показатель степени. Для нечётного корня"
                + " используйте root.";
      else if (lower.contains("rational exponent") || lower.contains("rational power"))
        reason = "Показатель степени должен быть рациональным числом, например [2] или [1] / [3].";
      else if (lower.contains("clamp minimum exceeds"))
        reason = "В clamp нижняя граница должна быть не больше верхней.";
      else if (lower.contains("could not certify"))
        reason =
            "Вычислительная библиотека не смогла надёжно определить итоговые сотые. Упростите"
                + " выражение.";
      else if (lower.contains("invalid default/min/max"))
        reason = "Начальное значение должно находиться между минимумом и максимумом.";
      if (reason != null) {
        if (recipe != null)
          for (Field field : project.item(recipe.outputItemId).fields)
            if (raw.contains(field.id)) return "Поле «" + field.name + "»: " + reason;
        return reason;
      }
      for (Item item : project.items) {
        raw = raw.replace(item.id, "«" + item.name + "»");
        for (Field field : item.fields) raw = raw.replace(field.id, "«" + field.name + "»");
      }
      for (Recipe r : project.recipes) raw = raw.replace(r.id, "«" + r.name + "»");
      if (raw.matches("(?s).*[А-Яа-яЁё].*")) return raw;
      return "Не удалось выполнить действие. Проверьте номера полей, запись формулы и допустимые"
          + " значения. Технические сведения записаны в журнал запуска.";
    }

    private void markDirty() {
      dirty = true;
    }

    private void stopEditing() {
      for (JTable table : allEditableTables())
        if (table.isEditing() && !table.getCellEditor().stopCellEditing())
          throw new IllegalArgumentException("Завершите изменение ячейки");
    }

    private List<JTable> allEditableTables() {
      List<JTable> tables = new ArrayList<>(compactInputTables);
      tables.add(outputTable);
      tables.add(inputTable);
      return tables;
    }

    private void updateDocumentTitle() {
      String title =
          document == null ? "Учебный пример · ещё не сохранён" : document.getFileName().toString();
      projectSource.setText(
          title + "  ·  В игре настройки меняются только после передачи каталога");
      projectSource.setToolTipText(
          document == null ? "Сохраните свой проект" : document.toString());
      Window owner = SwingUtilities.getWindowAncestor(this);
      if (owner instanceof JFrame frame) frame.setTitle("Параметры 1.6.1 — " + title);
    }

    private void commit() {
      stopEditing();
      commitFormula();
    }

    private void commitFormula() {
      if (recipe == null) return;
      if (editingFormula) {
        List<BoundEquation> bound = project.bind(recipe, equations.getText());
        recipe.equations = new ArrayList<>(bound);
        editingFormula = false;
      }
    }

    private void refreshAll() {
      refreshing = true;
      try {
        recipeChoice.removeAllItems();
        for (Recipe r : project.recipes) recipeChoice.addItem(r);
        recipeChoice.setSelectedItem(recipe);
        Object previousComponent = componentChoice.getSelectedItem();
        componentChoice.removeAllItems();
        if (recipe != null)
          for (EditorProject.Component c : recipe.inputs) componentChoice.addItem(c);
        if (recipe != null && recipe.inputs.contains(previousComponent))
          componentChoice.setSelectedItem(previousComponent);
        inputItem.removeAllItems();
        outputItem.removeAllItems();
        for (Item item : project.items) {
          inputItem.addItem(item);
          outputItem.addItem(item);
        }
        if (recipe != null) {
          outputItem.setSelectedItem(project.item(recipe.outputItemId));
          count.setValue(recipe.count);
        }
        carry.removeAllItems();
        carry.addItem("Начальные значения выходного предмета");
        if (recipe != null)
          for (EditorProject.Component c : recipe.inputs) carry.addItem("Перенести из: " + c.name);
        int carryIndex = 0;
        if (recipe != null)
          for (int i = 0; i < recipe.inputs.size(); i++)
            if (Objects.equals(recipe.copyCommonFrom, recipe.inputs.get(i).role))
              carryIndex = i + 1;
        carry.setSelectedIndex(carryIndex);
        refreshComponent();
        displayedInputs = recipe == null ? List.of() : project.inputRows(recipe);
        equations.setText(recipe == null ? "" : project.display(recipe));
        editingFormula = false;
        recipeHint.setText(
            recipe == null
                ? "Добавьте обработку, чтобы начать."
                : "Имя обработки видно здесь; для назначения механизму её ID: " + recipe.id);
        inputModel.fireTableDataChanged();
        outputModel.fireTableDataChanged();
        sizeColumns();
        rebuildCards();
        updateDocumentTitle();
      } finally {
        refreshing = false;
      }
    }

    private void refreshComponent() {
      boolean old = refreshing;
      refreshing = true;
      try {
        EditorProject.Component c = (EditorProject.Component) componentChoice.getSelectedItem();
        inputItem.setSelectedItem(c == null ? null : project.item(c.itemId));
      } finally {
        refreshing = old;
      }
    }

    private void sizeColumns() {
      widths(inputTable, new int[] {110, 80, 125, 100, 65, 65, 110});
      widths(outputTable, new int[] {65, 140, 100});
    }

    private static void widths(JTable table, int[] widths) {
      for (int i = 0; i < widths.length; i++) {
        table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(i).setWidth(widths[i]);
      }
    }

    private void changeSymbol(boolean output, int row, String text) {
      commitFormula(); // Bind the old draft before changing its visible vocabulary.
      String symbol = text.trim();
      if (symbol.equals(Integer.toString(row + 1))) symbol = "";
      Map<InputField, String> inputs = new LinkedHashMap<>(project.inputSymbols(recipe));
      Map<String, String> outputs = new LinkedHashMap<>(project.outputSymbols(recipe));
      if (output) {
        String id = project.outputIds(recipe).get(row);
        if (symbol.isBlank()) outputs.remove(id);
        else outputs.put(id, symbol);
      } else {
        InputRow source = displayedInputs.get(row);
        InputField key = new InputField(source.component().role, source.field().id);
        if (symbol.isBlank()) inputs.remove(key);
        else inputs.put(key, symbol);
      }
      NumberedFormulas.validateSymbols(
          project.inputFields(recipe), project.outputIds(recipe), inputs, outputs);
      if (output) {
        String id = project.outputIds(recipe).get(row);
        if (symbol.isBlank()) recipe.outputSymbols.remove(id);
        else recipe.outputSymbols.put(id, symbol);
      } else {
        InputRow source = displayedInputs.get(row);
        if (symbol.isBlank()) source.component().symbols.remove(source.field().id);
        else source.component().symbols.put(source.field().id, symbol);
      }
      boolean old = refreshing;
      refreshing = true;
      try {
        equations.setText(project.display(recipe));
        editingFormula = false;
      } finally {
        refreshing = old;
      }
      preview = Map.of();
      markDirty();
      inputModel.fireTableDataChanged();
      outputModel.fireTableDataChanged();
      status.setText(
          "Обозначение изменено. Смысл сохранённых формул сохранён. Нажмите «Рассчитать».");
    }

    private void calculate() {
      clearPreview();
      guarded(
          () -> {
            commit();
            if (recipe == null) return;
            preview = project.preview(recipe).values();
            outputModel.fireTableDataChanged();
            status.setForeground(new Color(25, 110, 65));
            status.setText(
                "Рассчитано. Справа показан выход для пробных входных значений. Промежуточные"
                    + " результаты не усекаются; выход — до сотых.");
          });
    }

    private void clearPreview() {
      preview = Map.of();
      outputModel.fireTableDataChanged();
    }

    private void switchRecipe(Recipe selected) {
      if (selected == recipe) return;
      try {
        commit();
        recipe = selected;
        preview = Map.of();
        refreshAll();
        calculate();
      } catch (Exception e) {
        refreshing = true;
        recipeChoice.setSelectedItem(recipe);
        refreshing = false;
        error(e);
      }
    }

    private void addRecipe() {
      commit();
      String name = ask("Название обработки", "Например: Плавка руды — линия 2", "Новая обработка");
      if (name == null) return;
      Recipe r = new Recipe(name);
      r.inputs.add(new EditorProject.Component("input", "Вход", project.items.get(0).id));
      r.outputItemId = project.items.get(project.items.size() - 1).id;
      project.recipes.add(r);
      recipe = r;
      markDirty();
      refreshAll();
      calculate();
    }

    private void renameRecipe() {
      commit();
      if (recipe == null) return;
      String name = ask("Название обработки", "Введите понятное название", recipe.name);
      if (name != null) {
        recipe.name = name;
        markDirty();
        refreshAll();
      }
    }

    private void duplicateRecipe() {
      commit();
      if (recipe == null) return;
      Recipe r = new Recipe(recipe.name + " — копия");
      r.outputItemId = recipe.outputItemId;
      r.count = recipe.count;
      r.copyCommonFrom = recipe.copyCommonFrom;
      r.equations = new ArrayList<>(recipe.equations);
      r.outputSymbols.putAll(recipe.outputSymbols);
      for (EditorProject.Component c : recipe.inputs) {
        EditorProject.Component copy = new EditorProject.Component(c.role, c.name, c.itemId);
        copy.sampleValues.putAll(c.sampleValues);
        copy.symbols.putAll(c.symbols);
        r.inputs.add(copy);
      }
      project.recipes.add(r);
      recipe = r;
      markDirty();
      refreshAll();
      calculate();
    }

    private void deleteRecipe() {
      commit();
      if (recipe == null) return;
      if (JOptionPane.showConfirmDialog(
              this,
              "Удалить обработку «" + recipe.name + "»? Предметы останутся.",
              "Удаление",
              JOptionPane.YES_NO_OPTION)
          != JOptionPane.YES_OPTION) return;
      project.recipes.remove(recipe);
      recipe = project.recipes.isEmpty() ? null : project.recipes.get(0);
      clearPreview();
      markDirty();
      refreshAll();
      calculate();
    }

    private void addComponent() {
      commit();
      if (recipe == null) return;
      if (recipe.inputs.size() >= 64)
        throw new IllegalArgumentException("В каталоге допускается до 64 входов");
      String name =
          ask(
              "Новый вход",
              "Название компонента, например «Пластина»",
              "Компонент " + (recipe.inputs.size() + 1));
      if (name == null) return;
      recipe.inputs.add(
          new EditorProject.Component(
              "input_" + UUID.randomUUID().toString().replace("-", ""),
              name,
              project.items.get(0).id));
      markDirty();
      refreshAll();
    }

    private void renameComponent() {
      commit();
      EditorProject.Component c = (EditorProject.Component) componentChoice.getSelectedItem();
      if (c == null) return;
      String name = ask("Название компонента", "Это название видно только при настройке", c.name);
      if (name != null) {
        c.name = name;
        markDirty();
        refreshAll();
      }
    }

    private void removeComponent() {
      commit();
      EditorProject.Component c = (EditorProject.Component) componentChoice.getSelectedItem();
      if (c == null) return;
      if (recipe.inputs.size() == 1)
        throw new IllegalArgumentException("Нельзя удалить последний вход");
      if (recipe.equations.stream()
          .anyMatch(e -> e.references().stream().anyMatch(f -> f.role().equals(c.role))))
        throw new IllegalArgumentException(
            "Этот вход используется в формулах. Сначала измените соответствующие строки.");
      recipe.inputs.remove(c);
      if (Objects.equals(recipe.copyCommonFrom, c.role)) recipe.copyCommonFrom = null;
      markDirty();
      refreshAll();
      calculate();
    }

    private void changeInputItem() {
      EditorProject.Component c = (EditorProject.Component) componentChoice.getSelectedItem();
      Item selected = (Item) inputItem.getSelectedItem();
      if (c == null || selected == null || selected.id.equals(c.itemId)) return;
      try {
        commit();
        for (BoundEquation eq : recipe.equations)
          for (var ref : eq.references())
            if (ref.role().equals(c.role)
                && selected.fields.stream().noneMatch(f -> f.id.equals(ref.fieldId())))
              throw new IllegalArgumentException(
                  "У нового предмета нет характеристики, используемой в формуле. Сначала измените"
                      + " формулу или добавьте нужные поля.");
        c.itemId = selected.id;
        markDirty();
        refreshAll();
        calculate();
      } catch (Exception e) {
        refreshComponent();
        throw e;
      }
    }

    private void changeOutputItem() {
      Item selected = (Item) outputItem.getSelectedItem();
      if (recipe == null || selected == null || selected.id.equals(recipe.outputItemId)) return;
      try {
        commit();
        for (BoundEquation e : recipe.equations)
          if (selected.fields.stream().noneMatch(f -> f.id.equals(e.outputFieldId())))
            throw new IllegalArgumentException(
                "У нового выхода нет поля назначения. Сначала удалите старые формулы или добавьте"
                    + " соответствующие поля.");
        recipe.outputItemId = selected.id;
        markDirty();
        refreshAll();
        calculate();
      } catch (Exception e) {
        refreshing = true;
        outputItem.setSelectedItem(project.item(recipe.outputItemId));
        refreshing = false;
        throw e;
      }
    }

    private void addItem() {
      commit();
      String[] common = {
        "minecraft:raw_iron",
        "minecraft:iron_ingot",
        "minecraft:raw_copper",
        "minecraft:copper_ingot",
        "minecraft:raw_gold",
        "minecraft:gold_ingot",
        "minecraft:dirt",
        "minecraft:stone",
        "minecraft:coal"
      };
      JComboBox<String> chooser = new JComboBox<>(common);
      chooser.setEditable(true);
      JPanel dialog = new JPanel(new GridLayout(0, 1, 4, 6));
      dialog.add(new JLabel("Выберите предмет или вставьте его ID из Minecraft (F3+H)."));
      dialog.add(chooser);
      dialog.add(new JLabel("Это существующий предмет. Редактор не создаёт новый тип Minecraft."));
      if (JOptionPane.showConfirmDialog(
              this, dialog, "Добавить предмет", JOptionPane.OK_CANCEL_OPTION)
          != JOptionPane.OK_OPTION) return;
      String id = chooser.getSelectedItem().toString().trim();
      dev.itemparams.core.ParameterSchema.validateId(id);
      if (project.items.stream().anyMatch(i -> i.id.equals(id)))
        throw new IllegalArgumentException("Этот предмет уже есть в проекте");
      String name =
          ask("Название предмета", "Понятное название в редакторе", EditorProject.friendlyItem(id));
      if (name == null) return;
      project.items.add(new Item(id, name));
      markDirty();
      refreshAll();
    }

    private void manageItems() {
      commit();
      DefaultListModel<Item> model = new DefaultListModel<>();
      for (Item item : project.items) model.addElement(item);
      JList<Item> list = new JList<>(model);
      list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      list.setSelectedIndex(0);
      JPanel panel = new JPanel(new BorderLayout(8, 8));
      panel.add(new JScrollPane(list), BorderLayout.CENTER);
      JPanel actions = row();
      actions.add(
          button(
              "Добавить предмет",
              () -> {
                addItem();
                model.clear();
                for (Item item : project.items) model.addElement(item);
                list.setSelectedIndex(model.size() - 1);
              }));
      actions.add(
          button(
              "Характеристики…",
              () -> {
                Item item = list.getSelectedValue();
                if (item != null) editItemFields(item);
              }));
      actions.add(
          button(
              "Изменить название",
              () -> {
                Item item = list.getSelectedValue();
                if (item == null) return;
                String name =
                    ask(
                        "Название предмета",
                        "Адрес Minecraft остаётся тем же; меняется название в редакторе",
                        item.name);
                if (name != null) {
                  item.name = name;
                  markDirty();
                  list.repaint();
                  refreshAll();
                }
              }));
      actions.add(
          button(
              "Удалить предмет",
              () -> {
                Item item = list.getSelectedValue();
                if (item == null) return;
                for (Recipe r : project.recipes) {
                  if (r.outputItemId.equals(item.id)
                      || r.inputs.stream().anyMatch(c -> c.itemId.equals(item.id)))
                    throw new IllegalArgumentException(
                        "Предмет используется в обработке «"
                            + r.name
                            + "». Сначала измените или удалите эту обработку.");
                }
                if (project.items.size() == 1)
                  throw new IllegalArgumentException("Оставьте хотя бы один предмет в проекте");
                project.items.remove(item);
                model.removeElement(item);
                markDirty();
                refreshAll();
              }));
      panel.add(actions, BorderLayout.SOUTH);
      panel.setPreferredSize(new Dimension(930, 420));
      JOptionPane.showMessageDialog(this, panel, "Предметы проекта", JOptionPane.PLAIN_MESSAGE);
    }

    private void editItemFields(Item item) {
      List<Field> draft = new ArrayList<>();
      item.fields.forEach(field -> draft.add(field.copy()));
      AbstractTableModel model =
          new AbstractTableModel() {
            private final String[] columns = {"Название", "По умолчанию", "Минимум", "Максимум"};

            @Override
            public int getRowCount() {
              return draft.size();
            }

            @Override
            public int getColumnCount() {
              return columns.length;
            }

            @Override
            public String getColumnName(int column) {
              return columns[column];
            }

            @Override
            public boolean isCellEditable(int row, int column) {
              return true;
            }

            @Override
            public Object getValueAt(int row, int column) {
              Field field = draft.get(row);
              return switch (column) {
                case 0 -> field.name;
                case 1 -> field.value;
                case 2 -> shownBound(field.minimum, true);
                default -> shownBound(field.maximum, false);
              };
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
              Field field = draft.get(row);
              String text = value.toString().trim();
              switch (column) {
                case 0 -> field.name = text;
                case 1 -> field.value = text;
                case 2 -> field.minimum = enteredBound(text, true);
                case 3 -> field.maximum = enteredBound(text, false);
                default -> throw new IllegalArgumentException("Неизвестная колонка");
              }
              fireTableCellUpdated(row, column);
            }
          };
      JTable table = new JTable(model);
      configureTable(table);
      table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
      table.getColumnModel().getColumn(0).setPreferredWidth(280);
      JPanel panel = new JPanel(new BorderLayout(8, 8));
      panel.add(
          note(
              "<b>"
                  + escapeHtml(item.name)
                  + "</b><br>Добавьте характеристики и задайте начальные значения. Обработка не"
                  + " требуется.<br>«По умолчанию» применяется, когда у экземпляра ещё нет"
                  + " сохранённого значения.<br>Минимум и максимум — допустимые границы. — означает"
                  + " общую границу хранения."),
          BorderLayout.NORTH);
      panel.add(tableScroll(table), BorderLayout.CENTER);
      JPanel actions = row();
      JButton add = new JButton("+ Характеристика");
      add.addActionListener(
          event -> {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
            int row = draft.size();
            draft.add(new Field(EditorProject.fresh("field"), "Новая характеристика", "0"));
            model.fireTableRowsInserted(row, row);
            table.setRowSelectionInterval(row, row);
            table.editCellAt(row, 0);
            if (table.getEditorComponent() != null)
              table.getEditorComponent().requestFocusInWindow();
          });
      actions.add(add);
      JButton remove = new JButton("Удалить выбранную");
      remove.addActionListener(
          event -> {
            if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;
            int row = table.getSelectedRow();
            if (row < 0) return;
            draft.remove(row);
            model.fireTableRowsDeleted(row, row);
          });
      actions.add(remove);
      panel.add(actions, BorderLayout.SOUTH);
      panel.setPreferredSize(new Dimension(760, 440));
      while (JOptionPane.showOptionDialog(
              this,
              panel,
              "Характеристики — " + item.name,
              JOptionPane.OK_CANCEL_OPTION,
              JOptionPane.PLAIN_MESSAGE,
              null,
              new String[] {"Применить", "Отмена"},
              "Применить")
          == JOptionPane.OK_OPTION) {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) continue;
        try {
          applyItemFields(item, draft);
          return;
        } catch (Exception failure) {
          error(failure);
        }
      }
    }

    private static String escapeHtml(String text) {
      return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Apply a detached field draft; invalid drafts cannot partially change the item definition. */
    public void applyItemFields(Item item, List<Field> proposed) {
      commit();
      if (!project.items.contains(item))
        throw new IllegalArgumentException("Предмет не принадлежит этому проекту");
      Objects.requireNonNull(proposed, "proposed");
      Item replacement = new Item(item.id, item.name);
      for (Field field : proposed) {
        if (field == null) throw new IllegalArgumentException("Пустая характеристика в списке");
        replacement.fields.add(field.copy());
      }
      project.schema(replacement);
      Set<String> retained = new HashSet<>();
      for (Field field : replacement.fields) retained.add(field.id);
      for (Field field : item.fields)
        if (!retained.contains(field.id)) ensureFieldRemovable(item, field);
      item.fields = replacement.fields;
      for (Recipe saved : project.recipes) {
        if (saved.outputItemId.equals(item.id)) saved.outputSymbols.keySet().retainAll(retained);
        for (EditorProject.Component component : saved.inputs)
          if (component.itemId.equals(item.id)) {
            component.symbols.keySet().retainAll(retained);
            component.sampleValues.keySet().retainAll(retained);
          }
      }
      markDirty();
      clearPreview();
      refreshAll();
      status.setForeground(new Color(85, 96, 110));
      status.setText(
          "Характеристики предмета обновлены. Сохраните проект и экспортируйте каталог.");
    }

    private Item fieldItem(boolean output) {
      if (recipe == null) throw new IllegalArgumentException("Сначала добавьте обработку");
      if (output) return project.item(recipe.outputItemId);
      EditorProject.Component c = (EditorProject.Component) componentChoice.getSelectedItem();
      if (c == null) throw new IllegalArgumentException("Выберите входной компонент");
      return project.item(c.itemId);
    }

    private void addField(boolean output) {
      commit();
      Item item = fieldItem(output);
      String name =
          ask("Новая характеристика", "Например: Масса, Чистота, Точность", "Новая характеристика");
      if (name == null) return;
      item.fields.add(new Field(EditorProject.fresh("field"), name, "0"));
      markDirty();
      refreshAll();
      calculate();
    }

    private Field selectedField(boolean output) {
      int row = (output ? outputTable : inputTable).getSelectedRow();
      if (row < 0)
        throw new IllegalArgumentException("Сначала выберите строку характеристики в таблице");
      return output
          ? project.item(recipe.outputItemId).fields.get(row)
          : project.inputRows(recipe).get(row).field();
    }

    private Item selectedFieldItem(boolean output) {
      int row = inputTable.getSelectedRow();
      return output
          ? project.item(recipe.outputItemId)
          : project.item(project.inputRows(recipe).get(row).component().itemId);
    }

    private void deleteField(boolean output) {
      commit();
      Field f = selectedField(output);
      Item owner = selectedFieldItem(output);
      ensureFieldRemovable(owner, f);
      List<Field> remaining = new ArrayList<>(owner.fields);
      remaining.remove(f);
      applyItemFields(owner, remaining);
      calculate();
    }

    private void ensureFieldRemovable(Item owner, Field f) {
      for (Recipe r : project.recipes)
        for (BoundEquation e : r.equations) {
          if (r.outputItemId.equals(owner.id) && e.outputFieldId().equals(f.id))
            throw new IllegalArgumentException(
                "Характеристика используется как результат в обработке «"
                    + r.name
                    + "». Сначала измените формулы.");
          for (var ref : e.references())
            for (EditorProject.Component c : r.inputs)
              if (c.role.equals(ref.role())
                  && c.itemId.equals(owner.id)
                  && ref.fieldId().equals(f.id))
                throw new IllegalArgumentException(
                    "Характеристика используется в формуле обработки «"
                        + r.name
                        + "». Сначала измените её.");
        }
    }

    private void moveField(boolean output, int offset) {
      commit();
      Field f = selectedField(output);
      Item owner = selectedFieldItem(output);
      int i = owner.fields.indexOf(f), j = i + offset;
      if (j < 0 || j >= owner.fields.size()) return;
      Collections.swap(owner.fields, i, j);
      markDirty();
      refreshAll();
      calculate();
    }

    private void copyFields() {
      commit();
      Item source = fieldItem(false), target = fieldItem(true);
      for (Field f : source.fields)
        if (target.fields.stream().noneMatch(t -> t.id.equals(f.id))) target.fields.add(f.copy());
      markDirty();
      refreshAll();
      calculate();
    }

    private void insertInput() {
      int row = inputTable.getSelectedRow();
      if (row < 0) throw new IllegalArgumentException("Выберите входную характеристику слева");
      insert(project.inputSymbol(recipe, row));
    }

    private void insertOutput() {
      int row = outputTable.getSelectedRow();
      if (row < 0) throw new IllegalArgumentException("Выберите выходную характеристику справа");
      insert(" = " + project.outputSymbol(recipe, row));
    }

    private void insertConstant() {
      String number =
          ask(
              "Вставить постоянное число",
              "Число будет отличаться от номера поля скобками [ ]. Например 0.95",
              "0.95");
      if (number != null) {
        new java.math.BigDecimal(number.replace(',', '.'));
        insert("[" + number.replace(',', '.') + "]");
      }
    }

    private void insert(String text) {
      equations.requestFocusInWindow();
      equations.replaceSelection(text);
    }

    private String ask(String title, String question, String initial) {
      Object value =
          JOptionPane.showInputDialog(
              this, question, title, JOptionPane.PLAIN_MESSAGE, null, null, initial);
      if (value == null) return null;
      String text = value.toString().trim();
      if (text.isEmpty()) throw new IllegalArgumentException("Название не должно быть пустым");
      return text;
    }

    private JFileChooser chooser(boolean folder) {
      JFileChooser c =
          new JFileChooser(document == null ? null : document.toFile().getParentFile());
      c.setDialogTitle(folder ? "Выберите папку" : "Проект параметров");
      if (folder) c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
      else
        c.setFileFilter(
            new FileNameExtensionFilter("Проекты параметров (*.ipproject)", "ipproject"));
      return c;
    }

    private void chooseOpen(boolean catalog) {
      if (!mayDiscard()) return;
      JFileChooser c = chooser(catalog);
      if (c.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
      Path p = c.getSelectedFile().toPath();
      if (catalog) {
        EditorProject imported;
        try {
          imported = EditorProject.importCatalog(p);
        } catch (IOException e) {
          throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (imported.items.isEmpty())
          throw new IllegalArgumentException("В папке не найдены определения items/*.json");
        project = imported;
        document = null;
        dirty = true;
        recipe = project.recipes.isEmpty() ? null : project.recipes.get(0);
        preview = Map.of();
        refreshAll();
        calculate();
      } else open(p);
    }

    public void open(Path p) {
      guarded(
          () -> {
            try {
              EditorProject loaded = EditorProject.load(p);
              project = loaded;
              document = p.toAbsolutePath();
              recipe = project.recipes.isEmpty() ? null : project.recipes.get(0);
              preview = Map.of();
              dirty = false;
              refreshAll();
              calculate();
            } catch (IOException e) {
              throw new IllegalArgumentException(e.getMessage(), e);
            }
          });
    }

    private boolean save(boolean saveAs) {
      try {
        commit();
        Path path = document;
        if (path == null || saveAs) {
          JFileChooser c = chooser(false);
          if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
          path = c.getSelectedFile().toPath();
          if (!path.toString().endsWith(".ipproject"))
            path = path.resolveSibling(path.getFileName() + ".ipproject");
          if (Files.exists(path)
              && JOptionPane.showConfirmDialog(
                      this,
                      "Заменить существующий проект?",
                      "Сохранение",
                      JOptionPane.YES_NO_OPTION)
                  != JOptionPane.YES_OPTION) return false;
        }
        project.save(path);
        document = path;
        dirty = false;
        updateDocumentTitle();
        status.setForeground(new Color(25, 110, 65));
        status.setText("Проект сохранён: " + path);
        return true;
      } catch (Exception e) {
        error(e);
        return false;
      }
    }

    private void export() {
      commit();
      project.validate();
      JFileChooser c = chooser(true);
      c.setDialogTitle("Выберите новую или пустую папку itemparams");
      if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
      Path target = c.getSelectedFile().toPath();
      try {
        project.exportCatalog(target);
      } catch (IOException e) {
        throw new IllegalArgumentException(e.getMessage(), e);
      }
      JOptionPane.showMessageDialog(
          this,
          "Готово: "
              + target
              + "\n"
              + "Внутри созданы items и recipes.\n"
              + "Поместите их в config/itemparams и перезапустите сервер.\n"
              + "Для игры редактор не требуется. Сохраните также проект, чтобы не потерять понятные"
              + " названия.\n"
              + "Адреса предметов проверит Minecraft при загрузке каталога.",
          "Экспорт завершён",
          JOptionPane.INFORMATION_MESSAGE);
      status.setText("Каталог экспортирован: " + target);
    }

    private void newProject() {
      if (!mayDiscard()) return;
      project = EditorProject.tutorialSample();
      recipe = project.recipes.get(0);
      document = null;
      dirty = false;
      preview = Map.of();
      refreshAll();
      calculate();
    }

    public boolean mayDiscard() {
      stopEditing();
      if (!dirty && !editingFormula) return true;
      int answer =
          JOptionPane.showConfirmDialog(
              this,
              "Сохранить изменения проекта перед продолжением?",
              "Есть несохранённые изменения",
              JOptionPane.YES_NO_CANCEL_OPTION);
      if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) return false;
      return answer == JOptionPane.NO_OPTION || save(false);
    }

    private void help() {
      JTextArea text =
          new JTextArea(
              "1. Входные предметы — отдельные списки слева. Выход — крайний список справа.\n\n"
                  + "2. Добавить ещё один вход: меню «Входы → Добавить входной предмет».\n"
                  + "   Тип предмета выбирается над своим списком.\n\n"
                  + "3. Двойной щелчок по символу или числу позволяет изменить его.\n"
                  + "   Числа в списке входа — для предварительного расчёта, не defaults игры.\n"
                  + "   Defaults и диапазоны: меню «Предметы» или кнопка «Поля…» у предмета.\n\n"
                  + "4. Формулы вводятся в нижнем поле, каждое назначение с новой строки:\n"
                  + "   M * P / [100] * [0.95] = M\n"
                  + "   [25] = H\n"
                  + "   M1 + M2 + M3 = M\n"
                  + "   sqrt(M ^ [2] + K ^ [2]) = H\n"
                  + "   Слева — входные значения, справа от = — символ выходного поля.\n"
                  + "   Постоянные числа — в [скобках]. Номера полей также поддержаны.\n\n"
                  + "5. Нажмите «Рассчитать». Смотрите столбец «Результат» справа.\n"
                  + "   Поля без формул получают значения по правилу под выходным списком.\n"
                  + "   Если выбран перенос, начальный ноль выхода не используется для такого"
                  + " поля.\n\n"
                  + "6. Сохраните проект через меню «Файл». Для игры нажмите «Передать в"
                  + " Minecraft…».\n"
                  + "   Выберите папку игры с mods. Старый каталог сохраняется резервной копией.\n"
                  + "   Заново откройте мир. В demo-станке выберите ID из меню «Рецепт».\n"
                  + "   Обычная печь эти формулы не использует.\n\n"
                  + "7. Demo-станок находится во вкладке функциональных блоков.\n"
                  + "   При первой настройке сохраняет входы/выход. Для перенастройки уберите\n"
                  + "   входы и нажмите «Сброс». Выход можно передать следующей операции.\n\n"
                  + "Поддержаны + - * / ^ sqrt root pow min max clamp и скобки.\n"
                  + "Иррациональные показатели, sin/cos/log не поддержаны. Итог — до сотых.\n"
                  + "Для редактирования выбранной строки: правая кнопка в таблице.\n");
      text.setEditable(false);
      text.setLineWrap(true);
      text.setWrapStyleWord(true);
      text.setFont(new Font("Dialog", Font.PLAIN, 15));
      text.setCaretPosition(0);
      JScrollPane scroll = new JScrollPane(text);
      scroll.setPreferredSize(new Dimension(740, 480));
      JOptionPane.showMessageDialog(
          this, scroll, "Как пользоваться списками и формулами", JOptionPane.INFORMATION_MESSAGE);
    }

    private final class InputModel extends AbstractTableModel {
      private final String[] columns = {
        "Обозначение",
        "Компонент",
        "Характеристика",
        "По умолчанию",
        "Минимум",
        "Максимум",
        "Для проверки"
      };

      @Override
      public int getRowCount() {
        return displayedInputs.size();
      }

      @Override
      public int getColumnCount() {
        return columns.length;
      }

      @Override
      public String getColumnName(int col) {
        return columns[col];
      }

      @Override
      public boolean isCellEditable(int row, int col) {
        return col == 0 || col >= 2;
      }

      @Override
      public Object getValueAt(int row, int col) {
        InputRow r = displayedInputs.get(row);
        Field f = r.field();
        return switch (col) {
          case 0 -> r.component().symbols.getOrDefault(f.id, Integer.toString(row + 1));
          case 1 -> r.component().name;
          case 2 -> f.name;
          case 3 -> f.value;
          case 4 -> shownBound(f.minimum, true);
          case 5 -> shownBound(f.maximum, false);
          default -> r.component().sampleValues.getOrDefault(f.id, f.value);
        };
      }

      @Override
      public void setValueAt(Object value, int row, int col) {
        InputRow r = displayedInputs.get(row);
        Field f = r.field();
        String v = value.toString().trim();
        if (col == 0) {
          guarded(() -> changeSymbol(false, row, v));
          return;
        }
        switch (col) {
          case 2 -> f.name = v;
          case 3 -> f.value = v;
          case 4 -> f.minimum = enteredBound(v, true);
          case 5 -> f.maximum = enteredBound(v, false);
          case 6 -> r.component().sampleValues.put(f.id, v);
          default -> {
            return;
          }
        }
        markDirty();
        preview = Map.of();
        fireTableRowsUpdated(row, row);
        outputModel.fireTableDataChanged();
        status.setText("Значение изменено. Нажмите «Рассчитать».");
      }
    }

    private final class OutputModel extends AbstractTableModel {
      private final String[] columns = {
        "Обозначение", "Характеристика", "По умолчанию", "Минимум", "Максимум", "Результат"
      };

      @Override
      public int getRowCount() {
        return recipe == null ? 0 : project.item(recipe.outputItemId).fields.size();
      }

      @Override
      public int getColumnCount() {
        return columns.length;
      }

      @Override
      public String getColumnName(int col) {
        return columns[col];
      }

      @Override
      public boolean isCellEditable(int row, int col) {
        return col >= 0 && col <= 4;
      }

      @Override
      public Object getValueAt(int row, int col) {
        Field f = project.item(recipe.outputItemId).fields.get(row);
        return switch (col) {
          case 0 -> recipe.outputSymbols.getOrDefault(f.id, Integer.toString(row + 1));
          case 1 -> f.name;
          case 2 -> f.value;
          case 3 -> shownBound(f.minimum, true);
          case 4 -> shownBound(f.maximum, false);
          default -> preview.containsKey(f.id) ? preview.get(f.id).toPlainString() : "—";
        };
      }

      @Override
      public void setValueAt(Object value, int row, int col) {
        Field f = project.item(recipe.outputItemId).fields.get(row);
        String v = value.toString().trim();
        if (col == 0) {
          guarded(() -> changeSymbol(true, row, v));
          return;
        }
        switch (col) {
          case 1 -> f.name = v;
          case 2 -> f.value = v;
          case 3 -> f.minimum = enteredBound(v, true);
          case 4 -> f.maximum = enteredBound(v, false);
          default -> {
            return;
          }
        }
        markDirty();
        preview = Map.of();
        fireTableRowsUpdated(row, row);
        inputModel.fireTableDataChanged();
        status.setText("Значение изменено. Нажмите «Рассчитать».");
      }
    }
  }
}
