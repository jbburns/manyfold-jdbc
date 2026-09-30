package io.github.jbburns.manyfold.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ManyfoldTest {

  @Test
  void urlPrefixIsAJdbcSubprotocol() {
    assertThat(Manyfold.URL_PREFIX).startsWith("jdbc:").endsWith(":");
  }

  @Test
  void delimiterNeverAppearsInAJdbcUrl() {
    assertThat(Manyfold.BACKEND_DELIMITER).doesNotContain(";", "=", "?", "&", "/", ":");
  }

  // The tests run against compiled classes, not the packaged jar, so there is no manifest and
  // therefore no implementation version.

  @Test
  void versionIsTheDevPlaceholderWhenRunningFromClasses() {
    assertThat(Manyfold.version()).isEqualTo("0.0.0-dev");
    assertThat(Manyfold.majorVersion()).isZero();
    assertThat(Manyfold.minorVersion()).isZero();
  }

  @Test
  void driverReportsTheSameVersionAsTheConstants() {
    ManyfoldDriver driver = new ManyfoldDriver();

    assertThat(driver.getMajorVersion()).isEqualTo(Manyfold.majorVersion());
    assertThat(driver.getMinorVersion()).isEqualTo(Manyfold.minorVersion());
    assertThat(driver.toString()).isEqualTo("manyfold-jdbc " + Manyfold.version());
  }
}
