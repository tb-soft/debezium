/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.dm.logminer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.connector.dm.DMConnection;
import io.debezium.connector.dm.DMConnectorConfig;
import io.debezium.connector.dm.DMConnectorConfig.LogMiningDmlParser;
import io.debezium.connector.dm.DMDatabaseSchema;
import io.debezium.connector.dm.DMOffsetContext;
import io.debezium.connector.dm.DMSchemaChangeEventEmitter;
import io.debezium.connector.dm.DMStreamingChangeEventSourceMetrics;
import io.debezium.connector.dm.DMValueConverters;
import io.debezium.connector.dm.Scn;
import io.debezium.connector.dm.logminer.parser.DmlParser;
import io.debezium.connector.dm.logminer.parser.DmlParserException;
import io.debezium.connector.dm.logminer.parser.LogMinerDmlParser;
import io.debezium.connector.dm.logminer.parser.SelectLobParser;
import io.debezium.connector.dm.logminer.parser.SimpleDmlParser;
import io.debezium.connector.dm.logminer.valueholder.LogMinerDmlEntry;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.ChangeEventSource.ChangeEventSourceContext;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;

/**
 * This class process entries obtained from LogMiner view.
 * It parses each entry.
 * On each DML it registers a callback in TransactionalBuffer.
 * On rollback it removes registered entries from TransactionalBuffer.
 * On commit it executes all registered callbacks, which dispatch ChangeRecords.
 * This also calculates metrics
 */
class LogMinerQueryResultProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogMinerQueryResultProcessor.class);

    private final ChangeEventSourceContext context;
    private final DMStreamingChangeEventSourceMetrics streamingMetrics;
    private final TransactionalBuffer transactionalBuffer;
    private final DmlParser dmlParser;
    private final DMOffsetContext offsetContext;
    private final DMDatabaseSchema schema;
    private final EventDispatcher<TableId> dispatcher;
    private final DMConnectorConfig connectorConfig;
    private final HistoryRecorder historyRecorder;
    private final SelectLobParser selectLobParser;

    private Scn currentOffsetScn = Scn.NULL;
    private Scn currentOffsetCommitScn = Scn.NULL;
    private Scn lastProcessedScn = Scn.NULL;
    private long stuckScnCounter = 0;

    LogMinerQueryResultProcessor(ChangeEventSourceContext context, DMConnectorConfig connectorConfig,
                                 DMStreamingChangeEventSourceMetrics streamingMetrics, TransactionalBuffer transactionalBuffer,
                                 DMOffsetContext offsetContext, DMDatabaseSchema schema,
                                 EventDispatcher<TableId> dispatcher,
                                 HistoryRecorder historyRecorder) {
        this.context = context;
        this.streamingMetrics = streamingMetrics;
        this.transactionalBuffer = transactionalBuffer;
        this.offsetContext = offsetContext;
        this.schema = schema;
        this.dispatcher = dispatcher;
        this.historyRecorder = historyRecorder;
        this.connectorConfig = connectorConfig;
        this.dmlParser = resolveParser(connectorConfig, schema.getValueConverters());
        this.selectLobParser = new SelectLobParser();
    }

    private static DmlParser resolveParser(DMConnectorConfig connectorConfig, DMValueConverters valueConverters) {
        if (connectorConfig.getLogMiningDmlParser().equals(LogMiningDmlParser.LEGACY)) {
            return new SimpleDmlParser(connectorConfig.getCatalogName(), valueConverters);
        }
        return new LogMinerDmlParser();
    }

    /**
     * This method does all the job
     * @param resultSet the info from LogMiner view
     * @throws SQLException thrown if any database exception occurs
     */
    void processResult(ResultSet resultSet, ZoneOffset zoneOffset) throws SQLException {
        int dmlCounter = 0, insertCounter = 0, updateCounter = 0, deleteCounter = 0;
        int commitCounter = 0;
        int rollbackCounter = 0;
        long rows = 0;
        Instant startTime = Instant.now();
        while (context.isRunning() && hasNext(resultSet)) {
            rows++;

            Scn scn = RowMapper.getScn(resultSet);
            if (scn.isNull()) {
                throw new DebeziumException("Unexpected null SCN detected in LogMiner results");
            }

            String tableName = RowMapper.getTableName(resultSet);
            String segOwner = RowMapper.getSegOwner(resultSet);
            int operationCode = RowMapper.getOperationCode(resultSet);
            Timestamp changeTime = RowMapper.getChangeTime(resultSet, zoneOffset);
            String txId = RowMapper.getTransactionId(resultSet);
            String operation = RowMapper.getOperation(resultSet);
            String userName = RowMapper.getUsername(resultSet);
            String rowId = RowMapper.getRowId(resultSet);
            int rollbackFlag = RowMapper.getRollbackFlag(resultSet);
            Object rsId = RowMapper.getRsId(resultSet);
            boolean dml = isDmlOperation(operationCode);

            String redoSql = RowMapper.getSqlRedo(resultSet, dml, historyRecorder, scn, tableName, segOwner, operationCode, changeTime, txId);

            LOGGER.info("sql={}, xid={}, scn={}, operationCode={}, operation={}, table={}, segOwner={}, userName={}, rowId={}, rollbackFlag={}", redoSql, txId, scn,
                    operationCode, operation,
                    tableName, segOwner, userName, rowId, rollbackFlag);

            String logMessage = String.format("transactionId=%s, SCN=%s, table_name=%s, segOwner=%s, operationCode=%s, offsetSCN=%s, " +
                    " commitOffsetSCN=%s", txId, scn, tableName, segOwner, operationCode, offsetContext.getScn(), offsetContext.getCommitScn());

            if (operationCode != RowMapper.MISSING_SCN) {
                lastProcessedScn = scn;
            }

            switch (operationCode) {
                case RowMapper.START: {
                    // Register start transaction.
                    // If already registered, does nothing due to overlapping mining strategy.
                    transactionalBuffer.registerTransaction(txId, scn);
                    break;
                }
                case RowMapper.COMMIT: {
                    // Commits a transaction
                    if (transactionalBuffer.isTransactionRegistered(txId)) {
                        historyRecorder.record(scn, tableName, segOwner, operationCode, changeTime, txId, 0, redoSql);
                        if (transactionalBuffer.commit(txId, scn, offsetContext, changeTime, context, logMessage, dispatcher)) {
                            LOGGER.trace("COMMIT, {}", logMessage);
                            commitCounter++;
                        }
                    }
                    break;
                }
                case RowMapper.ROLLBACK: {
                    if (rollbackFlag == 0) {
                        continue;
                    }
                    // Rollback a transaction
                    if (transactionalBuffer.isTransactionRegistered(txId)) {
                        historyRecorder.record(scn, tableName, segOwner, operationCode, changeTime, txId, 0, redoSql);
                        if (transactionalBuffer.rollback(txId, logMessage)) {
                            LOGGER.trace("ROLLBACK, {}", logMessage);
                            rollbackCounter++;
                        }
                    }
                    break;
                }
                case RowMapper.DDL: {
                    if (StringUtils.isEmpty(segOwner)) {
                        if (redoSql != null && redoSql.contains(".")) {
                            segOwner = getSegOwnerForRedoSql(redoSql);
                        }
                        else {
                            segOwner = userName;
                        }
                    }
                    if (transactionalBuffer.isDdlOperationRegistered(scn)) {
                        LOGGER.trace("DDL: {} has already been seen, skipped.", redoSql);
                        continue;
                    }
                    if (rollbackFlag == 1) {
                        LOGGER.info("ROLLBACK DDL: {}, REDO_SQL: {}", logMessage, redoSql);
                        continue;
                    }
                    historyRecorder.record(scn, tableName, segOwner, operationCode, changeTime, txId, 0, redoSql);
                    LOGGER.info("DDL: {}, REDO_SQL: {}", logMessage, redoSql);
                    try {
                        if (tableName != null) {
                            final TableId tableId = RowMapper.getTableId(connectorConfig.getCatalogName(), segOwner, tableName);
                            if (isTruncate(redoSql)) {
                                final Table table = getTableForDmlEvent(tableId);
                                if (table != null) {
                                    String finalSegOwner = segOwner;
                                    transactionalBuffer.registerDmlOperation(operationCode, txId, scn, tableId, () -> {
                                        final LogMinerDmlEntry dmlEntry = parse(redoSql, table, txId);
                                        dmlEntry.setObjectOwner(finalSegOwner);
                                        dmlEntry.setObjectName(tableName);
                                        return dmlEntry;
                                    },
                                            changeTime.toInstant(), rowId, rsId);
                                }
                            }
                            else {
                                transactionalBuffer.registerDdlOperation(scn);
                                dispatcher.dispatchSchemaChangeEvent(tableId,
                                        new DMSchemaChangeEventEmitter(
                                                connectorConfig,
                                                offsetContext,
                                                tableId,
                                                tableId.catalog(),
                                                tableId.schema(),
                                                redoSql,
                                                schema,
                                                changeTime.toInstant(),
                                                streamingMetrics));
                            }
                        }
                    }
                    catch (InterruptedException e) {
                        throw new DebeziumException("Failed to dispatch DDL event", e);
                    }
                }
                case RowMapper.MISSING_SCN: {
                    historyRecorder.record(scn, tableName, segOwner, operationCode, changeTime, txId, 0, redoSql);
                    LogMinerHelper.logWarn(streamingMetrics, "Missing SCN, {}", logMessage);
                    break;
                }
                case RowMapper.SELECT_LOB_LOCATOR: {
                    if (!connectorConfig.isLobEnabled()) {
                        LOGGER.trace("SEL_LOB_LOCATOR operation skipped for '{}', LOB not enabled.", redoSql);
                        continue;
                    }
                    LOGGER.trace("SEL_LOB_LOCATOR: {}, REDO_SQL: {}", logMessage, redoSql);
                    final TableId tableId = RowMapper.getTableId(connectorConfig.getCatalogName(), resultSet);
                    final Table table = schema.tableFor(tableId);
                    if (table == null) {
                        LogMinerHelper.logWarn(streamingMetrics, "SEL_LOB_LOCATOR for table '{}' is not known to the connector, skipped.", tableId);
                        continue;
                    }
                    transactionalBuffer.registerSelectLobOperation(operationCode, txId, scn, tableId, changeTime.toInstant(),
                            rowId, rsId, segOwner, tableName, redoSql, schema.tableFor(tableId), selectLobParser);
                    break;
                }
                case RowMapper.LOB_WRITE: {
                    if (!connectorConfig.isLobEnabled()) {
                        LOGGER.trace("LOB_WRITE operation skipped, LOB not enabled.");
                        continue;
                    }
                    final TableId tableId = RowMapper.getTableId(connectorConfig.getCatalogName(), resultSet);
                    if (schema.tableFor(tableId) == null) {
                        LogMinerHelper.logWarn(streamingMetrics, "LOB_WRITE for table '{}' is not known to the connector, skipped.", tableId);
                        continue;
                    }
                    transactionalBuffer.registerLobWriteOperation(operationCode, txId, scn, tableId, redoSql,
                            changeTime.toInstant(), rowId, rsId);
                    break;
                }
                case RowMapper.LOB_ERASE: {
                    if (!connectorConfig.isLobEnabled()) {
                        LOGGER.trace("LOB_ERASE operation skipped, LOB not enabled.");
                        continue;
                    }
                    final TableId tableId = RowMapper.getTableId(connectorConfig.getCatalogName(), resultSet);
                    if (schema.tableFor(tableId) == null) {
                        LogMinerHelper.logWarn(streamingMetrics, "LOB_ERASE for table '{}' is not known to the connector, skipped.", tableId);
                        continue;
                    }
                    transactionalBuffer.registerLobEraseOperation(operationCode, txId, scn, tableId, changeTime.toInstant(), rowId, rsId);
                    break;
                }
                case RowMapper.INSERT:
                case RowMapper.UPDATE:
                case RowMapper.DELETE: {
                    LOGGER.trace("DML, {}, sql {}", logMessage, redoSql);
                    if (redoSql != null) {
                        final TableId tableId = RowMapper.getTableId(connectorConfig.getCatalogName(), resultSet);
                        dmlCounter++;
                        switch (operationCode) {
                            case RowMapper.INSERT:
                                insertCounter++;
                                break;
                            case RowMapper.UPDATE:
                                updateCounter++;
                                break;
                            case RowMapper.DELETE:
                                deleteCounter++;
                                break;
                        }

                        final Table table = getTableForDmlEvent(tableId);

                        if (rollbackFlag == 1) {
                            // DML operation is to undo partial or all operations as a result of a rollback.
                            // This can be situations where an insert or update causes a constraint violation
                            // and a subsequent operation is written to the logs to revert the change.
                            transactionalBuffer.undoDmlOperation(txId, rowId, tableId);
                            continue;
                        }

                        String finalSegOwner = segOwner;
                        transactionalBuffer.registerDmlOperation(operationCode, txId, scn, tableId, () -> {
                            final LogMinerDmlEntry dmlEntry = parse(redoSql, table, txId);
                            dmlEntry.setObjectOwner(finalSegOwner);
                            dmlEntry.setObjectName(tableName);
                            return dmlEntry;
                        },
                                changeTime.toInstant(), rowId, rsId);
                    }
                    else {
                        LOGGER.trace("Redo SQL was empty, DML operation skipped.");
                    }

                    break;
                }
            }
        }

        Duration totalTime = Duration.between(startTime, Instant.now());
        if (dmlCounter > 0 || commitCounter > 0 || rollbackCounter > 0) {
            streamingMetrics.setLastCapturedDmlCount(dmlCounter);
            streamingMetrics.setLastDurationOfBatchProcessing(totalTime);

            warnStuckScn();
            currentOffsetScn = offsetContext.getScn();
            if (offsetContext.getCommitScn() != null) {
                currentOffsetCommitScn = offsetContext.getCommitScn();
            }
        }

        LOGGER.info("{} Rows, {} DMLs, {} Commits, {} Rollbacks, {} Inserts, {} Updates, {} Deletes. Processed in {} millis. " +
                "Lag:{}. Offset scn:{}. Offset commit scn:{}. Active transactions:{}. Sleep time:{}",
                rows, dmlCounter, commitCounter, rollbackCounter, insertCounter, updateCounter, deleteCounter, totalTime.toMillis(),
                streamingMetrics.getLagFromSourceInMilliseconds(), offsetContext.getScn(), offsetContext.getCommitScn(),
                streamingMetrics.getNumberOfActiveTransactions(), streamingMetrics.getMillisecondToSleepBetweenMiningQuery());

        streamingMetrics.addProcessedRows(rows);
        historyRecorder.flush();
    }

    Scn getLastProcessedScn() {
        return lastProcessedScn;
    }

    private boolean hasNext(ResultSet resultSet) throws SQLException {
        Instant rsNextStart = Instant.now();
        if (resultSet.next()) {
            streamingMetrics.addCurrentResultSetNext(Duration.between(rsNextStart, Instant.now()));
            return true;
        }
        return false;
    }

    private Table getTableForDmlEvent(TableId tableId) throws SQLException {
        Table table = schema.tableFor(tableId);
        if (table == null) {
            if (connectorConfig.getTableFilters().dataCollectionFilter().isIncluded(tableId)) {
                table = dispatchSchemaChangeEventAndGetTableForNewCapturedTable(tableId);
            }
            else {
                LogMinerHelper.logWarn(streamingMetrics, "DML for table '{}' that is not known to this connector, skipping.", tableId);
            }
        }
        return table;
    }

    private String getSegOwnerForRedoSql(String redoSql) {
        String owner = null;
        if (!redoSql.contains(".")) {
            return null;
        }
        Optional<String> tn = Arrays.stream(redoSql.split(" ")).filter(s -> s.contains(".")).findFirst();
        if (tn.isPresent()) {
            owner = tn.get().split("\\.")[0];
            if (!owner.startsWith("\"")) {
                owner = owner.toUpperCase();
            }
            else {
                owner = owner.substring(1, owner.length() - 1);
            }
        }
        return owner;
    }

    private Table dispatchSchemaChangeEventAndGetTableForNewCapturedTable(TableId tableId) throws SQLException {
        try {
            LOGGER.info("Table {} is new and will be captured.", tableId);
            offsetContext.event(tableId, Instant.now());
            dispatcher.dispatchSchemaChangeEvent(
                    tableId,
                    new DMSchemaChangeEventEmitter(
                            connectorConfig,
                            offsetContext,
                            tableId,
                            tableId.catalog(),
                            tableId.schema(),
                            getTableMetadataDdl(tableId),
                            schema,
                            Instant.now(),
                            streamingMetrics));

            return schema.tableFor(tableId);
        }
        catch (InterruptedException e) {
            throw new DebeziumException("Failed to dispatch schema change event", e);
        }
    }

    private String getTableMetadataDdl(TableId tableId) throws SQLException {
        final String pdbName = connectorConfig.getPdbName();
        // A separate connection must be used for this out-of-bands query while processing the LogMiner query results.
        // This should have negligible overhead as this should happen rarely.
        try (DMConnection connection = new DMConnection(connectorConfig.getJdbcConfig(), () -> getClass().getClassLoader())) {
            if (pdbName != null) {
                connection.setSessionToPdb(pdbName);
            }
            return connection.getTableMetadataDdl(tableId);
        }
    }

    /**
     * This method is warning if a long running transaction is discovered and could be abandoned in the future.
     * The criteria is the offset SCN remains the same in 25 mining cycles
     */
    private void warnStuckScn() {
        if (offsetContext != null && offsetContext.getCommitScn() != null) {
            final Scn scn = offsetContext.getScn();
            final Scn commitScn = offsetContext.getCommitScn();
            if (currentOffsetScn.equals(scn) && !currentOffsetCommitScn.equals(commitScn)) {
                stuckScnCounter++;
                // logWarn only once
                if (stuckScnCounter == 25) {
                    LogMinerHelper.logWarn(streamingMetrics,
                            "Offset SCN {} is not changing. It indicates long transaction(s). " +
                                    "Offset commit SCN: {}",
                            currentOffsetScn, commitScn);
                    streamingMetrics.incrementScnFreezeCount();
                }
            }
            else {
                stuckScnCounter = 0;
            }
        }
    }

    private LogMinerDmlEntry parse(String redoSql, Table table, String txId) {
        LogMinerDmlEntry dmlEntry;
        try {
            Instant parseStart = Instant.now();
            dmlEntry = dmlParser.parse(redoSql, table, txId);
            streamingMetrics.addCurrentParseTime(Duration.between(parseStart, Instant.now()));
        }
        catch (DmlParserException e) {
            StringBuilder message = new StringBuilder();
            message.append("DML statement couldn't be parsed.");
            message.append(" Please open a Jira issue with the statement '").append(redoSql).append("'.");
            if (LogMiningDmlParser.FAST.equals(connectorConfig.getLogMiningDmlParser())) {
                message.append(" You can set internal.log.mining.dml.parser='legacy' as a workaround until the parse error is fixed.");
            }
            throw new DmlParserException(message.toString(), e);
        }

        if (dmlEntry.getOldValues().length == 0) {
            if (RowMapper.UPDATE == dmlEntry.getOperation() || RowMapper.DELETE == dmlEntry.getOperation()) {
                LOGGER.warn("The DML event '{}' contained no before state.", redoSql);
                streamingMetrics.incrementWarningCount();
            }
        }

        return dmlEntry;
    }

    private boolean isTruncate(String sql) {
        return sql != null && (sql.startsWith("truncate") || sql.startsWith("TRUNCATE"));
    }

    private static boolean isDmlOperation(int operationCode) {
        switch (operationCode) {
            case RowMapper.INSERT:
            case RowMapper.UPDATE:
            case RowMapper.DELETE:
                return true;
            default:
                return false;
        }
    }
}
