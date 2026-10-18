package dev.itemparams.authoring;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.formula.InputLayout;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NumberedFormulasTest {
  private static final List<InputField> INPUTS =
      List.of(new InputField("ore", "material:mass"), new InputField("ore", "material:purity"));
  private static final List<String> OUTPUTS = List.of("product:mass", "product:purity");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0=1",
        "3=1",
        "1=0",
        "1=3",
        "1=1.0",
        "1.0=1",
        "1e0=1",
        "01=1",
        "0x1=1",
        "1+0.95=1",
        "1+[1/3]=1",
        "1+[1+2]=1",
        "1+[]=1",
        "1+[[2]]=1",
        "1=1=2",
        "1+2",
        "=1",
        "1=",
        "mass=1",
        "1+[NaN]=1",
        "1+[Infinity]=1",
    "1+[1]+IPAUTHORCONSTANT0=1",
    "ipauthorconstant0+[25]=1",
    "IpAuthorConstant0+[25]=1",
    "PI=1",
    "E=1",
        "[1e2000]=1",
        "1+[1,2]=1",
        "1+random()=1"
      })
  void invalidIndicesConstantsAndCapabilitiesAreRejected(String text) {
    assertThrows(
        IllegalArgumentException.class, () -> NumberedFormulas.bind(text, INPUTS, OUTPUTS));
  }

  @Test
  void negativeLiteralAndNegatedPositiveLiteralPreserveDistinctPowerSemantics() {
    ParameterSchema source =
        ParameterSchema.builder()
            .addRaw("material:mass", 100)
            .addRaw("material:purity", 200)
            .build();
    ParameterSchema output =
        ParameterSchema.builder().addRaw("product:mass", 0).addRaw("product:purity", 0).build();
    InputLayout layout = InputLayout.of(Map.of("ore", source));
    List<BoundEquation> equations =
        NumberedFormulas.bindAll("[-2] ^ [2] = 1\n-[2] ^ [2] = 2", INPUTS, OUTPUTS);
    for (int i = 0; i < 200; i++) {
      equations =
          equations.stream()
              .map(e -> NumberedFormulas.bind(e.display(INPUTS, OUTPUTS), INPUTS, OUTPUTS))
              .toList();
    }
    var recipe = NumberedFormulas.compile("test:negative_power", layout, output, equations, null);
    var result = recipe.execute(layout.bind(Map.of("ore", source.defaults())));
    assertEquals(400, result.getRaw("product:mass"));
    assertEquals(-400, result.getRaw("product:purity"));
    assertTrue(equations.get(0).display(INPUTS, OUTPUTS).contains("[-2]"));
    assertTrue(equations.get(0).canonicalExpression().length() < 30);
  }

  @Test
  void scientificConstantsAndInputSubtractionAreUnambiguous() {
    var equation = NumberedFormulas.bind("1 - 2 * [1e-3] + [-0.2] = 1", INPUTS, OUTPUTS);
    assertEquals(List.copyOf(INPUTS), equation.references());
    assertTrue(equation.canonicalExpression().contains("(1e-3)"));
    assertTrue(equation.canonicalExpression().contains("(-0.2)"));
    assertEquals(
        equation, NumberedFormulas.fromCanonical(equation.canonicalExpression(), OUTPUTS.get(0)));
    assertEquals(
        equation, NumberedFormulas.bind(equation.display(INPUTS, OUTPUTS), INPUTS, OUTPUTS));
  }

  @Test
  void immutableBindingsRejectForgedSavedReferences() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BoundEquation("${ore::material:mass}", "product:mass", List.of(INPUTS.get(1))));
    var equation = NumberedFormulas.bind("1=1", INPUTS, OUTPUTS);
    assertThrows(UnsupportedOperationException.class, () -> equation.references().clear());
  }

  @Test
  void lineNumberPointsToTheFailingEquationAndBlankLinesAreAllowed() {
    assertEquals(1, NumberedFormulas.bindAll("\n1=1\n\n", INPUTS, OUTPUTS).size());
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> NumberedFormulas.bindAll("1=1\n\n[1/3]=2", INPUTS, OUTPUTS));
    assertTrue(failure.getMessage().startsWith("Строка 3:"));
  }

  @Test
  void sourceAndDocumentLimitsApplyBeforeParsing() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NumberedFormulas.bind(
                " ".repeat(NumberedFormulas.MAX_LINE_LENGTH + 1), INPUTS, OUTPUTS));
    assertThrows(
        IllegalArgumentException.class,
        () -> NumberedFormulas.bindAll("\n".repeat(NumberedFormulas.MAX_LINES), INPUTS, OUTPUTS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NumberedFormulas.bindAll(
                " ".repeat(NumberedFormulas.MAX_DOCUMENT_LENGTH + 1), INPUTS, OUTPUTS));
  }

  @Test
  void flattenedRowsAndOutputRowsAreOrderedAndImmutable() {
    ParameterSchema a =
        ParameterSchema.builder().addRaw("part:mass", 0).addRaw("part:size", 0).build();
    ParameterSchema b = ParameterSchema.builder().addRaw("part:mass", 0).build();
    var ordered = new java.util.LinkedHashMap<String, ParameterSchema>();
    ordered.put("left", a);
    ordered.put("right", b);
    var fields = NumberedFormulas.inputFields(InputLayout.of(ordered));
    assertEquals(
        List.of(
            new InputField("left", "part:mass"),
            new InputField("left", "part:size"),
            new InputField("right", "part:mass")),
        fields);
    assertEquals(List.of("part:mass", "part:size"), NumberedFormulas.outputFields(a));
    assertThrows(UnsupportedOperationException.class, fields::clear);
  }

  @Test
  void duplicateDisplayRowsCannotSilentlyAliasDifferentNumbers() {
    assertThrows(
        IllegalArgumentException.class,
        () -> NumberedFormulas.bind("1=1", List.of(INPUTS.get(0), INPUTS.get(0)), OUTPUTS));
    assertThrows(
        IllegalArgumentException.class,
        () -> NumberedFormulas.bind("1=1", INPUTS, List.of(OUTPUTS.get(0), OUTPUTS.get(0))));
  }
}
