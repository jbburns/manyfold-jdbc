package io.github.jbburns.manyfold.jdbc.support;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Listed in the test-only {@code META-INF/services/java.sql.Driver} so that {@code ServiceLoader}
 * has a provider that cannot be instantiated, as one needing a missing native library would be.
 *
 * <p>The constructor throws only on a thread that has called {@link #failing()}. {@code
 * DriverManager} stops loading providers at the first one that throws, so a provider that always
 * failed would keep every driver listed after it, sqlite-jdbc among them, from registering for the
 * whole test run.
 */
public final class BrokenDriver implements Driver {

  private static final ThreadLocal<Boolean> FAILING = ThreadLocal.withInitial(() -> false);

  /** Makes the constructor throw on this thread until the result is closed. */
  public static AutoCloseable failing() {
    FAILING.set(true);
    return FAILING::remove;
  }

  /**
   * Throws while {@link #failing()} is in effect, which {@code ServiceLoader} reports as an error.
   */
  public BrokenDriver() {
    if (FAILING.get()) {
      throw new IllegalStateException("BrokenDriver cannot be instantiated");
    }
  }

  @Override
  public Connection connect(String url, Properties info) {
    return null;
  }

  @Override
  public boolean acceptsURL(String url) {
    return false;
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    return new DriverPropertyInfo[0];
  }

  @Override
  public int getMajorVersion() {
    return 0;
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
