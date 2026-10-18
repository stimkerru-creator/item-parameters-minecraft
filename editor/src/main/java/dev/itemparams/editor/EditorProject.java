package dev.itemparams.editor;

import com.google.gson.*;
import dev.itemparams.authoring.BoundEquation;
import dev.itemparams.authoring.InputField;
import dev.itemparams.authoring.NumberedFormulas;
import dev.itemparams.config.StrictJson;
import dev.itemparams.core.Numeric;
import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import dev.itemparams.formula.InputLayout;
import dev.itemparams.production.ParameterRecipe;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/** Editor document only. Runtime catalogs contain neither labels nor editor dependencies. */
public final class EditorProject {
  private static final Gson JSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private static final Pattern REFERENCE =
      Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_-]*)::([^}]+)}");
  public int editorVersion = 2;
  public List<Item> items = new ArrayList<>();
  public List<Recipe> recipes = new ArrayList<>();

  public static final class Field {
    public String id, name, value = "0", minimum = "-21474836.48", maximum = "21474836.47";

    public Field() {}

    public Field(String id, String name, String value) {
      this.id = id;
      this.name = name;
      this.value = value;
    }

    public Field copy() {
      Field f = new Field(id, name, value);
      f.minimum = minimum;
      f.maximum = maximum;
      return f;
    }
  }

  public static final class Item {
    public String id, name;
    public List<Field> fields = new ArrayList<>();

    public Item() {}

    public Item(String id, String name) {
      this.id = id;
      this.name = name;
    }

    @Override
    public String toString() {
      return name + " — " + id;
    }
  }

  public static final class Component {
    public String role, name, itemId;
    public Map<String, String> sampleValues = new LinkedHashMap<>();
    public Map<String, String> symbols = new LinkedHashMap<>();

    public Component() {}

    public Component(String role, String name, String itemId) {
      this.role = role;
      this.name = name;
      this.itemId = itemId;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  public static final class Recipe {
    public String id, name, outputItemId, copyCommonFrom;
    public int count = 1;
    public List<Component> inputs = new ArrayList<>();
    public List<BoundEquation> equations = new ArrayList<>();
    public Map<String, String> outputSymbols = new LinkedHashMap<>();

    public Recipe() {}

    public Recipe(String name) {
      this.id = fresh("recipe");
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  public record InputRow(Component component, Field field) {}

  public record Preview(Map<String, BigDecimal> values) {}

  private record Prepared(
      InputLayout layout, ParameterSchema output, Map<String, ParameterValues> values) {}

  public static String fresh(String kind) {
    return "editor:" + kind + "_" + UUID.randomUUID().toString().replace("-", "");
  }

  public Item item(String id) {
    for (Item item : items) if (Objects.equals(item.id, id)) return item;
    throw new IllegalArgumentException("Предмет не найден: " + id);
  }

  public List<InputRow> inputRows(Recipe recipe) {
    List<InputRow> rows = new ArrayList<>();
    for (Component c : recipe.inputs)
      for (Field f : item(c.itemId).fields) rows.add(new InputRow(c, f));
    return rows;
  }

  public List<InputField> inputFields(Recipe recipe) {
    return inputRows(recipe).stream()
        .map(r -> new InputField(r.component.role, r.field.id))
        .toList();
  }

  public List<String> outputIds(Recipe recipe) {
    return item(recipe.outputItemId).fields.stream().map(f -> f.id).toList();
  }

  public List<BoundEquation> bind(Recipe recipe, String text) {
    return NumberedFormulas.bindAll(
        text, inputFields(recipe), outputIds(recipe), inputSymbols(recipe), outputSymbols(recipe));
  }

  public Map<InputField, String> inputSymbols(Recipe recipe) {
    Map<InputField, String> result = new LinkedHashMap<>();
    for (InputRow row : inputRows(recipe)) {
      if (row.component.symbols == null)
        throw new IllegalArgumentException("Повреждены обозначения входных полей");
      String symbol = row.component.symbols.get(row.field.id);
      if (symbol != null && !symbol.isBlank())
        result.put(new InputField(row.component.role, row.field.id), symbol);
    }
    return result;
  }

  public Map<String, String> outputSymbols(Recipe recipe) {
    if (recipe.outputSymbols == null)
      throw new IllegalArgumentException("Повреждены обозначения выходных полей");
    Map<String, String> result = new LinkedHashMap<>();
    for (String id : outputIds(recipe)) {
      String symbol = recipe.outputSymbols.get(id);
      if (symbol != null && !symbol.isBlank()) result.put(id, symbol);
    }
    return result;
  }

  public String inputSymbol(Recipe recipe, int row) {
    InputRow source = inputRows(recipe).get(row);
    return source.component.symbols.getOrDefault(source.field.id, Integer.toString(row + 1));
  }

  public String outputSymbol(Recipe recipe, int row) {
    return recipe.outputSymbols.getOrDefault(outputIds(recipe).get(row), Integer.toString(row + 1));
  }

  public String display(Recipe recipe) {
    List<InputField> inputs = inputFields(recipe);
    List<String> outputs = outputIds(recipe);
    Map<InputField, String> inputSymbols = inputSymbols(recipe);
    Map<String, String> outputSymbols = outputSymbols(recipe);
    return String.join(
        "\n",
        recipe.equations.stream()
            .map(e -> e.display(inputs, outputs, inputSymbols, outputSymbols))
            .toList());
  }

  private static BigDecimal decimal(String text) {
    if (text == null || text.length() > 128)
      throw new IllegalArgumentException("Введите обычное число, например 12.25");
    try {
      return new BigDecimal(text.trim().replace(',', '.'));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Неверное число: " + text, e);
    }
  }

  public ParameterSchema schema(Item item) {
    ParameterSchema.validateId(item.id);
    ParameterSchema.Builder b = ParameterSchema.builder();
    for (Field f : item.fields) {
      if (f.name == null || f.name.isBlank())
        throw new IllegalArgumentException("У характеристики должно быть название");
      b.add(f.id, decimal(f.value), decimal(f.minimum), decimal(f.maximum));
    }
    return b.build();
  }

  private Prepared prepare(Recipe recipe) {
    if (recipe.inputs == null || recipe.inputs.isEmpty() || recipe.inputs.size() > 64)
      throw new IllegalArgumentException(
          "В рецепте нужен хотя бы один вход; каталог допускает до 64 входов");
    if (recipe.count < 1 || recipe.count > 64)
      throw new IllegalArgumentException("Количество выхода: от 1 до 64");
    Map<String, ParameterSchema> schemas = new LinkedHashMap<>();
    Map<String, ParameterValues> values = new LinkedHashMap<>();
    for (Component c : recipe.inputs) {
      ParameterSchema s = schema(item(c.itemId));
      if (schemas.putIfAbsent(c.role, s) != null)
        throw new IllegalArgumentException("Повторяется вход: " + c.name);
      ParameterValues v = s.defaults();
      if (c.sampleValues == null)
        throw new IllegalArgumentException("Повреждены пробные значения входа");
      for (var entry : c.sampleValues.entrySet()) {
        if (s.indexOf(entry.getKey()) >= 0)
          v.setRaw(entry.getKey(), Numeric.encodeExact(decimal(entry.getValue())));
      }
      values.put(c.role, v);
    }
    return new Prepared(InputLayout.of(schemas), schema(item(recipe.outputItemId)), values);
  }

  public Preview preview(Recipe recipe) {
    NumberedFormulas.validateSymbols(
        inputFields(recipe), outputIds(recipe), inputSymbols(recipe), outputSymbols(recipe));
    Prepared p = prepare(recipe);
    ParameterRecipe compiled =
        NumberedFormulas.compile(
            recipe.id, p.layout, p.output, recipe.equations, recipe.copyCommonFrom);
    ParameterValues result = compiled.execute(p.layout.bind(p.values));
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    for (String id : outputIds(recipe)) values.put(id, result.get(id));
    return new Preview(Collections.unmodifiableMap(values));
  }

  public void validate() {
    if (editorVersion != 2)
      throw new IllegalArgumentException("Эта версия редактора не поддерживает формат проекта");
    if (items == null || recipes == null || items.size() > 20000 || recipes.size() > 20000)
      throw new IllegalArgumentException("Повреждён или слишком велик проект");
    Set<String> ids = new HashSet<>();
    for (Item item : items) {
      if (item == null || item.fields == null || item.name == null || item.name.isBlank())
        throw new IllegalArgumentException("У предмета должно быть название");
      if (!ids.add(item.id))
        throw new IllegalArgumentException("Предмет указан дважды: " + item.id);
      schema(item);
    }
    ids.clear();
    for (Recipe recipe : recipes) {
      if (recipe == null
          || recipe.name == null
          || recipe.name.isBlank()
          || recipe.equations == null)
        throw new IllegalArgumentException("Повреждено определение обработки");
      if (!ids.add(recipe.id))
        throw new IllegalArgumentException("Рецепт указан дважды: " + recipe.id);
      Prepared p = prepare(recipe);
      NumberedFormulas.validateSymbols(
          inputFields(recipe), outputIds(recipe), inputSymbols(recipe), outputSymbols(recipe));
      NumberedFormulas.compile(
          recipe.id, p.layout, p.output, recipe.equations, recipe.copyCommonFrom);
      display(recipe); // Deleted fields must be reported before saving/exporting.
    }
  }

  public void save(Path target) throws IOException {
    validate();
    Path path = target.toAbsolutePath();
    if (Files.isSymbolicLink(path))
      throw new IOException("Нельзя сохранять проект поверх символической ссылки");
    Files.createDirectories(path.getParent());
    Path temporary = Files.createTempFile(path.getParent(), "itemparams-save-", ".tmp");
    try {
      Files.writeString(temporary, JSON.toJson(this), StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public static EditorProject load(Path path) throws IOException {
    JsonObject root = read(path);
    allow(root, "editorVersion", "items", "recipes");
    try {
      if (!root.has("editorVersion")
          || (root.get("editorVersion").getAsBigDecimal().intValueExact() != 1
              && root.get("editorVersion").getAsBigDecimal().intValueExact() != 2))
        throw new IllegalArgumentException("Эта версия редактора не поддерживает формат проекта");
      for (JsonElement value : root.getAsJsonArray("items")) {
        JsonObject item = value.getAsJsonObject();
        allow(item, "id", "name", "fields");
        for (JsonElement field : item.getAsJsonArray("fields"))
          allow(field.getAsJsonObject(), "id", "name", "value", "minimum", "maximum");
      }
      for (JsonElement value : root.getAsJsonArray("recipes")) {
        JsonObject recipe = value.getAsJsonObject();
        allow(
            recipe,
            "id",
            "name",
            "outputItemId",
            "copyCommonFrom",
            "count",
            "inputs",
            "equations",
            "outputSymbols");
        for (JsonElement input : recipe.getAsJsonArray("inputs"))
          allow(input.getAsJsonObject(), "role", "name", "itemId", "sampleValues", "symbols");
        for (JsonElement equation : recipe.getAsJsonArray("equations")) {
          JsonObject e = equation.getAsJsonObject();
          allow(e, "canonicalExpression", "outputFieldId", "references");
          for (JsonElement reference : e.getAsJsonArray("references"))
            allow(reference.getAsJsonObject(), "role", "fieldId");
        }
      }
      EditorProject project = JSON.fromJson(root, EditorProject.class);
      project.editorVersion = 2;
      project.validate();
      return project;
    } catch (RuntimeException e) {
      throw new IOException("Проект не открыт: " + e.getMessage(), e);
    }
  }

  /**
   * Fresh directory only. No user catalog can accidentally be replaced or mixed with duplicates.
   */
  public void exportCatalog(Path target) throws IOException {
    validate();
    JsonObject itemsFile = new JsonObject();
    itemsFile.addProperty("version", 1);
    JsonArray itemArray = new JsonArray();
    for (Item item : items) {
      JsonObject obj = new JsonObject();
      obj.addProperty("item", item.id);
      JsonArray fields = new JsonArray();
      for (Field f : item.fields) {
        JsonObject field = new JsonObject();
        field.addProperty("id", f.id);
        field.addProperty("default", decimal(f.value));
        field.addProperty("min", decimal(f.minimum));
        field.addProperty("max", decimal(f.maximum));
        fields.add(field);
      }
      obj.add("fields", fields);
      itemArray.add(obj);
    }
    itemsFile.add("items", itemArray);
    JsonObject recipesFile = new JsonObject();
    recipesFile.addProperty("version", 1);
    JsonArray recipeArray = new JsonArray();
    for (Recipe recipe : recipes) {
      JsonObject obj = new JsonObject();
      obj.addProperty("id", recipe.id);
      JsonObject inputs = new JsonObject();
      for (Component c : recipe.inputs) inputs.addProperty(c.role, c.itemId);
      obj.add("inputs", inputs);
      JsonObject output = new JsonObject();
      output.addProperty("item", recipe.outputItemId);
      output.addProperty("count", recipe.count);
      obj.add("output", output);
      if (recipe.copyCommonFrom != null) obj.addProperty("copy_common_from", recipe.copyCommonFrom);
      JsonObject assignments = new JsonObject();
      for (BoundEquation e : recipe.equations)
        assignments.addProperty(e.outputFieldId(), e.canonicalExpression());
      obj.add("assignments", assignments);
      recipeArray.add(obj);
    }
    recipesFile.add("recipes", recipeArray);
    Path path = target.toAbsolutePath().normalize();
    for (Path part = path; part != null; part = part.getParent())
      if (Files.isSymbolicLink(part))
        throw new IOException("Выберите обычную папку, не символическую ссылку");
    if (Files.exists(path)) {
      if (!Files.isDirectory(path)) throw new IOException("Выберите пустую папку для экспорта");
      try (Stream<Path> list = Files.list(path)) {
        if (list.findAny().isPresent())
          throw new IOException(
              "Для экспорта нужна пустая папка. Существующие файлы редактор не заменяет.");
      }
    }
    Path parent = path.getParent();
    Files.createDirectories(parent);
    Path staged = Files.createTempDirectory(parent, "itemparams-export-");
    try {
      Files.createDirectories(staged.resolve("items"));
      Files.createDirectories(staged.resolve("recipes"));
      Files.writeString(
          staged.resolve("items/editor-items.json"),
          JSON.toJson(itemsFile),
          StandardCharsets.UTF_8);
      Files.writeString(
          staged.resolve("recipes/editor-recipes.json"),
          JSON.toJson(recipesFile),
          StandardCharsets.UTF_8);
      if (Files.exists(path))
        Files.delete(path); // Still empty; a concurrent added file makes this fail safely.
      try {
        Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(staged, path);
      }
    } finally {
      if (Files.exists(staged)) {
        Files.deleteIfExists(staged.resolve("items/editor-items.json"));
        Files.deleteIfExists(staged.resolve("recipes/editor-recipes.json"));
        Files.deleteIfExists(staged.resolve("items"));
        Files.deleteIfExists(staged.resolve("recipes"));
        Files.deleteIfExists(staged);
      }
    }
  }

  public static EditorProject importCatalog(Path root) throws IOException {
    EditorProject project = new EditorProject();
    try {
      for (Path path : catalogFiles(root.resolve("items"))) {
        JsonObject file = read(path);
        version(file);
        allow(file, "version", "item", "fields", "items");
        if (file.has("items") && (file.has("item") || file.has("fields")))
          throw new IllegalArgumentException(
              "В файле одновременно задан предмет и список предметов");
        JsonArray entries = file.has("items") ? file.getAsJsonArray("items") : singleton(file);
        for (JsonElement entry : entries) {
          JsonObject obj = entry.getAsJsonObject();
          allow(obj, "version", "item", "fields");
          version(obj);
          String itemId = text(obj, "item");
          Item item = new Item(itemId, friendlyItem(itemId));
          for (JsonElement f : obj.getAsJsonArray("fields")) {
            if (f.isJsonPrimitive() && f.getAsJsonPrimitive().isString())
              item.fields.add(new Field(f.getAsString(), friendlyField(f.getAsString()), "0"));
            else {
              JsonObject field = f.getAsJsonObject();
              allow(field, "id", "default", "min", "max");
              String id = text(field, "id");
              Field v = new Field(id, friendlyField(id), numberOr(field, "default", "0"));
              v.minimum = numberOr(field, "min", "-21474836.48");
              v.maximum = numberOr(field, "max", "21474836.47");
              item.fields.add(v);
            }
          }
          project.items.add(item);
        }
      }
      for (Path path : catalogFiles(root.resolve("recipes"))) {
        JsonObject file = read(path);
        version(file);
        allow(
            file,
            "version",
            "id",
            "inputs",
            "output",
            "copy_common_from",
            "base",
            "transfers",
            "assignments",
            "recipes");
        if (file.has("recipes") && file.has("id"))
          throw new IllegalArgumentException("Одновременно указан рецепт и список рецептов");
        JsonArray entries = file.has("recipes") ? file.getAsJsonArray("recipes") : singleton(file);
        for (JsonElement entry : entries) {
          JsonObject obj = entry.getAsJsonObject();
          allow(
              obj,
              "version",
              "id",
              "inputs",
              "output",
              "copy_common_from",
              "base",
              "transfers",
              "assignments");
          version(obj);
          Recipe r = new Recipe(text(obj, "id"));
          r.id = text(obj, "id");
          for (var input : obj.getAsJsonObject("inputs").entrySet())
            r.inputs.add(new Component(input.getKey(), input.getKey(), string(input.getValue())));
          JsonObject output = obj.getAsJsonObject("output");
          allow(output, "item", "count");
          r.outputItemId = text(output, "item");
          r.count = output.has("count") ? output.get("count").getAsBigDecimal().intValueExact() : 1;
          r.copyCommonFrom = obj.has("copy_common_from") ? text(obj, "copy_common_from") : null;
          validateImportedRecipe(project, r, obj);
          Map<String, String> assignments = new LinkedHashMap<>();
          if (obj.has("base"))
            for (var v : obj.getAsJsonObject("base").entrySet()) {
              // Common-copy overrides base in the runtime; only retain effective base values.
              boolean copied = false;
              if (r.copyCommonFrom != null)
                for (Component c : r.inputs)
                  if (c.role.equals(r.copyCommonFrom))
                    copied =
                        project.item(c.itemId).fields.stream()
                            .anyMatch(f -> f.id.equals(v.getKey()));
              if (!copied) assignments.put(v.getKey(), numeric(v.getValue()));
            }
          if (obj.has("transfers"))
            for (var v : obj.getAsJsonObject("transfers").entrySet()) {
              JsonObject src = v.getValue().getAsJsonObject();
              allow(src, "input", "field");
              assignments.put(
                  v.getKey(), "${" + text(src, "input") + "::" + text(src, "field") + "}");
            }
          if (obj.has("assignments"))
            for (var v : obj.getAsJsonObject("assignments").entrySet())
              assignments.put(v.getKey(), string(v.getValue()));
          for (var v : assignments.entrySet()) {
            List<InputField> refs = new ArrayList<>();
            Matcher m = REFERENCE.matcher(v.getValue());
            while (m.find()) {
              InputField ref = new InputField(m.group(1), m.group(2));
              if (!refs.contains(ref)) refs.add(ref);
            }
            r.equations.add(new BoundEquation(v.getValue(), v.getKey(), refs));
          }
          project.recipes.add(r);
        }
      }
      project.validate();
      return project;
    } catch (RuntimeException e) {
      throw new IOException("Каталог не импортирован: " + e.getMessage(), e);
    }
  }

  private static void validateImportedRecipe(
      EditorProject project, Recipe recipe, JsonObject source) {
    Map<String, ParameterSchema> schemas = new LinkedHashMap<>();
    for (Component c : recipe.inputs)
      if (schemas.putIfAbsent(c.role, project.schema(project.item(c.itemId))) != null)
        throw new IllegalArgumentException("Повторяется вход: " + c.name);
    ParameterRecipe.Builder original =
        ParameterRecipe.builder(
            recipe.id, InputLayout.of(schemas), project.schema(project.item(recipe.outputItemId)));
    if (recipe.copyCommonFrom != null) original.copyCommonFrom(recipe.copyCommonFrom);
    if (source.has("base"))
      for (var entry : source.getAsJsonObject("base").entrySet())
        original.base(entry.getKey(), new BigDecimal(numeric(entry.getValue())));
    if (source.has("transfers"))
      for (var entry : source.getAsJsonObject("transfers").entrySet()) {
        JsonObject src = entry.getValue().getAsJsonObject();
        allow(src, "input", "field");
        original.transfer(entry.getKey(), text(src, "input"), text(src, "field"));
      }
    if (source.has("assignments"))
      for (var entry : source.getAsJsonObject("assignments").entrySet())
        original.assign(entry.getKey(), string(entry.getValue()));
    original.build();
  }

  private static List<Path> catalogFiles(Path dir) throws IOException {
    if (!Files.exists(dir)) return List.of();
    try (Stream<Path> paths = Files.walk(dir)) {
      List<Path> list = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
      if (list.size() > 40000) throw new IOException("Слишком много файлов каталога");
      long total = 0;
      for (Path p : list) {
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("Необычный файл: " + p);
        total += Files.size(p);
      }
      if (total > StrictJson.MAX_BYTES) throw new IOException("Каталог больше 64 МиБ");
      return list;
    }
  }

  private static JsonObject read(Path path) throws IOException {
    if (Files.size(path) > StrictJson.MAX_BYTES) throw new IOException("Слишком большой файл");
    return StrictJson.parseObject(Files.readString(path, StandardCharsets.UTF_8));
  }

  private static JsonArray singleton(JsonObject object) {
    JsonArray array = new JsonArray();
    array.add(object);
    return array;
  }

  private static String text(JsonObject obj, String key) {
    return string(Objects.requireNonNull(obj.get(key), "Не указано: " + key));
  }

  private static String string(JsonElement value) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Ожидается текст");
    return value.getAsString();
  }

  private static String numeric(JsonElement value) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Ожидается число");
    return value.getAsBigDecimal().toPlainString();
  }

  private static String numberOr(JsonObject obj, String key, String fallback) {
    return obj.has(key) ? numeric(obj.get(key)) : fallback;
  }

  private static void version(JsonObject obj) {
    if (obj.has("version") && obj.get("version").getAsBigDecimal().intValueExact() != 1)
      throw new IllegalArgumentException("Неизвестная версия каталога");
  }

  private static void allow(JsonObject obj, String... keys) {
    Set<String> allowed = Set.of(keys);
    for (String key : obj.keySet())
      if (!allowed.contains(key))
        throw new IllegalArgumentException(
            "Неподдерживаемая настройка «" + key + "». Импорт отменён, файл не изменён.");
  }

  public static String friendlyItem(String id) {
    return switch (id) {
      case "minecraft:raw_iron" -> "Рудное железо";
      case "minecraft:iron_ingot" -> "Железный слиток";
      case "minecraft:raw_copper" -> "Рудная медь";
      case "minecraft:copper_ingot" -> "Медный слиток";
      case "minecraft:raw_gold" -> "Рудное золото";
      case "minecraft:gold_ingot" -> "Золотой слиток";
      case "minecraft:dirt" -> "Земля";
      case "minecraft:stone" -> "Камень";
      case "minecraft:coal" -> "Уголь";
      default -> id;
    };
  }

  public static String friendlyField(String id) {
    String key = id.substring(id.indexOf(':') + 1);
    return switch (key) {
      case "mass" -> "Масса";
      case "density" -> "Плотность";
      case "hardness" -> "Твёрдость";
      case "purity" -> "Чистота";
      case "precision" -> "Точность";
      case "volume" -> "Объём";
      case "strength" -> "Прочность";
      case "elasticity" -> "Упругость";
      default -> key;
    };
  }

  public static EditorProject sample() {
    EditorProject p = new EditorProject();
    Item ore = new Item("minecraft:raw_iron", "Железная руда");
    ore.fields.add(new Field("factory:mass", "Масса", "10"));
    ore.fields.add(new Field("factory:density", "Плотность", "4"));
    ore.fields.add(new Field("factory:hardness", "Твёрдость", "7"));
    ore.fields.add(new Field("factory:purity", "Чистота", "90"));
    Item ingot = new Item("minecraft:iron_ingot", "Железный слиток");
    for (Field f : ore.fields) {
      Field copy = f.copy();
      copy.value = "0";
      ingot.fields.add(copy);
    }
    p.items.add(ore);
    p.items.add(ingot);
    Recipe recipe = new Recipe("Переработка руды — пример");
    recipe.inputs.add(new Component("ore", "Руда", ore.id));
    recipe.outputItemId = ingot.id;
    recipe.copyCommonFrom = "ore";
    recipe.equations = p.bind(recipe, "1 * 2 = 3\n3 + 2 = 2");
    p.recipes.add(recipe);
    return p;
  }

  public static EditorProject physicsSample() {
    EditorProject project = sample();
    Recipe recipe = project.recipes.get(0);
    String[] symbols = {"M", "ρ", "H", "P"};
    for (int i = 0; i < symbols.length; i++) {
      recipe.inputs.get(0).symbols.put(project.items.get(0).fields.get(i).id, symbols[i]);
      recipe.outputSymbols.put(project.items.get(1).fields.get(i).id, symbols[i]);
    }
    return project;
  }

  /** The first screen must demonstrate a real transformation, without implicit copying. */
  public static EditorProject tutorialSample() {
    EditorProject p = new EditorProject();
    Item ore = new Item("minecraft:raw_iron", "Рудное железо");
    ore.fields.add(new Field("factory:mass", "Масса", "1000"));
    ore.fields.add(new Field("factory:purity", "Чистота", "80"));
    Item ingot = new Item("minecraft:iron_ingot", "Железный слиток");
    ingot.fields.add(new Field("factory:mass", "Масса", "0"));
    ingot.fields.add(new Field("factory:purity", "Чистота", "0"));
    ingot.fields.add(new Field("factory:hardness", "Твёрдость", "0"));
    p.items.add(ore);
    p.items.add(ingot);
    Recipe r = new Recipe("Плавка сырья — пример");
    r.inputs.add(new Component("ore", "Сырьё", ore.id));
    r.outputItemId = ingot.id;
    r.inputs.get(0).symbols.put("factory:mass", "M");
    r.inputs.get(0).symbols.put("factory:purity", "P");
    r.outputSymbols.put("factory:mass", "M");
    r.outputSymbols.put("factory:purity", "P");
    r.outputSymbols.put("factory:hardness", "H");
    r.equations =
        p.bind(r, "M * P / [100] * [0.95] = M\nclamp(P + [10], [0], [100]) = P\n[25] = H");
    p.recipes.add(r);
    return p;
  }
}
