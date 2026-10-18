package dev.itemparams.core;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Mutable values owned by one caller/item. Not thread safe; copy before handing to another owner. */
public final class ParameterValues {
    private final ParameterSchema schema;
    private final int[] raw;
    private final Map<String,Integer> unknown;
    private long modificationVersion;
    private ParameterValues(ParameterSchema schema,int[] raw,Map<String,Integer> unknown) {
        this.schema=schema; this.raw=raw; this.unknown=unknown;
    }
    public static ParameterValues defaults(ParameterSchema schema) {
        Objects.requireNonNull(schema,"schema");
        int[] raw=new int[schema.size()];
        for (int i=0;i<raw.length;i++) raw[i]=schema.defaultRawAt(i);
        return new ParameterValues(schema,raw,Map.of());
    }
    public static ParameterValues fromRawMap(ParameterSchema schema,Map<String,Integer> saved) {
        Objects.requireNonNull(saved,"saved");
        if (saved.size()>ParameterSchema.MAX_FIELDS) throw new IllegalArgumentException("Saved parameters exceed field limit");
        ParameterValues defaults=defaults(schema);
        Map<String,Integer> unknown=new LinkedHashMap<>();
        for (Map.Entry<String,Integer> entry:saved.entrySet()) {
            ParameterSchema.validateId(entry.getKey());
            int value=Objects.requireNonNull(entry.getValue(),"Saved parameter value");
            int index=schema.indexOf(entry.getKey());
            if (index>=0) { schema.validateRaw(index,value); defaults.raw[index]=value; } else unknown.put(entry.getKey(),value);
        }
        if (schema.size()+unknown.size()>ParameterSchema.MAX_FIELDS)
            throw new IllegalArgumentException("Known and retained parameters exceed field limit");
        return new ParameterValues(schema,defaults.raw,Collections.unmodifiableMap(unknown));
    }
    public ParameterSchema schema() { return schema; }
    /** Invalidation token for caller-owned decoded caches. Values remain thread confined. */
    public long modificationVersion() { return modificationVersion; }
    public int getRaw(int index) { return raw[index]; }
    public int getRaw(String id) { return getRaw(schema.requireIndex(id)); }
    public void setRaw(int index,int value) { schema.validateRaw(index,value); raw[index]=value; modificationVersion++; }
    public void setRaw(String id,int value) { setRaw(schema.requireIndex(id),value); }
    public int getRaw(FieldHandle field) { check(field); return raw[field.index]; }
    public void setRaw(FieldHandle field,int value) { check(field); setRaw(field.index,value); }
    private void check(FieldHandle field) {
        if (field.schema!=schema) throw new IllegalArgumentException("Field handle belongs to another schema");
    }
    /** Detached array; modifications never affect these values until replaceRaw is called. */
    public int[] copyRaw() { return raw.clone(); }
    /** Atomic validation then copy. Retained unknown fields are preserved. */
    public void replaceRaw(int[] values) {
        if (values.length!=raw.length) throw new IllegalArgumentException("Raw array length does not match schema");
        for (int i=0;i<values.length;i++) schema.validateRaw(i,values[i]);
        System.arraycopy(values,0,raw,0,raw.length);
        modificationVersion++;
    }
    /** Applies an edit to a detached copy, committing only after the callback succeeds. */
    public void edit(java.util.function.Consumer<ParameterValues> editor) {
        ParameterValues draft=copy(); editor.accept(draft); replaceRaw(draft.raw);
    }
    public BigDecimal get(String id) { return Numeric.decode(getRaw(id)); }
    public BigDecimal get(int index) { return Numeric.decode(getRaw(index)); }
    public void set(String id,BigDecimal value) { setRaw(id,Numeric.encode(value)); }
    public void set(String id,double value) { setRaw(id,Numeric.encode(value)); }
    public ParameterValues copy() { return new ParameterValues(schema,raw.clone(),unknown); }
    public Map<String,Integer> toRawMap() {
        Map<String,Integer> result=new LinkedHashMap<>();
        for (int i=0;i<raw.length;i++) result.put(schema.idAt(i),raw[i]);
        result.putAll(unknown);
        return Collections.unmodifiableMap(result);
    }
}
