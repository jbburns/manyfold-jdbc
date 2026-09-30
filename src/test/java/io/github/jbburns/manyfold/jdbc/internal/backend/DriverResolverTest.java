package io.github.jbburns.manyfold.jdbc.internal.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import io.github.jbburns.manyfold.jdbc.support.BrokenDriver;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class DriverResolverTest {

  /** Stands in for the manyfold driver so the resolver has something to exclude. */
  public static final class SelfDriver implements Driver {
    @Override
    public Connection connect(String url, Properties info) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean acceptsURL(String url) {
      return true;
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

  private final DriverResolver resolver = new DriverResolver(SelfDriver.class);

  @Test
  void findsH2ThroughServiceLoader() throws Exception {
    Driver driver = resolver.resolve("jdbc:h2:mem:resolver", null);

    assertThat(driver.getClass().getName()).isEqualTo("org.h2.Driver");
  }

  @Test
  void findsSqliteThroughServiceLoader() throws Exception {
    Driver driver = resolver.resolve("jdbc:sqlite::memory:", null);

    assertThat(driver.getClass().getName()).isEqualTo("org.sqlite.JDBC");
  }

  @Test
  void neverReturnsItself() {
    // SelfDriver accepts every URL but is not registered anywhere; register it to prove exclusion.
    assertThatThrownBy(() -> resolver.resolve("jdbc:nothing-accepts-this:x", null))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageContaining("No JDBC driver accepts 'jdbc:nothing-accepts-this:x'")
        .hasMessageContaining("manyfold.<name>.driver");
  }

  @Test
  void explicitClassNameSkipsTheSearch() throws Exception {
    Driver driver = resolver.resolve("jdbc:h2:mem:explicit", "org.h2.Driver");

    assertThat(driver.getClass().getName()).isEqualTo("org.h2.Driver");
  }

  @Test
  void explicitClassMustAcceptTheUrl() {
    assertThatThrownBy(() -> resolver.resolve("jdbc:sqlite::memory:", "org.h2.Driver"))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageContaining("does not accept");
  }

  @Test
  void explicitClassMustExist() {
    assertThatThrownBy(() -> resolver.resolve("jdbc:h2:mem:x", "org.example.Missing"))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageContaining("Cannot load JDBC driver class 'org.example.Missing'");
  }

  @Test
  void explicitClassMayNotBeTheDriverItself() {
    assertThatThrownBy(() -> resolver.resolve("jdbc:h2:mem:x", SelfDriver.class.getName()))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageContaining("cannot be a backend of itself");
  }

  @Test
  void errorMessagesRedactCredentials() {
    assertThatThrownBy(() -> resolver.resolve("jdbc:nope://alice:s3cret@host/db", null))
        .isInstanceOf(SQLException.class)
        .hasMessageNotContaining("s3cret");
  }

  @Test
  void aServiceProviderThatCannotBeLoadedIsSkipped() throws Exception {
    try (AutoCloseable failing = BrokenDriver.failing()) {
      // Guard against the fixture rotting: the test-only services file must really contribute a
      // provider that ServiceLoader cannot instantiate.
      int broken = 0;
      Iterator<Driver> providers = ServiceLoader.load(Driver.class).iterator();
      while (true) {
        try {
          if (!providers.hasNext()) {
            break;
          }
          providers.next();
        } catch (ServiceConfigurationError e) {
          broken++;
        }
      }
      assertThat(broken).isEqualTo(1);

      assertThat(resolver.resolve("jdbc:h2:mem:after_broken", null).getClass().getName())
          .isEqualTo("org.h2.Driver");
      assertThat(resolver.resolve("jdbc:sqlite::memory:", null).getClass().getName())
          .isEqualTo("org.sqlite.JDBC");
      assertThat(resolver.candidates()).noneMatch(d -> d instanceof BrokenDriver);
    }
  }

  @Test
  void aSkippedProviderDoesNotStopTheManyfoldDriverFindingBackends() throws Exception {
    try (AutoCloseable failing = BrokenDriver.failing();
        Connection conn = DriverManager.getConnection("jdbc:manyfold:jdbc:h2:mem:via_manyfold");
        Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT 1")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(2)).isEqualTo(1);
    }
  }
}
