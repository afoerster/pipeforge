package io.phdata.jdbc.pipewrench

import java.sql.JDBCType

import io.phdata.jdbc.domain.Column
import org.scalatest.FunSuite

class ColumnBuilderTest extends FunSuite {
  test("map decimal") {
    val column = Column("col1", JDBCType.NUMERIC, false, 0, 10, 4)
    assertResult("DECIMAL")(ColumnBuilder.mapDataType(column))
  }

  test("map big decimal") {
    val column = Column("col1", JDBCType.NUMERIC, false, 0, 19, 0)
    assertResult("BIGINT")(ColumnBuilder.mapDataType(column))
  }

  test("map int") {
    val column = Column("col1", JDBCType.NUMERIC, false, 0, 9, 0)
    assertResult("INTEGER")(ColumnBuilder.mapDataType(column))
  }

  test("map short") {
    val column = Column("col1", JDBCType.NUMERIC, false, 0, 5, 0)
    assertResult("SHORT")(ColumnBuilder.mapDataType(column))
  }

  test("map boolean") {
    val column = Column("col1", JDBCType.BOOLEAN, false, 0, 0, 0)
    assertResult("BOOLEAN")(ColumnBuilder.mapDataType(column))
  }
}
