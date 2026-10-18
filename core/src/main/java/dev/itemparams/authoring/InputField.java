package dev.itemparams.authoring;

import dev.itemparams.core.ParameterSchema;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identity of one displayed input row, independent of its current row number. */
public record InputField(String role, String fieldId) {
  private static final Pattern ROLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

  public InputField {
    Objects.requireNonNull(role, "role");
    if (role.length() > 256 || !ROLE.matcher(role).matches())
      throw new IllegalArgumentException("Недопустимое обозначение входного предмета: " + role);
    ParameterSchema.validateId(fieldId);
  }

  public String canonicalReference() {
    return "${" + role + "::" + fieldId + "}";
  }
}
