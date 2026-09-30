package io.github.jbburns.manyfold.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jbburns.manyfold.jdbc.support.H2Pair;
import io.github.jbburns.manyfold.jdbc.support.SpyDriver;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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

  @Test
  void metadataResultSetsDoNotLeakTheVendorStatementOrConnection() throws Exception {
    try (H2Pair dbs = new H2Pair();
        Connection conn = DriverManager.getConnection(dbs.manyfoldUrl())) {
      DatabaseMetaData meta = conn.getMetaData();

      try (ResultSet rs = meta.getTables(null, "PUBLIC", "ORDERS", new String[] {"BASE TABLE"})) {
        assertThat(rs.getStatement()).isNull();
        assertThat(rs.isWrapperFor(org.h2.jdbc.JdbcResultSet.class)).isFalse();
        assertThat(rs.isWrapperFor(ResultSet.class)).isTrue();
        assertThat(rs.unwrap(ResultSet.class)).isSameAs(rs);
        assertThatThrownBy(() -> rs.unwrap(org.h2.jdbc.JdbcResultSet.class))
            .isInstanceOf(SQLException.class);
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("TABLE_NAME")).isEqualTo("ORDERS");
        assertThat(rs.getString(3)).isEqualTo("ORDERS");
        assertThat(rs.getMetaData().getColumnName(1)).isEqualTo("TABLE_CAT");
        assertThat(rs.next()).isFalse();
      }
      try (ResultSet rs = meta.getColumns(null, "PUBLIC", "ORDERS", "%")) {
        assertThat(rs.getStatement()).isNull();
        int columns = 0;
        while (rs.next()) {
          columns++;
        }
        assertThat(columns).isEqualTo(3);
      }
    }
  }
}
