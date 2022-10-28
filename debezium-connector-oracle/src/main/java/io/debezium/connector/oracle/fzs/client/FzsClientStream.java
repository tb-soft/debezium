/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.fzs.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.oracle.fzs.entry.FzsEntry;

public class FzsClientStream {
    private static final Logger logger = LoggerFactory.getLogger(FzsClientStream.class);
    private Thread processThread = null;
    private final FzsProducer fzsProducer;
    private FzsRecordListener fzsRecordListener = null;
    private final BlockingQueue<FzsEntry> recordQueue;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public FzsClientStream(String ip, String port) {
        this.recordQueue = new LinkedBlockingQueue<>(20000);
        this.fzsProducer = new FzsProducer(ip, port, recordQueue);
    }

    public void join() {
        if (processThread != null) {
            try {
                processThread.join();
            }
            catch (InterruptedException e) {
                logger.warn("Waits for process thread failed : {}", e.getMessage());
                triggerStop();
            }
        }
    }

    public void stop() {
        if (started.compareAndSet(true, false)) {
            logger.info("Try to stop this client");
            join();
            processThread = null;
            logger.info("Client stopped successfully");
        }
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            processThread = new Thread(() -> {
                while (isRunning()) {
                    FzsEntry lcr = null;

                    try {
                        lcr = recordQueue.poll(2000, TimeUnit.MILLISECONDS);
                    }
                    catch (InterruptedException e) {
                        // ignore exception
                    }
                    if (lcr == null) {
                        continue;
                    }
                    fzsRecordListener.process(lcr);
                }

                triggerStop();
                logger.info("fzs process lcr thread exit");
            });

            processThread.setDaemon(false);
            processThread.start();
            fzsProducer.run();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    public boolean isRunning() {
        return started.get();
    }

    public void triggerStop() {
        new Thread(this::stop).start();
    }

    public void setFzsRecordListener(FzsRecordListener fzsRecordListener) {
        this.fzsRecordListener = fzsRecordListener;
    }
}
