package dev.itemparams.config;

import com.google.gson.*;
import dev.itemparams.production.ProductionRecipe;
import dev.itemparams.production.CarryPolicy;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Parses, resolves and compiles everything before publishing a single volatile snapshot. */
public final class ConfigurationManager {
    private final Path file;
    private final Path recipeDirectory;
    private volatile ConfigurationSnapshot current;
    public ConfigurationManager(Path file) { this(file, file.resolveSibling("itemparams-recipes")); }
    public ConfigurationManager(Path file, Path recipeDirectory) {
        this.file = Objects.requireNonNull(file, "file");
        this.recipeDirectory = Objects.requireNonNull(recipeDirectory, "recipeDirectory");
    }
    public ConfigurationSnapshot current() {
        ConfigurationSnapshot result = current;
        if (result == null) throw new IllegalStateException("Configuration has not been initialized");
        return result;
    }
    public synchronized ConfigurationSnapshot reload() throws IOException {
        // This optional tool only reads consumer-owned files. The demo explicitly creates its example.
        if (Files.size(file) > StrictJson.MAX_BYTES) throw new IOException("Configuration exceeds 64 MiB");
        JsonObject root = StrictJson.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        RuntimeBuilder builder = new RuntimeBuilder();
        try {
            allowed(root, "version", "fields", "recipes");
            if (decimal(root, "version").intValueExact() != 1) throw new IllegalArgumentException("Unsupported config version");
            for (JsonElement element : array(root, "fields")) {
                JsonObject field = object(element, "field"); allowed(field, "id", "default", "min", "max");
                String id = namespaced(string(field, "id"));
                BigDecimal value = field.has("default") ? decimal(field, "default") : BigDecimal.ZERO;
                BigDecimal min = field.has("min") ? decimal(field, "min") : BigDecimal.valueOf(Integer.MIN_VALUE, 2);
                BigDecimal max = field.has("max") ? decimal(field, "max") : BigDecimal.valueOf(Integer.MAX_VALUE, 2);
                // Bounds must themselves be representable: avoid accidentally widening a requested range.
                requireHundredths(min); requireHundredths(max);
                builder.field(id, value, min, max);
            }
            loadRecipes(builder, array(root, "recipes"));
            Path directory = recipeDirectory;
            if (Files.exists(directory)) {
                if (!Files.isDirectory(directory)) throw new IOException("Recipe catalog path is not a directory");
                List<Path> parts;
                try (var files = Files.list(directory)) {
                    parts = files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
                }
                long totalBytes = Files.size(file);
                if (parts.size() > RuntimeBuilder.MAX_RECIPES) throw new IOException("Too many recipe catalog files");
                for (Path part : parts) {
                    totalBytes += Files.size(part);
                    if (totalBytes > StrictJson.MAX_BYTES) throw new IOException("Combined configuration exceeds 64 MiB");
                    JsonObject catalog = StrictJson.parseObject(Files.readString(part, StandardCharsets.UTF_8));
                    allowed(catalog, "version", "recipes");
                    if (decimal(catalog, "version").intValueExact() != 1) throw new IllegalArgumentException("Unsupported catalog version: " + part);
                    loadRecipes(builder, array(catalog, "recipes"));
                }
            }
            for (ParameterExtension extension : FabricLoader.getInstance().getEntrypoints("itemparams:extensions", ParameterExtension.class))
                extension.configure(builder);
            ConfigurationSnapshot next = builder.build();
            current = next;
            return next;
        } catch (RuntimeException ex) {
            throw new IOException("Configuration rejected: " + ex.getMessage(), ex);
        }
    }
    private static void loadRecipes(RuntimeBuilder builder, JsonArray array) {
            for (JsonElement element : array) {
                JsonObject recipe = object(element, "recipe"); allowed(recipe, "id", "input", "output", "assignments", "carry_input");
                String id = namespaced(string(recipe, "id"));
                Item input = item(string(recipe, "input"));
                JsonObject output = object(required(recipe, "output"), "output"); allowed(output, "item", "count");
                Item outputItem = item(string(output, "item"));
                int count = output.has("count") ? decimal(output, "count").intValueExact() : 1;
                JsonObject assignments = object(required(recipe, "assignments"), "assignments");
                if (assignments.size() > 4096) throw new IllegalArgumentException("Too many assignments");
                Map<String, String> formulas = new LinkedHashMap<>();
                assignments.entrySet().forEach(entry -> formulas.put(namespaced(entry.getKey()), text(entry.getValue(), "formula")));
                boolean carry = !recipe.has("carry_input") || bool(recipe, "carry_input");
                builder.recipe(id, input, outputItem, count, formulas, carry ? CarryPolicy.COPY_INPUT : CarryPolicy.DEFAULTS);
            }
    }
    private static void requireHundredths(BigDecimal value) {
        value.movePointRight(2).intValueExact();
    }
    private static Item item(String name) {
        Identifier id = new Identifier(namespaced(name));
        if (!Registries.ITEM.containsId(id) || id.equals(new Identifier("minecraft:air")))
            throw new IllegalArgumentException("Unknown or empty item: " + name);
        return Registries.ITEM.get(id);
    }
    private static String namespaced(String value) {
        if (!value.contains(":")) throw new IllegalArgumentException("Expected namespace:path: " + value);
        new Identifier(value); return value;
    }
    private static void allowed(JsonObject object, String... keys) {
        Set<String> allowed = Set.of(keys);
        for (String key : object.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown configuration key: " + key);
    }
    private static JsonElement required(JsonObject object, String key) {
        if (!object.has(key)) throw new IllegalArgumentException("Missing configuration key: " + key);
        return object.get(key);
    }
    private static JsonObject object(JsonElement value, String label) {
        if (!value.isJsonObject()) throw new IllegalArgumentException("Expected object: " + label);
        return value.getAsJsonObject();
    }
    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonArray()) throw new IllegalArgumentException("Expected array: " + key);
        return value.getAsJsonArray();
    }
    private static String string(JsonObject object, String key) { return text(required(object, key), key); }
    private static String text(JsonElement value, String label) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string: " + label);
        return value.getAsString();
    }
    private static BigDecimal decimal(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected number: " + key);
        return value.getAsBigDecimal();
    }
    private static boolean bool(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Expected boolean: " + key);
        return value.getAsBoolean();
    }
}
