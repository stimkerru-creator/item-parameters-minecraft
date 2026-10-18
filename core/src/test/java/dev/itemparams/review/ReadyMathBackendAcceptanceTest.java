package dev.itemparams.review;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import dev.itemparams.formula.CompiledExpression;
import dev.itemparams.formula.EvaluationWorkspace;
import dev.itemparams.formula.FormulaCompiler;
import dev.itemparams.formula.FormulaException;
import dev.itemparams.formula.InputLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Independent public-API acceptance checks for replacing the original calculator. */
class ReadyMathBackendAcceptanceTest {
    private static final ParameterSchema SCHEMA = ParameterSchema.builder()
            .addRaw("test:x", 125).addRaw("test:y", 250).build();

    private static int raw(String source) {
        return FormulaCompiler.compile(SCHEMA, source).evaluateRaw(SCHEMA.defaults());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "1/3*3|100", "(1/7+2/7)*7|300", "sqrt(2)*sqrt(8)|400",
            "root(2,3)*root(4,3)|200", "root(3,3)^3|300",
            "root(-8,3)|-200", "root(-8,-3)|-50", "root(16,-2)|25",
            "pow(16,0.5)|400", "pow(4,1/3)|158", "root(4,3)|158",
            "pow(27,sqrt(4)/3)|900", "root(256,sqrt(4))|1600",
            "-2^2|-400", "(-2)^2|400", "2^-2|25", "2^3^2|51200",
            "0.29|29", "0.58/2|29", "1.299|129", "-1.299|-129",
            "(1.001-1)*1000|100", "(1/1000)*1000|100",
            "(123456789012345678901234567890/7)*7/123456789012345678901234567890|100",
            "21474836.47|2147483647", "-21474836.48|-2147483648"
    }, delimiter = '|')
    void preservesExactArithmeticIntermediateFractionsAndLegacyOperators(String source, int expected) {
        assertEquals(expected, raw(source), source);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "sqrt(4-1e-40)|199", "-sqrt(4-1e-40)|-199",
            "sqrt(4)|200", "-sqrt(4)|-200",
            "1.24-1e-40*sqrt(2)|123", "-(1.24-1e-40*sqrt(2))|-123",
            "0.9999999999999999999999999999999999999999|99"
    }, delimiter = '|')
    void neverSnapsAnActualValueAcrossACentBoundary(String source, int expected) {
        assertEquals(expected, raw(source), source);
    }

    @Test
    void reportsOriginalSourcePositionsAfterBindingReferences() {
        String source = "${test:x} / (${test:y}-${test:y})";
        var expression = FormulaCompiler.compile(SCHEMA, source);
        var failure = assertThrows(FormulaException.class,
                () -> expression.evaluateRaw(SCHEMA.defaults()));
        assertEquals(source.indexOf('/'), failure.sourcePosition());
    }

    @Test
    void extremaUseExactComparisonsForNearbyRadicals() {
        assertEquals(200, raw("max(sqrt(4-1e-40),2)"));
        assertEquals(199, raw("min(sqrt(4-1e-40),2)"));
        assertEquals(200, raw("max(sqrt(4-1e-1000),2)"));
        assertEquals(199, raw("min(sqrt(4-1e-1000),2)"));
        assertEquals(200, raw("clamp(sqrt(4-1e-40),2,3)"));
    }

    @Test
    void extremeBoundaryEitherRemainsCorrectOrFailsExplicitly() {
        // A finite numerical precision must not silently turn these into exact boundaries.
        assertCorrectOrExplicitPrecisionFailure("sqrt(4-1e-1000)", 199);
        assertCorrectOrExplicitPrecisionFailure("-sqrt(4-1e-1000)", -199);
        assertCorrectOrExplicitPrecisionFailure("1.24-1e-1000*sqrt(2)", 123);
    }

    private static void assertCorrectOrExplicitPrecisionFailure(String source, int expected) {
        try {
            assertEquals(expected, raw(source), source);
        } catch (FormulaException expectedFailure) {
            assertNotNull(expectedFailure.getMessage());
            assertFalse(expectedFailure.getMessage().isBlank());
        }
    }

    @Test
    void fourNamedInputsRemainIndependentAcrossBindingAndSchemaRegistrationOrder() {
        ParameterSchema boltSchema = ParameterSchema.builder()
                .addRaw("part:mass", 20).addRaw("part:bolt-only", 100).build();
        ParameterSchema plateSchema = ParameterSchema.builder()
                .addRaw("part:plate-only", 200).addRaw("part:mass", 400).build();
        ParameterSchema hingeSchema = ParameterSchema.builder()
                .addRaw("part:strength", 2500).addRaw("part:mass", 3).build();
        ParameterSchema suspensionSchema = ParameterSchema.builder()
                .addRaw("part:mass", 4).addRaw("part:elasticity", 4000).build();
        Map<String, ParameterSchema> schemas = new LinkedHashMap<>();
        schemas.put("bolt", boltSchema);
        schemas.put("plate", plateSchema);
        schemas.put("hinge", hingeSchema);
        schemas.put("suspension", suspensionSchema);
        InputLayout layout = InputLayout.of(schemas);
        ParameterValues bolt = boltSchema.defaults();
        ParameterValues plate = plateSchema.defaults();
        ParameterValues hinge = hingeSchema.defaults();
        ParameterValues suspension = suspensionSchema.defaults();
        Map<String, ParameterValues> forward = new LinkedHashMap<>();
        forward.put("bolt", bolt);
        forward.put("plate", plate);
        forward.put("hinge", hinge);
        forward.put("suspension", suspension);
        Map<String, ParameterValues> reversed = new LinkedHashMap<>();
        reversed.put("suspension", suspension);
        reversed.put("hinge", hinge);
        reversed.put("plate", plate);
        reversed.put("bolt", bolt);
        CompiledExpression expression = FormulaCompiler.compileInputs(layout,
                "${bolt::part:mass}+10*${plate::part:mass}"
                        + "+100*${hinge::part:mass}+1000*${suspension::part:mass}");
        EvaluationWorkspace workspace = expression.newWorkspace();
        assertEquals(8320, expression.evaluateRawInputs(layout.bind(forward), workspace));
        assertEquals(8320, expression.evaluateRawInputs(layout.bind(reversed), workspace));
        Map<String, ParameterSchema> reversedSchemas = new LinkedHashMap<>();
        reversedSchemas.put("suspension", suspensionSchema);
        reversedSchemas.put("hinge", hingeSchema);
        reversedSchemas.put("plate", plateSchema);
        reversedSchemas.put("bolt", boltSchema);
        InputLayout reorderedLayout = InputLayout.of(reversedSchemas);
        CompiledExpression reordered = FormulaCompiler.compileInputs(reorderedLayout, expression.source());
        assertEquals(8320, reordered.evaluateRawInputs(reorderedLayout.bind(forward)));
        plate.setRaw("part:mass", 500);
        assertEquals(9320, expression.evaluateRawInputs(layout.bind(reversed), workspace));
        assertEquals(20, bolt.getRaw("part:mass"));
        assertEquals(3, hinge.getRaw("part:mass"));
        assertEquals(4, suspension.getRaw("part:mass"));
        assertThrows(FormulaException.class,
                () -> FormulaCompiler.compileInputs(layout, "${hinge::part:plate-only}"));
        assertThrows(IllegalArgumentException.class,
                () -> layout.bind(Map.of("bolt", bolt, "plate", plate, "hinge", hinge)));
    }

    @Test
    void invalidExpressionsAndFailedEvaluationLeaveInputsUntouchedAndWorkspaceReusable() {
        ParameterValues values = SCHEMA.defaults();
        for (String source : List.of("${test:missing}", "1+", "pow(1)", "sqrt(1,2)")) {
            assertThrows(FormulaException.class, () -> FormulaCompiler.compile(SCHEMA, source), source);
            assertEquals(125, values.getRaw("test:x"));
            assertEquals(250, values.getRaw("test:y"));
        }
        CompiledExpression expression = FormulaCompiler.compile(SCHEMA, "${test:x}/${test:y}");
        EvaluationWorkspace workspace = expression.newWorkspace();
        assertEquals(50, expression.evaluateRaw(values, workspace));
        values.setRaw("test:y", 0);
        assertThrows(FormulaException.class, () -> expression.evaluateRaw(values, workspace));
        assertEquals(125, values.getRaw("test:x"));
        assertEquals(0, values.getRaw("test:y"));
        values.setRaw("test:y", 250);
        assertEquals(50, expression.evaluateRaw(values, workspace));
        assertEquals(new BigDecimal("0.50"), expression.evaluateStored(values, workspace));
    }

    @Test
    void nonNumericLibraryCapabilitiesAreRejectedDuringCompilation() {
        // Invalid numeric arguments deliberately contain no path, URL or mutable symbol.
        // These tests only compile: they must never invoke library I/O or assignments.
        for (String source : List.of("Get(1)", "Import(1)", "Set(1,2)", "Random()",
                "RandomReal()", "RandomInteger(10)", "Run(1)", "1;2", "x=1")) {
            assertThrows(FormulaException.class, () -> FormulaCompiler.compile(SCHEMA, source), source);
        }
    }

    @Test
    void customRationalImplementationIsAbsentAndReadyMadeEngineIsPresent() throws Exception {
        ClassLoader loader = FormulaCompiler.class.getClassLoader();
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("dev.itemparams.formula.Rational", false, loader));
        assertNotNull(Class.forName("com.ezylang.evalex.Expression", false, loader));
        assertNotNull(Class.forName("spire.math.Algebraic", false, loader));
    }

    @Test
    void sharedCompiledExpressionHasIndependentReusableConcurrentWorkspaces() throws Exception {
        CompiledExpression expression = FormulaCompiler.compile(SCHEMA,
                "${test:x}/3*3+sqrt(${test:y}*${test:y})");
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                final int workerId = worker;
                tasks.add(() -> {
                    ParameterValues values = SCHEMA.defaults();
                    EvaluationWorkspace workspace = expression.newWorkspace();
                    for (int iteration = 0; iteration < 12; iteration++) {
                        int x = 100 + workerId * 1000 + iteration;
                        int y = 200 + workerId * 100 + iteration;
                        values.setRaw("test:x", x);
                        values.setRaw("test:y", y);
                        assertEquals(x + y, expression.evaluateRaw(values, workspace));
                    }
                    return null;
                });
            }
            for (var result : executor.invokeAll(tasks)) result.get();
        } finally {
            executor.shutdownNow();
        }
    }
}
