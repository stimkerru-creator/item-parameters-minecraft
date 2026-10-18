package dev.itemparams.formula;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable named input roles. Each role retains its own immutable parameter schema. */
public final class InputLayout {
    private static final Pattern NAME=Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
    private final String[] names;
    private final ParameterSchema[] schemas;
    private final Map<String,Integer> indices;

    private InputLayout(Map<String,ParameterSchema> inputs) {
        if (inputs.isEmpty()) throw new IllegalArgumentException("At least one input role is required");
        names=new String[inputs.size()]; schemas=new ParameterSchema[inputs.size()];
        Map<String,Integer> slots=new LinkedHashMap<>();
        int index=0;
        for (Map.Entry<String,ParameterSchema> entry:inputs.entrySet()) {
            String name=Objects.requireNonNull(entry.getKey(),"input name");
            if (name.length()>256 || !NAME.matcher(name).matches())
                throw new IllegalArgumentException("Invalid input role: " + name);
            names[index]=name; schemas[index]=Objects.requireNonNull(entry.getValue(),"input schema");
            slots.put(name,index++);
        }
        indices=Map.copyOf(slots);
    }
    public static InputLayout of(Map<String,ParameterSchema> inputs) {
        return new InputLayout(Objects.requireNonNull(inputs,"inputs"));
    }
    static InputLayout legacy(ParameterSchema schema) { return of(Map.of("input",schema)); }
    public int size() { return names.length; }
    public String nameAt(int index) { return names[index]; }
    public ParameterSchema schemaAt(int index) { return schemas[index]; }
    public int requireIndex(String name) {
        Integer index=indices.get(name);
        if (index==null) throw new IllegalArgumentException("Unknown input role: " + name);
        return index;
    }
    /** Binds references without copying parameter arrays; every role and exact schema must match. */
    public InputValues bind(Map<String,ParameterValues> inputs) {
        Objects.requireNonNull(inputs,"inputs");
        if (inputs.size()!=size()) throw new IllegalArgumentException("Inputs must contain exactly the declared roles");
        ParameterValues[] values=new ParameterValues[size()];
        for (int i=0;i<values.length;i++) {
            ParameterValues value=inputs.get(names[i]);
            if (value==null) throw new IllegalArgumentException("Missing input role: " + names[i]);
            if (value.schema()!=schemas[i]) throw new IllegalArgumentException("Input role " + names[i] + " requires its exact schema instance");
            values[i]=value;
        }
        return new InputValues(this,values);
    }
}
