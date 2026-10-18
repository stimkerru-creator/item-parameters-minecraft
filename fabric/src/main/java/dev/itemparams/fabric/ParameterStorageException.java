package dev.itemparams.fabric;

/** The existing item payload is incompatible or malformed; it is never silently reset. */
public final class ParameterStorageException extends IllegalArgumentException {
    public ParameterStorageException(String message) {
        super(message);
    }
}
