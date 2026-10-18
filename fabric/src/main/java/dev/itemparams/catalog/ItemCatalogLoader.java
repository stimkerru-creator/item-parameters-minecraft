package dev.itemparams.catalog;

import com.google.gson.*;
import dev.itemparams.config.StrictJson;
import dev.itemparams.core.Numeric;
import dev.itemparams.core.ParameterSchema;
import dev.itemparams.formula.InputLayout;
import dev.itemparams.production.ParameterRecipe;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Local server file catalog. Many small files or grouped files are supported.
 * No per-instance number combinations are stored here. Every reload is atomic.
 */
public final class ItemCatalogLoader {
    public static final int MAX_ITEM_TYPES = 20000;
    public static final int MAX_RECIPES = 20000;
    public static final int MAX_FILES = 40000;
    private ItemCatalogLoader() {}

    public static ItemCatalogSnapshot load(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        Path itemsDir = root.resolve("items");
        Path recipesDir = root.resolve("recipes");
        try {
            Map<Identifier, ItemDefinition> items = new LinkedHashMap<>();
            for (Path path : listCatalogPaths(itemsDir)) {
                try {
                    JsonObject file = readCatalogFile(path);
                    allow(file, "version", "item", "fields", "items");
                    version(file);
                    if (file.has("items")) {
                        if (file.has("item") || file.has("fields"))
                            throw new IllegalArgumentException("Use either one item or an items array");
                        for (JsonElement entry : array(file, "items")) addItem(items, object(entry));
                    } else addItem(items, file);
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException(path + ": " + failure.getMessage(), failure);
                }
            }
            Map<String, ConfiguredItemRecipe> recipes = new LinkedHashMap<>();
            for (Path path : listCatalogPaths(recipesDir)) {
                try {
                    JsonObject file = readCatalogFile(path);
                    allow(file, "version", "id", "inputs", "output", "copy_common_from", "base", "transfers", "assignments", "recipes");
                    version(file);
                    if (file.has("recipes")) {
                        if (file.has("id")) throw new IllegalArgumentException("Use either one recipe or a recipes array");
                        for (JsonElement entry : array(file, "recipes")) addRecipe(recipes, items, object(entry));
                    } else addRecipe(recipes, items, file);
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException(path + ": " + failure.getMessage(), failure);
                }
            }
            return new ItemCatalogSnapshot(items, recipes);
        } catch (RuntimeException ex) {
            throw new IOException("Item parameters catalog rejected: " + ex.getMessage(), ex);
        }
    }

    /** Retain sorted paths only; parsing and compilation is per-file, not a whole-catalog AST. */
    private static List<Path> listCatalogPaths(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a catalog directory: " + dir);
        List<Path> files;
        try (Stream<Path> stream = Files.walk(dir)) {
            files = stream.filter(p -> p.toString().endsWith(".json"))
                    .sorted().toList();
        }
        if (files.size() > MAX_FILES) throw new IOException("Too many configuration files");
        long bytes = 0;
        for (Path path : files) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Not a regular configuration file: " + path);
            bytes += Files.size(path);
            if (bytes > StrictJson.MAX_BYTES) throw new IOException("Catalog exceeds 64 MiB");
        }
        return files;
    }

    private static JsonObject readCatalogFile(Path path) throws IOException {
        // StrictJson rejects duplicate keys and excessive nesting; tree becomes
        // unreachable immediately after this one file is compiled into the catalog.
        try {
            return StrictJson.parseObject(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IOException("Invalid JSON in " + path + ": " + failure.getMessage(), failure);
        }
    }

    private static void addItem(Map<Identifier, ItemDefinition> items, JsonObject object) {
        allow(object, "version", "item", "fields");
        if (items.size() >= MAX_ITEM_TYPES) throw new IllegalArgumentException("Too many item definitions");
        Identifier id = identifier(text(object, "item"));
        Item item = resolve(id);
        ParameterSchema.Builder builder = ParameterSchema.builder();
        for (JsonElement element : array(object, "fields")) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                builder.addRaw(text(element), 0);
            } else {
                JsonObject field = object(element);
                allow(field, "id", "default", "min", "max");
                String fieldId = text(field, "id");
                BigDecimal defaultValue = numberOr(field, "default", Numeric.decode(0));
                BigDecimal min = numberOr(field, "min", Numeric.MIN_VALUE);
                BigDecimal max = numberOr(field, "max", Numeric.MAX_VALUE);
                builder.add(fieldId, defaultValue, min, max);
            }
        }
        ParameterSchema schema = builder.build();
        if (items.putIfAbsent(id, new ItemDefinition(id, item, schema)) != null)
            throw new IllegalArgumentException("Duplicate item definition: " + id);
    }

    private static void addRecipe(Map<String, ConfiguredItemRecipe> recipes,
                                  Map<Identifier, ItemDefinition> items, JsonObject object) {
        allow(object, "version", "id", "inputs", "output", "copy_common_from", "base", "transfers", "assignments");
        if (recipes.size() >= MAX_RECIPES) throw new IllegalArgumentException("Too many recipes");
        String id = text(object, "id");
        ParameterSchema.validateId(id);
        Map<String, ItemDefinition> roles = new LinkedHashMap<>();
        for (var entry : object(required(object, "inputs")).entrySet()) {
            ItemDefinition definition = requiredItem(items, text(entry.getValue()));
            if (roles.putIfAbsent(entry.getKey(), definition) != null) throw new IllegalArgumentException("Duplicate input role");
        }
        if (roles.isEmpty()) throw new IllegalArgumentException("Recipe must have at least one input role");
        if (roles.size() > 64) throw new IllegalArgumentException("Recipe exceeds 64 input roles");
        Map<String, ParameterSchema> layouts = new LinkedHashMap<>();
        roles.forEach((role, def) -> layouts.put(role, def.schema()));
        JsonObject output = object(required(object, "output"));
        allow(output, "item", "count");
        ItemDefinition target = requiredItem(items, text(output, "item"));
        int count = output.has("count") ? integer(output.get("count")) : 1;
        ParameterRecipe.Builder builder = ParameterRecipe.builder(id, InputLayout.of(layouts), target.schema());
        if (object.has("copy_common_from")) builder.copyCommonFrom(text(object, "copy_common_from"));
        if (object.has("base")) for (var entry : object(object.get("base")).entrySet())
            builder.base(entry.getKey(), number(entry.getValue()));
        if (object.has("transfers")) for (var entry : object(object.get("transfers")).entrySet()) {
            JsonObject source = object(entry.getValue());
            allow(source, "input", "field");
            builder.transfer(entry.getKey(), text(source, "input"), text(source, "field"));
        }
        if (object.has("assignments")) for (var entry : object(object.get("assignments")).entrySet())
            builder.assign(entry.getKey(), text(entry.getValue()));
        ConfiguredItemRecipe compiled = new ConfiguredItemRecipe(id, roles, target, count, builder.build());
        if (recipes.putIfAbsent(id, compiled) != null)
            throw new IllegalArgumentException("Duplicate recipe: " + id);
    }

    private static ItemDefinition requiredItem(Map<Identifier, ItemDefinition> items, String id) {
        ItemDefinition def = items.get(identifier(id));
        if (def == null) throw new IllegalArgumentException("Recipe references unconfigured item: " + id);
        return def;
    }
    private static Identifier identifier(String text) {
        ParameterSchema.validateId(text);
        return new Identifier(text);
    }
    private static Item resolve(Identifier id) {
        if (!Registries.ITEM.containsId(id) || id.equals(new Identifier("minecraft:air")))
            throw new IllegalArgumentException("Unknown or empty Item ID: " + id);
        return Registries.ITEM.get(id);
    }
    private static void version(JsonObject o) {
        if (o.has("version") && integer(o.get("version")) != 1)
            throw new IllegalArgumentException("Unsupported catalog version");
    }
    private static void allow(JsonObject o, String... keys) {
        Set<String> valid = Set.of(keys);
        for (String key : o.keySet()) if (!valid.contains(key))
            throw new IllegalArgumentException("Unknown configuration key: " + key);
    }
    private static JsonElement required(JsonObject o, String key) {
        if (!o.has(key)) throw new IllegalArgumentException("Missing key: " + key);
        return o.get(key);
    }
    private static JsonArray array(JsonObject o, String key) {
        JsonElement element = required(o, key);
        if (!element.isJsonArray()) throw new IllegalArgumentException("Expected array: " + key);
        return element.getAsJsonArray();
    }
    private static JsonObject object(JsonElement e) {
        if (!e.isJsonObject()) throw new IllegalArgumentException("Expected JSON object");
        return e.getAsJsonObject();
    }
    private static String text(JsonObject o, String key) { return text(required(o, key)); }
    private static String text(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected text value");
        return e.getAsString();
    }
    private static int integer(JsonElement e) { return number(e).intValueExact(); }
    private static BigDecimal numberOr(JsonObject o, String key, BigDecimal otherwise) {
        return o.has(key) ? number(o.get(key)) : otherwise;
    }
    private static BigDecimal number(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected numeric value");
        return e.getAsBigDecimal();
    }
}
