package io.phdata.jdbc.parsing

import java.sql.{Connection, JDBCType, ResultSetMetaData}

import com.typesafe.scalalogging.LazyLogging
import io.phdata.jdbc.domain.Column

class OracleMetadataParser(_connection: Connection)
    extends DatabaseMetadataParser
    with LazyLogging {

  def connection = _connection

  override def listTablesStatement(schema: String) =
    s"SELECT table_name FROM ALL_TABLES WHERE owner = '$schema'"

  override def listViewsStatement(schema: String) =
    s"SELECT view_name FROM ALL_VIEWS where owner = '$schema'"

  override def getColumnDefinitions(schema: String,
                                    table: String): Set[Column] = {
    def asBoolean(i: Int) = if (i == 0) false else true

    val query = singleRecordQuery(schema, table)
    logger.debug("Executing query: {}", query)
    val metaData: ResultSetMetaData =
      results(newStatement.executeQuery(query))(_.getMetaData).toList.head
    val oracleRsMetadata =
      metaData.asInstanceOf[oracle.jdbc.OracleResultSetMetaData]
    (1 to metaData.getColumnCount).map { i =>
      Column(
        metaData.getColumnName(i),
        JDBCType.valueOf(oracleRsMetadata.getColumnType(i)),
        asBoolean(metaData.isNullable(i)),
        i,
        metaData
          .getPrecision(i),
        metaData.getScale(i)
      )
    }.toSet
  }

  override def singleRecordQuery(schema: String, table: String) =
    s"SELECT * FROM ${schema}.${table} WHERE ROWNUM = 1"
}
