package io.github.jbburns.manyfold.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** H2 and SQLite behind one URL: different drivers, different URL shapes, one result set. */
class HeterogeneousBackendsTest {

  @Test
  void h2AndSqliteMergeIntoOneResultSet(@TempDir Path dir) throws Exception {
    String h2 = "jdbc:h2:mem:hetero_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    String sqlite = "jdbc:sqlite:" + dir.resolve("hetero.db");
    for (String url : new String[] {h2, sqlite}) {
      try (Connection c = DriverManager.getConnection(url);
          Statement s = c.createStatement()) {
        s.execute("CREATE TABLE t (id INTEGER, name VARCHAR(20))");
        s.execute(
            "INSERT INTO t VALUES (1, '" + (url.equals(h2) ? "from h2" : "from sqlite") + "')");
      }
    }

    String url = "jdbc:manyfold:memory=" + h2 + " || file=" + sqlite;
    try (Connection conn = DriverManager.getConnection(url);
        Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id, name FROM t")) {
      List<String> rows = new ArrayList<>();
      while (rs.next()) {
        rows.add(rs.getString(1) + ":" + rs.getInt(2) + ":" + rs.getString("name"));
      }
      assertThat(rows).containsExactly("memory:1:from h2", "file:1:from sqlite");
      assertThat(conn.getMetaData().getDatabaseProductName()).isEqualTo("H2");
    }

    String reversed = "jdbc:manyfold:file=" + sqlite + " || memory=" + h2;
    try (Connection conn = DriverManager.getConnection(reversed)) {
      assertThat(conn.getMetaData().getDatabaseProductName()).isEqualTo("SQLite");
      assertThat(conn.isReadOnly()).isTrue();
    }
  }
}
