package io.github.jbburns.manyfold.jdbc.internal.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManyfoldResultSetMetaDataTest {

  private ResultSetMetaData backend;
  private ManyfoldResultSetMetaData meta;

  @BeforeEach
  void setUp() throws SQLException {
    backend = mock(ResultSetMetaData.class);
    when(backend.getColumnCount()).thenReturn(2);
    when(backend.isAutoIncrement(1)).thenReturn(true);
    when(backend.isCaseSensitive(1)).thenReturn(false);
    when(backend.isSearchable(1)).thenReturn(true);
    when(backend.isCurrency(1)).thenReturn(true);
    when(backend.isNullable(1)).thenReturn(ResultSetMetaData.columnNullable);
    when(backend.isSigned(1)).thenReturn(true);
    when(backend.getColumnDisplaySize(1)).thenReturn(11);
    when(backend.getColumnLabel(1)).thenReturn("ID_LABEL");
    when(backend.getColumnName(1)).thenReturn("ID");
    when(backend.getSchemaName(1)).thenReturn("PUBLIC");
    when(backend.getPrecision(1)).thenReturn(10);
    when(backend.getScale(1)).thenReturn(3);
    when(backend.getTableName(1)).thenReturn("ORDERS");
    when(backend.getCatalogName(1)).thenReturn("CAT");
    when(backend.getColumnType(1)).thenReturn(Types.INTEGER);
    when(backend.getColumnTypeName(1)).thenReturn("INTEGER");
    when(backend.isReadOnly(1)).thenReturn(false);
    when(backend.isWritable(1)).thenReturn(true);
    when(backend.isDefinitelyWritable(1)).thenReturn(true);
    when(backend.getColumnClassName(1)).thenReturn("java.lang.Integer");
    meta = new ManyfoldResultSetMetaData(backend, "src", 4);
  }

  @Test
  void sourceColumnIsAFixedReadOnlyString() throws SQLException {
    assertThat(meta.getColumnCount()).isEqualTo(3);
    assertThat(meta.isAutoIncrement(1)).isFalse();
    assertThat(meta.isCaseSensitive(1)).isTrue();
    assertThat(meta.isSearchable(1)).isFalse();
    assertThat(meta.isCurrency(1)).isFalse();
    assertThat(meta.isNullable(1)).isEqualTo(ResultSetMetaData.columnNoNulls);
    assertThat(meta.isSigned(1)).isFalse();
    assertThat(meta.getColumnDisplaySize(1)).isEqualTo(4);
    assertThat(meta.getColumnLabel(1)).isEqualTo("src");
    assertThat(meta.getColumnName(1)).isEqualTo("src");
    assertThat(meta.getSchemaName(1)).isEmpty();
    assertThat(meta.getPrecision(1)).isEqualTo(4);
    assertThat(meta.getScale(1)).isZero();
    assertThat(meta.getTableName(1)).isEmpty();
    assertThat(meta.getCatalogName(1)).isEmpty();
    assertThat(meta.getColumnType(1)).isEqualTo(Types.VARCHAR);
    assertThat(meta.getColumnTypeName(1)).isEqualTo("VARCHAR");
    assertThat(meta.isReadOnly(1)).isTrue();
    assertThat(meta.isWritable(1)).isFalse();
    assertThat(meta.isDefinitelyWritable(1)).isFalse();
    assertThat(meta.getColumnClassName(1)).isEqualTo("java.lang.String");
  }

  @Test
  void displayWidthIsAtLeastTheColumnName() {
    assertThat(new ManyfoldResultSetMetaData(backend, "source_database", 2).getPrecisionOfSource())
        .isEqualTo("source_database".length());
  }

  @Test
  void otherColumnsShiftByOne() throws SQLException {
    assertThat(meta.isAutoIncrement(2)).isTrue();
    assertThat(meta.isCaseSensitive(2)).isFalse();
    assertThat(meta.isSearchable(2)).isTrue();
    assertThat(meta.isCurrency(2)).isTrue();
    assertThat(meta.isNullable(2)).isEqualTo(ResultSetMetaData.columnNullable);
    assertThat(meta.isSigned(2)).isTrue();
    assertThat(meta.getColumnDisplaySize(2)).isEqualTo(11);
    assertThat(meta.getColumnLabel(2)).isEqualTo("ID_LABEL");
    assertThat(meta.getColumnName(2)).isEqualTo("ID");
    assertThat(meta.getSchemaName(2)).isEqualTo("PUBLIC");
    assertThat(meta.getPrecision(2)).isEqualTo(10);
    assertThat(meta.getScale(2)).isEqualTo(3);
    assertThat(meta.getTableName(2)).isEqualTo("ORDERS");
    assertThat(meta.getCatalogName(2)).isEqualTo("CAT");
    assertThat(meta.getColumnType(2)).isEqualTo(Types.INTEGER);
    assertThat(meta.getColumnTypeName(2)).isEqualTo("INTEGER");
    assertThat(meta.isReadOnly(2)).isFalse();
    assertThat(meta.isWritable(2)).isTrue();
    assertThat(meta.isDefinitelyWritable(2)).isTrue();
    assertThat(meta.getColumnClassName(2)).isEqualTo("java.lang.Integer");
  }

  @Test
  void rejectsIndexesOutOfRange() {
    assertThatThrownBy(() -> meta.getColumnLabel(0)).isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> meta.getColumnLabel(4)).isInstanceOf(SQLException.class);
  }

  @Test
  void wrapsOnlyItself() throws SQLException {
    assertThat(meta.isWrapperFor(ResultSetMetaData.class)).isTrue();
    assertThat(meta.unwrap(ResultSetMetaData.class)).isSameAs(meta);
    assertThat(meta.isWrapperFor(String.class)).isFalse();
    assertThatThrownBy(() -> meta.unwrap(String.class)).isInstanceOf(SQLException.class);
  }
}
