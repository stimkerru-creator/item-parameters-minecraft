package dev.itemparams.fabric;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ItemInputFilterTest {
    private static final ParameterSchema SCHEMA = ParameterSchema.builder()
            .addRaw("test:x", 100).addRaw("test:y", 200).build();

    @BeforeAll static void bootstrapMinecraft() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test void unlockedFilterAdmitsOnlyItsItemWithAValidNumericState() {
        ItemInputFilter filter = filter();
        assertFalse(filter.isLocked());
        assertTrue(filter.sample().isEmpty());
        assertFalse(filter.matches(null));
        assertFalse(filter.matches(ItemStack.EMPTY));
        assertFalse(filter.matches(new ItemStack(Items.STICK)));
        ItemStack absent = new ItemStack(Items.IRON_INGOT);
        assertTrue(filter.matches(absent));
        assertNull(absent.getNbt(), "Admission must not materialize defaults into the candidate");
        assertTrue(filter.matches(item(123, 456)));
        assertTrue(filter.matches(item(Integer.MIN_VALUE, Integer.MAX_VALUE)));
        assertTrue(filter.matches(withUnknown("other:old", 789)));
        assertFalse(filter.isLocked(), "Admission alone does not begin production or lock values");
    }

    @Test void factoryRejectsAbsentOrEmptyItemDefinitions() {
        assertThrows(NullPointerException.class, () -> ItemInputFilter.of(null, SCHEMA));
        assertThrows(NullPointerException.class, () -> ItemInputFilter.of(Items.IRON_INGOT, null));
        assertThrows(IllegalArgumentException.class, () -> ItemInputFilter.of(Items.AIR, SCHEMA));
    }

    @Test void malformedUnsupportedOrDuplicatePayloadNeverPassesOrMutatesCandidates() {
        ItemStack badRoot = item(100, 200);
        badRoot.getNbt().putInt(ItemParameterStorage.NBT_KEY, 1);
        assertInvalidAndUnchanged(badRoot);
        ItemStack future = item(100, 200);
        payload(future).putInt("version", 2);
        assertInvalidAndUnchanged(future);
        ItemStack wrongVersionType = item(100, 200);
        payload(wrongVersionType).putShort("version", (short) 1);
        assertInvalidAndUnchanged(wrongVersionType);
        ItemStack scale = item(100, 200);
        payload(scale).putInt("scale", 1000);
        assertInvalidAndUnchanged(scale);
        ItemStack wrongArray = item(100, 200);
        payload(wrongArray).putIntArray("values", new int[]{100});
        assertInvalidAndUnchanged(wrongArray);
        ItemStack duplicate = item(100, 200);
        NbtList ids = new NbtList();
        ids.add(NbtString.of("test:x"));
        ids.add(NbtString.of("test:x"));
        payload(duplicate).put("ids", ids);
        assertInvalidAndUnchanged(duplicate);
    }

    @Test void configuredBoundsAreCheckedBeforeAdmissionAndLocking() {
        ParameterSchema limited = ParameterSchema.builder().addRaw("test:x", 100, 0, 1000)
                .addRaw("test:y", 200).build();
        ItemInputFilter filter = ItemInputFilter.of(Items.IRON_INGOT, limited);
        ItemStack outside = item(1001, 200);
        NbtCompound before = outside.getNbt().copy();
        assertFalse(filter.matches(outside));
        assertThrows(IllegalArgumentException.class, () -> filter.lock(outside));
        assertFalse(filter.isLocked());
        assertEquals(before, outside.getNbt());
        assertTrue(filter.matches(item(1000, 200)));
        filter.lock(item(1000, 200));
        assertFalse(filter.matches(outside));
        assertTrue(filter.matches(item(1000, 200)));
    }

    @Test void lockRequiresAllNumericFieldsIncludingFieldsUnusedByAnyFormulaAndOrphans() {
        ItemInputFilter filter = filter();
        ItemStack original = withUnknown("other:old", 789);
        filter.lock(original);
        assertTrue(filter.isLocked());
        assertTrue(filter.matches(original.copy()));
        assertFalse(filter.matches(item(100, 200)), "Dropping an unknown field changes the entire numeric state");
        assertFalse(filter.matches(withUnknown("other:old", 790)));
        assertFalse(filter.matches(withUnknown("different:old", 789)));
        ItemStack changedKnown = withUnknown("other:old", 789);
        ItemParameterStorage.edit(changedKnown, SCHEMA, values -> values.setRaw("test:y", 201));
        assertFalse(filter.matches(changedKnown));
        assertFalse(filter.matches(new ItemStack(Items.STICK)));
    }

    @Test void countAndUnrelatedNbtAreIgnoredAndTheSampleIsNumericOnly() {
        ItemInputFilter filter = filter();
        ItemStack original = item(123, 456);
        original.setCount(37);
        original.getOrCreateNbt().putString("othermod:description", "source-only");
        payload(original).putString("extension_metadata", "not-a-parameter");
        NbtCompound before = original.getNbt().copy();
        filter.lock(original);
        assertEquals(before, original.getNbt());
        assertEquals(37, original.getCount());
        ItemStack numericSample = filter.sample();
        assertEquals(1, numericSample.getCount());
        assertEquals(Set.of(ItemParameterStorage.NBT_KEY), numericSample.getNbt().getKeys());
        assertFalse(payload(numericSample).contains("extension_metadata"));
        ItemStack variant = item(123, 456);
        variant.setCount(2);
        variant.getOrCreateNbt().putString("different:owner_data", "ignored");
        assertTrue(filter.matches(variant));
        filter.lock(variant);
        assertEquals(123, ItemParameterStorage.read(filter.sample(), SCHEMA).getRaw("test:x"));
    }

    @Test void missingDefaultsAndReorderedOrPartialRepresentationsMatchSemantically() {
        ItemInputFilter filter = filter();
        filter.lock(new ItemStack(Items.IRON_INGOT));
        assertTrue(filter.matches(new ItemStack(Items.IRON_INGOT, 12)));
        assertTrue(filter.matches(item(100, 200)));
        ItemStack reordered = item(100, 200);
        NbtList reverse = new NbtList();
        reverse.add(NbtString.of("test:y"));
        reverse.add(NbtString.of("test:x"));
        payload(reordered).put("ids", reverse);
        payload(reordered).putIntArray("values", new int[]{200, 100});
        assertTrue(filter.matches(reordered));
        filter.lock(reordered);
        ItemStack partial = item(100, 200);
        NbtList one = new NbtList();
        one.add(NbtString.of("test:x"));
        payload(partial).put("ids", one);
        payload(partial).putIntArray("values", new int[]{100});
        assertTrue(filter.matches(partial));
        ItemStack changed = item(100, 201);
        assertFalse(filter.matches(changed));
    }

    @Test void sourceAndReturnedSampleMutationsCannotModifyLockedState() {
        ItemInputFilter filter = filter();
        ItemStack source = withUnknown("other:old", 789);
        filter.lock(source);
        ItemStack firstSample = filter.sample();
        ItemStack secondSample = filter.sample();
        assertNotSame(firstSample, secondSample);
        assertNotSame(firstSample.getNbt(), secondSample.getNbt());
        ItemParameterStorage.edit(source, SCHEMA, values -> values.setRaw("test:x", 500));
        ItemParameterStorage.edit(firstSample, SCHEMA, values -> values.setRaw("test:x", 900));
        secondSample.getNbt().remove(ItemParameterStorage.NBT_KEY);
        assertFalse(filter.matches(source));
        assertTrue(filter.matches(withUnknown("other:old", 789)));
        assertEquals(100, ItemParameterStorage.read(filter.sample(), SCHEMA).getRaw("test:x"));
        assertEquals(789, ItemParameterStorage.read(filter.sample(), SCHEMA).toRawMap().get("other:old"));
    }

    @Test void repeatedEquivalentLockIsHarmlessAndDifferentOrInvalidLockCannotOverwriteIt() {
        ItemInputFilter filter = filter();
        ItemStack source = item(123, 456);
        filter.lock(source);
        NbtCompound before = filter.sample().writeNbt(new NbtCompound());
        ItemStack equivalent = source.copy();
        equivalent.setCount(16);
        equivalent.getOrCreateNbt().putString("unrelated:tag", "ignored");
        assertDoesNotThrow(() -> filter.lock(equivalent));
        assertThrows(IllegalArgumentException.class, () -> filter.lock(item(124, 456)));
        assertThrows(IllegalArgumentException.class, () -> filter.lock(null));
        assertThrows(IllegalArgumentException.class, () -> filter.lock(ItemStack.EMPTY));
        ItemStack future = source.copy();
        payload(future).putInt("version", 2);
        assertThrows(IllegalArgumentException.class, () -> filter.lock(future));
        assertEquals(before, filter.sample().writeNbt(new NbtCompound()));
        assertTrue(filter.matches(source));
        assertFalse(filter.matches(future));
    }

    @Test void separateFiltersOwnIndependentAdmissionStates() {
        ItemInputFilter first = filter();
        ItemInputFilter second = filter();
        first.lock(item(100, 200));
        assertFalse(first.matches(item(500, 600)));
        assertTrue(second.matches(item(500, 600)));
        assertFalse(second.isLocked());
        second.lock(item(500, 600));
        assertTrue(first.matches(item(100, 200)));
        assertFalse(second.matches(item(100, 200)));
    }

    private static ItemInputFilter filter() { return ItemInputFilter.of(Items.IRON_INGOT, SCHEMA); }

    private static ItemStack item(int x, int y) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT);
        ItemParameterStorage.write(stack, ParameterValues.fromRawMap(SCHEMA, Map.of("test:x", x, "test:y", y)));
        return stack;
    }

    private static ItemStack withUnknown(String id, int value) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT);
        ItemParameterStorage.write(stack, ParameterValues.fromRawMap(SCHEMA,
                Map.of("test:x", 100, "test:y", 200, id, value)));
        return stack;
    }

    private static NbtCompound payload(ItemStack stack) {
        return stack.getNbt().getCompound(ItemParameterStorage.NBT_KEY);
    }

    private static void assertInvalidAndUnchanged(ItemStack candidate) {
        NbtCompound before = candidate.getNbt().copy();
        ItemInputFilter unlocked = filter();
        assertFalse(unlocked.matches(candidate));
        assertThrows(IllegalArgumentException.class, () -> unlocked.lock(candidate));
        assertFalse(unlocked.isLocked());
        assertTrue(unlocked.sample().isEmpty());
        ItemInputFilter locked = filter();
        locked.lock(item(100, 200));
        assertFalse(locked.matches(candidate));
        assertEquals(before, candidate.getNbt());
    }
}
