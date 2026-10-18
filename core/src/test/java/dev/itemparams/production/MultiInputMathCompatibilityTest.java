package dev.itemparams.production;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.formula.CompiledExpression;
import dev.itemparams.formula.FormulaCompiler;
import dev.itemparams.formula.FormulaException;
import dev.itemparams.formula.InputLayout;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Differential compatibility: the same operations through old and named-input public APIs. */
class MultiInputMathCompatibilityTest {
    @Test void allOperationsKeepTheirSingleInputSemanticsIncludingFailures() {
        ParameterSchema oldSchema = ParameterSchema.builder().addRaw("test:a", 0).addRaw("test:b", 0).build();
        ParameterSchema component = ParameterSchema.builder().addRaw("test:x", 0).build();
        InputLayout layout = InputLayout.of(Map.of("left", component, "right", component));
        var oldValues = oldSchema.defaults();
        var left = component.defaults();
        var right = component.defaults();
        var inputs = layout.bind(Map.of("left", left, "right", right));
        String[] templates = {
            "A+B", "-A-B", "A*B", "A/B", "sqrt(A)", "pow(A,B)", "root(A,B)",
            "min(A,B)", "max(A,B)", "clamp(A,min(0,B),max(0,B))", "(A/B)^2+0.001", "A^B^2"
        };
        int[] aValues = {Integer.MIN_VALUE, -1234, -100, -2, 0, 1, 2, 29, 100, 1234, Integer.MAX_VALUE};
        int[] bValues = {-300, -50, 0, 50, 100, 200, 300};
        for (String template : templates) {
            CompiledExpression legacy = FormulaCompiler.compile(oldSchema,
                    template.replace("A", "${test:a}").replace("B", "${test:b}"));
            CompiledExpression multi = FormulaCompiler.compileInputs(layout,
                    template.replace("A", "${left::test:x}").replace("B", "${right::test:x}"));
            var oldState = legacy.newWorkspace();
            var newState = multi.newWorkspace();
            for (int a : aValues) for (int b : bValues) {
                oldValues.setRaw(0, a); oldValues.setRaw(1, b);
                left.setRaw(0, a); right.setRaw(0, b);
                String description = template + " aRaw=" + a + " bRaw=" + b;
                Integer expected = null;
                try { expected = legacy.evaluateRaw(oldValues, oldState); }
                catch (FormulaException oldFailure) {
                    assertThrows(FormulaException.class, () -> multi.evaluateRawInputs(inputs, newState), description);
                }
                if (expected != null) assertEquals(expected.intValue(), multi.evaluateRawInputs(inputs, newState), description);
            }
        }
    }
}
