package dev.itemparams.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Signed int32 fixed point, two decimal places, truncation toward zero. */
public final class Numeric {
    public static final int SCALE = 2;
    public static final BigDecimal MIN_VALUE = BigDecimal.valueOf(Integer.MIN_VALUE,SCALE);
    public static final BigDecimal MAX_VALUE = BigDecimal.valueOf(Integer.MAX_VALUE,SCALE);
    private static final BigDecimal UNIT = new BigDecimal("0.01");
    private static final BigDecimal UPPER_EXCLUSIVE=MAX_VALUE.add(UNIT), LOWER_EXCLUSIVE=MIN_VALUE.subtract(UNIT);
    private Numeric() {}

    public static int encode(BigDecimal value) {
        if (value == null) throw new NullPointerException("value");
        // Reject huge exponent values before setScale can materialize a huge unscaled integer.
        if (value.compareTo(UPPER_EXCLUSIVE)>=0 || value.compareTo(LOWER_EXCLUSIVE)<=0)
            throw new IllegalArgumentException("Value is outside signed int32 fixed-point range");
        if (value.abs().compareTo(UNIT)<0) return 0;
        try { return value.setScale(SCALE,RoundingMode.DOWN).unscaledValue().intValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Value is outside signed int32 fixed-point range: " + value,e); }
    }

    public static int encode(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Value must be finite");
        return encode(BigDecimal.valueOf(value));
    }

    public static BigDecimal decode(int raw) { return BigDecimal.valueOf(raw,SCALE); }
    /** Strict configuration value: no truncation, and within the representable decimal interval. */
    public static int encodeExact(BigDecimal value) {
        if (value.compareTo(MIN_VALUE)<0 || value.compareTo(MAX_VALUE)>0
                || value.stripTrailingZeros().scale()>SCALE)
            throw new IllegalArgumentException("Configuration value must fit signed int32 with at most two decimal places");
        return encode(value);
    }
}
