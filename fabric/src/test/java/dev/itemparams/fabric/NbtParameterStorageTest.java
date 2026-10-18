package dev.itemparams.fabric;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** The codec needs only an owner's NBT root, with no ItemStack or registered game object. */
class NbtParameterStorageTest {
    private static final ParameterSchema SCHEMA = ParameterSchema.builder()
            .addRaw("test:a", 100).addRaw("test:b", 200).build();

    @Test void missingRootAndMissingPayloadReturnDetachedDefaultsWithoutMutation() {
        assertEquals(100, ParameterNbtStorage.read(null, SCHEMA).getRaw("test:a"));
        NbtCompound owner = new NbtCompound();
        owner.putString("owner", "entity-or-block-entity");
        NbtCompound before = owner.copy();
        ParameterValues detached = ParameterNbtStorage.read(owner, SCHEMA);
        detached.setRaw("test:a", 900);
        assertEquals(before, owner);
        assertFalse(owner.contains(ParameterNbtStorage.NBT_KEY));
        assertEquals(100, ParameterNbtStorage.read(owner, SCHEMA).getRaw("test:a"));
    }

    @Test void genericRootRoundTripPreservesAllSigned32BitValues() {
        ParameterValues values = SCHEMA.defaults();
        values.setRaw("test:a", Integer.MIN_VALUE);
        values.setRaw("test:b", Integer.MAX_VALUE);
        NbtCompound saved = ParameterNbtStorage.write(null, values);
        assertArrayEquals(new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE},
                saved.getCompound(ParameterNbtStorage.NBT_KEY).getIntArray("values"));
        ParameterValues loaded = ParameterNbtStorage.read(saved, SCHEMA);
        assertEquals(values.toRawMap(), loaded.toRawMap());
        loaded.setRaw("test:a", 0);
        assertEquals(Integer.MIN_VALUE, ParameterNbtStorage.read(saved, SCHEMA).getRaw("test:a"));
        assertEquals(ItemParameterStorage.NBT_KEY, ParameterNbtStorage.NBT_KEY);
        assertEquals(ItemParameterStorage.FORMAT_VERSION, ParameterNbtStorage.FORMAT_VERSION);
    }

    @Test void writeReturnsDetachedRootAndPreservesUnrelatedAndUnknownMetadata() {
        NbtCompound source = ParameterNbtStorage.write(null, SCHEMA.defaults());
        NbtCompound ownerData = new NbtCompound();
        ownerData.putString("note", "original");
        source.put("othermod:data", ownerData);
        source.getCompound(ParameterNbtStorage.NBT_KEY).putString("extension", "original");
        NbtCompound before = source.copy();
        ParameterValues edited = SCHEMA.defaults();
        edited.setRaw("test:a", 700);
        NbtCompound result = ParameterNbtStorage.write(source, edited);
        assertNotSame(source, result);
        assertEquals(before, source);
        assertEquals(700, ParameterNbtStorage.read(result, SCHEMA).getRaw("test:a"));
        assertEquals(100, ParameterNbtStorage.read(source, SCHEMA).getRaw("test:a"));
        result.getCompound("othermod:data").putString("note", "changed");
        result.getCompound(ParameterNbtStorage.NBT_KEY).putString("extension", "changed");
        assertEquals(before, source, "No nested tag may alias the supplied owner root");
    }

    @Test void schemaExtensionReordersValuesAddsDefaultsAndRetainsRemovedFields() {
        ParameterValues first = ParameterValues.fromRawMap(SCHEMA,
                Map.of("test:a", 123, "test:b", -456, "removed:orphan", 789));
        NbtCompound source = ParameterNbtStorage.write(null, first);
        ParameterSchema extended = ParameterSchema.builder().addRaw("test:b", 0)
                .addRaw("test:c", 999).build();
        ParameterValues next = ParameterNbtStorage.read(source, extended);
        assertEquals(-456, next.getRaw("test:b"));
        assertEquals(999, next.getRaw("test:c"));
        next.setRaw("test:b", 555);
        NbtCompound migrated = ParameterNbtStorage.write(source, next);
        assertEquals(123, ParameterNbtStorage.read(migrated, SCHEMA).getRaw("test:a"));
        assertEquals(555, ParameterNbtStorage.read(migrated, SCHEMA).getRaw("test:b"));
        assertEquals(789, ParameterNbtStorage.read(migrated, extended).toRawMap().get("removed:orphan"));
        assertEquals(-456, ParameterNbtStorage.read(source, SCHEMA).getRaw("test:b"));
    }

    @Test void malformedOrFuturePayloadRejectsReadAndWriteWithoutChangingItsOwner() {
        NbtCompound source = ParameterNbtStorage.write(null, SCHEMA.defaults());
        source.getCompound(ParameterNbtStorage.NBT_KEY).putInt("version", 2);
        assertRejectedUnchanged(source);
        source = new NbtCompound();
        source.putString(ParameterNbtStorage.NBT_KEY, "wrong type");
        assertRejectedUnchanged(source);
        source = ParameterNbtStorage.write(null, SCHEMA.defaults());
        source.getCompound(ParameterNbtStorage.NBT_KEY).putIntArray("values", new int[]{100});
        assertRejectedUnchanged(source);
    }

    @Test void tightenedBoundsRejectHistoricalDataWithoutRepairingTheSuppliedRoot() {
        NbtCompound source = ParameterNbtStorage.write(null, SCHEMA.defaults());
        NbtCompound before = source.copy();
        ParameterSchema restricted = ParameterSchema.builder().addRaw("test:a", 0, 0, 50)
                .addRaw("test:b", 200).build();
        assertThrows(IllegalArgumentException.class, () -> ParameterNbtStorage.read(source, restricted));
        assertThrows(IllegalArgumentException.class, () -> ParameterNbtStorage.write(source, restricted.defaults()));
        assertEquals(before, source);
    }

    private static void assertRejectedUnchanged(NbtCompound source) {
        NbtCompound before = source.copy();
        assertThrows(ParameterStorageException.class, () -> ParameterNbtStorage.read(source, SCHEMA));
        assertThrows(ParameterStorageException.class, () -> ParameterNbtStorage.write(source, SCHEMA.defaults()));
        assertEquals(before, source);
    }
}
