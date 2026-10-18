package dev.itemparams.formula;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Calculator-reference cases that previously failed because an integer Newton step was
 * incorrectly treated as converged (BigDecimal.ulp depends on scale, not precision). */
class RootConvergenceRegressionTest {
    private static final ParameterSchema SCHEMA = ParameterSchema.builder()
            .addRaw("test:x", 0).build();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "root(4,3)|158", "pow(4,1/3)|158", "root(5,4)|149",
            "root(66,3)|404", "root(84,4)|302", "root(6,5)|143",
            "root(7,6)|138", "root(8,7)|134", "root(9,8)|131",
            "root(-4,3)|-158", "root(-4,-3)|-62",
            "pow(4,2/3)|251", "root(4,3)*root(4,3)|251",
            "root(4,3)*root(4,3)*root(4,3)|400",
            "sqrt(2)*sqrt(8)|400", "root(0.04,3)|34",
            "root(4,3)+0.02|160", "(root(4,3)-1.58)*100|74",
            "(1/3)*3|100", "1.299|129"
    })
    void constantExpressionsAgreeWithIndependentHighPrecisionReference(String expression, int raw) {
        assertEquals(raw, FormulaCompiler.compile(SCHEMA, expression).evaluateRaw(SCHEMA.defaults()), expression);
    }

    @Test
    void runtimeParametersUseTheSameCorrectRootAlgorithmAcrossDifferentValues() {
        var root = FormulaCompiler.compile(SCHEMA, "root(${test:x},3)");
        var power = FormulaCompiler.compile(SCHEMA, "pow(${test:x},1/3)");
        var input = SCHEMA.defaults();
        var rootWorkspace = root.newWorkspace();
        var powerWorkspace = power.newWorkspace();
        for (int[] pair : new int[][] {{400,158},{6600,404},{800,200},{200,125},{2700,300}}) {
            input.setRaw("test:x", pair[0]);
            assertEquals(pair[1], root.evaluateRaw(input, rootWorkspace), "root input="+pair[0]);
            assertEquals(pair[1], power.evaluateRaw(input, powerWorkspace), "pow input="+pair[0]);
        }
    }
}
