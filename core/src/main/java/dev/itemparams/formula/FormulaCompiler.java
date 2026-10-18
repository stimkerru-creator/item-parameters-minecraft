package dev.itemparams.formula;

import com.ezylang.evalex.Expression;
import com.ezylang.evalex.parser.ASTNode;
import com.ezylang.evalex.parser.ParseException;
import com.ezylang.evalex.parser.Token;
import dev.itemparams.core.ParameterSchema;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** Resolves parameter IDs; EvalEx owns the grammar, parser and syntax tree. */
public final class FormulaCompiler {
  public static final int MAX_SOURCE_LENGTH = 8192, MAX_INSTRUCTIONS = 4096, MAX_DEPTH = 128;
  private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]*)}");
  private static final Pattern SCIENTIFIC =
      Pattern.compile("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)[eE][+-]?[0-9]+");
  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Set<String> FUNCTIONS = Set.of("sqrt", "pow", "root", "min", "max", "clamp");

  private FormulaCompiler() {}

  public static CompiledExpression compile(ParameterSchema schema, String source) {
    Objects.requireNonNull(schema, "schema");
    return compile(schema, InputLayout.legacy(schema), source);
  }

  public static CompiledExpression compileInputs(InputLayout layout, String source) {
    return compile(null, Objects.requireNonNull(layout, "layout"), source);
  }

  private static CompiledExpression compile(
      ParameterSchema schema, InputLayout layout, String source) {
    Objects.requireNonNull(source, "source");
    if (source.length() > MAX_SOURCE_LENGTH)
      throw new FormulaException("Expression exceeds source limit", MAX_SOURCE_LENGTH);
    if (source.isBlank()) throw new FormulaException("Expression is empty", 0);
    int nesting = 0;
    for (int i = 0; i < source.length(); i++) {
      if (source.charAt(i) == '(' && ++nesting > MAX_DEPTH)
        throw new FormulaException("Expression nesting exceeds limit", i);
      if (source.charAt(i) == ')') nesting--;
    }
    String publicText =
        SCIENTIFIC.matcher(REFERENCE.matcher(source).replaceAll("0")).replaceAll("0");
    Matcher names = NAME.matcher(publicText);
    while (names.find())
      if (!FUNCTIONS.contains(names.group()))
        throw new FormulaException("Unknown identifier: " + names.group(), names.start());

    Map<Long, CompiledExpression.Field> coordinates = new LinkedHashMap<>();
    Map<String, CompiledExpression.Field> bindings = new HashMap<>();
    Matcher references = REFERENCE.matcher(source);
    StringBuffer normalized = new StringBuffer();
    List<SourcePositions.Reference> referencePositions = new ArrayList<>();
    while (references.find()) {
      try {
        String ref = references.group(1), id;
        int separator = ref.indexOf("::"), role;
        if (schema != null) {
          if (separator >= 0)
            throw new IllegalArgumentException("Named inputs require compileInputs");
          role = 0;
          id = ref;
        } else {
          if (separator < 0) throw new IllegalArgumentException("Expected a named input reference");
          role = layout.requireIndex(ref.substring(0, separator));
          id = ref.substring(separator + 2);
        }
        ParameterSchema.validateId(id);
        int index = layout.schemaAt(role).requireIndex(id);
        long coordinate = ((long) role << 32) | (index & 0xffffffffL);
        CompiledExpression.Field field = coordinates.get(coordinate);
        if (field == null) {
          field = new CompiledExpression.Field("IPFIELD" + coordinates.size(), role, index);
          coordinates.put(coordinate, field);
          bindings.put(field.name(), field);
        }
        references.appendReplacement(normalized, field.name());
        referencePositions.add(
            new SourcePositions.Reference(
                normalized.length() - field.name().length(),
                normalized.length(),
                references.start(),
                references.end()));
      } catch (IllegalArgumentException failure) {
        throw new FormulaException(failure.getMessage(), references.start() + 2);
      }
    }
    references.appendTail(normalized);
    SourcePositions positions = new SourcePositions(referencePositions);
    try {
      Expression expression = new Expression(normalized.toString(), ReadyMath.CONFIGURATION);
      ASTNode tree = expression.getAbstractSyntaxTree();
      record Visit(ASTNode node, int depth) {}
      Deque<Visit> pending = new ArrayDeque<>();
      pending.push(new Visit(tree, 0));
      int count = 0;
      long literalBits = 0;
      Set<BigDecimal> uniqueLiterals = new HashSet<>();
      List<ASTNode> preorder = new ArrayList<>();
      while (!pending.isEmpty()) {
        Visit visit = pending.pop();
        Token token = visit.node().getToken();
        preorder.add(visit.node());
        if (++count > MAX_INSTRUCTIONS)
          throw new FormulaException("Expression exceeds node limit", 0);
        if (visit.depth() > MAX_DEPTH)
          throw new FormulaException("Expression AST exceeds depth limit", 0);
        switch (token.getType()) {
          case NUMBER_LITERAL -> {
            BigDecimal number = new BigDecimal(token.getValue());
            if (number.precision() > 1234 || Math.abs((long) number.scale()) > 1234)
              throw new FormulaException(
                  "Numeric literal exceeds size limit", positions.original(position(token)));
            if (uniqueLiterals.add(number)) {
              int numeratorBits =
                  number.scale() < 0
                      ? number.toBigIntegerExact().abs().bitLength()
                      : number.unscaledValue().abs().bitLength();
              int denominatorBits =
                  number.scale() > 0 ? java.math.BigInteger.TEN.pow(number.scale()).bitLength() : 1;
              if (Math.max(numeratorBits, denominatorBits) > 4096)
                throw new FormulaException(
                    "Numeric literal exceeds 4096 bits", positions.original(position(token)));
              literalBits += Math.max(numeratorBits, denominatorBits);
              if (literalBits > 16384)
                throw new FormulaException(
                    "Unique numeric literal work budget exceeded",
                    positions.original(position(token)));
            }
          }
          case VARIABLE_OR_CONSTANT -> {
            if (!bindings.containsKey(token.getValue()))
              throw new FormulaException("Unknown parameter", positions.original(position(token)));
          }
          case FUNCTION, INFIX_OPERATOR, PREFIX_OPERATOR -> {}
          default ->
              throw new FormulaException(
                  "Only numeric expressions are supported", positions.original(position(token)));
        }
        for (ASTNode child : visit.node().getParameters())
          pending.push(new Visit(child, visit.depth() + 1));
      }
      IdentityHashMap<ASTNode, Integer> capacities = new IdentityHashMap<>();
      for (int i = preorder.size() - 1; i >= 0; i--) {
        ASTNode node = preorder.get(i);
        int capacity = 1, retained = 0;
        for (ASTNode child : node.getParameters()) {
          capacity = Math.max(capacity, retained + capacities.get(child));
          retained++;
        }
        capacities.put(node, capacity);
      }
      return new CompiledExpression(
          schema,
          layout,
          source,
          expression,
          coordinates.values().toArray(CompiledExpression.Field[]::new),
          positions,
          count,
          capacities.get(tree));
    } catch (ParseException failure) {
      throw new FormulaException(
          failure.getMessage(), positions.original(Math.max(0, failure.getStartPosition() - 1)));
    } catch (StackOverflowError failure) {
      throw new FormulaException("Expression exceeds parser depth", 0);
    }
  }

  static int position(Token token) {
    return Math.max(0, token.getStartPosition() - 1);
  }
}
