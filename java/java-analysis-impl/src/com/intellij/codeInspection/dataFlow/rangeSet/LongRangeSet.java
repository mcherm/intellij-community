// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInspection.dataFlow.rangeSet;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInspection.dataFlow.DfaFactType;
import com.intellij.codeInspection.dataFlow.value.*;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.ThreeState;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * An immutable set of long values optimized for small number of ranges.
 *
 * @author Tagir Valeev
 */
public abstract class LongRangeSet {
  private static final String JETBRAINS_RANGE = "org.jetbrains.annotations.Range";
  private static final String CHECKER_RANGE = "org.checkerframework.common.value.qual.IntRange";
  private static final String CHECKER_GTE_NEGATIVE_ONE = "org.checkerframework.checker.index.qual.GTENegativeOne";
  private static final String CHECKER_NON_NEGATIVE = "org.checkerframework.checker.index.qual.NonNegative";
  private static final String CHECKER_POSITIVE = "org.checkerframework.checker.index.qual.Positive";
  private static final String JSR305_NONNEGATIVE = "javax.annotation.Nonnegative";
  private static final String VALIDATION_MIN = "javax.validation.constraints.Min";
  private static final String VALIDATION_MAX = "javax.validation.constraints.Max";
  private static final List<String> ANNOTATIONS = Arrays.asList(CHECKER_RANGE,
                                                                CHECKER_GTE_NEGATIVE_ONE,
                                                                CHECKER_NON_NEGATIVE,
                                                                CHECKER_POSITIVE,
                                                                JSR305_NONNEGATIVE,
                                                                VALIDATION_MIN,
                                                                VALIDATION_MAX);

  LongRangeSet() {}

  /**
   * Subtracts given set from the current
   *
   * @param other set to subtract
   * @return a new set
   */
  public abstract LongRangeSet subtract(LongRangeSet other);

  public LongRangeSet without(long value) {
    return subtract(point(value));
  }

  /**
   * @return true if set is empty
   */
  public boolean isEmpty() {
    return this == Empty.EMPTY;
  }

  /**
   * Intersects current set with other
   *
   * @param other other set to intersect with
   * @return a new set
   */
  public abstract LongRangeSet intersect(LongRangeSet other);

  /**
   * Merge current set with other
   *
   * @param other other set to merge with
   * @return a new set
   */
  public LongRangeSet unite(LongRangeSet other) {
    if(other.isEmpty() || other == this) return this;
    if(other.contains(this)) return other;
    // TODO: optimize
    return Range.LONG_RANGE.subtract(Range.LONG_RANGE.subtract(this).intersect(Range.LONG_RANGE.subtract(other)));
  }

  /**
   * @return a minimal value contained in the set
   * @throws NoSuchElementException if set is empty
   */
  public abstract long min();

  /**
   * @return a maximal value contained in the set
   * @throws NoSuchElementException if set is empty
   */
  public abstract long max();

  /**
   * Checks if current set and other set have at least one common element
   *
   * @param other other set to check whether intersection exists
   * @return true if this set intersects other set
   */
  public abstract boolean intersects(LongRangeSet other);

  /**
   * Checks whether current set contains given value
   *
   * @param value value to find
   * @return true if current set contains given value
   */
  public abstract boolean contains(long value);

  /**
   * Checks whether current set contains all the values from other set
   *
   * @param other a sub-set candidate
   * @return true if current set contains all the values from other
   */
  public abstract boolean contains(LongRangeSet other);

  /**
   * Creates a new set which contains all possible values satisfying given predicate regarding the current set.
   * <p>
   *   E.g. if current set is {0..10} and relation is "GT", then result will be {1..Long.MAX_VALUE} (values which can be greater than
   *   some value from the current set)
   *
   * @param relation relation to be applied to current set (JavaTokenType.EQEQ/NE/GT/GE/LT/LE)
   * @return new set or null if relation is unsupported
   */
  public LongRangeSet fromRelation(@Nullable DfaRelationValue.RelationType relation) {
    if (isEmpty() || relation == null) return null;
    switch (relation) {
      case EQ:
        return this;
      case NE: {
        long min = min();
        if (min == max()) return all().without(min);
        return all();
      }
      case GT: {
        long min = min();
        return min == Long.MAX_VALUE ? empty() : range(min + 1, Long.MAX_VALUE);
      }
      case GE:
        return range(min(), Long.MAX_VALUE);
      case LE:
        return range(Long.MIN_VALUE, max());
      case LT: {
        long max = max();
        return max == Long.MIN_VALUE ? empty() : range(Long.MIN_VALUE, max - 1);
      }
      default:
        return null;
    }
  }

  /**
   * Performs a supported binary operation from token (defined in {@link JavaTokenType}).
   *
   * @param token  a token which corresponds to the operation
   * @param right  a right-hand operand
   * @param isLong true if operation should be performed on long types (otherwise int is assumed)
   * @return the resulting LongRangeSet which covers possible results of the operation (probably including some more elements);
   * or null if the supplied token is not supported.
   */
  @Contract("null, _, _ -> null")
  @Nullable
  public final LongRangeSet binOpFromToken(IElementType token, LongRangeSet right, boolean isLong) {
    if (token == null) return null;
    if (token.equals(JavaTokenType.PLUS)) {
      return plus(right, isLong);
    }
    if (token.equals(JavaTokenType.MINUS)) {
      return minus(right, isLong);
    }
    if (token.equals(JavaTokenType.AND)) {
      return bitwiseAnd(right);
    }
    if (token.equals(JavaTokenType.PERC)) {
      return mod(right);
    }
    if (token.equals(JavaTokenType.DIV)) {
      return div(right, isLong);
    }
    if (token.equals(JavaTokenType.GTGT)) {
      return shiftRight(right, isLong);
    }
    if (token.equals(JavaTokenType.GTGTGT)) {
      return unsignedShiftRight(right, isLong);
    }
    if (token.equals(JavaTokenType.ASTERISK)) {
      return mul(right, isLong);
    }
    return null;
  }

  public abstract LongRangeSet castTo(PsiPrimitiveType type);

  /**
   * Returns a range which represents all the possible values after applying {@link Math#abs(int)} or {@link Math#abs(long)}
   * to the values from this set
   *
   * @param isLong whether {@link Math#abs(long)} is applied
   * @return a new range
   */
  @NotNull
  public abstract LongRangeSet abs(boolean isLong);

  /**
   * Returns a range which represents all the possible values after applying unary minus
   * to the values from this set
   *
   * @param isLong whether result should be truncated to {@code int}
   * @return a new range
   */
  @NotNull
  public abstract LongRangeSet negate(boolean isLong);

  /**
   * Returns a range which represents all the possible values after performing an addition between any value from this range
   * and any value from other range. The resulting range may contain some more values which cannot be produced by addition.
   * Guaranteed to be commutative.
   *
   * @param isLong whether result should be truncated to {@code int}
   * @return a new range
   */
  @NotNull
  public abstract LongRangeSet plus(LongRangeSet other, boolean isLong);

  /**
   * Returns a range which represents all the possible values after performing an addition between any value from this range
   * and any value from other range. The resulting range may contain some more values which cannot be produced by addition.
   *
   * @param isLong whether result should be truncated to {@code int}
   * @return a new range
   */
  @NotNull
  public LongRangeSet minus(LongRangeSet other, boolean isLong) {
    return plus(other.negate(isLong), isLong);
  }

  /**
   * Returns a range which represents all the possible values after applying {@code x & y} operation for
   * all {@code x} from this set and for all {@code y} from the other set. The resulting set may contain
   * some more values.
   *
   * @param other other set to perform bitwise-and with
   * @return a new range
   */
  @NotNull
  public LongRangeSet bitwiseAnd(LongRangeSet other) {
    if (this.isEmpty() || other.isEmpty()) return empty();
    long[] left = splitAtZero(asRanges());
    long[] right = splitAtZero(other.asRanges());
    // More than three intervals --> convert to single interval to make result more compact (though probably less precise)
    if (left.length > 6) {
      left = splitAtZero(new long[]{left[0], left[left.length - 1]});
    }
    if (right.length > 6) {
      right = splitAtZero(new long[]{right[0], right[right.length - 1]});
    }
    LongRangeSet result = all();
    for (int i = 0; i < left.length; i += 2) {
      for (int j = 0; j < right.length; j += 2) {
        result = result.subtract(bitwiseAnd(left[i], left[i + 1], right[j], right[j + 1]));
      }
    }
    return all().subtract(result);
  }
  
  abstract public LongRangeSet mul(LongRangeSet multiplier, boolean isLong);

  /**
   * Returns a range which represents all the possible values after applying {@code x / y} operation for
   * all {@code x} from this set and for all {@code y} from the divisor set. The resulting set may contain
   * some more values. Division by zero yields an empty set of possible results.
   *
   * @param divisor divisor set to divide by
   * @param isLong whether the operation is performed on long type (if false, the int type is assumed). This only changes the special
   *               treatment of {@code MIN_VALUE/-1} division; other division results do not depend on the resulting type.
   * @return a new range
   */
  @NotNull
  public LongRangeSet div(LongRangeSet divisor, boolean isLong) {
    if (divisor.isEmpty() || divisor.equals(new Point(0))) return empty();
    long[] left = splitAtZero(asRanges());
    long[] right = splitAtZero(new long[]{divisor.min(), divisor.max()});
    LongRangeSet result = empty();
    for (int i = 0; i < left.length; i += 2) {
      for (int j = 0; j < right.length; j += 2) {
        result = result.unite(divide(left[i], left[i + 1], right[j], right[j + 1], isLong));
      }
    }
    return result;
  }

  /**
   * Checks whether subtraction of this and other range may overflow
   * @param other range to subtract from this range
   * @param isLong whether subtraction should be performed on long values (otherwise int is assumed)
   * @return true if result may overflow, false if it never overflows
   */
  public boolean subtractionMayOverflow(LongRangeSet other, boolean isLong) {
    long leftMin = min();
    long leftMax = max();
    long rightMin = other.min();
    long rightMax = other.max();
    return isLong
           ? overflowsLong(leftMin, rightMax) || overflowsLong(leftMax, rightMin)
           : overflowsInt(leftMin, rightMax) || overflowsInt(leftMax, rightMin);
  }

  private static boolean overflowsInt(long a, long b) {
    long diff = a - b;
    return diff < Integer.MIN_VALUE || diff > Integer.MAX_VALUE;
  }

  private static boolean overflowsLong(long a, long b) {
    long diff = a - b;
    // Hacker's Delight 2nd Edition, 2-13 Overflow Detection
    return ((a ^ b) & (a ^ diff)) < 0;
  }

  @NotNull
  private static LongRangeSet divide(long dividendMin, long dividendMax, long divisorMin, long divisorMax, boolean isLong) {
    if (divisorMin == 0) {
      if (divisorMax == 0) return empty();
      divisorMin = 1;
    }
    if (dividendMin >= 0) {
      return divisorMin > 0
             ? range(dividendMin / divisorMax, dividendMax / divisorMin)
             : range(dividendMax / divisorMax, dividendMin / divisorMin);
    }
    if (divisorMin > 0) {
      return range(dividendMin / divisorMin, dividendMax / divisorMax);
    }
    long minValue = minValue(isLong);
    if (dividendMin == minValue && divisorMax == -1) {
      // MIN_VALUE/-1 = MIN_VALUE
      return point(minValue)
        .unite(divisorMin == -1 ? empty() : range(dividendMin / divisorMin, dividendMin / (divisorMax - 1)))
        .unite(dividendMax == minValue ? empty() : range(dividendMax / divisorMin, (dividendMin + 1) / divisorMax));
    }
    return range(dividendMax / divisorMin, dividendMin / divisorMax);
  }

  /**
   * Returns a range which represents all the possible values after applying {@code x >> y} operation for
   * all {@code x} from this set and for all {@code y} from the shiftSize set. The resulting set may contain
   * some more values.
   *
   * @param shiftSize set of possible shift sizes (number of bits to shift to the right)
   * @param isLong whether the operation is performed on long type (if false, the int type is assumed).
   * @return a new range
   */
  @NotNull
  public LongRangeSet shiftRight(LongRangeSet shiftSize, boolean isLong) {
    if (isEmpty() || shiftSize.isEmpty()) return empty();
    int maxShift = (isLong ? Long.SIZE : Integer.SIZE) - 1;
    if (shiftSize.min() < 0 || shiftSize.max() > maxShift) {
      shiftSize = shiftSize.bitwiseAnd(point(maxShift));
    }
    long min = shiftSize.min();
    long max = shiftSize.max();
    LongRangeSet negative = intersect(range(minValue(isLong), -1));
    LongRangeSet positive = intersect(range(0, maxValue(isLong)));
    return positive.shrPositive(min, max, isLong)
                   .unite(point(-1).minus(point(-1).minus(negative, isLong).shrPositive(min, max, isLong), isLong));
  }

  /**
   * Returns a range which represents all the possible values after applying {@code x >>> y} operation for
   * all {@code x} from this set and for all {@code y} from the shiftSize set. The resulting set may contain
   * some more values.
   *
   * @param shiftSize set of possible shift sizes (number of bits to shift to the right)
   * @param isLong whether the operation is performed on long type (if false, the int type is assumed).
   * @return a new range
   */
  @NotNull
  public LongRangeSet unsignedShiftRight(LongRangeSet shiftSize, boolean isLong) {
    if (isEmpty() || shiftSize.isEmpty()) return empty();
    int maxShift = (isLong ? Long.SIZE : Integer.SIZE) - 1;
    if (shiftSize.min() < 0 || shiftSize.max() > maxShift) {
      shiftSize = shiftSize.bitwiseAnd(point(maxShift));
    }
    long min = shiftSize.min();
    long max = shiftSize.max();
    LongRangeSet negative = intersect(range(minValue(isLong), -1));
    LongRangeSet positive = intersect(range(0, maxValue(isLong)));
    LongRangeSet result = positive.shrPositive(min, max, isLong);
    if (min == 0) {
      result = result.unite(negative);
      if (max == 0) return result;
      min++;
    }
    // for x < 0, y > 0, x >>> y = (MAX_VALUE - ((-1-x) >> 1)) >> (y-1)
    return result.unite(point(maxValue(isLong)).minus(point(-1).minus(negative, isLong).shrPositive(1, 1, isLong), isLong)
                                               .shrPositive(min - 1, max - 1, isLong));
  }

  private LongRangeSet shrPositive(long min, long max, boolean isLong) {
    if (isEmpty()) return empty();
    int maxShift = (isLong ? Long.SIZE : Integer.SIZE) - 1;
    if (max == maxShift) {
      return min == max ? point(0) : point(0).unite(div(range(1L << min, 1L << (max - 1)), isLong));
    }
    return div(range(1L << min, 1L << max), isLong);
  }

  /**
   * Returns a range which represents all the possible values after applying {@code x % y} operation for
   * all {@code x} from this set and for all {@code y} from the divisor set. The resulting set may contain
   * some more values. Division by zero yields an empty set of possible results.
   *
   * @param divisor divisor set to divide by
   * @return a new range
   */
  @NotNull
  abstract public LongRangeSet mod(LongRangeSet divisor);

  private static long[] splitAtZero(long[] ranges) {
    for (int i = 0; i < ranges.length; i += 2) {
      if (ranges[i] < 0 && ranges[i + 1] >= 0) {
        long[] result = new long[ranges.length + 2];
        System.arraycopy(ranges, 0, result, 0, i + 1);
        result[i + 1] = -1;
        System.arraycopy(ranges, i + 1, result, i + 3, ranges.length - i - 1);
        return result;
      }
    }
    return ranges;
  }

  private static LongRangeSet bitwiseAnd(long leftFrom, long leftTo, long rightFrom, long rightTo) {
    if (leftFrom == leftTo && rightFrom == rightTo) {
      return point(leftFrom & rightFrom);
    }
    if (leftFrom == leftTo && Long.bitCount(leftFrom+1) == 1) {
      return bitwiseMask(rightFrom, rightTo, leftFrom);
    }
    if (rightFrom == rightTo && Long.bitCount(rightFrom+1) == 1) {
      return bitwiseMask(leftFrom, leftTo, rightFrom);
    }
    ThreeState[] leftBits = bits(leftFrom, leftTo);
    ThreeState[] rightBits = bits(rightFrom, rightTo);
    ThreeState[] resultBits = new ThreeState[Long.SIZE];
    for (int i = 0; i < Long.SIZE; i++) {
      if (leftBits[i] == ThreeState.NO || rightBits[i] == ThreeState.NO) {
        resultBits[i] = ThreeState.NO;
      }
      else if (leftBits[i] == ThreeState.UNSURE || rightBits[i] == ThreeState.UNSURE) {
        resultBits[i] = ThreeState.UNSURE;
      }
      else {
        resultBits[i] = ThreeState.YES;
      }
    }
    return fromBits(resultBits);
  }

  /**
   * Returns the range after applying the mask to the input range which looks like 0..01..1 in binary
   * @param from input range start
   * @param to input range end
   * @param mask mask
   * @return range set after applying the mask
   */
  private static LongRangeSet bitwiseMask(long from, long to, long mask) {
    if (to - from > mask) return range(0, mask);
    long min = from & mask;
    long max = to & mask;
    assert min != max;
    if (min < max) return range(min, max);
    return new RangeSet(new long[] {0, max, min, mask});
  }

  /**
   * Creates a set which contains all the numbers satisfying the supplied bit vector.
   * Vector format is the same as returned by {@link #bits(long, long)}. The resulting set may
   * contain more values than necessary.
   *
   * @param bits a bit vector
   * @return a new LongRangeSet
   */
  private static LongRangeSet fromBits(ThreeState[] bits) {
    long from = 0;
    int i = 0;
    while (i < Long.SIZE && bits[i] != ThreeState.UNSURE) {
      if (bits[i] == ThreeState.YES) {
        from |= (1L << (Long.SIZE - 1 - i));
      }
      i++;
    }
    long to = ((1L << (Long.SIZE - i)) - 1) | from;
    int j = Long.SIZE - 1;
    while(j > i && bits[j] != ThreeState.UNSURE) {
      if (bits[j] == ThreeState.NO) {
        to &= ~(1L << Long.SIZE - 1 - j);
      }
      j--;
    }
    if(i == j) {
      return point(from).unite(point(to));
    }
    return from < to ? range(from, to) : range(to, from);
  }

  /**
   * Returns a bit vector for values between from and to.
   *
   * @param from lower bound
   * @param to upper bound
   * @return an array of 64 ThreeState values (NO = zero bit for all values, YES = one bit for all values,
   * UNSURE = both one and zero possible)
   */
  private static ThreeState[] bits(long from, long to) {
    ThreeState[] bits = new ThreeState[Long.SIZE];
    Arrays.fill(bits, ThreeState.NO);
    while (true) {
      int fromBit = Long.numberOfLeadingZeros(from);
      int toBit = Long.numberOfLeadingZeros(to);
      if (fromBit != toBit) {
        for (int i = Math.min(fromBit, toBit); i < Long.SIZE; i++) {
          bits[i] = ThreeState.UNSURE;
        }
        break;
      }
      if (fromBit == 64) break;
      bits[fromBit] = ThreeState.YES;
      long clearMask = ~(1L << (Long.SIZE - 1 - fromBit));
      from &= clearMask;
      to &= clearMask;
    }
    return bits;
  }

  private static String formatNumber(long value) {
    if (value == Long.MAX_VALUE) return "Long.MAX_VALUE";
    if (value == Long.MAX_VALUE - 1) return "Long.MAX_VALUE-1";
    if (value == Long.MIN_VALUE) return "Long.MIN_VALUE";
    if (value == Integer.MAX_VALUE) return "Integer.MAX_VALUE";
    if (value == Integer.MAX_VALUE - 1) return "Integer.MAX_VALUE-1";
    if (value == Integer.MIN_VALUE) return "Integer.MIN_VALUE";
    return String.valueOf(value);
  }
  /**
   * Returns a stream of all values from this range. Be careful: could be huge
   *
   * @return a new stream
   */
  public abstract LongStream stream();

  /**
   * @return an empty set
   */
  public static LongRangeSet empty() {
    return Empty.EMPTY;
  }

  /**
   * @return a set containing all possible long values
   */
  public static LongRangeSet all() {
    return Range.LONG_RANGE;
  }

  /**
   * Creates a set containing single given value
   *
   * @param value a value to be included into the set
   * @return a new set
   */
  public static LongRangeSet point(long value) {
    return new Point(value);
  }

  /**
   * Creates a set containing single value which is equivalent to supplied boxed constant (if its type is supported)
   *
   * @param val constant to create a set from
   * @return new LongRangeSet or null if constant type is unsupported
   */
  @Nullable
  public static LongRangeSet fromConstant(Object val) {
    if (val instanceof Byte || val instanceof Short || val instanceof Integer || val instanceof Long) {
      return point(((Number)val).longValue());
    }
    else if (val instanceof Character) {
      return point(((Character)val).charValue());
    }
    return null;
  }

  @Nullable
  public static LongRangeSet fromDfaValue(DfaValue value) {
    if (value instanceof DfaFactMapValue) {
      return ((DfaFactMapValue)value).get(DfaFactType.RANGE);
    }
    if (value instanceof DfaConstValue) {
      return fromConstant(((DfaConstValue)value).getValue());
    }
    if (value instanceof DfaVariableValue) {
      return fromType(value.getType());
    }
    return null;
  }

  /**
   * Creates a new set which contains all the numbers between from (inclusive) and to (inclusive)
   *
   * @param from lower bound
   * @param to upper bound (must be greater or equal to {@code from})
   * @return a new LongRangeSet
   */
  public static LongRangeSet range(long from, long to) {
    return from == to ? new Point(from) : new Range(from, to);
  }

  abstract long[] asRanges();

  static String toString(long from, long to) {
    return formatNumber(from) + (from == to ? "" : (to - from == 1 ? ", " : "..") + formatNumber(to));
  }

  static long minValue(boolean isLong) {
    return isLong ? Long.MIN_VALUE : Integer.MIN_VALUE;
  }

  static long maxValue(boolean isLong) {
    return isLong ? Long.MAX_VALUE : Integer.MAX_VALUE;
  }

  /**
   * @return LongRangeSet describing possible array or string indices (from 0 to Integer.MAX_VALUE)
   */
  public static LongRangeSet indexRange() {
    return Range.INDEX_RANGE;
  }

  /**
   * Creates a range for given type (for primitives and boxed: values range)
   *
   * @param type type to create a range for
   * @return a range or null if type is not supported
   */
  @Nullable
  public static LongRangeSet fromType(PsiType type) {
    if (!(type instanceof PsiPrimitiveType) && !TypeConversionUtil.isPrimitiveWrapper(type)) return null;
    type = PsiPrimitiveType.getOptionallyUnboxedType(type);
    if (type != null) {
      if (type.equals(PsiType.BYTE)) {
        return Range.BYTE_RANGE;
      }
      if (type.equals(PsiType.CHAR)) {
        return Range.CHAR_RANGE;
      }
      if (type.equals(PsiType.SHORT)) {
        return Range.SHORT_RANGE;
      }
      if (type.equals(PsiType.INT)) {
        return Range.INT_RANGE;
      }
      if (type.equals(PsiType.LONG)) {
        return all();
      }
    }
    return null;
  }

  @NotNull
  public static LongRangeSet fromPsiElement(PsiModifierListOwner owner) {
    if (owner == null) return all();
    return StreamEx.ofNullable(AnnotationUtil.findAnnotation(owner, JETBRAINS_RANGE))
                   .append(AnnotationUtil.findAnnotations(owner, ANNOTATIONS))
                   .map(LongRangeSet::fromAnnotation).foldLeft(all(), LongRangeSet::intersect);
  }

  private static LongRangeSet fromAnnotation(PsiAnnotation annotation) {
    switch (Objects.requireNonNull(annotation.getQualifiedName())) {
      case JETBRAINS_RANGE:
      case CHECKER_RANGE:
        Long from = AnnotationUtil.getLongAttributeValue(annotation, "from");
        Long to = AnnotationUtil.getLongAttributeValue(annotation, "to");
        if(from != null && to != null && to >= from) {
          return range(from, to);
        }
        break;
      case VALIDATION_MIN:
        Long minValue = AnnotationUtil.getLongAttributeValue(annotation, "value");
        if (minValue != null && annotation.findDeclaredAttributeValue("groups") == null) {
          return range(minValue, Long.MAX_VALUE);
        }
        break;
      case VALIDATION_MAX:
        Long maxValue = AnnotationUtil.getLongAttributeValue(annotation, "value");
        if (maxValue != null && annotation.findDeclaredAttributeValue("groups") == null) {
          return range(Long.MIN_VALUE, maxValue);
        }
        break;
      case CHECKER_GTE_NEGATIVE_ONE:
        return range(-1, Long.MAX_VALUE);
      case JSR305_NONNEGATIVE:
      case CHECKER_NON_NEGATIVE:
        return range(0, Long.MAX_VALUE);
      case CHECKER_POSITIVE:
        return range(1, Long.MAX_VALUE);
    }
    return all();
  }

  static LongRangeSet fromRanges(long[] ranges, int bound) {
    if (bound == 0) {
      return Empty.EMPTY;
    }
    else if (bound == 2) {
      return range(ranges[0], ranges[1]);
    }
    else {
      return new RangeSet(Arrays.copyOfRange(ranges, 0, bound));
    }
  }

  static final class Empty extends LongRangeSet {
    static final LongRangeSet EMPTY = new Empty();

    @Override
    public LongRangeSet subtract(LongRangeSet other) {
      return this;
    }

    @Override
    public LongRangeSet intersect(LongRangeSet other) {
      return this;
    }

    @Override
    public LongRangeSet unite(LongRangeSet other) {
      return other;
    }

    @Override
    public long min() {
      throw new NoSuchElementException();
    }

    @Override
    public long max() {
      throw new NoSuchElementException();
    }

    @Override
    public boolean intersects(LongRangeSet other) {
      return false;
    }

    @Override
    public boolean contains(long value) {
      return false;
    }

    @Override
    public boolean contains(LongRangeSet other) {
      return other.isEmpty();
    }

    @Override
    public LongRangeSet castTo(PsiPrimitiveType type) {
      if (TypeConversionUtil.isIntegralNumberType(type)) {
        return this;
      }
      throw new IllegalArgumentException(type.toString());
    }

    @NotNull
    @Override
    public LongRangeSet abs(boolean isLong) {
      return this;
    }

    @NotNull
    @Override
    public LongRangeSet negate(boolean isLong) {
      return this;
    }

    @NotNull
    @Override
    public LongRangeSet plus(LongRangeSet other, boolean isLong) {
      return this;
    }

    @Override
    public LongRangeSet mul(LongRangeSet multiplier, boolean isLong) {
      return this;
    }

    @NotNull
    @Override
    public LongRangeSet mod(LongRangeSet divisor) {
      return empty();
    }

    @Override
    public LongStream stream() {
      return LongStream.empty();
    }

    @Override
    long[] asRanges() {
      return new long[0];
    }

    @Override
    public int hashCode() {
      return 2154231;
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this;
    }

    @Override
    public String toString() {
      return "{}";
    }
  }

  static final class Point extends LongRangeSet {
    final long myValue;

    Point(long value) {
      myValue = value;
    }

    @Override
    public LongRangeSet subtract(LongRangeSet other) {
      return other.contains(myValue) ? Empty.EMPTY : this;
    }

    @Override
    public LongRangeSet intersect(LongRangeSet other) {
      return other.contains(myValue) ? this : Empty.EMPTY;
    }

    @Override
    public long min() {
      return myValue;
    }

    @Override
    public long max() {
      return myValue;
    }

    @Override
    public boolean intersects(LongRangeSet other) {
      return other.contains(myValue);
    }

    @Override
    public boolean contains(long value) {
      return myValue == value;
    }

    @Override
    public boolean contains(LongRangeSet other) {
      return other.isEmpty() || equals(other);
    }

    @Override
    public LongRangeSet castTo(PsiPrimitiveType type) {
      if (PsiType.LONG.equals(type)) return this;
      long newValue;
      if (PsiType.CHAR.equals(type)) {
        newValue = (char)myValue;
      }
      else if (PsiType.INT.equals(type)) {
        newValue = (int)myValue;
      }
      else if (PsiType.SHORT.equals(type)) {
        newValue = (short)myValue;
      }
      else if (PsiType.BYTE.equals(type)) {
        newValue = (byte)myValue;
      }
      else {
        throw new IllegalArgumentException(type.toString());
      }
      return newValue == myValue ? this : point(newValue);
    }

    @NotNull
    @Override
    public LongRangeSet abs(boolean isLong) {
      return myValue >= 0 || myValue == minValue(isLong) ? this : point(-myValue);
    }

    @NotNull
    @Override
    public LongRangeSet negate(boolean isLong) {
      return myValue == minValue(isLong) ? this : point(-myValue);
    }

    @NotNull
    @Override
    public LongRangeSet plus(LongRangeSet other, boolean isLong) {
      if (other.isEmpty()) return other;
      if (other instanceof Point) {
        long res = myValue + ((Point)other).myValue;
        return point(isLong ? res : (int)res);
      }
      return other.plus(this, isLong);
    }

    @Override
    public LongRangeSet mul(LongRangeSet multiplier, boolean isLong) {
      if (multiplier.isEmpty()) return multiplier;
      if (myValue == 0) return this;
      if (myValue == 1) return multiplier;
      if (myValue == -1) return multiplier.negate(isLong);
      if (multiplier instanceof Point) {
        long val = ((Point)multiplier).myValue;
        long res = myValue * val;
        return point(isLong ? res : (int)res);
      }
      long min = multiplier.min();
      long max = multiplier.max();
      if (isLong) {
        try {
          min = Math.multiplyExact(min, myValue);
          max = Math.multiplyExact(max, myValue);
        }
        catch (ArithmeticException e) {
          return Range.LONG_RANGE;
        }
      }
      else {
        min *= myValue;
        max *= myValue;
        if (min != (int)min || max != (int)max) return Range.INT_RANGE;
      }
      return min > max ? range(max, min) : range(min, max);
    }

    @NotNull
    @Override
    public LongRangeSet mod(LongRangeSet divisor) {
      if (divisor.isEmpty() || divisor.equals(point(0))) return empty();
      if (myValue == 0) return this;
      if (divisor instanceof Point) {
        return LongRangeSet.point(myValue % ((Point)divisor).myValue);
      }
      if (myValue != Long.MIN_VALUE) {
        long abs = Math.abs(myValue);
        if (!divisor.intersects(LongRangeSet.range(-abs, abs))) {
          // like 10 % [15..20] == 10 regardless on exact divisor value
          return this;
        }
      }
      LongRangeSet addend = empty();
      if (divisor.contains(Long.MIN_VALUE)) {
        divisor = divisor.subtract(point(Long.MIN_VALUE));
        addend = point(myValue);
      }
      long max = Math.max(0, Math.max(Math.abs(divisor.min()), Math.abs(divisor.max())) - 1);
      if (myValue < 0) {
        return LongRangeSet.range(Math.max(myValue, -max), 0).unite(addend);
      } else {
        // 10 % [-4..7] is [0..6], but 10 % [-30..30] is [0..10]
        return LongRangeSet.range(0, Math.min(myValue, max)).unite(addend);
      }
    }

    @Override
    public LongStream stream() {
      return LongStream.of(myValue);
    }

    @Override
    long[] asRanges() {
      return new long[] {myValue, myValue};
    }

    @Override
    public int hashCode() {
      return Long.hashCode(myValue);
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) return true;
      return o instanceof Point && myValue == ((Point)o).myValue;
    }

    @Override
    public String toString() {
      return "{" + formatNumber(myValue) + "}";
    }
  }

  static final class Range extends LongRangeSet {
    static final Range BYTE_RANGE = new Range(Byte.MIN_VALUE, Byte.MAX_VALUE);
    static final Range CHAR_RANGE = new Range(Character.MIN_VALUE, Character.MAX_VALUE);
    static final Range SHORT_RANGE = new Range(Short.MIN_VALUE, Short.MAX_VALUE);
    static final Range INT_RANGE = new Range(Integer.MIN_VALUE, Integer.MAX_VALUE);
    static final Range LONG_RANGE = new Range(Long.MIN_VALUE, Long.MAX_VALUE);
    static final Range INDEX_RANGE = new Range(0, Integer.MAX_VALUE);

    final long myFrom; // inclusive
    final long myTo; // inclusive

    Range(long from, long to) {
      if (to <= from) { // to == from => must be Point
        throw new IllegalArgumentException(to + "<=" + from);
      }
      myFrom = from;
      myTo = to;
    }

    @Override
    public LongRangeSet subtract(LongRangeSet other) {
      if (other.isEmpty()) return this;
      if (other == this) return Empty.EMPTY;
      if (other instanceof Point) {
        long value = ((Point)other).myValue;
        if (value < myFrom || value > myTo) return this;
        if (value == myFrom) return range(myFrom + 1, myTo);
        if (value == myTo) return range(myFrom, myTo - 1);
        return new RangeSet(new long[]{myFrom, value - 1, value + 1, myTo});
      }
      if (other instanceof Range) {
        long from = ((Range)other).myFrom;
        long to = ((Range)other).myTo;
        if (to < myFrom || from > myTo) return this;
        if (from <= myFrom && to >= myTo) return Empty.EMPTY;
        if (from > myFrom && to < myTo) {
          return new RangeSet(new long[]{myFrom, from - 1, to + 1, myTo});
        }
        if (from <= myFrom) {
          return range(to + 1, myTo);
        }
        assert to >= myTo;
        return range(myFrom, from - 1);
      }
      long[] ranges = ((RangeSet)other).myRanges;
      LongRangeSet result = this;
      for (int i = 0; i < ranges.length; i += 2) {
        result = result.subtract(range(ranges[i], ranges[i + 1]));
        if (result.isEmpty()) return result;
      }
      return result;
    }

    @Override
    public LongRangeSet intersect(LongRangeSet other) {
      if (other == this) return this;
      if (other.isEmpty()) return other;
      if (other instanceof Point) {
        return other.intersect(this);
      }
      if (other instanceof Range) {
        long from = ((Range)other).myFrom;
        long to = ((Range)other).myTo;
        if (from <= myFrom && to >= myTo) return this;
        if (from >= myFrom && to <= myTo) return other;
        if (from < myFrom) {
          from = myFrom;
        }
        if (to > myTo) {
          to = myTo;
        }
        return from <= to ? range(from, to) : Empty.EMPTY;
      }
      long[] ranges = ((RangeSet)other).myRanges;
      long[] result = new long[ranges.length];
      int index = 0;
      for (int i = 0; i < ranges.length; i += 2) {
        long[] res = intersect(range(ranges[i], ranges[i + 1])).asRanges();
        System.arraycopy(res, 0, result, index, res.length);
        index += res.length;
      }
      return fromRanges(result, index);
    }

    @Override
    public long min() {
      return myFrom;
    }

    @Override
    public long max() {
      return myTo;
    }

    @Override
    public boolean intersects(LongRangeSet other) {
      if (other.isEmpty()) return false;
      if (other instanceof RangeSet) {
        return other.intersects(this);
      }
      return myTo >= other.min() && myFrom <= other.max();
    }

    @Override
    public boolean contains(long value) {
      return myFrom <= value && myTo >= value;
    }

    @Override
    public boolean contains(LongRangeSet other) {
      return other.isEmpty() || other.min() >= myFrom && other.max() <= myTo;
    }

    @Override
    public LongRangeSet castTo(PsiPrimitiveType type) {
      if (PsiType.LONG.equals(type)) return this;
      if (PsiType.BYTE.equals(type)) {
        return mask(Byte.SIZE, type);
      }
      if (PsiType.SHORT.equals(type)) {
        return mask(Short.SIZE, type);
      }
      if (PsiType.INT.equals(type)) {
        return mask(Integer.SIZE, type);
      }
      if (PsiType.CHAR.equals(type)) {
        if (myFrom <= Character.MIN_VALUE && myTo >= Character.MAX_VALUE) return CHAR_RANGE;
        if (myFrom >= Character.MIN_VALUE && myTo <= Character.MAX_VALUE) return this;
        return bitwiseAnd(point(0xFFFF));
      }
      throw new IllegalArgumentException(type.toString());
    }

    @NotNull
    private LongRangeSet mask(int size, PsiPrimitiveType type) {
      long addend = 1L << (size - 1);
      if (myFrom <= -addend && myTo >= addend - 1) return Objects.requireNonNull(fromType(type));
      if (myFrom >= -addend && myTo <= addend - 1) return this;
      long mask = (1L << size) - 1;
      return plus(myFrom, myTo, addend, addend, true).bitwiseAnd(point(mask)).plus(point(-addend), true);
    }

    @NotNull
    @Override
    public LongRangeSet abs(boolean isLong) {
      if (myFrom >= 0) return this;
      long minValue = minValue(isLong);
      long low = myFrom, hi = myTo;
      if (low <= minValue) {
        low = minValue + 1;
      }
      if (myTo <= 0) {
        hi = -low;
        low = -myTo;
      }
      else {
        hi = Math.max(-low, hi);
        low = 0;
      }
      if (myFrom <= minValue) {
        return new RangeSet(new long[]{minValue, minValue, low, hi});
      }
      else {
        return new Range(low, hi);
      }
    }

    @NotNull
    @Override
    public LongRangeSet negate(boolean isLong) {
      long minValue = minValue(isLong);
      if (myFrom <= minValue) {
        if (myTo >= maxValue(isLong)) {
          return isLong ? LONG_RANGE : INT_RANGE;
        }
        return new RangeSet(new long[]{minValue, minValue, -myTo, -(minValue + 1)});
      }
      return new Range(-myTo, -myFrom);
    }

    @NotNull
    @Override
    public LongRangeSet plus(LongRangeSet other, boolean isLong) {
      if (other.isEmpty()) return other;
      if (isLong && equals(LONG_RANGE) || !isLong && equals(INT_RANGE)) return this;
      if (other instanceof Point || other instanceof Range || (other instanceof RangeSet && ((RangeSet)other).myRanges.length > 6)) {
        return plus(myFrom, myTo, other.min(), other.max(), isLong);
      }
      long[] ranges = other.asRanges();
      LongRangeSet result = empty();
      for (int i = 0; i < ranges.length; i += 2) {
        result = result.unite(plus(myFrom, myTo, ranges[i], ranges[i + 1], isLong));
      }
      return result;
    }

    @Override
    public LongRangeSet mul(LongRangeSet multiplier, boolean isLong) {
      if (multiplier.isEmpty()) return multiplier;
      if (multiplier instanceof Point) return multiplier.mul(this, isLong);
      return isLong ? LONG_RANGE : INT_RANGE;
    }

    @NotNull
    private static LongRangeSet plus(long from1, long to1, long from2, long to2, boolean isLong) {
      long len1 = to1 - from1; // may overflow
      long len2 = to2 - from2; // may overflow
      if ((len1 < 0 || len2 < 0) && len1 + len2 + 1 >= 0) { // total length more than 2^32
        return isLong ? LONG_RANGE : INT_RANGE;
      }
      long from = from1 + from2;
      long to = to1 + to2;
      if (!isLong) {
        if (to - from + 1 >= 0x1_0000_0000L) {
          return INT_RANGE;
        }
        from = (int)from;
        to = (int)to;
      }
      if (to < from) {
        return new RangeSet(new long[]{minValue(isLong), to, from, maxValue(isLong)});
      }
      else {
        return range(from, to);
      }
    }

    @NotNull
    @Override
    public LongRangeSet mod(LongRangeSet divisor) {
      if (divisor.isEmpty() || divisor.equals(point(0))) return empty();
      if (divisor instanceof Point && ((Point)divisor).myValue == Long.MIN_VALUE) {
        return this.contains(Long.MIN_VALUE) ? this.subtract(divisor).unite(point(0)) : this;
      }
      if (divisor.contains(Long.MIN_VALUE)) {
        return possibleMod();
      }
      long min = divisor.min();
      long max = divisor.max();
      long maxDivisor = Math.max(Math.abs(min), Math.abs(max));
      long minDivisor = min > 0 ? min : max < 0 ? Math.abs(max) : 0;
      if (!intersects(LongRangeSet.range(Long.MIN_VALUE, -minDivisor)) &&
          !intersects(LongRangeSet.range(minDivisor, Long.MAX_VALUE))) {
        return this;
      }
      return possibleMod().intersect(range(-maxDivisor + 1, maxDivisor - 1));
    }

    private LongRangeSet possibleMod() {
      if(contains(0)) return this;
      if(min() > 0) return range(0, max());
      return range(min(), 0);
    }

    @Override
    public LongStream stream() {
      return LongStream.rangeClosed(myFrom, myTo);
    }

    @Override
    long[] asRanges() {
      return new long[] {myFrom, myTo};
    }

    @Override
    public int hashCode() {
      return Long.hashCode(myFrom) * 1337 + Long.hashCode(myTo);
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) return true;
      return o instanceof Range && myFrom == ((Range)o).myFrom && myTo == ((Range)o).myTo;
    }

    @Override
    public String toString() {
      return "{" + toString(myFrom, myTo) + "}";
    }
  }

  static final class RangeSet extends LongRangeSet {
    final long[] myRanges;

    RangeSet(long[] ranges) {
      if (ranges.length < 4 || ranges.length % 2 != 0) {
        // 0 ranges = Empty; 1 range = Range
        throw new IllegalArgumentException("Bad length: " + ranges.length + " " + Arrays.toString(ranges));
      }
      for (int i = 0; i < ranges.length; i += 2) {
        if (ranges[i + 1] < ranges[i]) {
          throw new IllegalArgumentException("Bad sub-range #" + (i / 2) + " " + Arrays.toString(ranges));
        }
        if (i > 0 && (ranges[i - 1] == Long.MAX_VALUE || 1 + ranges[i - 1] > ranges[i])) {
          throw new IllegalArgumentException("Bad sub-ranges #" + (i / 2 - 1) + " and #" + (i / 2) + " " + Arrays.toString(ranges));
        }
      }
      myRanges = ranges;
    }

    @Override
    public LongRangeSet subtract(LongRangeSet other) {
      if (other.isEmpty()) return this;
      if (other == this) return Empty.EMPTY;
      long[] result = new long[myRanges.length + other.asRanges().length];
      int index = 0;
      for (int i = 0; i < myRanges.length; i += 2) {
        LongRangeSet res = range(myRanges[i], myRanges[i + 1]).subtract(other);
        long[] ranges = res.asRanges();
        System.arraycopy(ranges, 0, result, index, ranges.length);
        index += ranges.length;
      }
      return fromRanges(result, index);
    }

    @Override
    public LongRangeSet intersect(LongRangeSet other) {
      if (other == this) return this;
      if (other.isEmpty()) return other;
      if (other instanceof Point || other instanceof Range) {
        return other.intersect(this);
      }
      return subtract(all().subtract(other));
    }

    @Override
    public long min() {
      return myRanges[0];
    }

    @Override
    public long max() {
      return myRanges[myRanges.length - 1];
    }

    @Override
    public boolean intersects(LongRangeSet other) {
      if (other.isEmpty()) return false;
      if (other instanceof Point) {
        return contains(((Point)other).myValue);
      }
      long[] otherRanges = other.asRanges();
      int a = 0, b = 0;
      while (true) {
        long aFrom = myRanges[a];
        long aTo = myRanges[a + 1];
        long bFrom = otherRanges[b];
        long bTo = otherRanges[b + 1];
        if (aFrom <= bTo && bFrom <= aTo) return true;
        if (aFrom > bTo) {
          b += 2;
          if (b >= otherRanges.length) return false;
        }
        else {
          a += 2;
          if (a >= myRanges.length) return false;
        }
      }
    }

    @Override
    public boolean contains(long value) {
      for (int i = 0; i < myRanges.length; i += 2) {
        if (value >= myRanges[i] && value <= myRanges[i + 1]) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean contains(LongRangeSet other) {
      if (other.isEmpty() || other == this) return true;
      if (other instanceof Point) {
        return contains(((Point)other).myValue);
      }
      LongRangeSet result = other;
      for (int i = 0; i < myRanges.length; i += 2) {
        result = result.subtract(range(myRanges[i], myRanges[i + 1]));
        if (result.isEmpty()) return true;
      }
      return false;
    }

    @Override
    public LongRangeSet castTo(PsiPrimitiveType type) {
      LongRangeSet result = all();
      for (int i = 0; i < myRanges.length; i += 2) {
        result = result.subtract(range(myRanges[i], myRanges[i + 1]).castTo(type));
      }
      return all().subtract(result);
    }

    @NotNull
    @Override
    public LongRangeSet abs(boolean isLong) {
      LongRangeSet result = all();
      for (int i = 0; i < myRanges.length; i += 2) {
        result = result.subtract(range(myRanges[i], myRanges[i + 1]).abs(isLong));
      }
      return all().subtract(result);
    }

    @NotNull
    @Override
    public LongRangeSet negate(boolean isLong) {
      LongRangeSet result = all();
      for (int i = 0; i < myRanges.length; i += 2) {
        result = result.subtract(range(myRanges[i], myRanges[i + 1]).negate(isLong));
      }
      return all().subtract(result);
    }

    @NotNull
    @Override
    public LongRangeSet plus(LongRangeSet other, boolean isLong) {
      if (myRanges.length > 6) {
        return range(min(), max()).plus(other, isLong);
      }
      LongRangeSet result = empty();
      for (int i = 0; i < myRanges.length; i += 2) {
        result = result.unite(range(myRanges[i], myRanges[i + 1]).plus(other, isLong));
      }
      return result;
    }

    @Override
    public LongRangeSet mul(LongRangeSet multiplier, boolean isLong) {
      if (multiplier.isEmpty()) return multiplier;
      if (multiplier instanceof Point) return multiplier.mul(this, isLong);
      return isLong ? Range.LONG_RANGE : Range.INT_RANGE;
    }

    @NotNull
    @Override
    public LongRangeSet mod(LongRangeSet divisor) {
      if(divisor.isEmpty()) return empty();
      LongRangeSet result = empty();
      for (int i = 0; i < myRanges.length; i += 2) {
        result = result.unite(range(myRanges[i], myRanges[i + 1]).mod(divisor));
      }
      return result;
    }

    @Override
    public LongStream stream() {
      return IntStream.range(0, myRanges.length / 2)
        .mapToObj(idx -> LongStream.rangeClosed(myRanges[idx * 2], myRanges[idx * 2 + 1]))
        .reduce(LongStream::concat).orElseGet(LongStream::empty);
    }

    @Override
    long[] asRanges() {
      return myRanges;
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(myRanges);
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) return true;
      return o instanceof RangeSet && Arrays.equals(myRanges, ((RangeSet)o).myRanges);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("{");
      for (int i = 0; i < myRanges.length; i += 2) {
        if (i > 0) sb.append(", ");
        sb.append(LongRangeSet.toString(myRanges[i], myRanges[i + 1]));
      }
      sb.append("}");
      return sb.toString();
    }
  }
}
