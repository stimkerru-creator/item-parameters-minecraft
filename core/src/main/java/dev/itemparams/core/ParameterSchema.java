package dev.itemparams.core;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable ordered field dictionary. Saved IDs, never registration order, establish persistence. */
public final class ParameterSchema {
    public static final int MAX_FIELDS = 4096;
    public static final int MAX_ID_LENGTH = 256;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private final String[] ids;
    private final int[] defaults;
    private final int[] minimums,maximums;
    private final Map<String,Integer> indices;

    private ParameterSchema(LinkedHashMap<String,Definition> fields) {
        ids = fields.keySet().toArray(String[]::new);
        defaults = new int[ids.length];
        minimums=new int[ids.length]; maximums=new int[ids.length];
        Map<String,Integer> indices = new LinkedHashMap<>();
        for (int i=0;i<ids.length;i++) { Definition f=fields.get(ids[i]); defaults[i]=f.value; minimums[i]=f.min; maximums[i]=f.max; indices.put(ids[i],i); }
        this.indices=Map.copyOf(indices);
    }
    public static Builder builder() { return new Builder(); }
    public int size() { return ids.length; }
    public String idAt(int index) { return ids[index]; }
    public int defaultRawAt(int index) { return defaults[index]; }
    public int minRawAt(int index) { return minimums[index]; }
    public int maxRawAt(int index) { return maximums[index]; }
    public void validateRaw(int index,int value) {
        if (value<minimums[index] || value>maximums[index])
            throw new IllegalArgumentException("Parameter " + ids[index] + " is outside configured range");
    }
    public int indexOf(String id) { return indices.getOrDefault(id,-1); }
    public int requireIndex(String id) {
        int index=indexOf(id);
        if (index<0) throw new IllegalArgumentException("Unknown parameter: " + id);
        return index;
    }
    public ParameterValues defaults() { return ParameterValues.defaults(this); }
    public FieldHandle field(String id) { return new FieldHandle(this,requireIndex(id)); }
    public static void validateId(String id) {
        Objects.requireNonNull(id,"id");
        if (id.length()>MAX_ID_LENGTH || !ID.matcher(id).matches())
            throw new IllegalArgumentException("Expected namespaced parameter ID: " + id);
    }

    public static final class Builder {
        private final LinkedHashMap<String,Definition> fields=new LinkedHashMap<>();
        private Builder() {}
        /** Adds an already encoded default; decimal defaults use the BigDecimal overload. */
        public Builder addRaw(String id,int defaultRaw) { return addRaw(id,defaultRaw,Integer.MIN_VALUE,Integer.MAX_VALUE); }
        public Builder addRaw(String id,int defaultRaw,int minRaw,int maxRaw) {
            validateId(id);
            if (fields.containsKey(id)) throw new IllegalArgumentException("Duplicate parameter: " + id);
            if (fields.size()>=MAX_FIELDS) throw new IllegalArgumentException("Schema exceeds " + MAX_FIELDS + " fields");
            if (minRaw>maxRaw || defaultRaw<minRaw || defaultRaw>maxRaw)
                throw new IllegalArgumentException("Invalid default/min/max for " + id);
            fields.put(id,new Definition(defaultRaw,minRaw,maxRaw)); return this;
        }
        public Builder add(String id,BigDecimal defaultValue) { return addRaw(id,Numeric.encode(defaultValue)); }
        public Builder add(String id,double defaultValue) { return addRaw(id,Numeric.encode(defaultValue)); }
        public Builder add(String id,BigDecimal defaultValue,BigDecimal minimum,BigDecimal maximum) {
            return addRaw(id,Numeric.encodeExact(defaultValue),Numeric.encodeExact(minimum),Numeric.encodeExact(maximum));
        }
        public ParameterSchema build() { return new ParameterSchema(fields); }
    }
    private record Definition(int value,int min,int max) {}
}
