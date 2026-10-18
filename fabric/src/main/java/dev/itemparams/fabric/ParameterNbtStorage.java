package dev.itemparams.fabric;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Owner-independent NBT adapter for numeric parameters. Numeric values are signed
 * 32-bit integers measured in hundredths; IDs make saved arrays self-describing.
 * Both operations are pure: read returns detached values and write returns a new
 * root, preserving unrelated tags and unknown parameter IDs. A null root is absent data.
 *
 * In an entity/block-entity readNbt callback, read the owner root with this codec.
 * In writeNbt, first produce the detached root with write; after it succeeds, the
 * owner may install its parameter compound into the callback's output NBT. The
 * host owns dirty tracking, synchronization, thread confinement and persistence.
 * This adapter installs no mixins and attaches no data to owners automatically.
 */
public final class ParameterNbtStorage {
    public static final String NBT_KEY = "itemparams:parameters";
    public static final int FORMAT_VERSION = 1;
    public static final int SCALE = 100;
    public static final int MAX_FIELDS = ParameterSchema.MAX_FIELDS;
    public static final int MAX_ID_LENGTH = ParameterSchema.MAX_ID_LENGTH;
    private ParameterNbtStorage() {}

    /** Missing data supplies schema defaults without creating or changing NBT. */
    public static ParameterValues read(NbtCompound root, ParameterSchema schema) {
        Objects.requireNonNull(schema, "schema");
        return ParameterValues.fromRawMap(schema, readRaw(root));
    }

    /** Validates completely before returning a detached root; the argument is never mutated. */
    public static NbtCompound write(NbtCompound oldRoot, ParameterValues values) {
        Objects.requireNonNull(values, "values");
        Map<String, Integer> existing = readRaw(oldRoot);
        // A changed schema may impose tighter bounds. Never silently repair data rejected
        // by the active schema; explicit migration is the owner's responsibility.
        validateExisting(values.schema(), existing);
        Map<String, Integer> raw = new TreeMap<>(existing);
        raw.putAll(values.toRawMap());
        if (raw.size() > MAX_FIELDS) {
            throw malformed("too many fields: " + raw.size());
        }
        NbtList ids = new NbtList();
        int[] array = new int[raw.size()];
        int index = 0;
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            validateId(entry.getKey());
            if (entry.getValue() == null) throw malformed("null value for " + entry.getKey());
            ids.add(NbtString.of(entry.getKey()));
            array[index++] = entry.getValue();
        }
        NbtCompound root = oldRoot == null ? new NbtCompound() : oldRoot.copy();
        NbtCompound payload = root.contains(NBT_KEY)
                ? root.getCompound(NBT_KEY) : new NbtCompound();
        payload.putInt("version", FORMAT_VERSION);
        payload.putInt("scale", SCALE);
        payload.put("ids", ids);
        payload.putIntArray("values", array);
        root.put(NBT_KEY, payload);
        return root;
    }

    /** Read validated stored values for read-only display and inspection. */
    public static Map<String, Integer> storedRaw(NbtCompound root) {
        return java.util.Collections.unmodifiableMap(readRaw(root));
    }

    private static Map<String, Integer> readRaw(NbtCompound root) {
        Map<String, Integer> raw = new LinkedHashMap<>();
        if (root == null || !root.contains(NBT_KEY)) return raw;
        requireType(root, NBT_KEY, NbtElement.COMPOUND_TYPE);
        NbtCompound payload = root.getCompound(NBT_KEY);
        requireType(payload, "version", NbtElement.INT_TYPE);
        int version = payload.getInt("version");
        if (version != FORMAT_VERSION) throw malformed("unsupported version " + version);
        requireType(payload, "scale", NbtElement.INT_TYPE);
        if (payload.getInt("scale") != SCALE) throw malformed("unsupported numeric scale");
        requireType(payload, "ids", NbtElement.LIST_TYPE);
        requireType(payload, "values", NbtElement.INT_ARRAY_TYPE);
        NbtList ids = (NbtList) payload.get("ids");
        if (ids.getHeldType() != NbtElement.STRING_TYPE
                && !(ids.isEmpty() && ids.getHeldType() == NbtElement.END_TYPE)) {
            throw malformed("IDs list must hold strings");
        }
        int[] array = payload.getIntArray("values");
        if (ids.size() != array.length) throw malformed("IDs and values lengths differ");
        if (array.length > MAX_FIELDS) throw malformed("too many fields: " + array.length);
        for (int index = 0; index < ids.size(); index++) {
            NbtElement idTag = ids.get(index);
            if (idTag.getType() != NbtElement.STRING_TYPE) throw malformed("ID is not a string at " + index);
            String id = ids.getString(index);
            validateId(id);
            if (raw.putIfAbsent(id, array[index]) != null) throw malformed("duplicate ID " + id);
        }
        return raw;
    }

    private static void validateExisting(ParameterSchema schema, Map<String, Integer> existing) {
        int unknown = 0;
        for (Map.Entry<String, Integer> entry : existing.entrySet()) {
            int index = schema.indexOf(entry.getKey());
            if (index >= 0) schema.validateRaw(index, entry.getValue());
            else unknown++;
        }
        if (schema.size() + unknown > MAX_FIELDS) {
            throw malformed("known and retained parameters exceed field limit");
        }
    }

    private static void requireType(NbtCompound compound, String key, int type) {
        NbtElement tag = compound.get(key);
        if (tag == null || tag.getType() != type) throw malformed("missing or incorrect tag type: " + key);
    }

    private static void validateId(String id) {
        try {
            ParameterSchema.validateId(id);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw malformed("invalid field ID " + id);
        }
    }

    private static ParameterStorageException malformed(String message) {
        return new ParameterStorageException("Invalid " + NBT_KEY + " payload: " + message);
    }
}
