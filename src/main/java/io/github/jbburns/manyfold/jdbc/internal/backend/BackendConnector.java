package io.github.jbburns.manyfold.jdbc.internal.backend;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import io.github.jbburns.manyfold.jdbc.internal.url.BackendSpec;
import io.github.jbburns.manyfold.jdbc.internal.url.ManyfoldUrl;
import io.github.jbburns.manyfold.jdbc.internal.url.Redact;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Opens one vendor connection per backend, or none at all. */
public final class BackendConnector {

  private final DriverResolver resolver;

  /**
   * Creates a connector.
   *
   * @param resolver finds the vendor driver for each backend URL
   */
  public BackendConnector(DriverResolver resolver) {
    this.resolver = resolver;
  }

  /**
   * Connects to every backend in URL order.
   *
   * <p>If any backend fails, the ones already opened are closed and the failure is rethrown with
   * the backend's logical name. In read-only mode every connection is asked for a read-only
   * transaction; drivers that refuse to change that flag after connecting are tolerated, and the
   * refusal is recorded as a {@link SQLWarning} on the {@link Backend}, because the flag is a hint,
   * not the mechanism that enforces read-only mode.
   *
   * @param url the parsed manyfold URL
   * @param properties the properties given to the manyfold connection
   * @return the open backends
   * @throws SQLException if any backend cannot be opened
   */
  public List<Backend> open(ManyfoldUrl url, Properties properties) throws SQLException {
    List<Backend> opened = new ArrayList<>(url.backends().size());
    try {
      for (BackendSpec spec : url.backends()) {
        opened.add(openOne(url, spec, properties));
      }
    } catch (SQLException | RuntimeException e) {
      closeQuietly(opened);
      throw e;
    }
    return List.copyOf(opened);
  }

  private Backend openOne(ManyfoldUrl url, BackendSpec spec, Properties properties)
      throws SQLException {
    String explicitDriver = ManyfoldUrl.backendProperty(spec, properties, "driver");
    Driver driver = resolver.resolve(spec.url(), explicitDriver);
    Connection connection;
    try {
      connection = driver.connect(spec.url(), url.backendProperties(spec, properties));
    } catch (SQLException | RuntimeException e) {
      throw ManyfoldException.backendFailed(spec.name(), e);
    }
    if (connection == null) {
      throw new ManyfoldException(
          "Backend '"
              + spec.name()
              + "' failed: driver "
              + driver.getClass().getName()
              + " returned no connection for '"
              + Redact.url(spec.url())
              + "'",
          ManyfoldException.STATE_CONNECTION_FAILURE);
    }
    SQLWarning warning = null;
    if (url.options().readOnly()) {
      try {
        connection.setReadOnly(true);
      } catch (SQLException | RuntimeException e) {
        // A hint only. sqlite-jdbc, for one, refuses to change the flag after connecting.
        // Read-only mode is enforced by the statement guard regardless, but say so.
        String reason =
            e.getMessage() != null ? Redact.url(e.getMessage()) : e.getClass().getName();
        warning =
            new SQLWarning(
                "Backend '"
                    + spec.name()
                    + "' refused setReadOnly(true), so the database itself is not enforcing"
                    + " read-only mode; only the statement guard is: "
                    + reason,
                "01000",
                e);
      }
    }
    return new Backend(spec.name(), spec.url(), connection, warning);
  }

  private static void closeQuietly(List<Backend> backends) {
    for (Backend backend : backends) {
      try {
        backend.connection().close();
      } catch (SQLException | RuntimeException e) {
        // Best effort while unwinding from another failure.
      }
    }
  }
}
