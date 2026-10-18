package dev.itemparams.production;

/** A recipe could not produce a valid result; its input remains unchanged. */
public final class ProductionException extends IllegalArgumentException {
    public ProductionException(String message, Throwable cause) {
        super(message, cause);
    }
}
