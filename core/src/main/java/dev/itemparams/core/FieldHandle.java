package dev.itemparams.core;

/** Pre-resolved field index bound to one immutable schema instance. */
public final class FieldHandle {
    final ParameterSchema schema;
    final int index;
    FieldHandle(ParameterSchema schema,int index) { this.schema=schema; this.index=index; }
    public String id() { return schema.idAt(index); }
    public int index() { return index; }
    public ParameterSchema schema() { return schema; }
    public int minRaw() { return schema.minRawAt(index); }
    public int maxRaw() { return schema.maxRawAt(index); }
}
