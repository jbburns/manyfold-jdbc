package io.github.jbburns.manyfold.jdbc.internal.proxy;

import io.github.jbburns.manyfold.jdbc.Manyfold;
import java.lang.reflect.Method;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import org.jspecify.annotations.Nullable;

/**
 * The primary backend's {@link DatabaseMetaData}, except that the connection, URL and driver
 * identity describe the manyfold connection rather than the backend. Result sets are wrapped so
 * that {@code getStatement()} does not expose the backend's statement or connection.
 */
final class DatabaseMetaDataHandler extends BaseHandler {

  private final DatabaseMetaData primary;
  private final ConnectionHandler connection;

  DatabaseMetaDataHandler(DatabaseMetaData primary, ConnectionHandler connection) {
    this.primary = primary;
    this.connection = connection;
  }

  @Override
  protected @Nullable Object dispatch(Method method, Object[] args) throws Throwable {
    switch (method.getName()) {
      case "getConnection":
        return connection.proxy();
      case "getURL":
        return connection.redactedUrl();
      case "getDriverName":
        return Manyfold.NAME;
      case "getDriverVersion":
        return Manyfold.version();
      case "getDriverMajorVersion":
        return Manyfold.majorVersion();
      case "getDriverMinorVersion":
        return Manyfold.minorVersion();
      default:
        {
          Object result = call(method, primary, args);
          return result instanceof ResultSet
              ? Proxies.metaDataResultSet((ResultSet) result)
              : result;
        }
    }
  }

  @Override
  protected String describe() {
    return "ManyfoldDatabaseMetaData[" + connection.redactedUrl() + "]";
  }
}
