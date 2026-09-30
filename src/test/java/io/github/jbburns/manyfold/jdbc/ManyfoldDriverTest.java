package io.github.jbburns.manyfold.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.jbburns.manyfold.jdbc.internal.backend.Backend;
import io.github.jbburns.manyfold.jdbc.support.H2Pair;
import io.github.jbburns.manyfold.jdbc.support.SpyDriver;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ManyfoldDriverTest {

  @Test
  void isRegisteredWithDriverManager() throws Exception {
    Driver driver = DriverManager.getDriver("jdbc:manyfold:jdbc:h2:mem:x");

    assertThat(driver).isInstanceOf(ManyfoldDriver.class);
  }

  @Test
  void isListedAsAService() {
    assertThat(java.util.ServiceLoader.load(Driver.class))
        .anyMatch(d -> d instanceof ManyfoldDriver);
  }

  @Test
  void acceptsOnlyItsOwnPrefix() throws Exception {
    ManyfoldDriver driver = new ManyfoldDriver();

    assertThat(driver.acceptsURL("jdbc:manyfold:jdbc:h2:mem:x")).isTrue();
    assertThat(driver.acceptsURL("JDBC:MANYFOLD:jdbc:h2:mem:x")).isTrue();
    assertThat(driver.acceptsURL("jdbc:h2:mem:x")).isFalse();
    assertThat(driver.acceptsURL(null)).isFalse();
    assertThat(driver.connect("jdbc:h2:mem:x", new Properties())).isNull();
  }

  @Test
  void describesItselfHonestly() {
    ManyfoldDriver driver = new ManyfoldDriver();

    assertThat(driver.jdbcCompliant()).isFalse();
    assertThat(driver.getParentLogger()).isInstanceOf(Logger.class);
    assertThat(driver.getMajorVersion()).isGreaterThanOrEqualTo(0);
    assertThat(driver.toString()).startsWith(Manyfold.NAME);
  }

  @Test
  void propertyInfoListsDriverAndBackendPropertiesWithoutPasswordValues() throws Exception {
    Properties props = new Properties();
    props.setProperty("user", "u");
    props.setProperty("password", "s3cret");
    props.setProperty("manyfold.dev.password", "s3cret2");
    try (H2Pair dbs = new H2Pair()) {
      DriverPropertyInfo[] infos = new ManyfoldDriver().getPropertyInfo(dbs.manyfoldUrl(), props);

      assertThat(infos)
          .extracting(i -> i.name)
          .containsExactly(
              "user",
              "password",
              "manyfold.readOnly",
              "manyfold.sourceColumn",
              "manyfold.prod.user",
              "manyfold.prod.password",
              "manyfold.prod.driver",
              "manyfold.dev.user",
              "manyfold.dev.password",
              "manyfold.dev.driver");
      assertThat(infos).extracting(i -> i.value).doesNotContain("s3cret", "s3cret2");
      assertThat(infos[2].choices).containsExactly("true", "false");
      assertThat(infos[2].value).isEqualTo("true");
    }
  }

  @Test
  void propertyInfoStillWorksForAnUnparseableUrl() {
    DriverPropertyInfo[] infos = new ManyfoldDriver().getPropertyInfo("jdbc:manyfold:", null);

    assertThat(infos).extracting(i -> i.name).contains("user", "password", "manyfold.readOnly");
  }

  @Test
  void connectFailsNamingTheBackendWithoutADriver() {
    String url = "jdbc:manyfold:jdbc:h2:mem:fine || bad=jdbc:nothing-accepts-this://x";

    assertThatThrownBy(() -> DriverManager.getConnection(url))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageContaining("No JDBC driver accepts 'jdbc:nothing-accepts-this://x'");
  }

  @Test
  void connectClosesBackendsAlreadyOpenedWhenALaterOneFails() throws Exception {
    Connection spied = mock(Connection.class);
    try (SpyDriver spy = SpyDriver.registered(spied)) {
      String url = "jdbc:manyfold:first=jdbc:spy:one || second=jdbc:nothing-accepts-this://x";

      assertThatThrownBy(() -> DriverManager.getConnection(url)).isInstanceOf(SQLException.class);

      verify(spied).close();
    }
  }

  @Test
  void connectionIsUsableThroughDriverManager() throws Exception {
    try (H2Pair dbs = new H2Pair();
        Connection c = DriverManager.getConnection(dbs.manyfoldUrl())) {
      assertThat(c.isClosed()).isFalse();
      assertThat(c.isValid(1)).isTrue();
      assertThat(c.toString()).startsWith("ManyfoldConnection[").doesNotContain("password");
    }
  }

  @Test
  void propertyInfoWithANullUrlReturnsTheDriverWideProperties() {
    DriverPropertyInfo[] infos = new ManyfoldDriver().getPropertyInfo(null, new Properties());

    assertThat(infos).extracting(i -> i.name).contains("user", "password", "manyfold.readOnly");
  }

  @Test
  void connectWithANullUrlReturnsNull() throws Exception {
    assertThat(new ManyfoldDriver().connect(null, new Properties())).isNull();
  }

  @Test
  void backendRecordToStringDoesNotLeakThePassword() {
    Backend backend =
        new Backend(
            "prod", "jdbc:postgresql://u:s3cret@h/db?password=s3cret", mock(Connection.class));

    assertThat(backend.toString()).doesNotContain("s3cret").contains("prod");
  }
}
