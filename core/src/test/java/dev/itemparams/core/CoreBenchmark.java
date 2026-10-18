package dev.itemparams.core;

import dev.itemparams.formula.CompiledExpression;
import dev.itemparams.formula.EvaluationWorkspace;
import dev.itemparams.formula.FormulaCompiler;

/** Standalone synthetic timing harness, not JMH and not a Minecraft TPS claim. */
public final class CoreBenchmark {
    private static volatile long sink;
    private CoreBenchmark() {}
    public static void main(String[] args) {
        int iterations=args.length==0 ? 100_000 : Integer.parseInt(args[0]);
        if (iterations<1 || iterations>10_000_000) throw new IllegalArgumentException("Iterations: 1..10000000");
        ParameterSchema schema=ParameterSchema.builder().addRaw("bench:mass",1250).addRaw("bench:quality",735).build();
        ParameterValues input=schema.defaults();
        CompiledExpression expression=FormulaCompiler.compile(schema,
                "clamp((${bench:mass}*${bench:quality}/3+sqrt(0.81))*1.25,0,100000)");
        EvaluationWorkspace workspace=expression.newWorkspace();
        for (int i=0;i<30_000;i++) sink+=expression.evaluateRaw(input,workspace);
        System.out.println("Java " + System.getProperty("java.version") + "; EvalEx + Spire Algebraic; " + expression.instructionCount() + " syntax nodes");
        long checksum=0,start=System.nanoTime();
        for (int i=0;i<iterations;i++) checksum+=expression.evaluateRaw(input,workspace);
        long elapsed=System.nanoTime()-start; sink=checksum;
        System.out.printf(java.util.Locale.ROOT,"Reused workspace: %.1f ns/evaluation, %d iterations, checksum=%d%n",(double)elapsed/iterations,iterations,checksum);
        checksum=0; start=System.nanoTime();
        for (int i=0;i<iterations;i++) checksum+=expression.evaluateRaw(input);
        elapsed=System.nanoTime()-start; sink=checksum;
        System.out.printf(java.util.Locale.ROOT,"New workspace: %.1f ns/evaluation, %d iterations, checksum=%d%n",(double)elapsed/iterations,iterations,checksum);
        System.out.println("Warmup=30000. Ready-made expression and algebraic libraries; not a production-template hit. Re-run variation expected; use JMH/profiler before performance commitments.");
        workspace.clear();
    }
}
