package dev.itemparams.authoring;

import dev.itemparams.core.ParameterSchema;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Saved assignment binds stable field IDs, never row numbers. The constructor is suitable for
 * project serialization; compilation validates the saved binding against the actual schemas.
 */
public record BoundEquation(
    String canonicalExpression, String outputFieldId, List<InputField> references) {
  public BoundEquation {
    Objects.requireNonNull(canonicalExpression, "canonicalExpression");
    if (canonicalExpression.isBlank()
        || canonicalExpression.length() > NumberedFormulas.MAX_LINE_LENGTH)
      throw new IllegalArgumentException("Сохранённая формула пуста или слишком длинна");
    ParameterSchema.validateId(outputFieldId);
    references = List.copyOf(references);
    if (!references.equals(NumberedFormulas.references(canonicalExpression)))
      throw new IllegalArgumentException("Сохранённые связи полей не соответствуют формуле");
  }

  /** Render with the current display order; missing fields are reported rather than rebound. */
  public String display(List<InputField> inputs, List<String> outputs) {
    return NumberedFormulas.display(this, inputs, outputs);
  }

  /** Render current symbols without changing stable saved field identities. */
  public String display(
      List<InputField> inputs,
      List<String> outputs,
      Map<InputField, String> inputSymbols,
      Map<String, String> outputSymbols) {
    return NumberedFormulas.display(this, inputs, outputs, inputSymbols, outputSymbols);
  }
}
