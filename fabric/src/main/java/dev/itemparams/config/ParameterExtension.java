package dev.itemparams.config;

/** Register under the Fabric entrypoint "itemparams:extensions". Invoked for each reload. */
public interface ParameterExtension {
    void configure(RuntimeBuilder builder);
}
