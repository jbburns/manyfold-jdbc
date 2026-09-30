package io.github.jbburns.manyfold.jdbc.support;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * A driver whose URLs are keys into a table of canned outcomes: a connection to hand out, an
 * exception to throw, or (for an unknown key) {@code null}, which is what a driver does when it
 * declines a URL it nevertheless claimed to accept. Each instance has its own URL prefix so tests
 * never see each other's registrations. Register with {@link #registered()}; closing deregisters.
 */
public final class StubDriver implements Driver, AutoCloseable {

  private final String prefix = "jdbc:stub:" + UUID.randomUUID().toString().replace("-", "") + ":";
  private final Map<String, Object> outcomes = new ConcurrentHashMap<>();

  private StubDriver() {}

  /** Creates a driver and registers it with {@code DriverManager}. */
  public static StubDriver registered() throws SQLException {
    StubDriver driver = new StubDriver();
    DriverManager.registerDriver(driver);
    return driver;
  }

  /** The URL that this driver answers for {@code key}. */
  public String url(String key) {
    return prefix + key;
  }

  /** Makes {@code connect} for {@code key} return this connection. */
  public StubDriver connection(String key, Connection connection) {
    outcomes.put(key, connection);
    return this;
  }

  /** Makes {@code connect} for {@code key} throw. Only unchecked or {@link SQLException}. */
  public StubDriver failure(String key, Exception failure) {
    outcomes.put(key, failure);
    return this;
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    Object outcome = outcomes.get(url.substring(prefix.length()));
    if (outcome instanceof SQLException e) {
      throw e;
    }
    if (outcome instanceof RuntimeException e) {
      throw e;
    }
    return (Connection) outcome;
  }

  @Override
  public boolean acceptsURL(String url) {
    return url.startsWith(prefix);
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

  @Override
  public void close() throws SQLException {
    DriverManager.deregisterDriver(this);
  }
}
