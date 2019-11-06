/**
 * Autogenerated by Thrift Compiler (0.12.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package org.apache.spark.sql.thriftserver.cli.thrift;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.12.0)", date = "2019-11-06")
public class TColumn extends org.apache.thrift.TUnion<TColumn, TColumn._Fields> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("TColumn");
  private static final org.apache.thrift.protocol.TField BOOL_VAL_FIELD_DESC = new org.apache.thrift.protocol.TField("boolVal", org.apache.thrift.protocol.TType.STRUCT, (short)1);
  private static final org.apache.thrift.protocol.TField BYTE_VAL_FIELD_DESC = new org.apache.thrift.protocol.TField("byteVal", org.apache.thrift.protocol.TType.STRUCT, (short)2);
  private static final org.apache.thrift.protocol.TField I16_VAL_FIELD_DESC = new org.apache.thrift.protocol.TField("i16Val", org.apache.thrift.protocol.TType.STRUCT, (short)3);
  private static final org.apache.thrift.protocol.TField I32_VAL_FIELD_DESC = new org.apache.thrift.protocol.TField("i32Val", org.apache.thrift.protocol.TType.STRUCT, (short)4);
  private static final org.apache.thrift.protocol.TField I64_VAL_FIELD_DESC = new org.apache.thrift.protocol.TField("i64Val", org.apache.thrift.protocol.TType.STRUCT, (short)5);
  private static final org.apache.thrift.protocol.TField DOUBLE_VAL_FIELD_DESC = new org.apache.thrift.protocol.TField("doubleVal", org.apache.thrift.protocol.TType.STRUCT, (short)6);
  private static final org.apache.thrift.protocol.TField STRING_VAL_FIELD_DESC = new org.apache.thrift.protocol.TField("stringVal", org.apache.thrift.protocol.TType.STRUCT, (short)7);
  private static final org.apache.thrift.protocol.TField BINARY_VAL_FIELD_DESC = new org.apache.thrift.protocol.TField("binaryVal", org.apache.thrift.protocol.TType.STRUCT, (short)8);

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    BOOL_VAL((short)1, "boolVal"),
    BYTE_VAL((short)2, "byteVal"),
    I16_VAL((short)3, "i16Val"),
    I32_VAL((short)4, "i32Val"),
    I64_VAL((short)5, "i64Val"),
    DOUBLE_VAL((short)6, "doubleVal"),
    STRING_VAL((short)7, "stringVal"),
    BINARY_VAL((short)8, "binaryVal");

    private static final java.util.Map<java.lang.String, _Fields> byName = new java.util.HashMap<java.lang.String, _Fields>();

    static {
      for (_Fields field : java.util.EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // BOOL_VAL
          return BOOL_VAL;
        case 2: // BYTE_VAL
          return BYTE_VAL;
        case 3: // I16_VAL
          return I16_VAL;
        case 4: // I32_VAL
          return I32_VAL;
        case 5: // I64_VAL
          return I64_VAL;
        case 6: // DOUBLE_VAL
          return DOUBLE_VAL;
        case 7: // STRING_VAL
          return STRING_VAL;
        case 8: // BINARY_VAL
          return BINARY_VAL;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new java.lang.IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByName(java.lang.String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final java.lang.String _fieldName;

    _Fields(short thriftId, java.lang.String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public java.lang.String getFieldName() {
      return _fieldName;
    }
  }

  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.BOOL_VAL, new org.apache.thrift.meta_data.FieldMetaData("boolVal", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, TBoolColumn.class)));
    tmpMap.put(_Fields.BYTE_VAL, new org.apache.thrift.meta_data.FieldMetaData("byteVal", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, TByteColumn.class)));
    tmpMap.put(_Fields.I16_VAL, new org.apache.thrift.meta_data.FieldMetaData("i16Val", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, TI16Column.class)));
    tmpMap.put(_Fields.I32_VAL, new org.apache.thrift.meta_data.FieldMetaData("i32Val", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, TI32Column.class)));
    tmpMap.put(_Fields.I64_VAL, new org.apache.thrift.meta_data.FieldMetaData("i64Val", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, TI64Column.class)));
    tmpMap.put(_Fields.DOUBLE_VAL, new org.apache.thrift.meta_data.FieldMetaData("doubleVal", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, TDoubleColumn.class)));
    tmpMap.put(_Fields.STRING_VAL, new org.apache.thrift.meta_data.FieldMetaData("stringVal", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, TStringColumn.class)));
    tmpMap.put(_Fields.BINARY_VAL, new org.apache.thrift.meta_data.FieldMetaData("binaryVal", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, TBinaryColumn.class)));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(TColumn.class, metaDataMap);
  }

  public TColumn() {
    super();
  }

  public TColumn(_Fields setField, java.lang.Object value) {
    super(setField, value);
  }

  public TColumn(TColumn other) {
    super(other);
  }
  public TColumn deepCopy() {
    return new TColumn(this);
  }

  public static TColumn boolVal(TBoolColumn value) {
    TColumn x = new TColumn();
    x.setBoolVal(value);
    return x;
  }

  public static TColumn byteVal(TByteColumn value) {
    TColumn x = new TColumn();
    x.setByteVal(value);
    return x;
  }

  public static TColumn i16Val(TI16Column value) {
    TColumn x = new TColumn();
    x.setI16Val(value);
    return x;
  }

  public static TColumn i32Val(TI32Column value) {
    TColumn x = new TColumn();
    x.setI32Val(value);
    return x;
  }

  public static TColumn i64Val(TI64Column value) {
    TColumn x = new TColumn();
    x.setI64Val(value);
    return x;
  }

  public static TColumn doubleVal(TDoubleColumn value) {
    TColumn x = new TColumn();
    x.setDoubleVal(value);
    return x;
  }

  public static TColumn stringVal(TStringColumn value) {
    TColumn x = new TColumn();
    x.setStringVal(value);
    return x;
  }

  public static TColumn binaryVal(TBinaryColumn value) {
    TColumn x = new TColumn();
    x.setBinaryVal(value);
    return x;
  }


  @Override
  protected void checkType(_Fields setField, java.lang.Object value) throws java.lang.ClassCastException {
    switch (setField) {
      case BOOL_VAL:
        if (value instanceof TBoolColumn) {
          break;
        }
        throw new java.lang.ClassCastException("Was expecting value of type TBoolColumn for field 'boolVal', but got " + value.getClass().getSimpleName());
      case BYTE_VAL:
        if (value instanceof TByteColumn) {
          break;
        }
        throw new java.lang.ClassCastException("Was expecting value of type TByteColumn for field 'byteVal', but got " + value.getClass().getSimpleName());
      case I16_VAL:
        if (value instanceof TI16Column) {
          break;
        }
        throw new java.lang.ClassCastException("Was expecting value of type TI16Column for field 'i16Val', but got " + value.getClass().getSimpleName());
      case I32_VAL:
        if (value instanceof TI32Column) {
          break;
        }
        throw new java.lang.ClassCastException("Was expecting value of type TI32Column for field 'i32Val', but got " + value.getClass().getSimpleName());
      case I64_VAL:
        if (value instanceof TI64Column) {
          break;
        }
        throw new java.lang.ClassCastException("Was expecting value of type TI64Column for field 'i64Val', but got " + value.getClass().getSimpleName());
      case DOUBLE_VAL:
        if (value instanceof TDoubleColumn) {
          break;
        }
        throw new java.lang.ClassCastException("Was expecting value of type TDoubleColumn for field 'doubleVal', but got " + value.getClass().getSimpleName());
      case STRING_VAL:
        if (value instanceof TStringColumn) {
          break;
        }
        throw new java.lang.ClassCastException("Was expecting value of type TStringColumn for field 'stringVal', but got " + value.getClass().getSimpleName());
      case BINARY_VAL:
        if (value instanceof TBinaryColumn) {
          break;
        }
        throw new java.lang.ClassCastException("Was expecting value of type TBinaryColumn for field 'binaryVal', but got " + value.getClass().getSimpleName());
      default:
        throw new java.lang.IllegalArgumentException("Unknown field id " + setField);
    }
  }

  @Override
  protected java.lang.Object standardSchemeReadValue(org.apache.thrift.protocol.TProtocol iprot, org.apache.thrift.protocol.TField field) throws org.apache.thrift.TException {
    _Fields setField = _Fields.findByThriftId(field.id);
    if (setField != null) {
      switch (setField) {
        case BOOL_VAL:
          if (field.type == BOOL_VAL_FIELD_DESC.type) {
            TBoolColumn boolVal;
            boolVal = new TBoolColumn();
            boolVal.read(iprot);
            return boolVal;
          } else {
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
            return null;
          }
        case BYTE_VAL:
          if (field.type == BYTE_VAL_FIELD_DESC.type) {
            TByteColumn byteVal;
            byteVal = new TByteColumn();
            byteVal.read(iprot);
            return byteVal;
          } else {
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
            return null;
          }
        case I16_VAL:
          if (field.type == I16_VAL_FIELD_DESC.type) {
            TI16Column i16Val;
            i16Val = new TI16Column();
            i16Val.read(iprot);
            return i16Val;
          } else {
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
            return null;
          }
        case I32_VAL:
          if (field.type == I32_VAL_FIELD_DESC.type) {
            TI32Column i32Val;
            i32Val = new TI32Column();
            i32Val.read(iprot);
            return i32Val;
          } else {
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
            return null;
          }
        case I64_VAL:
          if (field.type == I64_VAL_FIELD_DESC.type) {
            TI64Column i64Val;
            i64Val = new TI64Column();
            i64Val.read(iprot);
            return i64Val;
          } else {
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
            return null;
          }
        case DOUBLE_VAL:
          if (field.type == DOUBLE_VAL_FIELD_DESC.type) {
            TDoubleColumn doubleVal;
            doubleVal = new TDoubleColumn();
            doubleVal.read(iprot);
            return doubleVal;
          } else {
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
            return null;
          }
        case STRING_VAL:
          if (field.type == STRING_VAL_FIELD_DESC.type) {
            TStringColumn stringVal;
            stringVal = new TStringColumn();
            stringVal.read(iprot);
            return stringVal;
          } else {
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
            return null;
          }
        case BINARY_VAL:
          if (field.type == BINARY_VAL_FIELD_DESC.type) {
            TBinaryColumn binaryVal;
            binaryVal = new TBinaryColumn();
            binaryVal.read(iprot);
            return binaryVal;
          } else {
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
            return null;
          }
        default:
          throw new java.lang.IllegalStateException("setField wasn't null, but didn't match any of the case statements!");
      }
    } else {
      org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
      return null;
    }
  }

  @Override
  protected void standardSchemeWriteValue(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    switch (setField_) {
      case BOOL_VAL:
        TBoolColumn boolVal = (TBoolColumn)value_;
        boolVal.write(oprot);
        return;
      case BYTE_VAL:
        TByteColumn byteVal = (TByteColumn)value_;
        byteVal.write(oprot);
        return;
      case I16_VAL:
        TI16Column i16Val = (TI16Column)value_;
        i16Val.write(oprot);
        return;
      case I32_VAL:
        TI32Column i32Val = (TI32Column)value_;
        i32Val.write(oprot);
        return;
      case I64_VAL:
        TI64Column i64Val = (TI64Column)value_;
        i64Val.write(oprot);
        return;
      case DOUBLE_VAL:
        TDoubleColumn doubleVal = (TDoubleColumn)value_;
        doubleVal.write(oprot);
        return;
      case STRING_VAL:
        TStringColumn stringVal = (TStringColumn)value_;
        stringVal.write(oprot);
        return;
      case BINARY_VAL:
        TBinaryColumn binaryVal = (TBinaryColumn)value_;
        binaryVal.write(oprot);
        return;
      default:
        throw new java.lang.IllegalStateException("Cannot write union with unknown field " + setField_);
    }
  }

  @Override
  protected java.lang.Object tupleSchemeReadValue(org.apache.thrift.protocol.TProtocol iprot, short fieldID) throws org.apache.thrift.TException {
    _Fields setField = _Fields.findByThriftId(fieldID);
    if (setField != null) {
      switch (setField) {
        case BOOL_VAL:
          TBoolColumn boolVal;
          boolVal = new TBoolColumn();
          boolVal.read(iprot);
          return boolVal;
        case BYTE_VAL:
          TByteColumn byteVal;
          byteVal = new TByteColumn();
          byteVal.read(iprot);
          return byteVal;
        case I16_VAL:
          TI16Column i16Val;
          i16Val = new TI16Column();
          i16Val.read(iprot);
          return i16Val;
        case I32_VAL:
          TI32Column i32Val;
          i32Val = new TI32Column();
          i32Val.read(iprot);
          return i32Val;
        case I64_VAL:
          TI64Column i64Val;
          i64Val = new TI64Column();
          i64Val.read(iprot);
          return i64Val;
        case DOUBLE_VAL:
          TDoubleColumn doubleVal;
          doubleVal = new TDoubleColumn();
          doubleVal.read(iprot);
          return doubleVal;
        case STRING_VAL:
          TStringColumn stringVal;
          stringVal = new TStringColumn();
          stringVal.read(iprot);
          return stringVal;
        case BINARY_VAL:
          TBinaryColumn binaryVal;
          binaryVal = new TBinaryColumn();
          binaryVal.read(iprot);
          return binaryVal;
        default:
          throw new java.lang.IllegalStateException("setField wasn't null, but didn't match any of the case statements!");
      }
    } else {
      throw new org.apache.thrift.protocol.TProtocolException("Couldn't find a field with field id " + fieldID);
    }
  }

  @Override
  protected void tupleSchemeWriteValue(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    switch (setField_) {
      case BOOL_VAL:
        TBoolColumn boolVal = (TBoolColumn)value_;
        boolVal.write(oprot);
        return;
      case BYTE_VAL:
        TByteColumn byteVal = (TByteColumn)value_;
        byteVal.write(oprot);
        return;
      case I16_VAL:
        TI16Column i16Val = (TI16Column)value_;
        i16Val.write(oprot);
        return;
      case I32_VAL:
        TI32Column i32Val = (TI32Column)value_;
        i32Val.write(oprot);
        return;
      case I64_VAL:
        TI64Column i64Val = (TI64Column)value_;
        i64Val.write(oprot);
        return;
      case DOUBLE_VAL:
        TDoubleColumn doubleVal = (TDoubleColumn)value_;
        doubleVal.write(oprot);
        return;
      case STRING_VAL:
        TStringColumn stringVal = (TStringColumn)value_;
        stringVal.write(oprot);
        return;
      case BINARY_VAL:
        TBinaryColumn binaryVal = (TBinaryColumn)value_;
        binaryVal.write(oprot);
        return;
      default:
        throw new java.lang.IllegalStateException("Cannot write union with unknown field " + setField_);
    }
  }

  @Override
  protected org.apache.thrift.protocol.TField getFieldDesc(_Fields setField) {
    switch (setField) {
      case BOOL_VAL:
        return BOOL_VAL_FIELD_DESC;
      case BYTE_VAL:
        return BYTE_VAL_FIELD_DESC;
      case I16_VAL:
        return I16_VAL_FIELD_DESC;
      case I32_VAL:
        return I32_VAL_FIELD_DESC;
      case I64_VAL:
        return I64_VAL_FIELD_DESC;
      case DOUBLE_VAL:
        return DOUBLE_VAL_FIELD_DESC;
      case STRING_VAL:
        return STRING_VAL_FIELD_DESC;
      case BINARY_VAL:
        return BINARY_VAL_FIELD_DESC;
      default:
        throw new java.lang.IllegalArgumentException("Unknown field id " + setField);
    }
  }

  @Override
  protected org.apache.thrift.protocol.TStruct getStructDesc() {
    return STRUCT_DESC;
  }

  @Override
  protected _Fields enumForId(short id) {
    return _Fields.findByThriftIdOrThrow(id);
  }

  @org.apache.thrift.annotation.Nullable
  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }


  public TBoolColumn getBoolVal() {
    if (getSetField() == _Fields.BOOL_VAL) {
      return (TBoolColumn)getFieldValue();
    } else {
      throw new java.lang.RuntimeException("Cannot get field 'boolVal' because union is currently set to " + getFieldDesc(getSetField()).name);
    }
  }

  public void setBoolVal(TBoolColumn value) {
    if (value == null) throw new java.lang.NullPointerException();
    setField_ = _Fields.BOOL_VAL;
    value_ = value;
  }

  public TByteColumn getByteVal() {
    if (getSetField() == _Fields.BYTE_VAL) {
      return (TByteColumn)getFieldValue();
    } else {
      throw new java.lang.RuntimeException("Cannot get field 'byteVal' because union is currently set to " + getFieldDesc(getSetField()).name);
    }
  }

  public void setByteVal(TByteColumn value) {
    if (value == null) throw new java.lang.NullPointerException();
    setField_ = _Fields.BYTE_VAL;
    value_ = value;
  }

  public TI16Column getI16Val() {
    if (getSetField() == _Fields.I16_VAL) {
      return (TI16Column)getFieldValue();
    } else {
      throw new java.lang.RuntimeException("Cannot get field 'i16Val' because union is currently set to " + getFieldDesc(getSetField()).name);
    }
  }

  public void setI16Val(TI16Column value) {
    if (value == null) throw new java.lang.NullPointerException();
    setField_ = _Fields.I16_VAL;
    value_ = value;
  }

  public TI32Column getI32Val() {
    if (getSetField() == _Fields.I32_VAL) {
      return (TI32Column)getFieldValue();
    } else {
      throw new java.lang.RuntimeException("Cannot get field 'i32Val' because union is currently set to " + getFieldDesc(getSetField()).name);
    }
  }

  public void setI32Val(TI32Column value) {
    if (value == null) throw new java.lang.NullPointerException();
    setField_ = _Fields.I32_VAL;
    value_ = value;
  }

  public TI64Column getI64Val() {
    if (getSetField() == _Fields.I64_VAL) {
      return (TI64Column)getFieldValue();
    } else {
      throw new java.lang.RuntimeException("Cannot get field 'i64Val' because union is currently set to " + getFieldDesc(getSetField()).name);
    }
  }

  public void setI64Val(TI64Column value) {
    if (value == null) throw new java.lang.NullPointerException();
    setField_ = _Fields.I64_VAL;
    value_ = value;
  }

  public TDoubleColumn getDoubleVal() {
    if (getSetField() == _Fields.DOUBLE_VAL) {
      return (TDoubleColumn)getFieldValue();
    } else {
      throw new java.lang.RuntimeException("Cannot get field 'doubleVal' because union is currently set to " + getFieldDesc(getSetField()).name);
    }
  }

  public void setDoubleVal(TDoubleColumn value) {
    if (value == null) throw new java.lang.NullPointerException();
    setField_ = _Fields.DOUBLE_VAL;
    value_ = value;
  }

  public TStringColumn getStringVal() {
    if (getSetField() == _Fields.STRING_VAL) {
      return (TStringColumn)getFieldValue();
    } else {
      throw new java.lang.RuntimeException("Cannot get field 'stringVal' because union is currently set to " + getFieldDesc(getSetField()).name);
    }
  }

  public void setStringVal(TStringColumn value) {
    if (value == null) throw new java.lang.NullPointerException();
    setField_ = _Fields.STRING_VAL;
    value_ = value;
  }

  public TBinaryColumn getBinaryVal() {
    if (getSetField() == _Fields.BINARY_VAL) {
      return (TBinaryColumn)getFieldValue();
    } else {
      throw new java.lang.RuntimeException("Cannot get field 'binaryVal' because union is currently set to " + getFieldDesc(getSetField()).name);
    }
  }

  public void setBinaryVal(TBinaryColumn value) {
    if (value == null) throw new java.lang.NullPointerException();
    setField_ = _Fields.BINARY_VAL;
    value_ = value;
  }

  public boolean isSetBoolVal() {
    return setField_ == _Fields.BOOL_VAL;
  }


  public boolean isSetByteVal() {
    return setField_ == _Fields.BYTE_VAL;
  }


  public boolean isSetI16Val() {
    return setField_ == _Fields.I16_VAL;
  }


  public boolean isSetI32Val() {
    return setField_ == _Fields.I32_VAL;
  }


  public boolean isSetI64Val() {
    return setField_ == _Fields.I64_VAL;
  }


  public boolean isSetDoubleVal() {
    return setField_ == _Fields.DOUBLE_VAL;
  }


  public boolean isSetStringVal() {
    return setField_ == _Fields.STRING_VAL;
  }


  public boolean isSetBinaryVal() {
    return setField_ == _Fields.BINARY_VAL;
  }


  public boolean equals(java.lang.Object other) {
    if (other instanceof TColumn) {
      return equals((TColumn)other);
    } else {
      return false;
    }
  }

  public boolean equals(TColumn other) {
    return other != null && getSetField() == other.getSetField() && getFieldValue().equals(other.getFieldValue());
  }

  @Override
  public int compareTo(TColumn other) {
    int lastComparison = org.apache.thrift.TBaseHelper.compareTo(getSetField(), other.getSetField());
    if (lastComparison == 0) {
      return org.apache.thrift.TBaseHelper.compareTo(getFieldValue(), other.getFieldValue());
    }
    return lastComparison;
  }


  @Override
  public int hashCode() {
    java.util.List<java.lang.Object> list = new java.util.ArrayList<java.lang.Object>();
    list.add(this.getClass().getName());
    org.apache.thrift.TFieldIdEnum setField = getSetField();
    if (setField != null) {
      list.add(setField.getThriftFieldId());
      java.lang.Object value = getFieldValue();
      if (value instanceof org.apache.thrift.TEnum) {
        list.add(((org.apache.thrift.TEnum)getFieldValue()).getValue());
      } else {
        list.add(value);
      }
    }
    return list.hashCode();
  }
  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }


  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, java.lang.ClassNotFoundException {
    try {
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }


}
