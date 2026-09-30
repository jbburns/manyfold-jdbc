package io.github.jbburns.manyfold.jdbc.internal.proxy;

import io.github.jbburns.manyfold.jdbc.internal.backend.Backend;
import io.github.jbburns.manyfold.jdbc.internal.url.Options;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Builds the JDK dynamic proxies that present N backend objects as one JDBC object. */
public final class Proxies {

  private Proxies() {}

  private static ClassLoader loader() {
    ClassLoader loader = Proxies.class.getClassLoader();
    return loader != null ? loader : ClassLoader.getSystemClassLoader();
  }

  /**
   * Wraps open backends as one connection.
   *
   * @param backends the open backends in URL order; the first is primary
   * @param options the driver options
   * @param redactedUrl the manyfold URL with credentials removed
   * @return the connection
   */
  public static Connection connection(List<Backend> backends, Options options, String redactedUrl) {
    ConnectionHandler handler = new ConnectionHandler(backends, options, redactedUrl);
    Connection proxy =
        (Connection) Proxy.newProxyInstance(loader(), new Class<?>[] {Connection.class}, handler);
    handler.attach(proxy);
    return proxy;
  }

  static Statement statement(
      Class<? extends Statement> type,
      List<Statement> statements,
      ConnectionHandler connection,
      @Nullable String sql) {
    StatementHandler handler = new StatementHandler(statements, connection, sql);
    Statement proxy = type.cast(Proxy.newProxyInstance(loader(), new Class<?>[] {type}, handler));
    handler.attach(proxy);
    return proxy;
  }

  static ResultSet resultSet(
      List<ResultSet> cursors, List<String> names, String sourceColumn, Statement statement)
      throws SQLException {
    ResultSetHandler handler = new ResultSetHandler(cursors, names, sourceColumn, statement);
    return (ResultSet) Proxy.newProxyInstance(loader(), new Class<?>[] {ResultSet.class}, handler);
  }

  static DatabaseMetaData databaseMetaData(DatabaseMetaData primary, ConnectionHandler connection) {
    return (DatabaseMetaData)
        Proxy.newProxyInstance(
            loader(),
            new Class<?>[] {DatabaseMetaData.class},
            new DatabaseMetaDataHandler(primary, connection));
  }

  static ResultSet metaDataResultSet(ResultSet delegate) {
    return (ResultSet)
        Proxy.newProxyInstance(
            loader(), new Class<?>[] {ResultSet.class}, new MetaDataResultSetHandler(delegate));
  }
}
