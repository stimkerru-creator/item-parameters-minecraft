package dev.itemparams.authoring;

import com.ezylang.evalex.config.ExpressionConfiguration;
import com.ezylang.evalex.functions.basic.SqrtFunction;
import com.ezylang.evalex.parser.ParseException;
import com.ezylang.evalex.parser.Token;
import com.ezylang.evalex.parser.Tokenizer;
import dev.itemparams.core.ParameterSchema;
import dev.itemparams.formula.FormulaCompiler;
import dev.itemparams.formula.InputLayout;
import dev.itemparams.production.ParameterRecipe;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authoring notation: {@code 1 * 2 = 3} or {@code M * ρ = V}. Bare positive integers on the left
 * identify input rows; constants are bracketed ({@code [0.95]}). Destinations accept output row
 * numbers or assigned symbols. Symbols bind to stable IDs before mathematical compilation. This
 * adapter only binds rows to IDs. EvalEx owns tokenization and FormulaCompiler owns all
 * mathematical validation, compilation and execution through the existing Spire backend.
 */
public final class NumberedFormulas {
  public static final int MAX_LINE_LENGTH = FormulaCompiler.MAX_SOURCE_LENGTH;
  public static final int MAX_LINES = ParameterSchema.MAX_FIELDS;
  public static final int MAX_DOCUMENT_LENGTH = 1_048_576;
  private static final Pattern POSITIVE_INDEX = Pattern.compile("[1-9][0-9]*");
  private static final Pattern DECIMAL =
      Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
  private static final Pattern CONSTANT = Pattern.compile("\\[([^\\[\\]]*)\\]");
  private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}:]+)::([^}]+)}");
  private static final Pattern INTERNAL_NAME =
      Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])IPAUTHOR[\\p{L}\\p{N}_]*");
  private static final String CONSTANT_PREFIX = "IPAUTHORCONSTANT";
  private static final String FIELD_PREFIX = "IPAUTHORFIELD";

  // These definitions are only tokenizer vocabulary, never an arithmetic implementation.
  @SuppressWarnings("unchecked")
  private static final ExpressionConfiguration TOKEN_CONFIGURATION =
      ExpressionConfiguration.defaultConfiguration()
          .withAdditionalFunctions(
              Map.entry("pow", new SqrtFunction()),
              Map.entry("root", new SqrtFunction()),
              Map.entry("clamp", new SqrtFunction()))
          .toBuilder()
          .arraysAllowed(false)
          .structuresAllowed(false)
          .implicitMultiplicationAllowed(false)
          .build();

  private NumberedFormulas() {}

  /** Flatten named input arrays in layout order for presentation; saved bindings use IDs. */
  public static List<InputField> inputFields(InputLayout layout) {
    Objects.requireNonNull(layout, "layout");
    List<InputField> result = new ArrayList<>();
    for (int role = 0; role < layout.size(); role++)
      for (int field = 0; field < layout.schemaAt(role).size(); field++)
        result.add(new InputField(layout.nameAt(role), layout.schemaAt(role).idAt(field)));
    return List.copyOf(result);
  }

  public static List<String> outputFields(ParameterSchema schema) {
    Objects.requireNonNull(schema, "schema");
    List<String> result = new ArrayList<>();
    for (int i = 0; i < schema.size(); i++) result.add(schema.idAt(i));
    return List.copyOf(result);
  }

  /** Bind one equation and validate its mathematics using the existing ready-library compiler. */
  public static BoundEquation bind(String line, List<InputField> inputs, List<String> outputs) {
    return bind(line, inputs, outputs, Map.of(), Map.of());
  }

  /** Optional presentation symbols are case-sensitive and independent in the two arrays. */
  public static BoundEquation bind(
      String line,
      List<InputField> inputs,
      List<String> outputs,
      Map<InputField, String> inputSymbols,
      Map<String, String> outputSymbols) {
    Objects.requireNonNull(line, "line");
    validateRows(inputs, outputs);
    SymbolBindings symbols =
        new SymbolBindings(inputs, outputs, inputSymbols, outputSymbols, TOKEN_CONFIGURATION);
    return bindValidated(line, inputs, outputs, symbols);
  }

  private static BoundEquation bindValidated(
      String line, List<InputField> inputs, List<String> outputs, SymbolBindings symbols) {
    if (line.length() > MAX_LINE_LENGTH)
      throw problem("Формула слишком длинная: максимум " + MAX_LINE_LENGTH + " символов");
    if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0)
      throw problem("Одна формула должна занимать одну строку");
    int equals = line.indexOf('=');
    if (equals < 0 || equals != line.lastIndexOf('='))
      throw problem(
          "Нужен один знак =: слева действие, справа номер или обозначение поля результата");
    String left = line.substring(0, equals).trim();
    if (left.isEmpty()) throw problem("Слева от = укажите действие или число в квадратных скобках");
    if (INTERNAL_NAME.matcher(left).find())
      throw problem("Служебные обозначения IPAUTHOR вводить нельзя");
    String destination = line.substring(equals + 1).trim();
    Integer namedTarget = symbols.output(destination);
    int target;
    if (namedTarget != null) target = namedTarget;
    else if (destination.matches("[0-9]+"))
      target = index(destination, outputs.size(), "выходного");
    else
      throw problem(
          "Неизвестное выходное обозначение «"
              + destination
              + "». Укажите номер или обозначение из правого списка");
    Map<String, String> constants = new HashMap<>();
    Matcher matcher = CONSTANT.matcher(left);
    StringBuffer masked = new StringBuffer();
    while (matcher.find()) {
      String literal = matcher.group(1).trim();
      validateLiteral(literal);
      String name = CONSTANT_PREFIX + constants.size();
      constants.put(name, literal);
      matcher.appendReplacement(masked, name);
    }
    matcher.appendTail(masked);
    StringBuilder canonical = new StringBuilder();
    for (Token token : tokens(masked.toString())) {
      String value = token.getValue();
      switch (token.getType()) {
        case NUMBER_LITERAL ->
            value = inputs.get(index(value, inputs.size(), "входного")).canonicalReference();
        case VARIABLE_OR_CONSTANT -> {
          String literal = constants.get(value);
          if (literal != null) value = "(" + literal + ")";
          else {
            InputField field = symbols.input(value);
            if (field == null)
              throw problem(
                  "Неизвестное входное обозначение «"
                      + value
                      + "». Укажите номер или обозначение из левого списка; регистр букв имеет"
                      + " значение");
            value = field.canonicalReference();
          }
        }
        case FUNCTION, INFIX_OPERATOR, PREFIX_OPERATOR, BRACE_OPEN, BRACE_CLOSE, COMMA -> {}
        default -> throw problem("Недопустимая запись: «" + value + "»");
      }
      if (!canonical.isEmpty()) canonical.append(' ');
      canonical.append(value);
    }
    String expression = canonical.toString();
    if (expression.length() > MAX_LINE_LENGTH)
      throw problem("Формула после связывания полей слишком длинная; разделите обработку");
    FormulaCompiler.compileInputs(layoutOf(inputs), expression);
    return new BoundEquation(expression, outputs.get(target), references(expression));
  }

  /**
   * Blank lines are ignored; duplicate destinations are rejected rather than silently overwritten.
   */
  public static List<BoundEquation> bindAll(
      String document, List<InputField> inputs, List<String> outputs) {
    return bindAll(document, inputs, outputs, Map.of(), Map.of());
  }

  public static List<BoundEquation> bindAll(
      String document,
      List<InputField> inputs,
      List<String> outputs,
      Map<InputField, String> inputSymbols,
      Map<String, String> outputSymbols) {
    Objects.requireNonNull(document, "document");
    validateRows(inputs, outputs);
    SymbolBindings symbols =
        new SymbolBindings(inputs, outputs, inputSymbols, outputSymbols, TOKEN_CONFIGURATION);
    if (document.length() > MAX_DOCUMENT_LENGTH)
      throw problem("Документ с формулами слишком большой");
    String[] lines = document.split("\\R", -1);
    if (lines.length > MAX_LINES)
      throw problem("Слишком много строк с формулами: максимум " + MAX_LINES);
    List<BoundEquation> result = new ArrayList<>();
    Set<String> targets = new LinkedHashSet<>();
    for (int line = 0; line < lines.length; line++) {
      if (lines[line].isBlank()) continue;
      try {
        BoundEquation equation = bindValidated(lines[line], inputs, outputs, symbols);
        if (!targets.add(equation.outputFieldId()))
          throw problem("Одно выходное поле назначено дважды. Оставьте для него одну формулу");
        result.add(equation);
      } catch (IllegalArgumentException failure) {
        throw new IllegalArgumentException(
            "Строка " + (line + 1) + ": " + failure.getMessage(), failure);
      }
    }
    return List.copyOf(result);
  }

  /** Restore a canonical saved assignment without reinterpreting its old display positions. */
  public static BoundEquation fromCanonical(String expression, String outputFieldId) {
    Objects.requireNonNull(expression, "expression");
    if (expression.length() > MAX_LINE_LENGTH) throw problem("Сохранённая формула слишком длинная");
    return new BoundEquation(expression, outputFieldId, references(expression));
  }

  /** All runtime behavior comes from the existing single/multi-input recipe compiler. */
  public static ParameterRecipe compile(
      String id,
      InputLayout inputs,
      ParameterSchema output,
      List<BoundEquation> equations,
      String copyCommonFrom) {
    Objects.requireNonNull(equations, "equations");
    if (equations.size() > MAX_LINES) throw problem("Слишком много выходных формул");
    ParameterRecipe.Builder builder = ParameterRecipe.builder(id, inputs, output);
    if (copyCommonFrom != null) builder.copyCommonFrom(copyCommonFrom);
    for (BoundEquation equation : equations) {
      if (output.indexOf(equation.outputFieldId()) < 0)
        throw problem("Удалено выходное поле формулы: " + equation.outputFieldId());
      for (InputField reference : equation.references()) {
        int role;
        try {
          role = inputs.requireIndex(reference.role());
        } catch (IllegalArgumentException missing) {
          throw problem("Удалён входной предмет формулы: " + reference.role());
        }
        if (inputs.schemaAt(role).indexOf(reference.fieldId()) < 0)
          throw problem("Удалено входное поле формулы: " + reference.fieldId());
      }
      builder.assign(equation.outputFieldId(), equation.canonicalExpression());
    }
    return builder.build();
  }

  static String display(BoundEquation equation, List<InputField> inputs, List<String> outputs) {
    return display(equation, inputs, outputs, Map.of(), Map.of());
  }

  static String display(
      BoundEquation equation,
      List<InputField> inputs,
      List<String> outputs,
      Map<InputField, String> inputSymbols,
      Map<String, String> outputSymbols) {
    validateRows(inputs, outputs);
    SymbolBindings symbols =
        new SymbolBindings(inputs, outputs, inputSymbols, outputSymbols, TOKEN_CONFIGURATION);
    int target = outputs.indexOf(equation.outputFieldId());
    if (target < 0) throw problem("Удалено выходное поле формулы: " + equation.outputFieldId());
    Map<String, String> positions = new HashMap<>();
    Matcher matcher = REFERENCE.matcher(equation.canonicalExpression());
    StringBuffer masked = new StringBuffer();
    while (matcher.find()) {
      InputField field = new InputField(matcher.group(1), matcher.group(2));
      int position = inputs.indexOf(field);
      if (position < 0)
        throw problem("Удалено входное поле формулы: " + field.role() + " / " + field.fieldId());
      String name = FIELD_PREFIX + positions.size();
      positions.put(name, symbols.inputName(field, position + 1));
      matcher.appendReplacement(masked, name);
    }
    matcher.appendTail(masked);
    StringBuilder display = new StringBuilder();
    List<Token> displayTokens = tokens(masked.toString());
    for (int i = 0; i < displayTokens.size(); i++) {
      Token token = displayTokens.get(i);
      String value = token.getValue();
      // Collapse only an isolated parenthesized literal, not an arithmetic expression.
      // This preserves negative constants and prevents parentheses accumulating on each edit.
      if (token.getType() == Token.TokenType.BRACE_OPEN) {
        int cursor = i + 1;
        String sign = "";
        if (cursor < displayTokens.size()
            && displayTokens.get(cursor).getType() == Token.TokenType.PREFIX_OPERATOR
            && Set.of("+", "-").contains(displayTokens.get(cursor).getValue()))
          sign = displayTokens.get(cursor++).getValue();
        if (cursor + 1 < displayTokens.size()
            && displayTokens.get(cursor).getType() == Token.TokenType.NUMBER_LITERAL
            && displayTokens.get(cursor + 1).getType() == Token.TokenType.BRACE_CLOSE) {
          value = "[" + sign + displayTokens.get(cursor).getValue() + "]";
          i = cursor + 1;
        }
      } else if (token.getType() == Token.TokenType.NUMBER_LITERAL) value = "[" + value + "]";
      else if (token.getType() == Token.TokenType.VARIABLE_OR_CONSTANT) {
        String position = positions.get(value);
        if (position == null)
          throw problem("Неизвестное обозначение в сохранённой формуле: " + value);
        value = position;
      }
      if (!display.isEmpty()) display.append(' ');
      display.append(value);
    }
    return display
        .append(" = ")
        .append(symbols.outputName(equation.outputFieldId(), target + 1))
        .toString();
  }

  /** Validate a proposed rename before any caller mutates its stored aliases or equations. */
  public static void validateSymbols(
      List<InputField> inputs,
      List<String> outputs,
      Map<InputField, String> inputSymbols,
      Map<String, String> outputSymbols) {
    validateRows(inputs, outputs);
    new SymbolBindings(inputs, outputs, inputSymbols, outputSymbols, TOKEN_CONFIGURATION);
  }

  static List<InputField> references(String expression) {
    LinkedHashSet<InputField> result = new LinkedHashSet<>();
    Matcher matcher = REFERENCE.matcher(expression);
    while (matcher.find()) result.add(new InputField(matcher.group(1), matcher.group(2)));
    return List.copyOf(result);
  }

  private static InputLayout layoutOf(List<InputField> rows) {
    Map<String, ParameterSchema.Builder> builders = new LinkedHashMap<>();
    for (InputField row : rows)
      builders
          .computeIfAbsent(row.role(), ignored -> ParameterSchema.builder())
          .addRaw(row.fieldId(), 0);
    Map<String, ParameterSchema> schemas = new LinkedHashMap<>();
    builders.forEach((name, builder) -> schemas.put(name, builder.build()));
    if (schemas.isEmpty()) schemas.put("input", ParameterSchema.builder().build());
    return InputLayout.of(schemas);
  }

  private static List<Token> tokens(String expression) {
    try {
      return new Tokenizer(expression, TOKEN_CONFIGURATION).parse();
    } catch (ParseException failure) {
      throw new IllegalArgumentException(
          "Проверьте запись формулы около символа "
              + failure.getStartPosition()
              + ": "
              + failure.getMessage(),
          failure);
    }
  }

  private static int index(String text, int size, String side) {
    if (!POSITIVE_INDEX.matcher(text).matches())
      throw problem(
          "Номер " + side + " поля — целое число от 1. Числа-значения пишите в [скобках]");
    try {
      int position = Integer.parseInt(text);
      if (position <= size) return position - 1;
    } catch (NumberFormatException ignored) {
      // An oversized position is the same user error as an index beyond the displayed list.
    }
    throw problem("В списке нет " + side + " поля № " + text + "; доступно полей: " + size);
  }

  private static void validateLiteral(String literal) {
    if (!DECIMAL.matcher(literal).matches())
      throw problem("В [скобках] должно быть одно число, например [25], [-0.2] или [1e-3]");
    try {
      BigDecimal value = new BigDecimal(literal);
      if (value.precision() > 1234 || Math.abs((long) value.scale()) > 1234)
        throw problem("Число в [скобках] слишком большое");
    } catch (NumberFormatException failure) {
      throw problem("Некорректное число в [скобках]");
    }
  }

  private static void validateRows(List<InputField> inputs, List<String> outputs) {
    Objects.requireNonNull(inputs, "inputs");
    Objects.requireNonNull(outputs, "outputs");
    if (new LinkedHashSet<>(inputs).size() != inputs.size())
      throw problem("Одно входное поле присутствует в списке дважды");
    if (new LinkedHashSet<>(outputs).size() != outputs.size())
      throw problem("Одно выходное поле присутствует в списке дважды");
    inputs.forEach(Objects::requireNonNull);
    outputs.forEach(ParameterSchema::validateId);
  }

  private static IllegalArgumentException problem(String message) {
    return new IllegalArgumentException(message);
  }
}
