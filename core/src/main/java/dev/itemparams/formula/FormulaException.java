package dev.itemparams.formula;

/** Invalid expression or a runtime mathematical domain/range failure. Positions are zero based. */
public final class FormulaException extends IllegalArgumentException {
    private final int sourcePosition;

    public FormulaException(String message, int sourcePosition) {
        super(message + " at character " + (sourcePosition + 1));
        this.sourcePosition = sourcePosition;
    }

    public int sourcePosition() {
        return sourcePosition;
    }
}
