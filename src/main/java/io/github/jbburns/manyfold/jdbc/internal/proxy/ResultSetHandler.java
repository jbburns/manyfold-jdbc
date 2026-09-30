package io.github.jbburns.manyfold.jdbc.internal.proxy;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A forward-only, read-only {@link ResultSet} that walks N backend result sets one after another.
 *
 * <p>Column 1 is the source column holding the logical name of the backend the current row came
 * from. Every other column index is the backend's index plus one, and every getter for those
 * columns is delegated to whichever backend cursor is current, so values, conversions and {@code
 * wasNull} behave exactly as the vendor driver implements them. Nothing is buffered.
 *
 * <p>Every backend must return the same number of columns; a mismatch is reported when the merged
 * result set is created, before any row is read.
 */
final class ResultSetHandler extends BaseHandler {

  private static final Set<String> UNSUPPORTED =
      Set.of(
          "beforeFirst",
          "afterLast",
          "first",
          "last",
          "absolute",
          "relative",
          "previous",
          "moveToInsertRow",
          "moveToCurrentRow",
          "insertRow",
          "updateRow",
          "deleteRow",
          "refreshRow",
          "cancelRowUpdates",
          "getCursorName",
          "isLast");

  private final List<ResultSet> cursors;
  private final List<String> names;
  private final String sourceColumn;
  private final Statement statement;
  private final int sourceWidth;
  private int current;
  private boolean started;
  private boolean afterLast;
  private boolean closed;
  private long row;
  private boolean lastReadWasSource;

  ResultSetHandler(
      List<ResultSet> cursors, List<String> names, String sourceColumn, Statement statement)
      throws SQLException {
    this.cursors = List.copyOf(cursors);
    this.names = List.copyOf(names);
    this.sourceColumn = sourceColumn;
    this.statement = statement;
    int width = 0;
    for (String name : names) {
      width = Math.max(width, name.length());
    }
    this.sourceWidth = width;
    checkColumnCounts();
  }

  private void checkColumnCounts() throws SQLException {
    try {
      int expected = cursors.get(0).getMetaData().getColumnCount();
      for (int i = 1; i < cursors.size(); i++) {
        int actual = cursors.get(i).getMetaData().getColumnCount();
        if (actual != expected) {
          throw new ManyfoldException(
              "Backend '"
                  + names.get(i)
                  + "' returned "
                  + actual
                  + " columns but '"
                  + names.get(0)
                  + "' returned "
                  + expected
                  + "; every backend must return the same columns",
              "HY000");
        }
      }
    } catch (SQLException | RuntimeException e) {
      closeQuietly();
      throw e;
    }
  }

  private ResultSet current() {
    return cursors.get(Math.min(current, cursors.size() - 1));
  }

  @Override
  protected @Nullable Object dispatch(Object proxy, Method method, Object[] args) throws Throwable {
    String name = method.getName();
    if (UNSUPPORTED.contains(name) || name.startsWith("update")) {
      throw new SQLFeatureNotSupportedException(
          "The merged result set is forward-only and read-only: " + name + " is not supported",
          ManyfoldException.STATE_FEATURE_NOT_SUPPORTED);
    }
    switch (name) {
      case "next" -> {
        return next();
      }
      case "close" -> {
        close();
        return null;
      }
      case "isClosed" -> {
        return closed;
      }
      case "wasNull" -> {
        return !lastReadWasSource && current().wasNull();
      }
      case "getMetaData" -> {
        ResultSetMetaData meta = cursors.get(0).getMetaData();
        return new ManyfoldResultSetMetaData(meta, sourceColumn, sourceWidth);
      }
      case "getStatement" -> {
        return statement;
      }
      case "findColumn" -> {
        String label = requireLabel((String) args[0]);
        if (label.equalsIgnoreCase(sourceColumn)) {
          return 1;
        }
        return (Integer) Objects.requireNonNull(callCurrent(method, args)) + 1;
      }
      case "getRow" -> {
        return Math.toIntExact(Math.min(row, Integer.MAX_VALUE));
      }
      case "isBeforeFirst" -> {
        return !started;
      }
      case "isAfterLast" -> {
        return afterLast;
      }
      case "isFirst" -> {
        return row == 1 && !afterLast;
      }
      case "getType" -> {
        return ResultSet.TYPE_FORWARD_ONLY;
      }
      case "getConcurrency" -> {
        return ResultSet.CONCUR_READ_ONLY;
      }
      case "getFetchDirection" -> {
        return ResultSet.FETCH_FORWARD;
      }
      case "setFetchDirection" -> {
        if (!Integer.valueOf(ResultSet.FETCH_FORWARD).equals(args[0])) {
          throw new SQLFeatureNotSupportedException(
              "Only FETCH_FORWARD is supported", ManyfoldException.STATE_FEATURE_NOT_SUPPORTED);
        }
        return null;
      }
      case "rowUpdated", "rowInserted", "rowDeleted" -> {
        return false;
      }
      case "setFetchSize", "clearWarnings" -> {
        for (ResultSet cursor : cursors) {
          call(method, cursor, args);
        }
        return null;
      }
      default -> {
        if (name.startsWith("get") && args.length > 0) {
          if (args[0] instanceof Integer column) {
            return getByIndex(method, args, column);
          }
          if (method.getParameterTypes()[0] == String.class) {
            return getByLabel(method, args, requireLabel((String) args[0]));
          }
        }
        return call(method, current(), args);
      }
    }
  }

  private boolean next() throws SQLException {
    if (closed) {
      throw new SQLException("Result set is closed", "24000");
    }
    started = true;
    while (current < cursors.size()) {
      boolean more;
      try {
        more = cursors.get(current).next();
      } catch (SQLException | RuntimeException e) {
        throw ManyfoldException.backendFailed(names.get(current), e);
      }
      if (more) {
        row++;
        return true;
      }
      current++;
    }
    afterLast = true;
    return false;
  }

  private @Nullable Object getByIndex(Method method, Object[] args, int column) throws Throwable {
    if (column == 1) {
      return sourceValue(method, args);
    }
    lastReadWasSource = false;
    Object[] shifted = args.clone();
    shifted[0] = column - 1;
    return callCurrent(method, shifted);
  }

  private @Nullable Object getByLabel(Method method, Object[] args, String label) throws Throwable {
    if (label.equalsIgnoreCase(sourceColumn)) {
      return sourceValue(method, args);
    }
    lastReadWasSource = false;
    return callCurrent(method, args);
  }

  /** Calls the current cursor, naming the backend in whatever it throws. */
  private @Nullable Object callCurrent(Method method, Object[] args) throws Throwable {
    try {
      return call(method, current(), args);
    } catch (SQLException | RuntimeException e) {
      throw ManyfoldException.backendFailed(names.get(Math.min(current, names.size() - 1)), e);
    }
  }

  private static String requireLabel(@Nullable String label) throws SQLException {
    if (label == null) {
      throw new SQLException("The column label is null", "22023");
    }
    return label;
  }

  private Object sourceValue(Method method, Object[] args) throws SQLException {
    if (!started || afterLast || closed) {
      throw new SQLException("No current row", "24000");
    }
    lastReadWasSource = true;
    String value = names.get(current);
    switch (method.getName()) {
      case "getString", "getNString" -> {
        return value;
      }
      case "getObject" -> {
        if (args.length == 2 && args[1] instanceof Class<?> type) {
          if (type.isAssignableFrom(String.class)) {
            return value;
          }
          throw conversion(type.getName());
        }
        return value;
      }
      case "getCharacterStream", "getNCharacterStream" -> {
        return new StringReader(value);
      }
      case "getBytes" -> {
        return value.getBytes(StandardCharsets.UTF_8);
      }
      case "getAsciiStream", "getBinaryStream" -> {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
      }
      default -> throw conversion(method.getName());
    }
  }

  private SQLDataException conversion(String target) {
    return new SQLDataException(
        "The source column '" + sourceColumn + "' is a string and cannot be read as " + target,
        "22018");
  }

  private void close() throws SQLException {
    if (closed) {
      return;
    }
    closed = true;
    SQLException failure = null;
    for (ResultSet cursor : cursors) {
      try {
        cursor.close();
      } catch (SQLException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.setNextException(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private void closeQuietly() {
    for (ResultSet cursor : cursors) {
      try {
        cursor.close();
      } catch (SQLException | RuntimeException e) {
        // Unwinding from a construction failure.
      }
    }
  }

  @Override
  protected String describe() {
    return "ManyfoldResultSet[" + String.join(", ", names) + "]";
  }
}
