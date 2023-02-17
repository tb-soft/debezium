/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.fzs.entry;

import java.util.concurrent.BlockingQueue;

public interface FzsParser {
    void parser(byte[] bytes, BlockingQueue<FzsEntry> outQueue);
}