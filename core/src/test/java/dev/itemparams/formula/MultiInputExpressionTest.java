package dev.itemparams.formula;

import dev.itemparams.core.ParameterSchema;
import dev.itemparams.core.ParameterValues;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Named roles remain separate schemas and share the legacy parser, math and VM. */
class MultiInputExpressionTest {
    private final ParameterSchema boltSchema=ParameterSchema.builder().addRaw("part:mass",125).addRaw("part:strength",400).build();
    private final ParameterSchema plateSchema=ParameterSchema.builder().addRaw("part:strength",900).addRaw("part:mass",250).build();
    private InputLayout layout() {
        Map<String,ParameterSchema> roles=new LinkedHashMap<>(); roles.put("bolt",boltSchema); roles.put("plate",plateSchema);
        return InputLayout.of(roles);
    }
    private InputValues values(InputLayout layout) { return layout.bind(Map.of("plate",plateSchema.defaults(),"bolt",boltSchema.defaults())); }

    @Test void distinctRoleAndFieldCoordinatesResolveAgainstEachIndependentSchema() {
        InputLayout layout=layout();
        CompiledExpression expression=FormulaCompiler.compileInputs(layout,
                "${bolt::part:mass}+${plate::part:mass}+${plate::part:strength}-${bolt::part:strength}");
        assertSame(layout,expression.inputLayout()); assertEquals(875,expression.evaluateRawInputs(values(layout)));
        assertThrows(IllegalStateException.class,expression::schema);
        assertThrows(IllegalStateException.class,()->expression.evaluateRaw(boltSchema.defaults()));
    }

    @Test void bindingOrderDoesNotChangeNamesAndBindingsDoNotCopyParameterArrays() {
        InputLayout layout=layout(); ParameterValues bolt=boltSchema.defaults(),plate=plateSchema.defaults();
        Map<String,ParameterValues> supplied=new LinkedHashMap<>(); supplied.put("plate",plate); supplied.put("bolt",bolt);
        InputValues bound=layout.bind(supplied); supplied.clear();
        assertSame(bolt,bound.get("bolt")); assertSame(plate,bound.valueAt(layout.requireIndex("plate")));
        CompiledExpression expression=FormulaCompiler.compileInputs(layout,"${bolt::part:mass}+${plate::part:mass}");
        var workspace=expression.newWorkspace(); assertEquals(375,expression.evaluateRawInputs(bound,workspace));
        bolt.setRaw("part:mass",700); assertEquals(950,expression.evaluateRawInputs(bound,workspace));
        plate.edit(draft->draft.setRaw("part:mass",800)); assertEquals(1500,expression.evaluateRawInputs(bound,workspace));
        ParameterValues replacement=plate.copy(); replacement.setRaw("part:mass",900);
        assertEquals(1600,expression.evaluateRawInputs(layout.bind(Map.of("bolt",bolt,"plate",replacement)),workspace));
        workspace.clear(); assertEquals(1500,expression.evaluateRawInputs(bound,workspace));
    }

    @Test void bindingRejectsMissingExtraNullAndDifferentSchemaInstancesBeforeEvaluation() {
        InputLayout layout=layout(); ParameterValues bolt=boltSchema.defaults(),plate=plateSchema.defaults();
        assertThrows(IllegalArgumentException.class,()->layout.bind(Map.of("bolt",bolt)));
        assertThrows(IllegalArgumentException.class,()->layout.bind(Map.of("bolt",bolt,"plate",plate,"extra",bolt)));
        assertThrows(IllegalArgumentException.class,()->layout.bind(Map.of("wrong",bolt,"plate",plate)));
        Map<String,ParameterValues> nullValue=new LinkedHashMap<>(); nullValue.put("bolt",null); nullValue.put("plate",plate);
        assertThrows(IllegalArgumentException.class,()->layout.bind(nullValue));
        ParameterSchema identical=ParameterSchema.builder().addRaw("part:mass",125).addRaw("part:strength",400).build();
        assertThrows(IllegalArgumentException.class,()->layout.bind(Map.of("bolt",identical.defaults(),"plate",plate)));
        CompiledExpression expression=FormulaCompiler.compileInputs(layout,"1");
        InputLayout differentLayout=layout();
        assertThrows(IllegalArgumentException.class,()->expression.evaluateRawInputs(values(differentLayout)));
    }

    @Test void rootIdentityOptimizerNeverConfusesSameSlotNumbersFromDifferentRoles() {
        ParameterSchema schema=ParameterSchema.builder().addRaw("part:x",400).build();
        InputLayout layout=InputLayout.of(Map.of("left",schema,"right",schema));
        ParameterValues left=schema.defaults(),right=schema.defaults(); right.setRaw(0,900);
        InputValues bound=layout.bind(Map.of("left",left,"right",right));
        CompiledExpression product=FormulaCompiler.compileInputs(layout,"sqrt(${left::part:x})*sqrt(${right::part:x})");
        assertEquals(600,product.evaluateRawInputs(bound));
        right.setRaw(0,-900); assertThrows(FormulaException.class,()->product.evaluateRawInputs(bound));
        CompiledExpression sameRole=FormulaCompiler.compileInputs(layout,"sqrt(${left::part:x})*sqrt(${left::part:x})");
        left.setRaw(0,2); assertEquals(2,sameRole.evaluateRawInputs(bound));
    }

    @Test void rolesAreCaseSensitiveAllowHyphensAndHaveNoSmallFixedCount() {
        ParameterSchema schema=ParameterSchema.builder().addRaw("part:x",100).build();
        Map<String,ParameterSchema> schemas=new LinkedHashMap<>(); Map<String,ParameterValues> inputs=new LinkedHashMap<>();
        StringBuilder source=new StringBuilder();
        for (int i=0;i<80;i++) {
            String role="part-"+i; schemas.put(role,schema); inputs.put(role,schema.defaults());
            if (i>0) source.append('+'); source.append("${").append(role).append("::part:x}");
        }
        InputLayout layout=InputLayout.of(schemas);
        assertEquals(80,layout.size()); assertEquals(8000,FormulaCompiler.compileInputs(layout,source.toString()).evaluateRawInputs(layout.bind(inputs)));
        assertThrows(IllegalArgumentException.class,()->layout.requireIndex("Part-0"));
        assertThrows(IllegalArgumentException.class,()->InputLayout.of(Map.of("bad:name",schema)));
        assertThrows(IllegalArgumentException.class,()->InputLayout.of(Map.of("a".repeat(257),schema)));
    }

    @Test void parserRejectsUnknownRolesAndFieldsAndReportsReferencePosition() {
        InputLayout layout=layout();
        for (String source:new String[]{"${other::part:mass}","${bolt::part:missing}","${bolt::bad}","${part:mass}","${bolt::part:mass::extra}"})
            assertThrows(FormulaException.class,()->FormulaCompiler.compileInputs(layout,source),source);
        FormulaException error=assertThrows(FormulaException.class,()->FormulaCompiler.compileInputs(layout,"1+${other::part:mass}"));
        assertEquals(4,error.sourcePosition());
        assertThrows(FormulaException.class,()->FormulaCompiler.compile(boltSchema,"${bolt::part:mass}"));
    }

    @Test void legacyApiAndNewEntryPointsUseOneWorkspaceAndIdenticalArithmeticSemantics() {
        CompiledExpression legacy=FormulaCompiler.compile(boltSchema,"sqrt(${part:mass})^2");
        assertSame(boltSchema,legacy.schema()); assertEquals(125,legacy.evaluateRaw(boltSchema.defaults()));
        InputLayout layout=layout(); InputValues bound=values(layout);
        CompiledExpression expression=FormulaCompiler.compileInputs(layout,"${bolt::part:mass}/${plate::part:mass}*3");
        EvaluationWorkspace workspace=new EvaluationWorkspace(4);
        assertEquals(150,expression.evaluateRawInputs(bound,workspace));
        assertEquals(125,legacy.evaluateRaw(boltSchema.defaults(),workspace));
        assertEquals(new BigDecimal("1.50"),expression.evaluateStoredInputs(bound,workspace));
        assertEquals(0,new BigDecimal("1.5").compareTo(expression.evaluateApproximateDecimalInputs(bound,workspace)));
        bound.get("plate").setRaw("part:mass",0);
        assertThrows(FormulaException.class,()->expression.evaluateRawInputs(bound,workspace));
        bound.get("plate").setRaw("part:mass",250); assertEquals(150,expression.evaluateRawInputs(bound,workspace));
    }
    @Test void selectiveRolePreparationInvalidatesDisjointExpressionsAfterConstantsAndRebinding() {
        InputLayout layout=layout(); InputValues bound=values(layout);
        CompiledExpression bolt=FormulaCompiler.compileInputs(layout,"${bolt::part:mass}+${bolt::part:mass}");
        CompiledExpression plate=FormulaCompiler.compileInputs(layout,"${plate::part:mass}");
        CompiledExpression constant=FormulaCompiler.compileInputs(layout,"1/3*3");
        EvaluationWorkspace workspace=new EvaluationWorkspace(3);
        assertEquals(250,bolt.evaluateRawInputs(bound,workspace));
        assertEquals(250,plate.evaluateRawInputs(bound,workspace));
        bound.get("plate").setRaw("part:mass",700);
        bound.get("bolt").setRaw("part:mass",300);
        assertEquals(100,constant.evaluateRawInputs(bound,workspace));
        assertEquals(600,bolt.evaluateRawInputs(bound,workspace));
        assertEquals(700,plate.evaluateRawInputs(bound,workspace));
        ParameterValues newBolt=boltSchema.defaults(),newPlate=plateSchema.defaults();
        newBolt.setRaw("part:mass",450); newPlate.setRaw("part:mass",800);
        InputValues rebound=layout.bind(Map.of("bolt",newBolt,"plate",newPlate));
        assertEquals(100,constant.evaluateRawInputs(rebound,workspace));
        assertEquals(800,plate.evaluateRawInputs(rebound,workspace));
        assertEquals(900,bolt.evaluateRawInputs(rebound,workspace));
        workspace.clear();
        assertEquals(100,constant.evaluateRawInputs(bound,workspace));
        assertEquals(700,plate.evaluateRawInputs(bound,workspace));
        assertEquals(600,bolt.evaluateRawInputs(bound,workspace));
    }
}
