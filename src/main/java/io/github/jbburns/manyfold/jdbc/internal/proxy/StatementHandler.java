package io.github.jbburns.manyfold.jdbc.internal.proxy;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import io.github.jbburns.manyfold.jdbc.internal.exec.FanOut;
import io.github.jbburns.manyfold.jdbc.internal.guard.ReadOnlyGuard;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Statement}, {@link java.sql.PreparedStatement} or {@link java.sql.CallableStatement}
 * over N backend statements.
 *
 * <p>Executions run on every backend concurrently and their results are merged: result sets become
 * one merged result set, update counts are summed. Parameter setters and other void methods fan out
 * sequentially. Single-valued getters come from the first backend. Streams and readers passed to
 * the streaming parameter setters are read once and replayed to every backend.
 *
 * <p>Some things cannot be merged and come from the primary (first) backend only: {@code
 * getWarnings}, the {@code ParameterMetaData} of a prepared statement, and the OUT parameters of a
 * {@link java.sql.CallableStatement}. The other backends do run the call, but their OUT values are
 * not readable through this object.
 */
final class StatementHandler extends BaseHandler {

  private final List<Statement> statements;
  private final ConnectionHandler connection;
  private final FanOut fanOut;
  private final @Nullable String preparedSql;
  private @Nullable Statement proxy;

  StatementHandler(List<Statement> statements, ConnectionHandler connection, @Nullable String sql) {
    this.statements = List.copyOf(statements);
    this.connection = connection;
    this.fanOut = connection.fanOut();
    this.preparedSql = sql;
  }

  void attach(Statement proxy) {
    this.proxy = proxy;
  }

  private Statement proxy() {
    return Objects.requireNonNull(proxy, "proxy not attached");
  }

  private Statement primary() {
    return statements.get(0);
  }

  private boolean readOnly() {
    return connection.options().readOnly();
  }

  private String sourceColumn() {
    return connection.options().sourceColumn();
  }

  @Override
  protected @Nullable Object dispatch(Method method, Object[] args) throws Throwable {
    switch (method.getName()) {
      case "executeQuery" -> {
        guard(sqlArgument(method, args));
        List<ResultSet> results =
            fanOut.parallel(
                statements, s -> (ResultSet) Objects.requireNonNull(call(method, s, args)));
        return merged(results);
      }
      case "execute" -> {
        guard(sqlArgument(method, args));
        List<Boolean> results =
            fanOut.parallel(
                statements, s -> (Boolean) Objects.requireNonNull(call(method, s, args)));
        return agree(results, "execute");
      }
      case "executeUpdate", "executeLargeUpdate" -> {
        refuseWrite(method.getName(), sqlArgument(method, args));
        List<Number> counts =
            fanOut.parallel(
                statements, s -> (Number) Objects.requireNonNull(call(method, s, args)));
        return sum(counts, method.getReturnType() == long.class);
      }
      case "addBatch" -> {
        refuseWrite("addBatch", sqlArgument(method, args));
        fanOut.sequential(statements, s -> call(method, s, args));
        return null;
      }
      case "executeBatch" -> {
        refuseWrite("executeBatch", null);
        List<int[]> counts =
            fanOut.parallel(statements, s -> (int[]) Objects.requireNonNull(call(method, s, args)));
        return sumIntArrays(counts);
      }
      case "executeLargeBatch" -> {
        refuseWrite("executeLargeBatch", null);
        List<long[]> counts =
            fanOut.parallel(
                statements, s -> (long[]) Objects.requireNonNull(call(method, s, args)));
        return sumLongArrays(counts);
      }
      case "getResultSet", "getGeneratedKeys" -> {
        List<@Nullable ResultSet> results =
            fanOut.sequential(statements, s -> (ResultSet) call(method, s, args));
        if (results.get(0) == null) {
          closeAll(results);
          return null;
        }
        for (int i = 1; i < results.size(); i++) {
          if (results.get(i) == null) {
            closeAll(results);
            throw new ManyfoldException(
                "Backend '"
                    + fanOut.names().get(i)
                    + "' produced no result set for "
                    + method.getName()
                    + " while '"
                    + fanOut.names().get(0)
                    + "' did",
                "HY000");
          }
        }
        List<ResultSet> present = new ArrayList<>(results.size());
        for (ResultSet rs : results) {
          present.add(Objects.requireNonNull(rs));
        }
        return merged(present);
      }
      case "getUpdateCount", "getLargeUpdateCount" -> {
        List<Number> counts =
            fanOut.sequential(
                statements, s -> (Number) Objects.requireNonNull(call(method, s, args)));
        for (Number count : counts) {
          if (count.longValue() < 0) {
            return method.getReturnType() == long.class
                ? (Number) Long.valueOf(-1L)
                : (Number) Integer.valueOf(-1);
          }
        }
        return sum(counts, method.getReturnType() == long.class);
      }
      case "getMoreResults" -> {
        List<Boolean> results =
            fanOut.sequential(
                statements, s -> (Boolean) Objects.requireNonNull(call(method, s, args)));
        return agree(results, "getMoreResults");
      }
      case "getConnection" -> {
        return connection.proxy();
      }
      case "getMetaData" -> {
        ResultSetMetaData meta = (ResultSetMetaData) call(method, primary(), args);
        return meta == null
            ? null
            : new ManyfoldResultSetMetaData(meta, sourceColumn(), longestName());
      }
      case "isClosed" -> {
        return connection.isClosed() || primary().isClosed();
      }
      case "close" -> {
        if (connection.isClosed()) {
          // The connection already closed its statements; closing again is a no-op.
          closeQuietly();
          return null;
        }
        fanOut.sequential(statements, s -> call(method, s, args));
        return null;
      }
      case "cancel" -> {
        fanOut.sequential(statements, s -> call(method, s, args));
        return null;
      }
      case "setBinaryStream",
          "setAsciiStream",
          "setUnicodeStream",
          "setCharacterStream",
          "setNCharacterStream",
          "setBlob",
          "setClob",
          "setNClob" -> {
        setStream(method, args);
        return null;
      }
      default -> {
        if (method.getReturnType() == void.class) {
          fanOut.sequential(statements, s -> call(method, s, args));
          return null;
        }
        return call(method, primary(), args);
      }
    }
  }

  /**
   * Fans out a streaming parameter setter. A stream or reader can be consumed only once, so it is
   * buffered and every backend receives its own fresh copy. Any length argument passes through.
   */
  private void setStream(Method method, Object[] args) throws Throwable {
    int index = -1;
    for (int i = 0; i < args.length; i++) {
      if (args[i] instanceof InputStream || args[i] instanceof Reader) {
        index = i;
        break;
      }
    }
    if (index < 0) {
      fanOut.sequential(statements, s -> call(method, s, args));
      return;
    }
    int streamIndex = index;
    Object source = args[streamIndex];
    byte[] bytes = null;
    String text = null;
    try {
      if (source instanceof InputStream in) {
        bytes = in.readAllBytes();
      } else {
        text = readAll((Reader) source);
      }
    } catch (IOException e) {
      throw new ManyfoldException(
          "Cannot read the stream passed to " + method.getName() + ": " + e.getMessage(),
          "HY000",
          e);
    }
    byte[] copyBytes = bytes;
    String copyText = text;
    fanOut.sequential(
        statements,
        s -> {
          Object[] own = args.clone();
          own[streamIndex] =
              copyBytes != null
                  ? new ByteArrayInputStream(copyBytes)
                  : new StringReader(Objects.requireNonNull(copyText));
          return call(method, s, own);
        });
  }

  private static String readAll(Reader reader) throws IOException {
    StringWriter out = new StringWriter();
    char[] buffer = new char[8192];
    for (int n = reader.read(buffer); n >= 0; n = reader.read(buffer)) {
      out.write(buffer, 0, n);
    }
    return out.toString();
  }

  private void closeQuietly() {
    for (Statement statement : statements) {
      try {
        statement.close();
      } catch (SQLException | RuntimeException e) {
        // The connection is already closed; there is nothing left to report.
      }
    }
  }

  private @Nullable String sqlArgument(Method method, Object[] args) throws ManyfoldException {
    if (args.length > 0 && method.getParameterTypes()[0] == String.class) {
      if (args[0] == null) {
        if (readOnly()) {
          throw ReadOnlyGuard.refusal("statement is null");
        }
        return null;
      }
      return (String) args[0];
    }
    return preparedSql;
  }

  private void guard(@Nullable String sql) throws ManyfoldException {
    if (readOnly() && sql != null) {
      ReadOnlyGuard.check(sql);
    }
  }

  private void refuseWrite(String operation, @Nullable String sql) throws ManyfoldException {
    if (!readOnly()) {
      return;
    }
    if (sql != null) {
      ReadOnlyGuard.check(sql);
    }
    throw ReadOnlyGuard.refusal(operation + " is not allowed");
  }

  private ResultSet merged(List<ResultSet> results) throws SQLException {
    return Proxies.resultSet(results, fanOut.names(), sourceColumn(), proxy());
  }

  private int longestName() {
    int width = 0;
    for (String name : fanOut.names()) {
      width = Math.max(width, name.length());
    }
    return width;
  }

  private static void closeAll(List<@Nullable ResultSet> results) {
    for (ResultSet rs : results) {
      if (rs != null) {
        try {
          rs.close();
        } catch (SQLException | RuntimeException e) {
          // Unwinding; nothing useful to do with a secondary failure.
        }
      }
    }
  }

  private Boolean agree(List<Boolean> results, String operation) throws ManyfoldException {
    Boolean first = results.get(0);
    for (int i = 1; i < results.size(); i++) {
      if (!first.equals(results.get(i))) {
        throw new ManyfoldException(
            "Backends disagree on the outcome of "
                + operation
                + ": '"
                + fanOut.names().get(0)
                + "' returned "
                + first
                + " but '"
                + fanOut.names().get(i)
                + "' returned "
                + results.get(i),
            "HY000");
      }
    }
    return first;
  }

  private static Object sum(List<Number> counts, boolean asLong) throws ManyfoldException {
    try {
      long total = 0;
      for (Number count : counts) {
        total = Math.addExact(total, count.longValue());
      }
      return asLong ? (Object) total : (Object) Math.toIntExact(total);
    } catch (ArithmeticException e) {
      throw new ManyfoldException(
          "The update counts of the backends add up to more than "
              + (asLong ? "a long" : "an int; use the large variant of the method")
              + " can hold",
          "22003",
          e);
    }
  }

  private int[] sumIntArrays(List<int[]> arrays) throws ManyfoldException {
    int[] total = arrays.get(0).clone();
    for (int i = 1; i < arrays.size(); i++) {
      int[] next = arrays.get(i);
      checkBatchLength(total.length, next.length, i);
      for (int j = 0; j < total.length; j++) {
        total[j] = addBatchCount(total[j], next[j]);
      }
    }
    return total;
  }

  private long[] sumLongArrays(List<long[]> arrays) throws ManyfoldException {
    long[] total = arrays.get(0).clone();
    for (int i = 1; i < arrays.size(); i++) {
      long[] next = arrays.get(i);
      checkBatchLength(total.length, next.length, i);
      for (int j = 0; j < total.length; j++) {
        total[j] = addBatchCount(total[j], next[j]);
      }
    }
    return total;
  }

  private void checkBatchLength(int expected, int actual, int backend) throws ManyfoldException {
    if (expected != actual) {
      throw new ManyfoldException(
          "Backend '"
              + fanOut.names().get(backend)
              + "' returned "
              + actual
              + " batch results but '"
              + fanOut.names().get(0)
              + "' returned "
              + expected,
          "HY000");
    }
  }

  /** Adds batch counts, keeping the JDBC sentinels SUCCESS_NO_INFO and EXECUTE_FAILED. */
  private static int addBatchCount(int a, int b) throws ManyfoldException {
    if (a < 0 || b < 0) {
      return Math.min(a, b);
    }
    try {
      return Math.addExact(a, b);
    } catch (ArithmeticException e) {
      throw batchOverflow(e);
    }
  }

  private static long addBatchCount(long a, long b) throws ManyfoldException {
    if (a < 0 || b < 0) {
      return Math.min(a, b);
    }
    try {
      return Math.addExact(a, b);
    } catch (ArithmeticException e) {
      throw batchOverflow(e);
    }
  }

  private static ManyfoldException batchOverflow(ArithmeticException e) {
    return new ManyfoldException(
        "The batch update counts of the backends add up to more than the count type can hold",
        "22003",
        e);
  }

  @Override
  protected String describe() {
    return "ManyfoldStatement[" + connection.redactedUrl() + "]";
  }
}
