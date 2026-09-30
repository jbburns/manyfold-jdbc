package io.github.jbburns.manyfold.jdbc.support;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A driver for {@code jdbc:spy:} URLs that hands out a canned connection, so tests can watch what
 * manyfold does with it. Register it for the duration of a test with {@link #registered}.
 */
public final class SpyDriver implements Driver, AutoCloseable {

  private final Connection connection;

  private SpyDriver(Connection connection) {
    this.connection = connection;
  }

  /** Registers a spy with {@code DriverManager}; closing the result deregisters it. */
  public static SpyDriver registered(Connection connection) throws SQLException {
    SpyDriver driver = new SpyDriver(connection);
    DriverManager.registerDriver(driver);
    return driver;
  }

  @Override
  public void close() throws SQLException {
    DriverManager.deregisterDriver(this);
  }

  @Override
  public Connection connect(String url, Properties info) {
    return acceptsURL(url) ? connection : null;
  }

  @Override
  public boolean acceptsURL(String url) {
    return url.startsWith("jdbc:spy:");
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    return new DriverPropertyInfo[0];
  }

  @Override
  public int getMajorVersion() {
    return 1;
  }

  @Override
  public int getMinorVersion() {
    return 0;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getGlobal();
  }
}
