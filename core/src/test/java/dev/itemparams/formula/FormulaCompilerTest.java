package dev.itemparams.formula;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.concurrent.Executors;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FormulaCompilerTest {
    private final ParameterSchema schema=ParameterSchema.builder().addRaw("test:x",125).addRaw("test:y",250).build();
    private int raw(String expression) { return FormulaCompiler.compile(schema,expression).evaluateRaw(schema.defaults()); }

    @ParameterizedTest
    @CsvSource(value={"-2^2|-400","2^-2|25","2^3^2|51200","(-2)^2|400",
            "0.29|29","0.58/2|29","1/3*3|100","(1/7+2/7)*7|300","sqrt(0.81)|90",
            "root(-8,3)|-200","root(-8,-3)|-50","root(64,3)|400","pow(16,0.5)|400",
            "1.299|129","-1.299|-129","sqrt(2)|141","1e2+2.5e-1|10025",
            "min(3,2)|200","max(-3,-2)|-200","clamp(3,0,2)|200","clamp(-3,0,2)|0",
            "${test:x}*${test:y}+1|412","21474836.47|2147483647","-21474836.48|-2147483648"},delimiter='|')
    void preciseArithmeticPrecedenceAndFunctions(String expression,int expected) { assertEquals(expected,raw(expression)); }

    @ParameterizedTest
    @CsvSource(value={"1/0","1/-0","sqrt(-1)","pow(-2,0.5)","0^0","0^-1","root(-8,2)","root(1,0)","clamp(1,2,0)","2^10001","2^10000","21474836.48","1e9999"},delimiter='|')
    void domainRangeAndResourceErrorsAreExplicit(String expression) {
        assertThrows(FormulaException.class,()->raw(expression));
    }
    @Test void sourcePositionsAndInvalidGrammar() {
        for (String expression:List.of("","1+","${test:missing}","${test:x","1e", "pow(1)","sqrt(1,2)","unknown(1)","1 2",".e2"))
            assertThrows(FormulaException.class,()->FormulaCompiler.compile(schema,expression),expression);
        FormulaException division=assertThrows(FormulaException.class,()->raw("1 + 2 / 0"));
        assertEquals(6,division.sourcePosition());
        assertThrows(FormulaException.class,()->FormulaCompiler.compile(schema,"(".repeat(129)+"1"+")".repeat(129)));
        assertThrows(FormulaException.class,()->FormulaCompiler.compile(schema,"1+".repeat(2100)+"1"));
        assertThrows(FormulaException.class,()->FormulaCompiler.compile(schema," ".repeat(8193)));
    }
    @Test void workspaceCanBeReusedAfterSuccessAndFailureAndInputsAreReadOnly() {
        CompiledExpression expression=FormulaCompiler.compile(schema,"${test:x}/${test:y}");
        EvaluationWorkspace workspace=expression.newWorkspace();
        ParameterValues input=schema.defaults(); assertEquals(50,expression.evaluateRaw(input,workspace));
        input.setRaw("test:y",0); assertThrows(FormulaException.class,()->expression.evaluateRaw(input,workspace));
        input.setRaw("test:y",250); assertEquals(50,expression.evaluateRaw(input,workspace));
        assertEquals(125,input.getRaw("test:x"));
        ParameterSchema other=ParameterSchema.builder().addRaw("test:x",125).addRaw("test:y",250).build();
        assertThrows(IllegalArgumentException.class,()->expression.evaluateRaw(other.defaults()));
        assertThrows(IllegalArgumentException.class,()->expression.evaluateRaw(input,new EvaluationWorkspace(1)));
    }
    @Test void exactLargeIntermediateUsesBigIntegerFallbackThenCancels() {
        assertEquals(100,raw("(123456789012345678901234567890/7)*7/123456789012345678901234567890"));
        assertEquals(99,raw("0.9999999999999999999999999999999999999999"));
        assertEquals(100,raw("(1/3+2/3)"));
        assertEquals(100,raw("2^1024/2^1024"));
        assertEquals(100,raw("min(12345678901234567890/12345678901234567891,1)*100/100+0.01"));
    }
    @Test void sharedImmutableProgramWorksWithIndependentConcurrentWorkspaces() throws Exception {
        CompiledExpression expression=FormulaCompiler.compile(schema,"${test:x}*2+${test:y}/3");
        var executor=Executors.newFixedThreadPool(2);
        try {
            var tasks=List.<java.util.concurrent.Callable<Integer>>of(
                    ()->expression.evaluateRaw(schema.defaults(),expression.newWorkspace()),
                    ()->expression.evaluateRaw(schema.defaults(),expression.newWorkspace()));
            for (var result:executor.invokeAll(tasks)) assertEquals(333,result.get());
        } finally { executor.shutdownNow(); }
    }
    @Test void dynamicBuffersPreserveExactInstructionLimitAndErrorPositions() {
        var terms=new java.util.ArrayList<String>(java.util.Collections.nCopies(2048,"1"));
        terms.set(0,"-1");
        String maximum=balancedSum(terms,0,terms.size());
        CompiledExpression expression=FormulaCompiler.compile(schema,maximum);
        assertEquals(FormulaCompiler.MAX_INSTRUCTIONS,expression.sourceInstructionCount());
        assertEquals(1,expression.instructionCount());
        assertEquals(204600,expression.evaluateRaw(schema.defaults()));
        assertThrows(FormulaException.class,()->FormulaCompiler.compile(schema,"-"+maximum));
        String failure="1+".repeat(31)+"1/0";
        FormulaException error=assertThrows(FormulaException.class,()->raw(failure));
        assertEquals(failure.indexOf('/'),error.sourcePosition());
    }

    private static String balancedSum(java.util.List<String> terms,int from,int to) {
        if(to-from==1) return terms.get(from);
        int middle=(from+to)/2;
        return "("+balancedSum(terms,from,middle)+"+"+balancedSum(terms,middle,to)+")";
    }
}
