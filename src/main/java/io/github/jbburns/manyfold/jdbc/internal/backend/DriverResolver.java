package io.github.jbburns.manyfold.jdbc.internal.backend;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import io.github.jbburns.manyfold.jdbc.internal.url.Redact;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Finds the vendor driver for a real JDBC URL.
 *
 * <p>SQL clients such as DBeaver and SQuirreL load every jar in a driver definition into one class
 * loader and instantiate the configured driver class directly, without going through {@code
 * DriverManager}. Vendor drivers in that loader may therefore never have registered themselves. So
 * the lookup order is:
 *
 * <ol>
 *   <li>an explicit class name, when the user gave one;
 *   <li>{@link ServiceLoader} over this driver's own class loader, then the thread context loader;
 *   <li>{@link DriverManager#getDrivers()}, for plain classpath applications.
 * </ol>
 *
 * The manyfold driver itself is never a candidate, so a manyfold URL nested inside a manyfold URL
 * is reported as unsupported rather than recursing.
 */
public final class DriverResolver {

  private final Class<? extends Driver> self;

  /**
   * Creates a resolver.
   *
   * @param self the manyfold driver class, which is excluded from every lookup
   */
  public DriverResolver(Class<? extends Driver> self) {
    this.self = self;
  }

  /**
   * Resolves the driver for a URL.
   *
   * @param url the real JDBC URL
   * @param explicitClassName a driver class to use instead of searching, or null
   * @return a driver whose {@code acceptsURL} returned true
   * @throws SQLException if no driver accepts the URL
   */
  public Driver resolve(String url, @Nullable String explicitClassName) throws SQLException {
    if (explicitClassName != null && !explicitClassName.isBlank()) {
      return explicit(url, explicitClassName.trim());
    }
    for (Driver candidate : candidates()) {
      if (accepts(candidate, url)) {
        return candidate;
      }
    }
    throw new ManyfoldException(
        "No JDBC driver accepts '"
            + Redact.url(url)
            + "'. Add the vendor driver jar to the same driver definition (or classpath) as"
            + " manyfold-jdbc, or name the class with the manyfold.<name>.driver property.",
        ManyfoldException.STATE_CONNECTION_FAILURE);
  }

  private Driver explicit(String url, String className) throws SQLException {
    Driver driver;
    try {
      Class<?> type = Class.forName(className, true, loader());
      driver = type.asSubclass(Driver.class).getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
      throw new ManyfoldException(
          "Cannot load JDBC driver class '" + className + "': " + e,
          ManyfoldException.STATE_CONNECTION_FAILURE,
          e);
    }
    if (self.isInstance(driver)) {
      throw new ManyfoldException(
          "The manyfold driver cannot be a backend of itself",
          ManyfoldException.STATE_CONNECTION_FAILURE);
    }
    if (!accepts(driver, url)) {
      throw new ManyfoldException(
          "Driver class '" + className + "' does not accept '" + Redact.url(url) + "'",
          ManyfoldException.STATE_CONNECTION_FAILURE);
    }
    return driver;
  }

  /** Every driver visible to us, in lookup order, without duplicates. */
  List<Driver> candidates() {
    Set<Class<?>> seen = new LinkedHashSet<>();
    List<Driver> result = new ArrayList<>();

    Set<ClassLoader> loaders = new LinkedHashSet<>();
    loaders.add(loader());
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    if (context != null) {
      loaders.add(context);
    }
    for (ClassLoader loader : loaders) {
      Iterator<Driver> it = ServiceLoader.load(Driver.class, loader).iterator();
      while (true) {
        Driver driver;
        try {
          if (!it.hasNext()) {
            break;
          }
          driver = it.next();
        } catch (ServiceConfigurationError | LinkageError e) {
          // A driver that cannot be loaded, for example one needing a missing native library,
          // must not prevent the others from being found.
          continue;
        }
        add(seen, result, driver);
      }
    }

    Enumeration<Driver> registered = DriverManager.getDrivers();
    while (registered.hasMoreElements()) {
      add(seen, result, registered.nextElement());
    }
    return result;
  }

  private void add(Set<Class<?>> seen, List<Driver> result, Driver driver) {
    if (!self.isInstance(driver) && seen.add(driver.getClass())) {
      result.add(driver);
    }
  }

  private ClassLoader loader() {
    ClassLoader loader = self.getClassLoader();
    return loader != null ? loader : ClassLoader.getSystemClassLoader();
  }

  private static boolean accepts(Driver driver, String url) {
    try {
      return driver.acceptsURL(url);
    } catch (SQLException | RuntimeException e) {
      return false;
    }
  }
}
