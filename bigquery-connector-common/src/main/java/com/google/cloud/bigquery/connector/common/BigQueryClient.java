/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigquery.connector.common;

import com.google.cloud.BaseServiceException;
import com.google.cloud.RetryOption;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobConfiguration;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableDefinition;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.bigquery.TableResult;
import com.google.cloud.http.BaseHttpServiceException;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threeten.bp.Duration;

// holds caches and mappings
// presto converts the dataset and table names to lower case, while BigQuery is case sensitive
// the mappings here keep the mappings
public class BigQueryClient {
  private static final Logger log = LoggerFactory.getLogger(BigQueryClient.class);

  private static Cache<String, TableInfo> destinationTableCache =
      CacheBuilder.newBuilder().expireAfterWrite(15, TimeUnit.MINUTES).maximumSize(1000).build();

  private final BigQuery bigQuery;
  private final Optional<String> materializationProject;
  private final Optional<String> materializationDataset;

  public BigQueryClient(
      BigQuery bigQuery,
      Optional<String> materializationProject,
      Optional<String> materializationDataset) {
    this.bigQuery = bigQuery;
    this.materializationProject = materializationProject;
    this.materializationDataset = materializationDataset;
  }

  /**
   * Waits for a BigQuery Job to complete: this is a blocking function.
   *
   * @param job The {@code Job} to keep track of.
   */
  public static void waitForJob(Job job) {
    try {
      Job completedJob =
          job.waitFor(
              RetryOption.initialRetryDelay(Duration.ofSeconds(1)),
              RetryOption.totalTimeout(Duration.ofMinutes(3)));
      if (completedJob == null && completedJob.getStatus().getError() != null) {
        throw new UncheckedIOException(
            new IOException(completedJob.getStatus().getError().toString()));
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(
          "Could not copy table from temporary sink to destination table.", e);
    }
  }

  // return empty if no filters are used
  private static Optional<String> createWhereClause(String[] filters) {
    if (filters.length == 0) {
      return Optional.empty();
    }
    return Optional.of(Stream.of(filters).collect(Collectors.joining(") AND (", "(", ")")));
  }

  public TableInfo getTable(TableId tableId) {
    return bigQuery.getTable(tableId);
  }

  /**
   * Checks whether the requested table exists in BigQuery.
   *
   * @param tableId The TableId of the requested table in BigQuery
   * @return True if the requested table exists in BigQuery, false otherwise.
   */
  public boolean tableExists(TableId tableId) {
    return getTable(tableId) != null;
  }

  /**
   * Creates an empty table in BigQuery.
   *
   * @param tableId The TableId of the table to be created.
   * @param schema The Schema of the table to be created.
   * @return The {@code Table} object representing the table that was created.
   */
  public Table createTable(TableId tableId, Schema schema) {
    TableInfo tableInfo = TableInfo.newBuilder(tableId, StandardTableDefinition.of(schema)).build();
    return bigQuery.create(tableInfo);
  }

  /**
   * Creates a temporary table with a time-to-live of 1 day, and the same location as the
   * destination table; the temporary table will have the same name as the destination table, with
   * the current time in milliseconds appended to it; useful for holding temporary data in order to
   * overwrite the destination table.
   *
   * @param destinationTableId The TableId of the eventual destination for the data going into the
   *     temporary table.
   * @param schema The Schema of the destination / temporary table.
   * @return The {@code Table} object representing the created temporary table.
   */
  public Table createTempTable(TableId destinationTableId, Schema schema) {
    String tempProject = materializationProject.orElseGet(destinationTableId::getProject);
    String tempDataset = materializationDataset.orElseGet(destinationTableId::getDataset);
    String tableName = destinationTableId.getTable() + System.nanoTime();
    TableId tempTableId =
        tempProject == null
            ? TableId.of(tempDataset, tableName)
            : TableId.of(tempProject, tempDataset, tableName);
    // Build TableInfo with expiration time of one day from current epoch.
    TableInfo tableInfo =
        TableInfo.newBuilder(tempTableId, StandardTableDefinition.of(schema))
            .setExpirationTime(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1))
            .build();
    return bigQuery.create(tableInfo);
  }

  /**
   * Deletes this table in BigQuery.
   *
   * @param tableId The TableId of the table to be deleted.
   * @return True if the operation was successful, false otherwise.
   */
  public boolean deleteTable(TableId tableId) {
    return bigQuery.delete(tableId);
  }

  /**
   * Overwrites the given destination table, with all the data from the given temporary table,
   * transactionally.
   *
   * @param temporaryTableId The {@code TableId} representing the temporary-table.
   * @param destinationTableId The {@code TableId} representing the destination table.
   * @return The {@code Job} object representing this operation (which can be tracked to wait until
   *     it has finished successfully).
   */
  public Job overwriteDestinationWithTemporary(
      TableId temporaryTableId, TableId destinationTableId) {
    String queryFormat =
        "MERGE `%s`\n"
            + "USING (SELECT * FROM `%s`)\n"
            + "ON FALSE\n"
            + "WHEN NOT MATCHED THEN INSERT ROW\n"
            + "WHEN NOT MATCHED BY SOURCE THEN DELETE";

    QueryJobConfiguration queryConfig =
        QueryJobConfiguration.newBuilder(
                sqlFromFormat(queryFormat, destinationTableId, temporaryTableId))
            .setUseLegacySql(false)
            .build();

    return create(JobInfo.newBuilder(queryConfig).build());
  }

  String sqlFromFormat(String queryFormat, TableId destinationTableId, TableId temporaryTableId) {
    String destinationTableName = fullTableName(destinationTableId);
    String temporaryTableName = fullTableName(temporaryTableId);
    return String.format(queryFormat, destinationTableName, temporaryTableName);
  }

  /**
   * Creates a String appropriately formatted for BigQuery Storage Write API representing the given
   * table.
   *
   * @param tableId The {@code TableId} representing the given object.
   * @return The formatted String.
   */
  public String createTablePathForBigQueryStorage(TableId tableId) {
    return String.format(
        "projects/%s/datasets/%s/tables/%s",
        tableId.getProject(), tableId.getDataset(), tableId.getTable());
  }

  public TableInfo getReadTable(ReadTableOptions options) {
    Optional<String> query = options.query();
    // first, let check if this is a query
    if (query.isPresent()) {
      // in this case, let's materialize it and use it as the table
      validateViewsEnabled(options);
      String sql = query.get();
      return materializeQueryToTable(sql, options.expirationTimeInMinutes());
    }

    TableInfo table = getTable(options.tableId());
    if (table == null) {
      return null;
    }

    TableDefinition tableDefinition = table.getDefinition();
    TableDefinition.Type tableType = tableDefinition.getType();
    if (TableDefinition.Type.TABLE == tableType || TableDefinition.Type.EXTERNAL == tableType) {
      return table;
    }
    if (TableDefinition.Type.VIEW == tableType
        || TableDefinition.Type.MATERIALIZED_VIEW == tableType) {
      validateViewsEnabled(options);
      // view materialization is done in a lazy manner, so it can occur only when the data is read
      return table;
    }
    // not regular table or a view
    throw new BigQueryConnectorException(
        BigQueryErrorCode.UNSUPPORTED,
        String.format(
            "Table type '%s' of table '%s.%s' is not supported",
            tableType, table.getTableId().getDataset(), table.getTableId().getTable()));
  }

  private void validateViewsEnabled(ReadTableOptions options) {
    if (!options.viewsEnabled()) {
      throw new BigQueryConnectorException(
          BigQueryErrorCode.UNSUPPORTED,
          String.format(
              "Views are not enabled. You can enable views by setting '%s' to true. Notice"
                  + " additional cost may occur.",
              options.viewEnabledParamName()));
    }
  }

  DatasetId toDatasetId(TableId tableId) {
    return DatasetId.of(tableId.getProject(), tableId.getDataset());
  }

  public String getProjectId() {
    return bigQuery.getOptions().getProjectId();
  }

  Iterable<Dataset> listDatasets(String projectId) {
    return bigQuery.listDatasets(projectId).iterateAll();
  }

  Iterable<Table> listTables(DatasetId datasetId, TableDefinition.Type... types) {
    Set<TableDefinition.Type> allowedTypes = ImmutableSet.copyOf(types);
    Iterable<Table> allTables = bigQuery.listTables(datasetId).iterateAll();
    return StreamSupport.stream(allTables.spliterator(), false)
        .filter(table -> allowedTypes.contains(table.getDefinition().getType()))
        .collect(ImmutableList.toImmutableList());
  }

  TableId createDestinationTable(
      Optional<String> referenceProject, Optional<String> referenceDataset) {
    String project = materializationProject.orElse(referenceProject.orElse(null));
    String dataset = materializationDataset.orElse(referenceDataset.orElse(null));
    String name =
        String.format(
            "_bqc_%s", UUID.randomUUID().toString().toLowerCase(Locale.ENGLISH).replace("-", ""));
    return project == null ? TableId.of(dataset, name) : TableId.of(project, dataset, name);
  }

  public Table update(TableInfo table) {
    return bigQuery.update(table);
  }

  public Job createAndWaitFor(JobConfiguration.Builder jobConfiguration) {
    return createAndWaitFor(jobConfiguration.build());
  }

  public Job createAndWaitFor(JobConfiguration jobConfiguration) {
    JobInfo jobInfo = JobInfo.of(jobConfiguration);
    Job job = bigQuery.create(jobInfo);

    log.info("Submitted job {}. jobId: {}", jobConfiguration, job.getJobId());
    // TODO(davidrab): add retry options
    try {
      return job.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BigQueryException(
          BaseHttpServiceException.UNKNOWN_CODE,
          String.format("Failed to run the job [%s]", job),
          e);
    }
  }

  Job create(JobInfo jobInfo) {
    return bigQuery.create(jobInfo);
  }

  public TableResult query(String sql) {
    try {
      return bigQuery.query(QueryJobConfiguration.of(sql));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BigQueryException(
          BaseHttpServiceException.UNKNOWN_CODE,
          String.format("Failed to run the query [%s]", sql),
          e);
    }
  }

  String createSql(TableId table, ImmutableList<String> requiredColumns, String[] filters) {
    String columns =
        requiredColumns.isEmpty()
            ? "*"
            : requiredColumns.stream()
                .map(column -> String.format("`%s`", column))
                .collect(Collectors.joining(","));

    return createSql(table, columns, filters);
  }

  // assuming the SELECT part is properly formatted, can be used to call functions such as COUNT and
  // SUM
  String createSql(TableId table, String formattedQuery, String[] filters) {
    String tableName = fullTableName(table);

    String whereClause = createWhereClause(filters).map(clause -> "WHERE " + clause).orElse("");

    return String.format("SELECT %s FROM `%s` %s", formattedQuery, tableName, whereClause);
  }

  public static String fullTableName(TableId tableId) {
    if (tableId.getProject() == null) {
      return String.format("%s.%s", tableId.getDataset(), tableId.getTable());
    } else {
      return String.format(
          "%s.%s.%s", tableId.getProject(), tableId.getDataset(), tableId.getTable());
    }
  }

  public long calculateTableSize(TableId tableId, Optional<String> filter) {
    return calculateTableSize(getTable(tableId), filter);
  }

  public long calculateTableSize(TableInfo tableInfo, Optional<String> filter) {
    try {
      TableDefinition.Type type = tableInfo.getDefinition().getType();
      if (type == TableDefinition.Type.TABLE && !filter.isPresent()) {
        return tableInfo.getNumRows().longValue();
      } else if (type == TableDefinition.Type.VIEW
          || type == TableDefinition.Type.MATERIALIZED_VIEW
          || (type == TableDefinition.Type.TABLE && filter.isPresent())) {
        // run a query
        String table = fullTableName(tableInfo.getTableId());
        String whereClause = filter.map(f -> "WHERE " + f).orElse("");
        String sql = String.format("SELECT COUNT(*) from `%s` %s", table, whereClause);
        TableResult result = bigQuery.query(QueryJobConfiguration.of(sql));
        return result.iterateAll().iterator().next().get(0).getLongValue();
      } else {
        throw new IllegalArgumentException(
            String.format(
                "Unsupported table type %s for table %s",
                type, fullTableName(tableInfo.getTableId())));
      }
    } catch (InterruptedException e) {
      throw new BigQueryConnectorException(
          "Querying table size was interrupted on the client side", e);
    }
  }

  /**
   * Runs the provided query on BigQuery and saves the result in a temporary table.
   *
   * @param querySql the query to be run
   * @param expirationTimeInMinutes the time in minutes until the table is expired and auto-deleted
   * @return a reference to the table
   */
  public TableInfo materializeQueryToTable(String querySql, int expirationTimeInMinutes) {
    TableId tableId = createDestinationTable(Optional.empty(), Optional.empty());
    return materializeTable(querySql, tableId, expirationTimeInMinutes);
  }

  /**
   * Runs the provided query on BigQuery and saves the result in a temporary table. This method is
   * intended to be used to materialize views, so the view location (based on its TableId) is taken
   * as a location for the temporary table, removing the need to set the materializationProject and
   * materializationDataset properties
   *
   * @param querySql the query to be run
   * @param viewId the view the query came from
   * @param expirationTimeInMinutes the time in hours until the table is expired and auto-deleted
   * @return a reference to the table
   */
  public TableInfo materializeViewToTable(
      String querySql, TableId viewId, int expirationTimeInMinutes) {
    TableId tableId =
        createDestinationTable(
            Optional.ofNullable(viewId.getProject()), Optional.ofNullable(viewId.getDataset()));
    return materializeTable(querySql, tableId, expirationTimeInMinutes);
  }

  private TableInfo materializeTable(
      String querySql, TableId destinationTableId, int expirationTimeInMinutes) {
    try {
      return destinationTableCache.get(
          querySql,
          new DestinationTableBuilder(this, querySql, destinationTableId, expirationTimeInMinutes));
    } catch (Exception e) {
      throw new BigQueryConnectorException(
          BigQueryErrorCode.BIGQUERY_VIEW_DESTINATION_TABLE_CREATION_FAILED,
          String.format(
              "Error creating destination table using the following query: [%s]", querySql),
          e);
    }
  }

  public interface ReadTableOptions {
    TableId tableId();

    Optional<String> query();

    boolean viewsEnabled();

    String viewEnabledParamName();

    int expirationTimeInMinutes();
  }

  static class DestinationTableBuilder implements Callable<TableInfo> {
    final BigQueryClient bigQueryClient;
    final String querySql;
    final TableId destinationTable;
    final int expirationTimeInMinutes;

    DestinationTableBuilder(
        BigQueryClient bigQueryClient,
        String querySql,
        TableId destinationTable,
        int expirationTimeInMinutes) {
      this.bigQueryClient = bigQueryClient;
      this.querySql = querySql;
      this.destinationTable = destinationTable;
      this.expirationTimeInMinutes = expirationTimeInMinutes;
    }

    @Override
    public TableInfo call() {
      return createTableFromQuery();
    }

    TableInfo createTableFromQuery() {
      log.debug("destinationTable is %s", destinationTable);
      JobInfo jobInfo =
          JobInfo.of(
              QueryJobConfiguration.newBuilder(querySql)
                  .setDestinationTable(destinationTable)
                  .build());
      log.debug("running query %s", jobInfo);
      Job job = waitForJob(bigQueryClient.create(jobInfo));
      log.debug("job has finished. %s", job);
      if (job.getStatus().getError() != null) {
        throw BigQueryUtil.convertToBigQueryException(job.getStatus().getError());
      }
      // add expiration time to the table
      TableInfo createdTable = bigQueryClient.getTable(destinationTable);
      long expirationTime =
          createdTable.getCreationTime() + TimeUnit.MINUTES.toMillis(expirationTimeInMinutes);
      Table updatedTable =
          bigQueryClient.update(createdTable.toBuilder().setExpirationTime(expirationTime).build());
      return updatedTable;
    }

    Job waitForJob(Job job) {
      try {
        return job.waitFor();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BigQueryException(
            BaseServiceException.UNKNOWN_CODE,
            String.format("Job %s has been interrupted", job.getJobId()),
            e);
      }
    }
  }
}