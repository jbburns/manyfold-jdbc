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
}
