package dev.itemparams.formula;

import dev.itemparams.core.Numeric;
import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regressions for numerical failures found by the independent review. Uses only public APIs. */
class FormulaNumericalRegressionTest {
    private final ParameterSchema schema=ParameterSchema.builder().addRaw("test:x",125).build();
    private int raw(String source) { return FormulaCompiler.compile(schema,source).evaluateRaw(schema.defaults()); }

    @Test void exactZeroAndOneCasesDoNotEnterTheApproximateEvaluator() {
        for (String source:List.of("pow(0,1e-1000)","root(0,1e1000)","pow(0,0.0001)"))
            assertEquals(0,raw(source),source);
        for (String source:List.of("pow(1,5000.5)","pow(1,1e1000)","root(1,1e-1000)"))
            assertEquals(100,raw(source),source);
        for (String source:List.of("pow(0,0)","pow(0,-1e-1000)","root(0,-3)","root(0,0)"))
            assertThrows(FormulaException.class,()->raw(source),source);
    }

    @Test void knownApproximatePrecisionFailuresRemainBounded() {
        for (String source:List.of("pow(2,1e-1000)","pow(0.5,1e-20)",
                "(1-pow(0.5,1e-20))*1e22"))
            assertThrows(FormulaException.class,()->raw(source),source);
        // Binary64 used to round this base to exactly 1. High-precision decimal
        // evaluation must keep the meaningful digits and then quantize once.
        assertEquals(100,raw("pow(1.00000000000000000001,0.3)"));
    }

    @Test void threeCommonRootCompositionsAreExactBeforeQuantization() {
        assertEquals(200,raw("sqrt(2)^2"));
        assertEquals(2,raw("sqrt(0.02)^2"));
        assertEquals(200,raw("sqrt(2)*sqrt(2)"));
        assertEquals(300,raw("root(3,3)^3"));
        assertEquals(-300,raw("root(-3,3)^3"));
        ParameterValues input=schema.defaults();
        for (String source:List.of("sqrt(${test:x})^2","sqrt(${test:x})*sqrt(${test:x})",
                "root(${test:x},3)^3","root(${test:x},-3)^-3")) {
            CompiledExpression expression=FormulaCompiler.compile(schema,source);
            var workspace=expression.newWorkspace();
            for (int value:new int[]{1,2,29,125,Integer.MAX_VALUE}) {
                input.setRaw(0,value); assertEquals(value,expression.evaluateRaw(input,workspace),source);
            }
        }
        assertEquals(225,raw("sqrt(${test:x}+1)*sqrt(${test:x}+1)"));
    }

    @Test void ExactRewritesPreserveNegativeAndZeroDomainFailures() {
        for (String source:List.of("sqrt(-2)^2","sqrt(-2)*sqrt(-2)","root(-3,2)^2","root(0,-3)^-3"))
            assertThrows(FormulaException.class,()->FormulaCompiler.compile(schema,source),source);
        ParameterValues input=schema.defaults(); input.setRaw(0,-300);
        for (String source:List.of("sqrt(${test:x})^2","sqrt(${test:x})*sqrt(${test:x})","root(${test:x},2)^2")) {
            CompiledExpression expression=FormulaCompiler.compile(schema,source);
            assertThrows(FormulaException.class,()->expression.evaluateRaw(input),source);
        }
        assertEquals(-300,FormulaCompiler.compile(schema,"root(${test:x},3)^3").evaluateRaw(input));
        input.setRaw(0,0);
        assertThrows(FormulaException.class,()->FormulaCompiler.compile(schema,"root(${test:x},-3)^-3").evaluateRaw(input));
    }



    @Test void fractionalRootsAndPowersKeepAlgebraicResultsAtTheStorageBoundary() {
        assertEquals(400,raw("sqrt(2)*sqrt(8)"));
        assertEquals(200,raw("root(2,3)*root(4,3)"));
        assertEquals(200,raw("pow(2,1/3)*pow(4,1/3)"));
        assertEquals(200,raw("pow(2,1/3)^3"));
        assertEquals(900,raw("pow(27,2/3)"));
    }

    @Test void generalizedRootAndPowerRewritesPreserveRuntimeDomains() {
        ParameterValues input=schema.defaults();
        input.setRaw("test:x",200);
        assertEquals(400,FormulaCompiler.compile(schema,"sqrt(${test:x})*sqrt(8)").evaluateRaw(input));
        assertEquals(200,FormulaCompiler.compile(schema,"root(${test:x},3)*root(4,3)").evaluateRaw(input));
        assertEquals(200,FormulaCompiler.compile(schema,"pow(${test:x},1/3)*pow(4,1/3)").evaluateRaw(input));

        input.setRaw("test:x",-200);
        assertThrows(FormulaException.class,()->FormulaCompiler.compile(schema,
                "sqrt(${test:x})*sqrt(8)").evaluateRaw(input));
        assertEquals(200,FormulaCompiler.compile(schema,
                "root(${test:x},3)*root(-4,3)").evaluateRaw(input));
        assertEquals(-200,FormulaCompiler.compile(schema,
                "root(${test:x},3)*root(4,3)").evaluateRaw(input));
        assertThrows(FormulaException.class,()->FormulaCompiler.compile(schema,
                "pow(${test:x},1/3)*pow(4,1/3)").evaluateRaw(input));
    }

    @Test void repeatedExpensiveConstantsAreFoldedOnceAndNeverRunPerItem() {
        String pair="(root(1e1200,3)-root(1e1200,3))";
        CompiledExpression expression=FormulaCompiler.compile(schema,balancedRepeatedSum(pair,160));
        assertEquals(1279,expression.sourceInstructionCount());
        assertEquals(1,expression.instructionCount());
        var workspace=expression.newWorkspace();
        for (int i=0;i<100;i++) assertEquals(0,expression.evaluateRaw(schema.defaults(),workspace));
    }

    @Test void uniqueExpensiveConstantsHaveAWorkBudgetBeforeComputation() {
        FormulaException error=assertThrows(FormulaException.class,()->FormulaCompiler.compile(schema,
                "root(1e1200,3)+root(1e1197,3)+root(1e1194,3)+root(1e1191,3)+root(1e1188,3)"));
        assertTrue(error.getMessage().contains("work budget"),error.getMessage());
    }

    private static String balancedRepeatedSum(String term,int count) {
        if(count==1) return term;
        int left=count/2;
        return "("+balancedRepeatedSum(term,left)+"+"+balancedRepeatedSum(term,count-left)+")";
    }

    @Test void storedDecimalApiCannotRoundAcrossTheExactHundredthBoundary() {
        CompiledExpression expression=FormulaCompiler.compile(schema,"2-1e-40");
        ParameterValues input=schema.defaults();
        assertEquals(199,expression.evaluateRaw(input));
        assertEquals(new BigDecimal("1.99"),expression.evaluateStored(input));
        assertEquals(199,Numeric.encode(expression.evaluateStored(input)));
        // The approximate diagnostic API must not pretend a representable tiny difference is zero.
        assertTrue(expression.evaluateApproximateDecimal(input).compareTo(new BigDecimal("2")) < 0);
    }
}
