/*
 * Copyright 2018 phData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.phdata.pipeforge.jdbc

import java.sql._

import com.typesafe.scalalogging.LazyLogging
import io.phdata.pipeforge.jdbc.config.{ DatabaseConf, DatabaseType, ObjectType }
import io.phdata.pipeforge.jdbc.domain.{ Column, Table }

import scala.util.{ Failure, Success, Try }

/**
 * Parses table definitions from a database
 */
trait DatabaseMetadataParser extends LazyLogging {

  /**
   * Database connection
   * @return Connection
   */
  def connection: Connection

  /**
   * Database specific query that returns a result set containing all tables in the specified schema
   * @param schema Schema or database name
   * @return SQL query listing databases in schema
   */
  def listTablesStatement(schema: String): String

  /**
   * Database specific query selecting a single row from the a schema and table
   * @param schema Schema or database name
   * @param table Table name
   * @return SQL query selecting a single row from table
   */
  def singleRecordQuery(schema: String, table: String): String

  /**
   * Database specific query that returns a result set containing all views in the specified schema
   * @param schema Schema or database name
   * @return
   */
  def listViewsStatement(schema: String): String

  /**
   * Build column definitions for a specific table
   * @param schema Schema or database name
   * @param table Table name
   * @return A Set of Column definitions
   */
  def getColumnDefinitions(schema: String, table: String): Try[Set[Column]] = {
    val query = singleRecordQuery(schema, table)
    logger.debug(s"Gathering column definitions for $schema.$table, query: {}", query)
    results(newStatement.executeQuery(query))(_.getMetaData).toList.headOption match {
      case Some(metaData) =>
        val rsMetadata = metaData.asInstanceOf[java.sql.ResultSetMetaData]
        Success(mapMetaDataToColumn(metaData, rsMetadata))
      case None =>
        Failure(
          new Exception(s"$table does not contain any records, cannot provide column definitions"))
    }
  }

  /**
   * Main starting point for gathering table and column metadata
   * @param objectType Table or View
   * @param schema Schema or database name
   * @param tableWhiteList Optional table whitelisting
   * @return A Set of table definitions
   */
  def getTablesMetadata(objectType: ObjectType.Value,
                        schema: String,
                        tableWhiteList: Option[Set[String]],
                        skipWhiteListCheck: Boolean = false): Try[Set[Table]] =
    // Query database for a list of tables or views
    if (skipWhiteListCheck) {
      tableWhiteList match {
        case Some(tables) => Try(tables.flatMap(getTableMetadata(schema, _)))
        case None         => Failure(new Exception("Whitelist tables not specified."))
      }
    } else {
      val sourceTables = listTables(objectType, schema)
      checkWhiteListedTables(sourceTables, tableWhiteList) match {
        case Success(tables) => Try(tables.flatMap(getTableMetadata(schema, _)))
        case Failure(ex)     => Failure(ex)
      }
    }

  /**
   * Verifies all user supplied white listed tables or views with database
   * @param sourceTables A set containing a list of source tables or views
   * @param tableWhiteList Optional user supplied table whitelisting
   * @return A Set of tables
   */
  def checkWhiteListedTables(sourceTables: Set[String],
                             tableWhiteList: Option[Set[String]]): Try[Set[String]] =
    tableWhiteList match {
      case Some(whiteList) =>
        logger.debug("Checking user supplied white list against source system: {}", whiteList)
        if (whiteList.subsetOf(sourceTables)) {
          Success(whiteList)
        } else {
          Failure(new Exception(
            s"A table in the whitelist was not found in the source system, whitelist=$whiteList, source tables=$sourceTables"))
        }
      case None => Success(sourceTables)
    }

  /**
   * Gets metadata for an individual table and its columns
   * @param schema Schema or database name
   * @param table Table name
   * @return Table definition
   */
  def getTableMetadata(schema: String, table: String): Option[Table] =
    getColumnDefinitions(schema, table) match {
      case Success(allColumns) =>
        val pks     = primaryKeys(schema, table, allColumns)
        val columns = allColumns.diff(pks)
        Some(Table(table, pks, columns))
      case Failure(ex) =>
        logger.warn(s"Failed to get metadata for table:$table", ex)
        None
    }

  /**
   * Gets the primary keys for a table
   * @param schema Schema or database name
   * @param table Table name
   * @param columns Complete set of column definitions for the table
   * @return Primary key definitions
   */
  def primaryKeys(schema: String, table: String, columns: Set[Column]): Set[Column] = {
    val rs: ResultSet = metadata.getPrimaryKeys(schema, schema, table)
    logger.debug("Gathering primary keys from JDBC metadata")
    val pks = results(rs) { record =>
      record.getString("COLUMN_NAME") -> record.getInt("KEY_SEQ")
    }.toMap

    mapPrimaryKeyToColumn(pks, columns)
  }

  /**
   * Maps a JDBC result set to Column definition
   * @param metaData Result set metadata
   * @param rsMetadata
   * @return A set of column definitions
   */
  def mapMetaDataToColumn(metaData: ResultSetMetaData,
                          rsMetadata: ResultSetMetaData): Set[Column] = {
    def asBoolean(i: Int) = if (i == 0) false else true

    (1 to metaData.getColumnCount).map { i =>
      Column(
        metaData.getColumnName(i),
        JDBCType.valueOf(rsMetadata.getColumnType(i)),
        asBoolean(metaData.isNullable(i)),
        i,
        metaData.getPrecision(i),
        metaData.getScale(i)
      )
    }.toSet
  }

  /**
   * Finds the column definitions for primary key definitions
   * @param primaryKeys A Map containing the primary key and its column position
   * @param columns A Set of column definitions
   * @return Primary key column definitions
   */
  def mapPrimaryKeyToColumn(primaryKeys: Map[String, Int], columns: Set[Column]) =
    primaryKeys.flatMap {
      case (key, index) =>
        columns.find(_.name == key) match {
          case Some(column) => Some(column)
          case None         => None
        }
    }.toSet

  def metadata = connection.getMetaData

  /**
   * Gathers a list of tables or views from the specified schema in the database
   * @param objectType Table or View
   * @param schema Schema or database name
   * @return A Set of tables or views
   */
  def listTables(objectType: ObjectType.Value, schema: String): Set[String] = {
    val stmt: Statement = newStatement
    val query =
      if (objectType == ObjectType.TABLE) listTablesStatement(schema)
      else listViewsStatement(schema)
    logger.debug(s"Getting list of source ${objectType.toString}s, query: {}", query)
    results(stmt.executeQuery(query))(_.getString(1)).toSet
  }

  /**
   * Prepares a statement
   * @return
   */
  def newStatement = connection.createStatement()

  /**
   * Helper for iterating through a result set
   * @param resultSet The result set to iterate over
   * @param f the function to call on the result set
   * @tparam T The output
   * @return
   */
  protected def results[T](resultSet: ResultSet)(f: ResultSet => T) = {
    val iterator = new Iterator[T] {
      def hasNext = resultSet.next()

      def next() = f(resultSet)
    }

    val result = iterator.toList
    resultSet.close()
    result
  }
}

/**
 * Parses table definitions from database
 */
object DatabaseMetadataParser extends LazyLogging {

  /**
   * Parses table definition from database
   * @param configuration Database configuration
   * @return Set of table definitions
   */
  def parse(configuration: DatabaseConf, skipWhiteListCheck: Boolean = false): Try[Set[Table]] = {
    logger.info("Extracting metadata information from database: {}", configuration)

    // Establish connection to database
    getConnection(configuration) match {
      case Success(connection) =>
        // Determine the database type and parse table definitions
        configuration.databaseType match {
          case DatabaseType.MYSQL =>
            new MySQLMetadataParser(connection)
              .getTablesMetadata(configuration.objectType,
                                 configuration.schema,
                                 configuration.tables,
                                 skipWhiteListCheck)
          case DatabaseType.ORACLE =>
            new OracleMetadataParser(connection)
              .getTablesMetadata(configuration.objectType,
                                 configuration.schema,
                                 configuration.tables,
                                 skipWhiteListCheck)
          case DatabaseType.MSSQL =>
            new MsSQLMetadataParser(connection)
              .getTablesMetadata(configuration.objectType,
                                 configuration.schema,
                                 configuration.tables,
                                 skipWhiteListCheck)
          case DatabaseType.HANA =>
            new HANAMetadataParser(connection)
              .getTablesMetadata(configuration.objectType,
                                 configuration.schema,
                                 configuration.tables,
                                 skipWhiteListCheck)
          case DatabaseType.TERADATA =>
            new TeradataMetadataParser(connection)
              .getTablesMetadata(configuration.objectType,
                                 configuration.schema,
                                 configuration.tables,
                                 skipWhiteListCheck)
          case _ =>
            Failure(
              new Exception(
                s"Metadata parser for database type: " +
                s"${configuration.databaseType} has not been configured"))
        }
      case Failure(e) =>
        logger.error(s"Failed connecting to: $configuration", e)
        throw e
    }
  }

  /**
   * Create a connection to the database
   * @param configuration Database configuration
   * @return
   */
  def getConnection(configuration: DatabaseConf) = {
    logger.debug("Connecting to database: {}", configuration)
    Try(
      DriverManager
        .getConnection(configuration.jdbcUrl, configuration.username, configuration.password))
  }

}
