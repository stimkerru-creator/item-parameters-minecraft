package dev.itemparams.core;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ParameterValuesTest {
    @Test void fixedPointTruncatesTowardZeroAndUsesFullSignedRange() {
        assertEquals(129,Numeric.encode(new BigDecimal("1.299")));
        assertEquals(-129,Numeric.encode(new BigDecimal("-1.299")));
        assertEquals(29,Numeric.encode(0.29));
        assertEquals(0,Numeric.encode(new BigDecimal("-0.009")));
        assertEquals(Integer.MIN_VALUE,Numeric.encode(Numeric.MIN_VALUE));
        assertEquals(Integer.MAX_VALUE,Numeric.encode(Numeric.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->Numeric.encode(new BigDecimal("21474836.48")));
        assertThrows(IllegalArgumentException.class,()->Numeric.encode(new BigDecimal("-21474836.49")));
        assertThrows(IllegalArgumentException.class,()->Numeric.encode(new BigDecimal("1e100000000")));
        assertThrows(IllegalArgumentException.class,()->Numeric.encode(Double.NaN));
    }
    @Test void schemaChangesRetainMeaningAndUnknownFieldsAcrossGenerations() {
        ParameterSchema original=ParameterSchema.builder().addRaw("test:a",1).addRaw("test:b",2).build();
        ParameterValues values=original.defaults(); values.setRaw("test:a",1234); values.setRaw("test:b",5678);
        ParameterSchema changed=ParameterSchema.builder().addRaw("test:b",0).addRaw("test:c",333).build();
        ParameterValues migrated=ParameterValues.fromRawMap(changed,values.toRawMap());
        assertEquals(5678,migrated.getRaw("test:b")); assertEquals(333,migrated.getRaw("test:c"));
        migrated.setRaw("test:b",9000);
        ParameterSchema reintroduced=ParameterSchema.builder().addRaw("test:c",0).addRaw("test:a",0).addRaw("test:b",0).build();
        ParameterValues restored=ParameterValues.fromRawMap(reintroduced,migrated.toRawMap());
        assertEquals(1234,restored.getRaw("test:a")); assertEquals(9000,restored.getRaw("test:b"));
        assertEquals(333,restored.getRaw("test:c"));
    }
    @Test void copiedValuesAndSnapshotsAreIndependentAndHandleIsSchemaBound() {
        ParameterSchema schema=ParameterSchema.builder().addRaw("test:a",100).build();
        ParameterValues first=schema.defaults(),second=first.copy();
        FieldHandle handle=schema.field("test:a"); second.setRaw(handle,200);
        assertEquals(100,first.getRaw(handle));
        int[] snapshot=first.copyRaw(); snapshot[0]=300;
        assertEquals(100,first.getRaw(0)); first.replaceRaw(snapshot); snapshot[0]=400;
        assertEquals(300,first.getRaw(0));
        ParameterSchema other=ParameterSchema.builder().addRaw("test:a",100).build();
        assertThrows(IllegalArgumentException.class,()->other.defaults().getRaw(handle));
        assertThrows(UnsupportedOperationException.class,()->first.toRawMap().put("test:x",1));
    }
    @Test void boundsApplyToLoadDefaultsEditsAndBulkCommitIsAtomic() {
        ParameterSchema schema=ParameterSchema.builder().addRaw("test:a",10,0,100).addRaw("test:b",20,0,100).build();
        ParameterValues values=schema.defaults();
        assertThrows(IllegalArgumentException.class,()->values.setRaw("test:a",101));
        assertThrows(IllegalArgumentException.class,()->ParameterValues.fromRawMap(schema,Map.of("test:a",-1)));
        assertThrows(IllegalArgumentException.class,()->values.replaceRaw(new int[]{99,101}));
        assertArrayEquals(new int[]{10,20},values.copyRaw());
        assertThrows(IllegalArgumentException.class,()->values.edit(draft->{ draft.setRaw(0,99); draft.setRaw(1,101); }));
        assertArrayEquals(new int[]{10,20},values.copyRaw());
        values.edit(draft->{ draft.setRaw(0,99); draft.setRaw(1,100); });
        assertArrayEquals(new int[]{99,100},values.copyRaw());
        assertThrows(IllegalArgumentException.class,()->ParameterSchema.builder().addRaw("test:a",101,0,100));
        assertThrows(IllegalArgumentException.class,()->ParameterSchema.builder().add("test:a",BigDecimal.ZERO,new BigDecimal("-0.001"),BigDecimal.ONE));
    }
    @Test void validatesIdsDuplicatesAndMaximumFieldCount() {
        assertThrows(IllegalArgumentException.class,()->ParameterSchema.builder().addRaw("plain",0));
        assertThrows(IllegalArgumentException.class,()->ParameterSchema.builder().addRaw("Test:a",0));
        assertThrows(IllegalArgumentException.class,()->ParameterSchema.builder().addRaw("test:a",0).addRaw("test:a",1));
        ParameterSchema.Builder builder=ParameterSchema.builder();
        for (int i=0;i<ParameterSchema.MAX_FIELDS;i++) builder.addRaw("test:f"+i,0);
        assertEquals(ParameterSchema.MAX_FIELDS,builder.build().size());
        assertThrows(IllegalArgumentException.class,()->builder.addRaw("test:overflow",0));
    }
}
