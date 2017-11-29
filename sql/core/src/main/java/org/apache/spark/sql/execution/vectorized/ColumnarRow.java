/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.vectorized;

import java.math.BigDecimal;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.util.MapData;
import org.apache.spark.sql.types.*;
import org.apache.spark.unsafe.types.CalendarInterval;
import org.apache.spark.unsafe.types.UTF8String;

/**
 * Row abstraction in {@link ColumnVector}. The instance of this class is intended
 * to be reused, callers should copy the data out if it needs to be stored.
 */
public final class ColumnarRow extends InternalRow {
  protected int rowId;
  private final ColumnVector[] columns;
  private final WritableColumnVector[] writableColumns;

  // Ctor used if this is a struct.
  ColumnarRow(ColumnVector[] columns) {
    this.columns = columns;
    this.writableColumns = new WritableColumnVector[this.columns.length];
    for (int i = 0; i < this.columns.length; i++) {
      if (this.columns[i] instanceof WritableColumnVector) {
        this.writableColumns[i] = (WritableColumnVector) this.columns[i];
      }
    }
  }

  public ColumnVector[] columns() { return columns; }

  @Override
  public int numFields() { return columns.length; }

  /**
   * Revisit this. This is expensive. This is currently only used in test paths.
   */
  @Override
  public InternalRow copy() {
    GenericInternalRow row = new GenericInternalRow(columns.length);
    for (int i = 0; i < numFields(); i++) {
      if (isNullAt(i)) {
        row.setNullAt(i);
      } else {
        DataType dt = columns[i].dataType();
        if (dt instanceof BooleanType) {
          row.setBoolean(i, getBoolean(i));
        } else if (dt instanceof ByteType) {
          row.setByte(i, getByte(i));
        } else if (dt instanceof ShortType) {
          row.setShort(i, getShort(i));
        } else if (dt instanceof IntegerType) {
          row.setInt(i, getInt(i));
        } else if (dt instanceof LongType) {
          row.setLong(i, getLong(i));
        } else if (dt instanceof FloatType) {
          row.setFloat(i, getFloat(i));
        } else if (dt instanceof DoubleType) {
          row.setDouble(i, getDouble(i));
        } else if (dt instanceof StringType) {
          row.update(i, getUTF8String(i).copy());
        } else if (dt instanceof BinaryType) {
          row.update(i, getBinary(i));
        } else if (dt instanceof DecimalType) {
          DecimalType t = (DecimalType)dt;
          row.setDecimal(i, getDecimal(i, t.precision(), t.scale()), t.precision());
        } else if (dt instanceof DateType) {
          row.setInt(i, getInt(i));
        } else if (dt instanceof TimestampType) {
          row.setLong(i, getLong(i));
        } else {
          throw new RuntimeException("Not implemented. " + dt);
        }
      }
    }
    return row;
  }

  @Override
  public boolean anyNull() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isNullAt(int ordinal) { return columns[ordinal].isNullAt(rowId); }

  @Override
  public boolean getBoolean(int ordinal) { return columns[ordinal].getBoolean(rowId); }

  @Override
  public byte getByte(int ordinal) { return columns[ordinal].getByte(rowId); }

  @Override
  public short getShort(int ordinal) { return columns[ordinal].getShort(rowId); }

  @Override
  public int getInt(int ordinal) { return columns[ordinal].getInt(rowId); }

  @Override
  public long getLong(int ordinal) { return columns[ordinal].getLong(rowId); }

  @Override
  public float getFloat(int ordinal) { return columns[ordinal].getFloat(rowId); }

  @Override
  public double getDouble(int ordinal) { return columns[ordinal].getDouble(rowId); }

  @Override
  public Decimal getDecimal(int ordinal, int precision, int scale) {
    if (columns[ordinal].isNullAt(rowId)) return null;
    return columns[ordinal].getDecimal(rowId, precision, scale);
  }

  @Override
  public UTF8String getUTF8String(int ordinal) {
    if (columns[ordinal].isNullAt(rowId)) return null;
    return columns[ordinal].getUTF8String(rowId);
  }

  @Override
  public byte[] getBinary(int ordinal) {
    if (columns[ordinal].isNullAt(rowId)) return null;
    return columns[ordinal].getBinary(rowId);
  }

  @Override
  public CalendarInterval getInterval(int ordinal) {
    if (columns[ordinal].isNullAt(rowId)) return null;
    final int months = columns[ordinal].getChildColumn(0).getInt(rowId);
    final long microseconds = columns[ordinal].getChildColumn(1).getLong(rowId);
    return new CalendarInterval(months, microseconds);
  }

  @Override
  public ColumnarRow getStruct(int ordinal, int numFields) {
    if (columns[ordinal].isNullAt(rowId)) return null;
    return columns[ordinal].getStruct(rowId);
  }

  @Override
  public ColumnarArray getArray(int ordinal) {
    if (columns[ordinal].isNullAt(rowId)) return null;
    return columns[ordinal].getArray(rowId);
  }

  @Override
  public MapData getMap(int ordinal) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object get(int ordinal, DataType dataType) {
    if (dataType instanceof BooleanType) {
      return getBoolean(ordinal);
    } else if (dataType instanceof ByteType) {
      return getByte(ordinal);
    } else if (dataType instanceof ShortType) {
      return getShort(ordinal);
    } else if (dataType instanceof IntegerType) {
      return getInt(ordinal);
    } else if (dataType instanceof LongType) {
      return getLong(ordinal);
    } else if (dataType instanceof FloatType) {
      return getFloat(ordinal);
    } else if (dataType instanceof DoubleType) {
      return getDouble(ordinal);
    } else if (dataType instanceof StringType) {
      return getUTF8String(ordinal);
    } else if (dataType instanceof BinaryType) {
      return getBinary(ordinal);
    } else if (dataType instanceof DecimalType) {
      DecimalType t = (DecimalType) dataType;
      return getDecimal(ordinal, t.precision(), t.scale());
    } else if (dataType instanceof DateType) {
      return getInt(ordinal);
    } else if (dataType instanceof TimestampType) {
      return getLong(ordinal);
    } else if (dataType instanceof ArrayType) {
      return getArray(ordinal);
    } else if (dataType instanceof StructType) {
      return getStruct(ordinal, ((StructType)dataType).fields().length);
    } else if (dataType instanceof MapType) {
      return getMap(ordinal);
    } else {
      throw new UnsupportedOperationException("Datatype not supported " + dataType);
    }
  }

  @Override
  public void update(int ordinal, Object value) {
    if (value == null) {
      setNullAt(ordinal);
    } else {
      DataType dt = columns[ordinal].dataType();
      if (dt instanceof BooleanType) {
        setBoolean(ordinal, (boolean) value);
      } else if (dt instanceof IntegerType) {
        setInt(ordinal, (int) value);
      } else if (dt instanceof ShortType) {
        setShort(ordinal, (short) value);
      } else if (dt instanceof LongType) {
        setLong(ordinal, (long) value);
      } else if (dt instanceof FloatType) {
        setFloat(ordinal, (float) value);
      } else if (dt instanceof DoubleType) {
        setDouble(ordinal, (double) value);
      } else if (dt instanceof DecimalType) {
        DecimalType t = (DecimalType) dt;
        setDecimal(ordinal, Decimal.apply((BigDecimal) value, t.precision(), t.scale()),
                t.precision());
      } else {
        throw new UnsupportedOperationException("Datatype not supported " + dt);
      }
    }
  }

  @Override
  public void setNullAt(int ordinal) {
    getWritableColumn(ordinal).putNull(rowId);
  }

  @Override
  public void setBoolean(int ordinal, boolean value) {
    WritableColumnVector column = getWritableColumn(ordinal);
    column.putNotNull(rowId);
    column.putBoolean(rowId, value);
  }

  @Override
  public void setByte(int ordinal, byte value) {
    WritableColumnVector column = getWritableColumn(ordinal);
    column.putNotNull(rowId);
    column.putByte(rowId, value);
  }

  @Override
  public void setShort(int ordinal, short value) {
    WritableColumnVector column = getWritableColumn(ordinal);
    column.putNotNull(rowId);
    column.putShort(rowId, value);
  }

  @Override
  public void setInt(int ordinal, int value) {
    WritableColumnVector column = getWritableColumn(ordinal);
    column.putNotNull(rowId);
    column.putInt(rowId, value);
  }

  @Override
  public void setLong(int ordinal, long value) {
    WritableColumnVector column = getWritableColumn(ordinal);
    column.putNotNull(rowId);
    column.putLong(rowId, value);
  }

  @Override
  public void setFloat(int ordinal, float value) {
    WritableColumnVector column = getWritableColumn(ordinal);
    column.putNotNull(rowId);
    column.putFloat(rowId, value);
  }

  @Override
  public void setDouble(int ordinal, double value) {
    WritableColumnVector column = getWritableColumn(ordinal);
    column.putNotNull(rowId);
    column.putDouble(rowId, value);
  }

  @Override
  public void setDecimal(int ordinal, Decimal value, int precision) {
    WritableColumnVector column = getWritableColumn(ordinal);
    column.putNotNull(rowId);
    column.putDecimal(rowId, value, precision);
  }

  private WritableColumnVector getWritableColumn(int ordinal) {
    WritableColumnVector column = writableColumns[ordinal];
    assert (!column.isConstant);
    return column;
  }
}
