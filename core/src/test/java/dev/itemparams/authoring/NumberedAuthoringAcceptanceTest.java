package dev.itemparams.authoring;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import dev.itemparams.formula.InputLayout;
import dev.itemparams.production.ParameterRecipe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Independent user-contract tests: array positions are presentation, IDs are identity. */
class NumberedAuthoringAcceptanceTest {
  private static final List<InputField> SOURCES = List.of(
      new InputField("ore", "material:mass"),
      new InputField("ore", "material:density"),
      new InputField("ore", "material:hardness"));
  private static final List<String> TARGETS = List.of(
      "product:mass", "product:strength", "product:volume");

  @Test void requestedTwoEquationsReadTheOriginalInputAndWriteDifferentOutputFields() {
    ParameterSchema source = source(false);
    ParameterSchema output = output(false);
    InputLayout layout = InputLayout.of(Map.of("ore", source));
    ParameterValues input = source.defaults();
    Map<String, Integer> before = input.toRawMap();
    List<BoundEquation> equations = NumberedFormulas.bindAll("1*2=3\n3+2=2", SOURCES, TARGETS);
    ParameterRecipe recipe = NumberedFormulas.compile("review:example", layout, output, equations, null);

    ParameterValues actual = recipe.execute(layout.bind(Map.of("ore", input)));
    assertEquals(4000, actual.getRaw("product:volume"));
    assertEquals(1100, actual.getRaw("product:strength"));
    assertEquals(991, actual.getRaw("product:mass"));
    assertEquals(before, input.toRawMap(), "Authoring must not turn assignments into input mutations");
  }

  @Test void appendAndReorderChangeOnlyVisibleNumbersNotSavedMeaning() {
    BoundEquation equation = NumberedFormulas.bind("1*2=3", SOURCES, TARGETS);
    List<InputField> reordered = List.of(SOURCES.get(2), SOURCES.get(1), SOURCES.get(0),
        new InputField("ore", "material:new_field"));
    List<String> targets = List.of(TARGETS.get(2), TARGETS.get(0), TARGETS.get(1), "product:new_field");
    assertEquals("3*2=1", equation.display(reordered, targets).replaceAll("\\s+", ""));

    ParameterSchema source = source(true);
    ParameterSchema output = output(true);
    InputLayout layout = InputLayout.of(Map.of("ore", source));
    ParameterRecipe recipe = NumberedFormulas.compile("review:reordered", layout, output,
        List.of(equation), null);
    assertEquals(4000, recipe.execute(layout.bind(Map.of("ore", source.defaults())))
        .getRaw("product:volume"));
  }

  @Test void deletedReferencedSourceAndDeletedTargetAreRejectedRatherThanRenumbered() {
    BoundEquation equation = NumberedFormulas.bind("1*2=3", SOURCES, TARGETS);
    assertThrows(IllegalArgumentException.class,
        () -> equation.display(List.of(SOURCES.get(1), SOURCES.get(2)), TARGETS));
    assertThrows(IllegalArgumentException.class,
        () -> equation.display(SOURCES, List.of(TARGETS.get(0), TARGETS.get(1))));
  }

  @Test void multipleNamedInputsWithIdenticalFieldIdsStayIndependentAfterRoleReorder() {
    ParameterSchema left = ParameterSchema.builder().addRaw("part:mass", 125).build();
    ParameterSchema right = ParameterSchema.builder().addRaw("part:mass", 250).build();
    ParameterSchema output = ParameterSchema.builder().addRaw("part:mass", 0).build();
    List<InputField> fields = List.of(new InputField("left", "part:mass"),
        new InputField("right", "part:mass"));
    List<BoundEquation> equations = NumberedFormulas.bindAll("1*[10]+2=1", fields,
        List.of("part:mass"));
    LinkedHashMap<String, ParameterSchema> reversed = new LinkedHashMap<>();
    reversed.put("right", right);
    reversed.put("left", left);
    InputLayout layout = InputLayout.of(reversed);
    ParameterRecipe recipe = NumberedFormulas.compile("review:roles", layout, output, equations, null);
    assertEquals(1500, recipe.execute(layout.bind(Map.of("left", left.defaults(),
        "right", right.defaults()))).getRaw("part:mass"));
  }

  @Test void finalOnlyTruncationUsesTheExistingExactBackend() {
    ParameterSchema source = source(false);
    ParameterSchema output = output(false);
    InputLayout layout = InputLayout.of(Map.of("ore", source));
    List<BoundEquation> equations = NumberedFormulas.bindAll(
        "([1]/[3])*[3]=1\nroot([2],[3])^[3]=2\n[0.129]=3", SOURCES, TARGETS);
    ParameterRecipe recipe = NumberedFormulas.compile("review:exact", layout, output, equations, null);
    ParameterValues actual = recipe.execute(layout.bind(Map.of("ore", source.defaults())));
    assertEquals(100, actual.getRaw("product:mass"));
    assertEquals(200, actual.getRaw("product:strength"));
    assertEquals(12, actual.getRaw("product:volume"));
  }

  @Test void duplicateOutputAssignmentsAreRejectedBeforeAnyExecution() {
    assertThrows(IllegalArgumentException.class,
        () -> NumberedFormulas.bindAll("1+2=1\n3=1", SOURCES, TARGETS));
  }

  @Test void errorsInOneOutputNeverMutateAnInputOrExposeAPartialOutput() {
    ParameterSchema source = source(false);
    ParameterSchema output = output(false);
    InputLayout layout = InputLayout.of(Map.of("ore", source));
    List<BoundEquation> equations = NumberedFormulas.bindAll("1+[10]=1\n2/[0]=2", SOURCES, TARGETS);
    ParameterValues input = source.defaults();
    Map<String, Integer> before = input.toRawMap();
    assertThrows(IllegalArgumentException.class, () -> {
      ParameterRecipe recipe = NumberedFormulas.compile("review:failure", layout, output, equations, null);
      recipe.execute(layout.bind(Map.of("ore", input)));
    });
    assertEquals(before, input.toRawMap());
  }

  private static ParameterSchema source(boolean reordered) {
    if (reordered) return ParameterSchema.builder().addRaw("material:new_field", 99999)
        .addRaw("material:hardness", 700).addRaw("material:density", 400)
        .addRaw("material:mass", 1000).build();
    return ParameterSchema.builder().addRaw("material:mass", 1000)
        .addRaw("material:density", 400).addRaw("material:hardness", 700).build();
  }

  private static ParameterSchema output(boolean reordered) {
    if (reordered) return ParameterSchema.builder().addRaw("product:new_field", 0)
        .addRaw("product:volume", 993).addRaw("product:mass", 991)
        .addRaw("product:strength", 992).build();
    return ParameterSchema.builder().addRaw("product:mass", 991)
        .addRaw("product:strength", 992).addRaw("product:volume", 993).build();
  }
}
