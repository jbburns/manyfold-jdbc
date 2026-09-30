package io.github.jbburns.manyfold.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Reproduces how DBeaver and SQuirreL load drivers: every jar of a driver definition in one {@link
 * URLClassLoader} whose parent cannot see the application classpath, and the driver class
 * instantiated by name rather than through {@code DriverManager}.
 *
 * <p>In that setup the vendor driver has never registered with {@code DriverManager}, and even if
 * it had, {@code DriverManager} would hide it from callers in another class loader. The manyfold
 * driver must find it through {@code ServiceLoader} on its own class loader.
 */
class IsolatedClassLoaderTest {

  private static URL locationOf(Class<?> type) {
    return type.getProtectionDomain().getCodeSource().getLocation();
  }

  @Test
  void findsVendorDriverInItsOwnClassLoaderWithoutDriverManager() throws Exception {
    URL[] jars = {locationOf(ManyfoldDriver.class), locationOf(org.h2.Driver.class)};
    try (URLClassLoader loader = new URLClassLoader(jars, ClassLoader.getPlatformClassLoader())) {
      Class<?> driverClass = Class.forName(ManyfoldDriver.class.getName(), true, loader);
      assertThat(driverClass).isNotSameAs(ManyfoldDriver.class);
      Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();

      String url =
          "jdbc:manyfold:a=jdbc:h2:mem:iso_a;DB_CLOSE_DELAY=-1 || b=jdbc:h2:mem:iso_b;DB_CLOSE_DELAY=-1";
      try (Connection conn = driver.connect(url, new Properties());
          Statement s = conn.createStatement();
          ResultSet rs = s.executeQuery("SELECT 42 AS answer")) {
        List<String> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(rs.getString(1) + "=" + rs.getInt("answer"));
        }
        assertThat(rows).containsExactly("a=42", "b=42");
        assertThat(conn.getMetaData().getDriverName()).isEqualTo("manyfold-jdbc");
      }
    }
  }
}
