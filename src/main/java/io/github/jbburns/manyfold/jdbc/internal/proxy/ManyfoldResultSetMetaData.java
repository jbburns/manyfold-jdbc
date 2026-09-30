package io.github.jbburns.manyfold.jdbc.internal.proxy;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * The primary backend's metadata with the source column prepended as column 1.
 *
 * <p>The source column is reported as a non-nullable {@code VARCHAR} that is read-only and not
 * searchable, with a display size wide enough for the longest logical name.
 */
public final class ManyfoldResultSetMetaData implements ResultSetMetaData {

  private final ResultSetMetaData delegate;
  private final String sourceColumn;
  private final int sourceWidth;

  /**
   * Creates the metadata.
   *
   * @param delegate the primary backend's metadata
   * @param sourceColumn name of the injected column
   * @param sourceWidth display width for the injected column
   */
  public ManyfoldResultSetMetaData(
      ResultSetMetaData delegate, String sourceColumn, int sourceWidth) {
    this.delegate = delegate;
    this.sourceColumn = sourceColumn;
    this.sourceWidth = Math.max(sourceWidth, sourceColumn.length());
  }

  /** The display width and precision reported for the source column. */
  int getPrecisionOfSource() {
    return sourceWidth;
  }

  private boolean isSource(int column) throws SQLException {
    if (column < 1 || column > getColumnCount()) {
      throw new SQLException("Column index out of range: " + column, "42703");
    }
    return column == 1;
  }

  @Override
  public int getColumnCount() throws SQLException {
    return delegate.getColumnCount() + 1;
  }

  @Override
  public boolean isAutoIncrement(int column) throws SQLException {
    return !isSource(column) && delegate.isAutoIncrement(column - 1);
  }

  @Override
  public boolean isCaseSensitive(int column) throws SQLException {
    return isSource(column) || delegate.isCaseSensitive(column - 1);
  }

  @Override
  public boolean isSearchable(int column) throws SQLException {
    return !isSource(column) && delegate.isSearchable(column - 1);
  }

  @Override
  public boolean isCurrency(int column) throws SQLException {
    return !isSource(column) && delegate.isCurrency(column - 1);
  }

  @Override
  public int isNullable(int column) throws SQLException {
    return isSource(column) ? columnNoNulls : delegate.isNullable(column - 1);
  }

  @Override
  public boolean isSigned(int column) throws SQLException {
    return !isSource(column) && delegate.isSigned(column - 1);
  }

  @Override
  public int getColumnDisplaySize(int column) throws SQLException {
    return isSource(column) ? sourceWidth : delegate.getColumnDisplaySize(column - 1);
  }

  @Override
  public String getColumnLabel(int column) throws SQLException {
    return isSource(column) ? sourceColumn : delegate.getColumnLabel(column - 1);
  }

  @Override
  public String getColumnName(int column) throws SQLException {
    return isSource(column) ? sourceColumn : delegate.getColumnName(column - 1);
  }

  @Override
  public String getSchemaName(int column) throws SQLException {
    return isSource(column) ? "" : delegate.getSchemaName(column - 1);
  }

  @Override
  public int getPrecision(int column) throws SQLException {
    return isSource(column) ? sourceWidth : delegate.getPrecision(column - 1);
  }

  @Override
  public int getScale(int column) throws SQLException {
    return isSource(column) ? 0 : delegate.getScale(column - 1);
  }

  @Override
  public String getTableName(int column) throws SQLException {
    return isSource(column) ? "" : delegate.getTableName(column - 1);
  }

  @Override
  public String getCatalogName(int column) throws SQLException {
    return isSource(column) ? "" : delegate.getCatalogName(column - 1);
  }

  @Override
  public int getColumnType(int column) throws SQLException {
    return isSource(column) ? Types.VARCHAR : delegate.getColumnType(column - 1);
  }

  @Override
  public String getColumnTypeName(int column) throws SQLException {
    return isSource(column) ? "VARCHAR" : delegate.getColumnTypeName(column - 1);
  }

  @Override
  public boolean isReadOnly(int column) throws SQLException {
    return isSource(column) || delegate.isReadOnly(column - 1);
  }

  @Override
  public boolean isWritable(int column) throws SQLException {
    return !isSource(column) && delegate.isWritable(column - 1);
  }

  @Override
  public boolean isDefinitelyWritable(int column) throws SQLException {
    return !isSource(column) && delegate.isDefinitelyWritable(column - 1);
  }

  @Override
  public String getColumnClassName(int column) throws SQLException {
    return isSource(column) ? String.class.getName() : delegate.getColumnClassName(column - 1);
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("Not a wrapper for " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }
}
