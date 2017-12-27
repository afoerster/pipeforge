package io.phdata.jdbc.pipewrench

import java.sql.JDBCType

import io.phdata.jdbc.domain.Column

object ColumnBuilder {
  def buildColumns(columns: Set[Column]) = {
    columns.toList
      .sortBy(_.index)
      .map(buildColumn)
  }

  def buildColumn(column: Column) = {
    val dataType = mapDataType(column)
    val map =
      Map("name" -> column.name, "datatype" -> dataType, "comment" -> "")

    if (dataType == DataType.DECIMAL) {
      map + ("scale" -> column.scale) + ("precision" -> column.precision)
    } else {
      map
    }
  }

  def mapDataType(column: Column) = {
    column match {
      case Column(_, JDBCType.NUMERIC, _, _, p, s) if s > 0 =>            DataType.DECIMAL
      case Column(_, JDBCType.NUMERIC, _, _, p, s) if s == 0 && p > 19 => DataType.DECIMAL
      case Column(_, JDBCType.NUMERIC, _, _, p, s) if s == 0 && p > 10 => DataType.BIG_INT
      case Column(_, JDBCType.NUMERIC, _, _, p, s) if s == 0 && p > 5 =>  DataType.INTEGER
      case Column(_, JDBCType.NUMERIC, _, _, p, s) if s == 0 && p > 3 =>  DataType.SHORT
      case _                                                          =>  column.dataType.toString
    }
  }
}

object DataType extends Enumeration {
  val BOOLEAN = Value("BOOLEAN")
  val DECIMAL = Value("DECIMAL")
  val BIG_INT = Value("BIGINT")
  val INTEGER = Value("INTEGER")
  val SHORT = Value("SHORT")
}
// map column java for numeric types
