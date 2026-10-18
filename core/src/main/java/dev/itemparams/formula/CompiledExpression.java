package dev.itemparams.formula;

import com.ezylang.evalex.*;
import com.ezylang.evalex.parser.ParseException;
import dev.itemparams.core.*;
import java.math.BigDecimal;
import java.util.Objects;
import spire.math.Algebraic;

/** Schema bindings around an EvalEx program with Spire algebraic arithmetic. */
public final class CompiledExpression {
  record Field(String name, int role, int index) {}

  private final ParameterSchema schema;
  private final InputLayout layout;
  private final String source;
  private final Expression prototype;
  private final Field[] fields;
  private final SourcePositions positions;
  private final int nodes;
  private final int workspaceSize;
  private final Algebraic constant;
  private final Integer constantRaw;
  private final String constantStorageError;

  CompiledExpression(
      ParameterSchema schema,
      InputLayout layout,
      String source,
      Expression prototype,
      Field[] fields,
      SourcePositions positions,
      int nodes,
      int workspaceSize) {
    this.schema = schema;
    this.layout = layout;
    this.source = source;
    this.prototype = prototype;
    this.fields = fields;
    this.positions = positions;
    this.nodes = nodes;
    this.workspaceSize = fields.length == 0 ? 1 : workspaceSize;
    Algebraic value = null;
    Integer raw = null;
    String error = null;
    if (fields.length == 0) {
      value = evaluate(prototype);
      try {
        raw = ReadyMath.stored(value);
      } catch (ArithmeticException failure) {
        error = failure.getMessage();
      }
    }
    constant = value;
    constantRaw = raw;
    constantStorageError = error;
  }

  public ParameterSchema schema() {
    if (schema == null)
      throw new IllegalStateException(
          "Multi-input expression has no single schema; use inputLayout()");
    return schema;
  }

  public InputLayout inputLayout() {
    return layout;
  }

  public String source() {
    return source;
  }

  /** Syntax-node count, independent of historical VM opcodes. */
  public int sourceInstructionCount() {
    return nodes;
  }

  public int instructionCount() {
    return constant == null ? nodes : 1;
  }

  public int requiredWorkspaceSize() {
    return workspaceSize;
  }

  public EvaluationWorkspace newWorkspace() {
    return new EvaluationWorkspace(requiredWorkspaceSize());
  }

  public int evaluateRaw(ParameterValues input) {
    return evaluateRaw(input, newWorkspace());
  }

  public int evaluateRaw(ParameterValues input, EvaluationWorkspace workspace) {
    validate(input, workspace);
    return raw(calculate(input, null, workspace));
  }

  public int evaluateRawInputs(InputValues inputs) {
    return evaluateRawInputs(inputs, newWorkspace());
  }

  public int evaluateRawInputs(InputValues inputs, EvaluationWorkspace workspace) {
    validate(inputs, workspace);
    return raw(calculate(null, inputs, workspace));
  }

  public BigDecimal evaluateStored(ParameterValues input) {
    return Numeric.decode(evaluateRaw(input));
  }

  public BigDecimal evaluateStored(ParameterValues input, EvaluationWorkspace workspace) {
    return Numeric.decode(evaluateRaw(input, workspace));
  }

  public BigDecimal evaluateStoredInputs(InputValues inputs) {
    return Numeric.decode(evaluateRawInputs(inputs));
  }

  public BigDecimal evaluateStoredInputs(InputValues inputs, EvaluationWorkspace workspace) {
    return Numeric.decode(evaluateRawInputs(inputs, workspace));
  }

  public BigDecimal evaluateApproximateDecimal(ParameterValues input) {
    return evaluateApproximateDecimal(input, newWorkspace());
  }

  public BigDecimal evaluateApproximateDecimal(
      ParameterValues input, EvaluationWorkspace workspace) {
    validate(input, workspace);
    return decimal(calculate(input, null, workspace));
  }

  public BigDecimal evaluateApproximateDecimalInputs(InputValues inputs) {
    return evaluateApproximateDecimalInputs(inputs, newWorkspace());
  }

  public BigDecimal evaluateApproximateDecimalInputs(
      InputValues inputs, EvaluationWorkspace workspace) {
    validate(inputs, workspace);
    return decimal(calculate(null, inputs, workspace));
  }

  @Deprecated(since = "1.1")
  public BigDecimal evaluate(ParameterValues input) {
    return evaluateApproximateDecimal(input);
  }

  @Deprecated(since = "1.1")
  public BigDecimal evaluate(ParameterValues input, EvaluationWorkspace workspace) {
    return evaluateApproximateDecimal(input, workspace);
  }

  private void validate(ParameterValues input, EvaluationWorkspace workspace) {
    Objects.requireNonNull(input, "input");
    validate(workspace);
    if (schema == null) throw new IllegalStateException("Use named-input evaluation");
    if (input.schema() != schema)
      throw new IllegalArgumentException("Expression and input must use the same schema instance");
  }

  private void validate(InputValues inputs, EvaluationWorkspace workspace) {
    Objects.requireNonNull(inputs, "inputs");
    validate(workspace);
    if (inputs.layout() != layout)
      throw new IllegalArgumentException("Expression and inputs must use the same layout instance");
  }

  private void validate(EvaluationWorkspace workspace) {
    Objects.requireNonNull(workspace, "workspace");
    if (workspace.capacity() < requiredWorkspaceSize())
      throw new IllegalArgumentException("Insufficient workspace capacity");
  }

  private Algebraic calculate(
      ParameterValues legacy, InputValues inputs, EvaluationWorkspace workspace) {
    if (constant != null) return constant;
    try {
      Expression expression = workspace.session(this, prototype);
      for (Field field : fields) {
        ParameterValues values = legacy == null ? inputs.valueAt(field.role()) : legacy;
        expression.with(field.name(), ReadyMath.rawValue(values.getRaw(field.index())));
      }
      return evaluate(expression);
    } catch (ParseException failure) {
      throw new FormulaException(failure.getMessage(), 0);
    }
  }

  private Algebraic evaluate(Expression expression) {
    try {
      return ReadyMath.number(expression.evaluate());
    } catch (EvaluationException failure) {
      throw new FormulaException(
          failure.getMessage(), positions.original(Math.max(0, failure.getStartPosition() - 1)));
    } catch (ParseException failure) {
      throw new FormulaException(
          failure.getMessage(), positions.original(Math.max(0, failure.getStartPosition() - 1)));
    } catch (ArithmeticException failure) {
      throw new FormulaException(failure.getMessage(), Math.max(0, source.length() - 1));
    } catch (StackOverflowError failure) {
      throw new FormulaException("Library expression exceeds recursion limit", 0);
    }
  }

  private int raw(Algebraic value) {
    if (constant != null) {
      if (constantStorageError != null)
        throw new FormulaException(constantStorageError, Math.max(0, source.length() - 1));
      return constantRaw;
    }
    try {
      return ReadyMath.stored(value);
    } catch (ArithmeticException failure) {
      throw new FormulaException(failure.getMessage(), Math.max(0, source.length() - 1));
    }
  }

  private BigDecimal decimal(Algebraic value) {
    try {
      return value.toBigDecimal(new java.math.MathContext(128)).bigDecimal();
    } catch (ArithmeticException failure) {
      throw new FormulaException(failure.getMessage(), Math.max(0, source.length() - 1));
    }
  }
}
