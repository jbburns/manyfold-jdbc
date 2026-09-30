package io.github.jbburns.manyfold.jdbc.internal.proxy;

import io.github.jbburns.manyfold.jdbc.internal.backend.Backend;
import io.github.jbburns.manyfold.jdbc.internal.exec.FanOut;
import io.github.jbburns.manyfold.jdbc.internal.guard.ReadOnlyGuard;
import io.github.jbburns.manyfold.jdbc.internal.url.Options;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Connection} over N backend connections.
 *
 * <p>Statements are created on every backend and wrapped. Transaction and session settings fan out
 * to every backend. Anything that returns a single value is answered by the first backend. In
 * read-only mode, attempts to turn the read-only flag off are ignored so the backends stay in the
 * state the connector put them in.
 */
final class ConnectionHandler extends BaseHandler {

  private final List<Backend> backends;
  private final List<Connection> connections;
  private final FanOut fanOut;
  private final Options options;
  private final String redactedUrl;
  private volatile boolean closed;
  private @Nullable Connection proxy;

  ConnectionHandler(List<Backend> backends, Options options, String redactedUrl) {
    this.backends = List.copyOf(backends);
    List<Connection> conns = new ArrayList<>(backends.size());
    List<String> names = new ArrayList<>(backends.size());
    for (Backend backend : backends) {
      conns.add(backend.connection());
      names.add(backend.name());
    }
    this.connections = List.copyOf(conns);
    this.fanOut = new FanOut(names);
    this.options = options;
    this.redactedUrl = redactedUrl;
  }

  void attach(Connection proxy) {
    this.proxy = proxy;
  }

  Connection proxy() {
    return Objects.requireNonNull(proxy, "proxy not attached");
  }

  FanOut fanOut() {
    return fanOut;
  }

  Options options() {
    return options;
  }

  String redactedUrl() {
    return redactedUrl;
  }

  boolean isClosed() {
    return closed;
  }

  List<Backend> backends() {
    return backends;
  }

  private Connection primary() {
    return connections.get(0);
  }

  @Override
  protected @Nullable Object dispatch(Object proxy, Method method, Object[] args) throws Throwable {
    switch (method.getName()) {
      case "createStatement" -> {
        return createStatement(Statement.class, method, args, null);
      }
      case "prepareStatement" -> {
        return createStatement(PreparedStatement.class, method, args, (String) args[0]);
      }
      case "prepareCall" -> {
        return createStatement(CallableStatement.class, method, args, (String) args[0]);
      }
      case "getMetaData" -> {
        return Proxies.databaseMetaData(primary().getMetaData(), this);
      }
      case "close" -> {
        close();
        return null;
      }
      case "isClosed" -> {
        return closed || primary().isClosed();
      }
      case "isValid" -> {
        for (Boolean valid :
            fanOut.sequential(
                connections, c -> Objects.requireNonNull((Boolean) call(method, c, args)))) {
          if (!Boolean.TRUE.equals(valid)) {
            return false;
          }
        }
        return true;
      }
      case "setReadOnly" -> {
        if (options.readOnly() && !Boolean.TRUE.equals(args[0])) {
          return null;
        }
        fanOut.sequential(connections, c -> call(method, c, args));
        return null;
      }
      case "isReadOnly" -> {
        return options.readOnly() || primary().isReadOnly();
      }
      case "setSavepoint" -> {
        List<Savepoint> savepoints =
            fanOut.sequential(
                connections, c -> Objects.requireNonNull((Savepoint) call(method, c, args)));
        return new ManyfoldSavepoint(savepoints);
      }
      case "rollback", "releaseSavepoint" -> {
        if (args.length == 1 && args[0] instanceof ManyfoldSavepoint savepoint) {
          List<Savepoint> parts = savepoint.savepoints();
          List<Integer> indexes = new ArrayList<>();
          for (int i = 0; i < connections.size(); i++) {
            indexes.add(i);
          }
          fanOut.sequential(
              indexes, i -> call(method, connections.get(i), new Object[] {parts.get(i)}));
          return null;
        }
        if (args.length == 1) {
          throw new SQLException("Savepoint was not created by this connection");
        }
        fanOut.sequential(connections, c -> call(method, c, args));
        return null;
      }
      default -> {
        if (method.getReturnType() == void.class) {
          fanOut.sequential(connections, c -> call(method, c, args));
          return null;
        }
        return call(method, primary(), args);
      }
    }
  }

  private Object createStatement(
      Class<? extends Statement> type, Method method, Object[] args, @Nullable String sql)
      throws Throwable {
    if (sql != null && options.readOnly()) {
      ReadOnlyGuard.check(sql);
    }
    List<Statement> statements = new ArrayList<>(connections.size());
    try {
      for (Connection connection : connections) {
        statements.add((Statement) Objects.requireNonNull(call(method, connection, args)));
      }
    } catch (Throwable t) {
      for (Statement statement : statements) {
        try {
          statement.close();
        } catch (SQLException | RuntimeException e) {
          t.addSuppressed(e);
        }
      }
      int failed = statements.size();
      throw io.github.jbburns.manyfold.jdbc.ManyfoldException.backendFailed(
          fanOut.names().get(failed), t);
    }
    return Proxies.statement(type, statements, this, sql);
  }

  private void close() throws SQLException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      fanOut.sequential(
          connections, c -> call(Connection.class.getMethod("close"), c, new Object[0]));
    } finally {
      fanOut.close();
    }
  }

  @Override
  protected String describe() {
    return "ManyfoldConnection[" + redactedUrl + "]";
  }
}
