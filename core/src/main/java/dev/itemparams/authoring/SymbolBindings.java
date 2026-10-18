package dev.itemparams.authoring;

import com.ezylang.evalex.config.ExpressionConfiguration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validated presentation names; canonical equations never persist these mutable names. */
final class SymbolBindings {
  private static final Set<String> TEXT_OPERATORS =
      Set.of("AND", "OR", "NOT", "XOR", "NAND", "NOR", "XNOR", "MOD");
  private final Map<String, InputField> inputBySymbol = new HashMap<>();
  private final Map<String, Integer> outputBySymbol = new HashMap<>();
  private final Map<InputField, String> inputNames = new HashMap<>();
  private final Map<String, String> outputNames = new HashMap<>();

  SymbolBindings(
      List<InputField> inputs,
      List<String> outputs,
      Map<InputField, String> inputSymbols,
      Map<String, String> outputSymbols,
      ExpressionConfiguration vocabulary) {
    Objects.requireNonNull(inputSymbols, "inputSymbols");
    Objects.requireNonNull(outputSymbols, "outputSymbols");
    for (InputField key : inputSymbols.keySet())
      if (!inputs.contains(key))
        throw new IllegalArgumentException(
            "Обозначение ссылается на отсутствующее входное поле: " + key);
    for (String key : outputSymbols.keySet())
      if (!outputs.contains(key))
        throw new IllegalArgumentException(
            "Обозначение ссылается на отсутствующее выходное поле: " + key);
    for (int i = 0; i < inputs.size(); i++) {
      InputField field = inputs.get(i);
      String name = validate(inputSymbols.get(field), i + 1, vocabulary);
      if (name == null) continue;
      if (inputBySymbol.putIfAbsent(name, field) != null)
        throw new IllegalArgumentException(
            "Входное обозначение «" + name + "» назначено двум полям. Задайте разные обозначения");
      inputNames.put(field, name);
    }
    for (int i = 0; i < outputs.size(); i++) {
      String field = outputs.get(i);
      String name = validate(outputSymbols.get(field), i + 1, vocabulary);
      if (name == null) continue;
      if (outputBySymbol.putIfAbsent(name, i) != null)
        throw new IllegalArgumentException(
            "Выходное обозначение «" + name + "» назначено двум полям. Задайте разные обозначения");
      outputNames.put(field, name);
    }
  }

  InputField input(String name) {
    return inputBySymbol.get(name);
  }

  Integer output(String name) {
    return outputBySymbol.get(name);
  }

  String inputName(InputField field, int position) {
    return inputNames.getOrDefault(field, Integer.toString(position));
  }

  String outputName(String field, int position) {
    return outputNames.getOrDefault(field, Integer.toString(position));
  }

  private static String validate(String value, int position, ExpressionConfiguration vocabulary) {
    if (value == null || value.isBlank()) return null;
    String name = value.trim();
    if (name.matches("[0-9]+")) {
      if (name.equals(Integer.toString(position))) return null;
      throw new IllegalArgumentException(
          "Числовое обозначение поля должно совпадать с его номером "
              + position
              + ". Для другого обозначения используйте буквы, например M или масса");
    }
    if (name.length() > 64 || !start(name.charAt(0))) throw invalid(name);
    for (int i = 1; i < name.length(); i++) {
      char character = name.charAt(i);
      if (!start(character) && !Character.isDigit(character)) throw invalid(name);
    }
    var operators = vocabulary.getOperatorDictionary();
    if (name.toUpperCase(Locale.ROOT).startsWith("IPAUTHOR")
        || TEXT_OPERATORS.contains(name.toUpperCase(Locale.ROOT))
        || vocabulary.getFunctionDictionary().hasFunction(name)
        || operators.hasPrefixOperator(name)
        || operators.hasPostfixOperator(name)
        || operators.hasInfixOperator(name))
      throw new IllegalArgumentException(
          "Обозначение «" + name + "» занято математической функцией или служебной командой");
    return name;
  }

  private static boolean start(char character) {
    return Character.isLetter(character) || character == '_';
  }

  private static IllegalArgumentException invalid(String name) {
    return new IllegalArgumentException(
        "Недопустимое обозначение «"
            + name
            + "»: используйте до 64 букв, цифр или _, начиная с буквы или _. Например M, m2, ρ,"
            + " масса");
  }
}
