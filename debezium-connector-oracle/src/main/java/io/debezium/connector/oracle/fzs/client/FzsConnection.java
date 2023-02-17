/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.fzs.client;

public interface FzsConnection extends Runnable {
    void setIpAndPort(String ip, String port);

    byte[] poll();
    void stop();
}