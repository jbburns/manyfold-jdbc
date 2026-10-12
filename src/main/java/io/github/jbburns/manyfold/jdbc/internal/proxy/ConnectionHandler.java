package io.github.jbburns.manyfold.jdbc.internal.proxy;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import io.github.jbburns.manyfold.jdbc.internal.backend.Backend;
import io.github.jbburns.manyfold.jdbc.internal.exec.FanOut;
import io.github.jbburns.manyfold.jdbc.internal.guard.ReadOnlyGuard;
import io.github.jbburns.manyfold.jdbc.internal.rewrite.BackendSql;
import io.github.jbburns.manyfold.jdbc.internal.rewrite.Directives;
import io.github.jbburns.manyfold.jdbc.internal.url.Options;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A {@link Connection} over N backend connections.
 *
 * <p>Statements are created on every backend and wrapped. A prepared statement is prepared once,
 * with each backend receiving its own text if a leading {@code manyfold} directive asks for
 * substitutions. Transaction and session settings fan out to every backend. Anything that returns a
 * single value is answered by the first backend. In read-only mode, attempts to turn the read-only
 * flag off are ignored so the backends stay in the state the connector put them in.
 *
 * <p>Objects that cannot be merged come from the primary (first) backend only: {@code createBlob},
 * {@code createClob}, {@code createNClob}, {@code createSQLXML}, {@code createArrayOf} and {@code
 * createStruct}, and the parameter metadata of prepared statements. Values created that way are not
 * usable on the other backends.
 *
 * <p>{@code getWarnings} returns the warnings recorded while connecting, such as a backend that
 * refused the read-only hint, followed by the primary backend's own warnings. {@code clearWarnings}
 * clears both.
 */
final class ConnectionHandler extends BaseHandler {

  private final List<Backend> backends;
  private final List<Connection> connections;
  private final FanOut fanOut;
  private final Options options;
  private final String redactedUrl;
  private volatile boolean closed;
  private volatile List<SQLWarning> openWarnings;
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
    this.openWarnings = chain(backends);
    this.fanOut = new FanOut(names);
    this.options = options;
    this.redactedUrl = redactedUrl;
  }

  /** Links the warnings recorded while opening the backends, in URL order. */
  private static List<SQLWarning> chain(List<Backend> backends) {
    List<SQLWarning> warnings = new ArrayList<>();
    for (Backend backend : backends) {
      SQLWarning warning = backend.warning();
      if (warning != null) {
        if (!warnings.isEmpty()) {
          warnings.get(warnings.size() - 1).setNextWarning(warning);
        }
        warnings.add(warning);
      }
    }
    return List.copyOf(warnings);
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
  protected @Nullable Object dispatch(Method method, Object[] args) throws Throwable {
    switch (method.getName()) {
      case "createStatement":
        {
          return createStatement(Statement.class, method, args, false);
        }
      case "prepareStatement":
        {
          return createStatement(PreparedStatement.class, method, args, true);
        }
      case "prepareCall":
        {
          return createStatement(CallableStatement.class, method, args, true);
        }
      case "getMetaData":
        {
          return Proxies.databaseMetaData(primary().getMetaData(), this);
        }
      case "close":
        {
          close();
          return null;
        }
      case "isClosed":
        {
          return closed || primary().isClosed();
        }
      case "abort":
        {
          if (closed) {
            return null;
          }
          try {
            fanOut.sequential(connections, c -> call(method, c, args));
          } finally {
            closed = true;
            fanOut.close();
          }
          return null;
        }
      case "isValid":
        {
          if (closed) {
            return false;
          }
          for (Boolean valid :
              fanOut.sequential(
                  connections, c -> Objects.requireNonNull((Boolean) call(method, c, args)))) {
            if (!Boolean.TRUE.equals(valid)) {
              return false;
            }
          }
          return true;
        }
      case "setReadOnly":
        {
          if (options.readOnly() && !Boolean.TRUE.equals(args[0])) {
            return null;
          }
          fanOut.sequential(connections, c -> call(method, c, args));
          return null;
        }
      case "getWarnings":
        {
          List<SQLWarning> ours = openWarnings;
          SQLWarning primaryWarnings = primary().getWarnings();
          if (ours.isEmpty()) {
            return primaryWarnings;
          }
          // The last of our own warnings points at whatever the primary reports right now.
          ours.get(ours.size() - 1).setNextWarning(primaryWarnings);
          return ours.get(0);
        }
      case "clearWarnings":
        {
          openWarnings = List.of();
          fanOut.sequential(connections, c -> call(method, c, args));
          return null;
        }
      case "isReadOnly":
        {
          return options.readOnly() || primary().isReadOnly();
        }
      case "setSavepoint":
        {
          List<Savepoint> savepoints =
              fanOut.sequential(
                  connections, c -> Objects.requireNonNull((Savepoint) call(method, c, args)));
          return new ManyfoldSavepoint(savepoints);
        }
      case "rollback":
      case "releaseSavepoint":
        {
          if (args.length == 1 && args[0] instanceof ManyfoldSavepoint) {
            ManyfoldSavepoint savepoint = (ManyfoldSavepoint) args[0];
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
      default:
        {
          if (method.getReturnType() == void.class) {
            fanOut.sequential(connections, c -> call(method, c, args));
            return null;
          }
          return call(method, primary(), args);
        }
    }
  }

  private Object createStatement(
      Class<? extends Statement> type, Method method, Object[] args, boolean prepared)
      throws Throwable {
    String sql = prepared ? (String) args[0] : null;
    if (options.readOnly() && prepared) {
      if (sql == null) {
        throw ReadOnlyGuard.refusal("statement is null");
      }
      ReadOnlyGuard.check(sql);
    }
    // The guard has seen the text as the caller wrote it. Directives are read next and rejected
    // before any backend is called; each backend then prepares its own text.
    BackendSql plan = sql == null ? null : Directives.plan(sql, fanOut.names());
    List<Statement> statements = new ArrayList<>(connections.size());
    try {
      for (int i = 0; i < connections.size(); i++) {
        Object[] own = args;
        if (plan != null) {
          own = args.clone();
          own[0] = plan.sqlFor(i);
        }
        statements.add((Statement) Objects.requireNonNull(call(method, connections.get(i), own)));
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
      List<@Nullable String> sent = plan == null ? null : plan.sent();
      throw ManyfoldException.backendFailed(
          fanOut.names().get(failed), t, sent == null ? null : sent.get(failed));
    }
    return Proxies.statement(type, statements, this, plan);
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
