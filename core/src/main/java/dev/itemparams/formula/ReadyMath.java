package dev.itemparams.formula;

import com.ezylang.evalex.*;
import com.ezylang.evalex.config.*;
import com.ezylang.evalex.data.EvaluationValue;
import com.ezylang.evalex.functions.*;
import com.ezylang.evalex.operators.*;
import com.ezylang.evalex.parser.Token;
import java.math.*;
import java.util.Map;
import scala.Option;
import spire.math.Algebraic;
import spire.math.AlgebraicAlgebra;
import spire.math.Rational;
import spire.math.SafeLong;

/** EvalEx extension points delegating numerical work to the unmodified Spire library. */
@SuppressWarnings("unchecked") // The library's dictionary factories use generic varargs.
final class ReadyMath {
  private static final int EXPONENT_LIMIT = 10000;
  private static final Algebraic HUNDRED = Algebraic.apply(100);
  private static final AlgebraicAlgebra ORDER = (AlgebraicAlgebra) Algebraic.AlgebraicAlgebra();
  static final ExpressionConfiguration CONFIGURATION =
      ExpressionConfiguration.builder()
          .mathContext(MathContext.UNLIMITED)
          .decimalPlacesRounding(-1)
          .decimalPlacesResult(-1)
          .binaryAllowed(true)
          .arraysAllowed(false)
          .structuresAllowed(false)
          .implicitMultiplicationAllowed(false)
          .defaultConstants(Map.of())
          .powerOfPrecedence(OperatorIfc.OPERATOR_PRECEDENCE_UNARY)
          .maxRecursionDepth(FormulaCompiler.MAX_DEPTH * 2 + 4)
          .operatorDictionary(
              MapBasedOperatorDictionary.ofOperators(
                  Map.entry("+", new Binary("+")),
                  Map.entry("-", new Binary("-")),
                  Map.entry("*", new Binary("*")),
                  Map.entry("/", new Binary("/")),
                  Map.entry("^", new Binary("^")),
                  Map.entry("+", new Unary(false)),
                  Map.entry("-", new Unary(true))))
          .functionDictionary(
              MapBasedFunctionDictionary.ofFunctions(
                  Map.entry("sqrt", new Sqrt()), Map.entry("pow", new Pair("pow")),
                  Map.entry("root", new Pair("root")), Map.entry("min", new Pair("min")),
                  Map.entry("max", new Pair("max")), Map.entry("clamp", new Clamp())))
          .build();

  private ReadyMath() {}

  static EvaluationValue rawValue(int raw) {
    return EvaluationValue.binaryValue(Algebraic.apply(Rational.apply(raw, 100)));
  }

  static Algebraic number(EvaluationValue value) {
    if (value.isBinaryValue() && value.getValue() instanceof Algebraic algebraic) return algebraic;
    if (value.isNumberValue())
      return Algebraic.apply(Rational.apply(value.getNumberValue().toString()));
    throw new ArithmeticException("Only numerical values are supported");
  }

  static int stored(Algebraic value) {
    Option<Rational> fraction = value.toRational();
    BigInteger integer;
    if (fraction.isDefined()) {
      integer = fraction.get().$times(Rational.apply(100)).toBigInt().bigInteger();
    } else {
      Algebraic scaled = value.$times(HUNDRED);
      integer = scaled.toBigInt().bigInteger();
      // Certify the library's conversion with its exact ordering. No epsilon,
      // correction or additional rounding algorithm is used by this adapter.
      Algebraic candidate = Algebraic.apply(Rational.apply(integer.toString()));
      boolean valid =
          scaled.signum() >= 0
              ? scaled.compare(candidate) >= 0
                  && scaled.compare(candidate.$plus(Algebraic.One())) < 0
              : scaled.compare(candidate) <= 0
                  && scaled.compare(candidate.$minus(Algebraic.One())) > 0;
      if (!valid) throw new ArithmeticException("Library could not certify the final hundredth");
    }
    try {
      return integer.intValueExact();
    } catch (ArithmeticException overflow) {
      throw new ArithmeticException("Result exceeds signed int32 parameter range");
    }
  }

  private static Rational exponent(Algebraic value) {
    Option<Rational> exact = value.toRational();
    if (exact.isDefined()) return exact.get();
    // Use Spire's bounded rational reconstruction only as a candidate, then prove
    // equality with its exact algebraic comparison. An approximation is never accepted.
    Rational candidate =
        Rational.apply(value.toBigDecimal(new MathContext(64)))
            .limitDenominatorTo(SafeLong.apply(EXPONENT_LIMIT));
    if (value.compare(Algebraic.apply(candidate)) != 0)
      throw new ArithmeticException(
          "Power/root exponent must be a provable bounded rational number");
    return candidate;
  }

  private static int bounded(BigInteger value) {
    if (value.abs().compareTo(BigInteger.valueOf(EXPONENT_LIMIT)) > 0)
      throw new ArithmeticException("Power/root exponent exceeds " + EXPONENT_LIMIT);
    return value.intValueExact();
  }

  private static Algebraic power(Algebraic base, Algebraic degree) {
    Rational exponent = exponent(degree);
    if (base.isZero() && exponent.signum() <= 0)
      throw new ArithmeticException("Zero requires a positive exponent");
    if (base.signum() < 0 && !exponent.isWhole())
      throw new ArithmeticException("Negative base requires an integer exponent; use root");
    if (base.isZero()) return Algebraic.Zero();
    if (base.compare(Algebraic.One()) == 0) return Algebraic.One();
    int p = bounded(exponent.numerator().toBigInteger()),
        q = bounded(exponent.denominator().toBigInteger());
    if (q == 1) {
      Option<Rational> fraction = base.toRational();
      if (fraction.isDefined()) {
        Rational value = fraction.get();
        int bits =
            Math.max(
                value.numerator().toBigInteger().abs().bitLength(),
                value.denominator().toBigInteger().bitLength());
        if (bits > 1 && (long) (bits - 1) * Math.abs((long) p) > 4096)
          throw new ArithmeticException("Power exceeds 4096-bit resource limit");
      }
      return base.pow(p);
    }
    return base.nroot(q).pow(p);
  }

  private static Algebraic root(Algebraic value, Algebraic degree) {
    Rational exponent = exponent(degree);
    if (exponent.signum() == 0) throw new ArithmeticException("Root degree cannot be zero");
    if (value.isZero()) {
      if (exponent.signum() < 0)
        throw new ArithmeticException("Zero cannot have a negative root degree");
      return Algebraic.Zero();
    }
    if (value.compare(Algebraic.One()) == 0) return Algebraic.One();
    int p = bounded(exponent.numerator().toBigInteger()),
        q = bounded(exponent.denominator().toBigInteger());
    if (value.signum() < 0 && (q != 1 || (p & 1) == 0))
      throw new ArithmeticException("Negative radicand requires an odd integer root");
    if (value.isZero() && p < 0)
      throw new ArithmeticException("Zero cannot have a negative root degree");
    return value.signum() < 0
        ? value.unary_$minus().nroot(p).pow(q).unary_$minus()
        : value.nroot(p).pow(q);
  }

  private static EvaluationValue result(Algebraic result) {
    return EvaluationValue.binaryValue(result);
  }

  private static EvaluationException failure(Token token, RuntimeException failure) {
    return new EvaluationException(
        token, failure.getMessage() == null ? "Numerical operation failed" : failure.getMessage());
  }

  @InfixOperator(precedence = 20)
  private static final class Binary extends AbstractOperator {
    private final String operation;

    Binary(String operation) {
      this.operation = operation;
    }

    @Override
    public int getPrecedence(ExpressionConfiguration configuration) {
      return switch (operation) {
        case "*", "/" -> 30;
        case "^" -> 60;
        default -> 20;
      };
    }

    @Override
    public boolean isLeftAssociative() {
      return !operation.equals("^");
    }

    @Override
    public EvaluationValue evaluate(Expression expression, Token token, EvaluationValue... operands)
        throws EvaluationException {
      try {
        Algebraic a = number(operands[0]), b = number(operands[1]);
        if (operation.equals("/") && b.isZero()) throw new ArithmeticException("Division by zero");
        return result(
            switch (operation) {
              case "+" -> a.$plus(b);
              case "-" -> a.$minus(b);
              case "*" -> a.$times(b);
              case "/" -> a.$div(b);
              case "^" -> power(a, b);
              default -> throw new IllegalStateException();
            });
      } catch (RuntimeException error) {
        throw failure(token, error);
      }
    }
  }

  @PrefixOperator(leftAssociative = false)
  private static final class Unary extends AbstractOperator {
    private final boolean negative;

    Unary(boolean negative) {
      this.negative = negative;
    }

    @Override
    public EvaluationValue evaluate(Expression expression, Token token, EvaluationValue... operands)
        throws EvaluationException {
      try {
        Algebraic value = number(operands[0]);
        return result(negative ? value.unary_$minus() : value);
      } catch (RuntimeException error) {
        throw failure(token, error);
      }
    }
  }

  @FunctionParameter(name = "value")
  private static final class Sqrt extends AbstractFunction {
    @Override
    public EvaluationValue evaluate(
        Expression expression, Token token, EvaluationValue... parameters)
        throws EvaluationException {
      try {
        Algebraic value = number(parameters[0]);
        if (value.signum() < 0)
          throw new ArithmeticException("Square root requires a nonnegative value");
        return result(value.sqrt());
      } catch (RuntimeException error) {
        throw failure(token, error);
      }
    }
  }

  @FunctionParameter(name = "a")
  @FunctionParameter(name = "b")
  private static final class Pair extends AbstractFunction {
    private final String operation;

    Pair(String operation) {
      this.operation = operation;
    }

    @Override
    public EvaluationValue evaluate(
        Expression expression, Token token, EvaluationValue... parameters)
        throws EvaluationException {
      try {
        Algebraic a = number(parameters[0]), b = number(parameters[1]);
        return result(
            switch (operation) {
              case "pow" -> power(a, b);
              case "root" -> root(a, b);
              case "min" -> (Algebraic) ORDER.min(a, b);
              case "max" -> (Algebraic) ORDER.max(a, b);
              default -> throw new IllegalStateException();
            });
      } catch (RuntimeException error) {
        throw failure(token, error);
      }
    }
  }

  @FunctionParameter(name = "value")
  @FunctionParameter(name = "low")
  @FunctionParameter(name = "high")
  private static final class Clamp extends AbstractFunction {
    @Override
    public EvaluationValue evaluate(
        Expression expression, Token token, EvaluationValue... parameters)
        throws EvaluationException {
      try {
        Algebraic value = number(parameters[0]),
            low = number(parameters[1]),
            high = number(parameters[2]);
        if (low.compare(high) > 0) throw new ArithmeticException("Clamp minimum exceeds maximum");
        return result((Algebraic) ORDER.min(ORDER.max(value, low), high));
      } catch (RuntimeException error) {
        throw failure(token, error);
      }
    }
  }
}
