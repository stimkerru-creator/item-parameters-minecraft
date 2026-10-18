package dev.itemparams.formula;

import dev.itemparams.core.ParameterValues;

/** Immutable role binding of mutable, caller-owned values. No parameter arrays are copied. */
public final class InputValues {
    private final InputLayout layout;
    private final ParameterValues[] values;
    InputValues(InputLayout layout,ParameterValues[] values) { this.layout=layout; this.values=values; }
    public InputLayout layout() { return layout; }
    public int size() { return values.length; }
    public ParameterValues valueAt(int index) { return values[index]; }
    public ParameterValues get(String name) { return valueAt(layout.requireIndex(name)); }
}
