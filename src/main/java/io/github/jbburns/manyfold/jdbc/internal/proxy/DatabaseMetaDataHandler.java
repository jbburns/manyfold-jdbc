package io.github.jbburns.manyfold.jdbc.internal.proxy;

import io.github.jbburns.manyfold.jdbc.Manyfold;
import java.lang.reflect.Method;
import java.sql.DatabaseMetaData;
import org.jspecify.annotations.Nullable;

/**
 * The primary backend's {@link DatabaseMetaData}, except that the connection, URL and driver
 * identity describe the manyfold connection rather than the backend.
 */
final class DatabaseMetaDataHandler extends BaseHandler {

  private final DatabaseMetaData primary;
  private final ConnectionHandler connection;

  DatabaseMetaDataHandler(DatabaseMetaData primary, ConnectionHandler connection) {
    this.primary = primary;
    this.connection = connection;
  }

  @Override
  protected @Nullable Object dispatch(Object proxy, Method method, Object[] args) throws Throwable {
    return switch (method.getName()) {
      case "getConnection" -> connection.proxy();
      case "getURL" -> connection.redactedUrl();
      case "getDriverName" -> Manyfold.NAME;
      case "getDriverVersion" -> Manyfold.version();
      case "getDriverMajorVersion" -> Manyfold.majorVersion();
      case "getDriverMinorVersion" -> Manyfold.minorVersion();
      default -> call(method, primary, args);
    };
  }

  @Override
  protected String describe() {
    return "ManyfoldDatabaseMetaData[" + connection.redactedUrl() + "]";
  }
}
