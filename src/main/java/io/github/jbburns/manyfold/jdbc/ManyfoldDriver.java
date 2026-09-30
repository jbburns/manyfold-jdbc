package io.github.jbburns.manyfold.jdbc;

import io.github.jbburns.manyfold.jdbc.internal.backend.Backend;
import io.github.jbburns.manyfold.jdbc.internal.backend.BackendConnector;
import io.github.jbburns.manyfold.jdbc.internal.backend.DriverResolver;
import io.github.jbburns.manyfold.jdbc.internal.proxy.Proxies;
import io.github.jbburns.manyfold.jdbc.internal.url.BackendSpec;
import io.github.jbburns.manyfold.jdbc.internal.url.ManyfoldUrl;
import io.github.jbburns.manyfold.jdbc.internal.url.Options;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The manyfold pass-through JDBC driver.
 *
 * <p>Accepts URLs of the form {@code jdbc:manyfold:[options;][name=]jdbc:... || [name=]jdbc:...}.
 * Each backend URL is handed to the vendor driver that accepts it. The connection returned runs
 * every statement against every backend and merges the results, prepending a column that names the
 * backend each row came from.
 *
 * <p>The driver registers itself with {@link DriverManager} when the class is initialised and is
 * also listed in {@code META-INF/services/java.sql.Driver}, so both classpath applications and SQL
 * clients that instantiate the class directly can use it.
 */
public final class ManyfoldDriver implements Driver {

  private static final Logger LOGGER = Logger.getLogger("io.github.jbburns.manyfold.jdbc");

  static {
    try {
      DriverManager.registerDriver(new ManyfoldDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final DriverResolver resolver = new DriverResolver(ManyfoldDriver.class);

  /** Creates a driver. SQL clients call this constructor reflectively. */
  public ManyfoldDriver() {}

  @Override
  public @Nullable Connection connect(String url, @Nullable Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    Properties properties = info == null ? new Properties() : info;
    ManyfoldUrl parsed = ManyfoldUrl.parse(url, properties);
    List<Backend> backends = new BackendConnector(resolver).open(parsed, properties);
    return Proxies.connection(backends, parsed.options(), parsed.redacted());
  }

  @Override
  public boolean acceptsURL(@Nullable String url) {
    return ManyfoldUrl.accepts(url);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, @Nullable Properties info) {
    Properties properties = info == null ? new Properties() : info;
    List<DriverPropertyInfo> result = new ArrayList<>();
    result.add(property("user", properties.getProperty("user"), "User name for every backend"));
    result.add(property("password", null, "Password for every backend"));
    DriverPropertyInfo readOnly =
        property(
            ManyfoldUrl.PROPERTY_PREFIX + Options.READ_ONLY,
            properties.getProperty(ManyfoldUrl.PROPERTY_PREFIX + Options.READ_ONLY, "true"),
            "Refuse statements that can modify data (default true)");
    readOnly.choices = new String[] {"true", "false"};
    result.add(readOnly);
    result.add(
        property(
            ManyfoldUrl.PROPERTY_PREFIX + Options.SOURCE_COLUMN,
            properties.getProperty(
                ManyfoldUrl.PROPERTY_PREFIX + Options.SOURCE_COLUMN,
                Manyfold.DEFAULT_SOURCE_COLUMN),
            "Name of the column that identifies the backend each row came from"));
    try {
      for (BackendSpec backend : ManyfoldUrl.parse(url, properties).backends()) {
        String prefix = ManyfoldUrl.PROPERTY_PREFIX + backend.name() + ".";
        result.add(
            property(
                prefix + "user",
                ManyfoldUrl.backendProperty(backend, properties, "user"),
                "User name for backend '" + backend.name() + "' only"));
        result.add(
            property(
                prefix + "password", null, "Password for backend '" + backend.name() + "' only"));
        result.add(
            property(
                prefix + "driver",
                ManyfoldUrl.backendProperty(backend, properties, "driver"),
                "Driver class for backend '" + backend.name() + "', instead of searching"));
      }
    } catch (SQLException e) {
      // The URL is not parseable yet; the driver-wide properties are still useful.
    }
    return result.toArray(new DriverPropertyInfo[0]);
  }

  private static DriverPropertyInfo property(
      String name, @Nullable String value, String description) {
    DriverPropertyInfo info = new DriverPropertyInfo(name, value);
    info.description = description;
    info.required = false;
    return info;
  }

  @Override
  public int getMajorVersion() {
    return Manyfold.majorVersion();
  }

  @Override
  public int getMinorVersion() {
    return Manyfold.minorVersion();
  }

  /** Always false: the driver has not been run through the JDBC compliance suite. */
  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() {
    return LOGGER;
  }

  @Override
  public String toString() {
    return Manyfold.NAME + " " + Manyfold.version();
  }
}
