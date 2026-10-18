package dev.itemparams.formula;

import com.ezylang.evalex.Expression;
import com.ezylang.evalex.parser.ParseException;

/** Owner-confined EvalEx sessions sharing already parsed syntax trees. */
public final class EvaluationWorkspace {
  private final int capacity;
  private CompiledExpression owner;
  private Expression expression;

  public EvaluationWorkspace(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
    this.capacity = capacity;
  }

  public int capacity() {
    return capacity;
  }

  Expression session(CompiledExpression owner, Expression prototype) throws ParseException {
    if (this.owner != owner) {
      expression = prototype.copy();
      this.owner = owner;
    }
    return expression;
  }

  public void clear() {
    owner = null;
    expression = null;
  }
}
