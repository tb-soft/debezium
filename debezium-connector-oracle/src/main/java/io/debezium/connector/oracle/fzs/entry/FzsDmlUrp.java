/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.fzs.entry;

import static io.debezium.connector.oracle.fzs.entry.BytesUtil.getByteOrInt;
import static io.debezium.connector.oracle.fzs.entry.BytesUtil.getByteOrShort;
import static io.debezium.connector.oracle.fzs.entry.BytesUtil.getString;
import static io.debezium.connector.oracle.fzs.entry.BytesUtil.readBytes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import io.debezium.connector.oracle.OracleValueConverters;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class FzsDmlUrp extends FzsDmlEntryImpl {

    private final Map<Integer, ColumnInfo> newValuesMap = new HashMap<>();
    private final Map<Integer, ColumnInfo> oldValuesMap = new HashMap<>();

    void parseUpdateColumnValues(ByteBuf byteBuf, Object[] values, int skipSize, boolean isBefore) throws UnsupportedEncodingException {
        int columnCount = values.length;
        String[] columName = new String[columnCount];
        int[] columnType = new int[columnCount];
        int[] columnId = new int[columnCount];
        for (int index = 0; index < columnCount; index++) {
            columnId[index] = getByteOrShort(byteBuf); // columnId
            int colLen = getByteOrShort(byteBuf);
            byte[] bytes = null;
            if (colLen > 0) {
                bytes = readBytes(byteBuf, colLen);
            }
            columName[index] = getString(byteBuf);
            columnType[index] = getByteOrShort(byteBuf);
            getByteOrInt(byteBuf); // col_length_max
            setValueByColumnType(values, columnType[index], colLen, bytes, index);
            byteBuf.readerIndex(byteBuf.readerIndex() + skipSize); // col_csform + col_csid + col_null
            ColumnInfo columnInfo = new ColumnInfo(columnId[index], columName[index], columnType[index], values[index]);
            if (isBefore) {
                oldValuesMap.put(columnId[index], columnInfo);
            }
            else {
                newValuesMap.put(columnId[index], columnInfo);
            }
        }
    }

    @Override
    public void parse(byte[] data) throws IOException {
        ByteBuf byteBuf = Unpooled.wrappedBuffer(data, 0, data.length);
        setScn(byteBuf.readerIndex(SCN_OFFSET).readLong());
        setTransactionId(Long.toString(byteBuf.readerIndex(TRANS_ID_OFFSET).readLong()));
        byteBuf.readerIndex(64);
        setObjectOwner(getString(byteBuf));
        setObjectName(getString(byteBuf));
        setSourceTime(Instant.now());
        byteBuf.readerIndex(byteBuf.readerIndex() + 1); // table has pk or uk
        byteBuf.readerIndex(byteBuf.readerIndex() + 8); // scn time

        // put value to temp map
        int columnCount = getByteOrShort(byteBuf);
        int newColumnCount = getByteOrShort(byteBuf);
        Object[] newValues = new Object[newColumnCount];
        Object[] oldValues = null;
        parseUpdateColumnValues(byteBuf, newValues, 4, false);
        if (columnCount > 0) {
            byteBuf.readerIndex(byteBuf.readerIndex() + 1); // bit flag where
            int oldColumnCount = getByteOrShort(byteBuf);
            oldValues = new Object[oldColumnCount];
            parseUpdateColumnValues(byteBuf, oldValues, 3, true);
        }

        /*
         * *
         * set map val to urp entry
         * if new value is lack, get it from old value, if old value is lack also, set this column to UNAVAILABLE_VALUE
         * all old lob value will be set to UNAVAILABLE_VALUE
         */
        String[] columName = new String[columnCount];
        int[] columnType = new int[columnCount];
        Object[] allNewValues = new Object[columnCount];
        Object[] allOldValues = new Object[columnCount];
        for (int i = 0; i < columnCount; i++) {
            ColumnInfo newColumnInfo = newValuesMap.get(i + 1);
            ColumnInfo oldColumnInfo = oldValuesMap.get(i + 1);
            boolean isLob = false;

            // set new value
            if (newColumnInfo != null) {
                columName[i] = newColumnInfo.getColumnName();
                columnType[i] = newColumnInfo.getColumnType();
                allNewValues[i] = newColumnInfo.getColumnValue();
                isLob = isLob(columnType[i]);
            }
            else if (oldColumnInfo != null) {
                columName[i] = oldColumnInfo.getColumnName();
                columnType[i] = oldColumnInfo.getColumnType();
                isLob = isLob(columnType[i]);
                if (isLob) {
                    allNewValues[i] = OracleValueConverters.UNAVAILABLE_VALUE;
                }
                else {
                    allNewValues[i] = oldColumnInfo.getColumnValue();
                }
            }
            else {
                allNewValues[i] = OracleValueConverters.UNAVAILABLE_VALUE;
                allOldValues[i] = OracleValueConverters.UNAVAILABLE_VALUE;
            }

            // set old value, all old lob is UNAVAILABLE
            if (oldColumnInfo != null) {
                allOldValues[i] = oldColumnInfo.getColumnValue();
            }
            if (isLob) {
                allOldValues[i] = OracleValueConverters.UNAVAILABLE_VALUE;
            }
        }

        setOldColumnNames(columName);
        setOldValues(allOldValues);
        setOldColumnTypes(columnType);

        setNewColumnNames(columName);
        setNewValues(allNewValues);
        setNewColumnTypes(columnType);
    }

    @Override
    public OpCode getEventType() {
        return OpCode.UPDATE;
    }
}

class ColumnInfo {
    private final int columnId;
    private final String columnName;
    private final int columnType;
    private final Object columnValue;

    public ColumnInfo(int columnId, String columnName, int columnType, Object columnValue) {
        this.columnId = columnId;
        this.columnName = columnName;
        this.columnType = columnType;
        this.columnValue = columnValue;
    }

    public int getColumnId() {
        return columnId;
    }

    public String getColumnName() {
        return columnName;
    }

    public int getColumnType() {
        return columnType;
    }

    public Object getColumnValue() {
        return columnValue;
    }

}