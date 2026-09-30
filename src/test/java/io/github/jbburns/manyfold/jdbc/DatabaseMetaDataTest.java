package io.github.jbburns.manyfold.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jbburns.manyfold.jdbc.support.H2Pair;
import io.github.jbburns.manyfold.jdbc.support.SpyDriver;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseMetaDataTest {

  @Test
  void metadataComesFromThePrimaryBackendExceptForIdentity() throws Exception {
    try (H2Pair dbs = new H2Pair();
        Connection conn = DriverManager.getConnection(dbs.manyfoldUrl())) {
      DatabaseMetaData meta = conn.getMetaData();

      assertThat(meta.getDatabaseProductName()).isEqualTo("H2");
      assertThat(meta.getConnection()).isSameAs(conn);
      assertThat(meta.getURL())
          .isEqualTo("jdbc:manyfold:prod=" + dbs.prodUrl + " || dev=" + dbs.devUrl);
      assertThat(meta.getDriverName()).isEqualTo("manyfold-jdbc");
      assertThat(meta.getDriverVersion()).isEqualTo(Manyfold.version());
      assertThat(meta.getDriverMajorVersion()).isEqualTo(Manyfold.majorVersion());
      assertThat(meta.isReadOnly()).isFalse();
      assertThat(meta.toString()).startsWith("ManyfoldDatabaseMetaData[");
      assertThat(meta.isWrapperFor(DatabaseMetaData.class)).isTrue();

      List<String> tables = new ArrayList<>();
      try (ResultSet rs = meta.getTables(null, "PUBLIC", "%", new String[] {"BASE TABLE"})) {
        while (rs.next()) {
          tables.add(rs.getString("TABLE_NAME"));
        }
      }
      assertThat(tables).contains("ORDERS", "ONLY_HERE");
    }
  }

  @Test
  void urlNeverExposesCredentials() throws Exception {
    Connection spied = mock(Connection.class);
    when(spied.getMetaData()).thenReturn(mock(DatabaseMetaData.class));
    try (SpyDriver spy = SpyDriver.registered(spied)) {
      String url =
          "jdbc:manyfold:a=jdbc:spy://alice:s3cret@host/db?password=s3cret || b=jdbc:spy:x";
      try (Connection conn = DriverManager.getConnection(url)) {
        assertThat(conn.getMetaData().getURL())
            .isEqualTo("jdbc:manyfold:a=jdbc:spy://host/db?password=*** || b=jdbc:spy:x");
        assertThat(conn.toString()).doesNotContain("s3cret");
      }
    }
  }
}
