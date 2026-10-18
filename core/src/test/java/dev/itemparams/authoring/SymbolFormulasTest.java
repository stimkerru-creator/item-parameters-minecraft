package dev.itemparams.authoring;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.formula.InputLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SymbolFormulasTest {
  private static final InputField MASS = new InputField("ore", "material:mass");
  private static final InputField DENSITY = new InputField("plate", "material:mass");
  private static final InputField ADDITIVE = new InputField("additive", "material:mass");
  private static final List<InputField> INPUTS = List.of(MASS, DENSITY, ADDITIVE);
  private static final List<String> OUTPUTS = List.of("product:mass", "product:strength");
  private static final Map<InputField, String> SYMBOLS =
      Map.of(MASS, "M", DENSITY, "m", ADDITIVE, "ρ");
  private static final Map<String, String> DESTINATIONS =
      Map.of(OUTPUTS.get(0), "M", OUTPUTS.get(1), "σ");

  @Test
  void threeItemsCaseSensitivePhysicsSymbolsUseTheSameReadyMath() {
    var inputSchemas = new LinkedHashMap<String, ParameterSchema>();
    inputSchemas.put("ore", ParameterSchema.builder().addRaw("material:mass", 1000).build());
    inputSchemas.put("plate", ParameterSchema.builder().addRaw("material:mass", 400).build());
    inputSchemas.put("additive", ParameterSchema.builder().addRaw("material:mass", 900).build());
    var layout = InputLayout.of(inputSchemas);
    var output =
        ParameterSchema.builder().addRaw(OUTPUTS.get(0), 0).addRaw(OUTPUTS.get(1), 0).build();
    String document = "M * m + sqrt(ρ) = M\nroot(M + m + ρ, [3]) ^ [3] = σ";
    var equations = NumberedFormulas.bindAll(document, INPUTS, OUTPUTS, SYMBOLS, DESTINATIONS);
    var recipe = NumberedFormulas.compile("test:symbols", layout, output, equations, null);
    var result =
        recipe.execute(
            layout.bind(
                Map.of(
                    "ore", inputSchemas.get("ore").defaults(),
                    "plate", inputSchemas.get("plate").defaults(),
                    "additive", inputSchemas.get("additive").defaults())));
    assertEquals(4300, result.getRaw(OUTPUTS.get(0)));
    assertEquals(2300, result.getRaw(OUTPUTS.get(1)));
    assertEquals(List.of(MASS, DENSITY, ADDITIVE), equations.get(0).references());
    assertTrue(equations.get(0).canonicalExpression().contains("${plate::material:mass}"));
  }

  @Test
  void rowsAndSymbolsAreInterchangeableAndSameSymbolInBothScopesIsAllowed() {
    var numbered = NumberedFormulas.bind("1 + 2 * 3 = 1", INPUTS, OUTPUTS);
    var symbolic = NumberedFormulas.bind("M + m * ρ = M", INPUTS, OUTPUTS, SYMBOLS, DESTINATIONS);
    var mixed = NumberedFormulas.bind("M + 2 * ρ = 1", INPUTS, OUTPUTS, SYMBOLS, DESTINATIONS);
    assertEquals(numbered, symbolic);
    assertEquals(numbered, mixed);
    assertEquals("M + m * ρ = M", symbolic.display(INPUTS, OUTPUTS, SYMBOLS, DESTINATIONS));
    assertEquals("1 + 2 * 3 = 1", symbolic.display(INPUTS, OUTPUTS));
  }

  @Test
  void unicodeNamesDigitsUnderscoresAndEnergyEAreSupported() {
    var names = Map.of(MASS, "масса", DENSITY, "E", ADDITIVE, "_ρ2");
    var equation =
        NumberedFormulas.bind(
            "масса + E + _ρ2 + [1e-3] = энергия",
            INPUTS,
            OUTPUTS,
            names,
            Map.of(OUTPUTS.get(0), "энергия"));
    assertEquals(3, equation.references().size());
    assertEquals(
        equation,
        NumberedFormulas.bind(
            equation.display(INPUTS, OUTPUTS, names, Map.of(OUTPUTS.get(0), "энергия")),
            INPUTS,
            OUTPUTS,
            names,
            Map.of(OUTPUTS.get(0), "энергия")));
  }

  @Test
  void blanksAndMatchingNumericAliasesUseDefaultPositions() {
    Map<InputField, String> names = Map.of(MASS, " ", DENSITY, "2");
    var equation =
        NumberedFormulas.bind("1 + 2 = 1", INPUTS, OUTPUTS, names, Map.of(OUTPUTS.get(0), "1"));
    assertEquals(
        "1 + 2 = 1", equation.display(INPUTS, OUTPUTS, names, Map.of(OUTPUTS.get(0), "1")));
  }

  @Test
  void renameAndReorderRegenerateTextWithoutChangingCanonicalBindings() {
    var equation = NumberedFormulas.bind("M - m = M", INPUTS, OUTPUTS, SYMBOLS, DESTINATIONS);
    var reordered = List.of(ADDITIVE, DENSITY, MASS);
    var reverseOutputs = List.of(OUTPUTS.get(1), OUTPUTS.get(0));
    var newNames = Map.of(MASS, "масса", DENSITY, "пластина");
    var newTargets = Map.of(OUTPUTS.get(0), "результат");
    String text = equation.display(reordered, reverseOutputs, newNames, newTargets);
    assertEquals("масса - пластина = результат", text);
    assertEquals(
        equation, NumberedFormulas.bind(text, reordered, reverseOutputs, newNames, newTargets));
    assertEquals("3 - 2 = 2", equation.display(reordered, reverseOutputs));
  }

  @Test
  void unknownNamesAndRemovedFieldsFailPredictably() {
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> NumberedFormulas.bind("mass = M", INPUTS, OUTPUTS, SYMBOLS, DESTINATIONS))
            .getMessage()
            .contains("mass"));
    assertThrows(
        IllegalArgumentException.class,
        () -> NumberedFormulas.bind("M = unknown", INPUTS, OUTPUTS, SYMBOLS, DESTINATIONS));
    var equation = NumberedFormulas.bind("M = M", INPUTS, OUTPUTS, SYMBOLS, DESTINATIONS);
    assertThrows(IllegalArgumentException.class, () -> equation.display(List.of(DENSITY), OUTPUTS));
    assertThrows(
        IllegalArgumentException.class, () -> equation.display(INPUTS, List.of(OUTPUTS.get(1))));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sqrt",
        "SQRT",
        "pow",
        "root",
        "min",
        "max",
        "clamp",
        "AND",
        "Or",
        "NOT",
        "XOR",
        "IPAUTHORCONSTANT0",
        "ipauthorfield1",
        "12",
        "01",
        "2",
        "1M",
        "M m",
        "a-b",
        "a.b",
        "[2]",
        "😀"
      })
  void unsafeAndAmbiguousSymbolsAreRejected(String name) {
    assertThrows(
        IllegalArgumentException.class,
        () -> NumberedFormulas.validateSymbols(INPUTS, OUTPUTS, Map.of(MASS, name), Map.of()));
  }

  @Test
  void duplicatesStaleMappingsAndOverlongSymbolsFailBeforeMutation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NumberedFormulas.validateSymbols(
                INPUTS, OUTPUTS, Map.of(MASS, "M", DENSITY, "M"), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NumberedFormulas.validateSymbols(
                INPUTS, OUTPUTS, Map.of(), Map.of(OUTPUTS.get(0), "M", OUTPUTS.get(1), "M")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NumberedFormulas.validateSymbols(
                INPUTS, OUTPUTS, Map.of(new InputField("other", "material:mass"), "M"), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NumberedFormulas.validateSymbols(
                INPUTS, OUTPUTS, Map.of(), Map.of("product:missing", "M")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NumberedFormulas.validateSymbols(
                INPUTS, OUTPUTS, Map.of(MASS, "M".repeat(65)), Map.of()));
    assertDoesNotThrow(
        () ->
            NumberedFormulas.validateSymbols(
                INPUTS, OUTPUTS, Map.of(MASS, "M".repeat(64)), Map.of()));
  }

  @Test
  void repeatedAliasesForTheSameOutputCannotOverwriteEarlierEquation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> NumberedFormulas.bindAll("M = M\nm = 1", INPUTS, OUTPUTS, SYMBOLS, DESTINATIONS));
  }

  @Test
  void renamedSymbolsCannotInjectServiceConstantsAndBracketNumbersRemainNumbers() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NumberedFormulas.bind(
                "[1] + ipauthorconstant0 = M", INPUTS, OUTPUTS, SYMBOLS, DESTINATIONS));
    var equation =
        NumberedFormulas.bind("M / [3] * [3] = M", INPUTS, OUTPUTS, SYMBOLS, DESTINATIONS);
    assertEquals(
        equation,
        NumberedFormulas.bind(
            equation.display(INPUTS, OUTPUTS, SYMBOLS, DESTINATIONS),
            INPUTS,
            OUTPUTS,
            SYMBOLS,
            DESTINATIONS));
    assertTrue(equation.canonicalExpression().contains("(3)"));
  }
}
