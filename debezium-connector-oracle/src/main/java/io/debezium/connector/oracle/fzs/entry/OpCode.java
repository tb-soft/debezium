/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.fzs.entry;

public enum OpCode {
    INSERT(0xb2),
    DELETE(0xb3),
    UPDATE(0xb5),
    DDL(0xd1),
    START(0x52),
    COMMIT(0x54),
    UNSUPPORTED(255);

    private static final OpCode[] types = new OpCode[256];

    static {
        for (OpCode option : OpCode.values()) {
            types[option.getValue()] = option;
        }
    }

    private final int value;

    OpCode(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static OpCode from(int value) {
        return value < types.length ? types[value] : OpCode.UNSUPPORTED;
    }
}
