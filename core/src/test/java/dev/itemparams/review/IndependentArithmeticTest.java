package dev.itemparams.review;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import dev.itemparams.formula.CompiledExpression;
import dev.itemparams.formula.FormulaCompiler;
import dev.itemparams.formula.FormulaException;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent algebraic oracles, authored outside the implementation agent. */
final class IndependentArithmeticTest {
    private static ParameterSchema schema() {
        return ParameterSchema.builder().addRaw("test:a", 0).addRaw("test:b", 0).addRaw("test:c", 0).build();
    }

    @Test void divisionThenMultiplicationPreservesEveryInt32EndpointAndRandomInput() {
        ParameterSchema schema = schema();
        ParameterValues input = schema.defaults();
        CompiledExpression expression = FormulaCompiler.compile(schema, "(${test:a} / ${test:b}) * ${test:b}");
        var workspace = expression.newWorkspace();
        int[] endpoints = {Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 0, 1, 29, -29, 100025};
        for (int a : endpoints) for (int b : endpoints) {
            if (b == 0) continue;
            input.setRaw("test:a", a); input.setRaw("test:b", b);
            assertEquals(a, expression.evaluateRaw(input, workspace), a + " / " + b);
        }
        Random random = new Random(0x17FA_BC01L);
        for (int iteration = 0; iteration < 10000; iteration++) {
            int a = random.nextInt(), b;
            do { b = random.nextInt(); } while (b == 0);
            input.setRaw("test:a", a); input.setRaw("test:b", b);
            assertEquals(a, expression.evaluateRaw(input, workspace));
        }
    }

    @Test void composedFormulaAgreesWithIndependentBigIntegerOracleIncludingOverflow() {
        ParameterSchema schema = schema();
        ParameterValues input = schema.defaults();
        CompiledExpression expression = FormulaCompiler.compile(schema,
            "(${test:a} * ${test:b} + ${test:c}) / ${test:b}");
        var workspace = expression.newWorkspace();
        Random random = new Random(0xC0FF_EEL);
        for (int iteration = 0; iteration < 10000; iteration++) {
            int a = random.nextInt(), b, c = random.nextInt();
            do { b = random.nextInt(); } while (b == 0);
            input.setRaw("test:a", a); input.setRaw("test:b", b); input.setRaw("test:c", c);
            // Inputs represent A/100, B/100, C/100. Final raw = trunc((A*B+100*C)/B).
            BigInteger denominator = BigInteger.valueOf(b);
            BigInteger expected = BigInteger.valueOf(a).multiply(denominator)
                .add(BigInteger.valueOf(c).multiply(BigInteger.valueOf(100))).divide(denominator);
            if (expected.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || expected.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                assertThrows(FormulaException.class, () -> expression.evaluateRaw(input, workspace));
            } else {
                assertEquals(expected.intValueExact(), expression.evaluateRaw(input, workspace));
            }
        }
    }

    @Test void preciseDecimalBoundariesAndExactRootsAreNotShiftedByBinaryRoundoff() {
        ParameterSchema schema = schema();
        ParameterValues input = schema.defaults();
        String[] expressions = {"0.29", "(1/3)*3", "sqrt(0.81)", "root(-8,3)",
            "2^-2", "-2^2", "2^3^2", "-0.129", "0.00999", "-0.00999",
            "21474836.479", "-21474836.489", "(100000000000000000000/3)*3/100000000000000000000"};
        int[] expected = {29, 100, 90, -200, 25, -400, 51200, -12, 0, 0,
            Integer.MAX_VALUE, Integer.MIN_VALUE, 100};
        for (int i = 0; i < expressions.length; i++) {
            assertEquals(expected[i], FormulaCompiler.compile(schema, expressions[i]).evaluateRaw(input), expressions[i]);
        }
        for (String invalid : new String[]{"0^0", "1/0", "sqrt(-1)", "pow(-1,0.5)",
            "21474836.48", "-21474836.49"}) {
            assertThrows(FormulaException.class, () -> FormulaCompiler.compile(schema, invalid).evaluateRaw(input), invalid);
        }
    }
}
