package dev.itemparams.config;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;

/** Bounded JSON parser which rejects duplicate object keys before Gson can erase them. */
public final class StrictJson {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private StrictJson() {}
    public static JsonObject parseObject(String source) throws IOException {
        if (source.length() > MAX_BYTES) throw new IOException("Configuration exceeds 64 MiB");
        try (JsonReader reader = new JsonReader(new StringReader(source))) {
            reader.setLenient(false);
            JsonElement result = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || !result.isJsonObject())
                throw new IOException("Expected a single JSON object");
            return result.getAsJsonObject();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new IOException("Invalid JSON: " + ex.getMessage(), ex);
        }
    }
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw new IOException("JSON nesting exceeds 32");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException("Duplicate JSON key: " + key);
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray array = new JsonArray();
                while (reader.hasNext()) {
                    if (array.size() >= 20000) throw new IOException("JSON array exceeds 20000 elements");
                    array.add(read(reader, depth + 1));
                }
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String number = reader.nextString();
                if (number.length() > 128 || !number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
                    throw new IOException("Invalid or excessive JSON number");
                BigDecimal decimal = new BigDecimal(number);
                if (Math.abs((long) decimal.scale()) > 1024) throw new IOException("Numeric exponent too large");
                yield new JsonPrimitive(decimal);
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Unexpected JSON token at " + reader.getPath());
        };
    }
}
