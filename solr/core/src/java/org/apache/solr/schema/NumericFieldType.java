/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.schema;

import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.solr.common.SolrException;
import org.apache.solr.search.FunctionRangeQuery;
import org.apache.solr.search.QParser;
import org.apache.solr.search.function.ValueSourceRangeFilter;
import org.apache.solr.util.DateMathParser;

public abstract class NumericFieldType extends PrimitiveFieldType {

  public static enum NumberType {
    INTEGER,
    LONG,
    FLOAT,
    DOUBLE,
    DATE
  }

  protected NumberType type;

  /**
   * @return the type of this field
   */
  final public NumberType getType() {
    return type;
  }

  private static long FLOAT_NEGATIVE_INFINITY_BITS = (long)Float.floatToIntBits(Float.NEGATIVE_INFINITY);
  private static long DOUBLE_NEGATIVE_INFINITY_BITS = Double.doubleToLongBits(Double.NEGATIVE_INFINITY);
  private static long FLOAT_POSITIVE_INFINITY_BITS = (long)Float.floatToIntBits(Float.POSITIVE_INFINITY);
  private static long DOUBLE_POSITIVE_INFINITY_BITS = Double.doubleToLongBits(Double.POSITIVE_INFINITY);
  private static long FLOAT_MINUS_ZERO_BITS = (long)Float.floatToIntBits(-0f);
  private static long DOUBLE_MINUS_ZERO_BITS = Double.doubleToLongBits(-0d);
  private static long FLOAT_ZERO_BITS = (long)Float.floatToIntBits(0f);
  private static long DOUBLE_ZERO_BITS = Double.doubleToLongBits(0d);

  protected Query getDocValuesRangeQuery(QParser parser, SchemaField field, String min, String max,
      boolean minInclusive, boolean maxInclusive) {
    assert field.hasDocValues() && !field.multiValued();
    
    switch (getType()) {
      case INTEGER:
        return numericDocValuesRangeQuery(field.getName(),
              min == null ? null : (long) Integer.parseInt(min),
              max == null ? null : (long) Integer.parseInt(max),
              minInclusive, maxInclusive);
      case FLOAT:
        return getRangeQueryForFloatDoubleDocValues(field, min, max, minInclusive, maxInclusive);
      case LONG:
        return numericDocValuesRangeQuery(field.getName(),
              min == null ? null : Long.parseLong(min),
              max == null ? null : Long.parseLong(max),
              minInclusive, maxInclusive);
      case DOUBLE:
        return getRangeQueryForFloatDoubleDocValues(field, min, max, minInclusive, maxInclusive);
      case DATE:
        return numericDocValuesRangeQuery(field.getName(),
              min == null ? null : DateMathParser.parseMath(null, min).getTime(),
              max == null ? null : DateMathParser.parseMath(null, max).getTime(),
              minInclusive, maxInclusive);
      default:
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Unknown type for point field");
    }
  }
  
  protected Query getRangeQueryForFloatDoubleDocValues(SchemaField sf, String min, String max, boolean minInclusive, boolean maxInclusive) {
    Query query;
    String fieldName = sf.getName();

    Number minVal = min == null ? null : getType() == NumberType.FLOAT ? Float.parseFloat(min): Double.parseDouble(min);
    Number maxVal = max == null ? null : getType() == NumberType.FLOAT ? Float.parseFloat(max): Double.parseDouble(max);
    
    Long minBits = 
        min == null ? null : getType() == NumberType.FLOAT ? (long) Float.floatToIntBits(minVal.floatValue()): Double.doubleToLongBits(minVal.doubleValue());
    Long maxBits = 
        max == null ? null : getType() == NumberType.FLOAT ? (long) Float.floatToIntBits(maxVal.floatValue()): Double.doubleToLongBits(maxVal.doubleValue());
    
    long negativeInfinityBits = getType() == NumberType.FLOAT ? FLOAT_NEGATIVE_INFINITY_BITS : DOUBLE_NEGATIVE_INFINITY_BITS;
    long positiveInfinityBits = getType() == NumberType.FLOAT ? FLOAT_POSITIVE_INFINITY_BITS : DOUBLE_POSITIVE_INFINITY_BITS;
    long minusZeroBits = getType() == NumberType.FLOAT ? FLOAT_MINUS_ZERO_BITS : DOUBLE_MINUS_ZERO_BITS;
    long zeroBits = getType() == NumberType.FLOAT ? FLOAT_ZERO_BITS : DOUBLE_ZERO_BITS;
    
    // If min is negative (or -0d) and max is positive (or +0d), then issue a FunctionRangeQuery
    if ((minVal == null || minVal.doubleValue() < 0d || minBits == minusZeroBits) && 
        (maxVal == null || (maxVal.doubleValue() > 0d || maxBits == zeroBits))) {

      ValueSource vs = getValueSource(sf, null);
      query = new FunctionRangeQuery(new ValueSourceRangeFilter(vs, min, max, minInclusive, maxInclusive));

    } else { // If both max and min are negative (or -0d), then issue range query with max and min reversed
      if ((minVal == null || minVal.doubleValue() < 0d || minBits == minusZeroBits) &&
          (maxVal != null && (maxVal.doubleValue() < 0d || maxBits == minusZeroBits))) {
        query = numericDocValuesRangeQuery
            (fieldName, maxBits, (min == null ? negativeInfinityBits : minBits), maxInclusive, minInclusive);
      } else { // If both max and min are positive, then issue range query
        query = numericDocValuesRangeQuery
            (fieldName, minBits, (max == null ? positiveInfinityBits : maxBits), minInclusive, maxInclusive);
      }
    }
    return query;
  }
  
  public static Query numericDocValuesRangeQuery(
      String field,
      Number lowerValue, Number upperValue,
      boolean lowerInclusive, boolean upperInclusive) {

    long actualLowerValue = Long.MIN_VALUE;
    if (lowerValue != null) {
      actualLowerValue = lowerValue.longValue();
      if (lowerInclusive == false) {
        if (actualLowerValue == Long.MAX_VALUE) {
          return new MatchNoDocsQuery();
        }
        ++actualLowerValue;
      }
    }

    long actualUpperValue = Long.MAX_VALUE;
    if (upperValue != null) {
      actualUpperValue = upperValue.longValue();
      if (upperInclusive == false) {
        if (actualUpperValue == Long.MIN_VALUE) {
          return new MatchNoDocsQuery();
        }
        --actualUpperValue;
      }
    }
    return NumericDocValuesField.newRangeQuery(field, actualLowerValue, actualUpperValue);
  }
}
