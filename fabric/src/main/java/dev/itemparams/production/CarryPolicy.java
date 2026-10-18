package dev.itemparams.production;

/** Treatment of input parameter fields not assigned by a recipe. Arbitrary item NBT never carries. */
public enum CarryPolicy {
    COPY_INPUT,
    DEFAULTS
}
