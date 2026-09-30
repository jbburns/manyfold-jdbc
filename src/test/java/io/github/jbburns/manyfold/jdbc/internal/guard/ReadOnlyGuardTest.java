package io.github.jbburns.manyfold.jdbc.internal.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReadOnlyGuardTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT 1",
        "select * from orders where status = 'x'",
        "  \n\t SELECT 1",
        "-- a comment\nSELECT 1",
        "/* block\ncomment */ SELECT 1",
        "WITH t AS (SELECT 1) SELECT * FROM t",
        "SHOW TABLES",
        "EXPLAIN SELECT 1",
        "DESCRIBE orders",
        "DESC orders",
        "VALUES (1, 2)",
        "TABLE orders",
        "SELECT 'INSERT INTO x' AS literal",
        "SELECT 'it''s an update' FROM t",
        "SELECT \"delete\" FROM \"insert\"",
        "SELECT `update` FROM t",
        "SELECT [drop] FROM t",
        "SELECT * FROM audit.delete_log",
        "SELECT * FROM t -- INSERT INTO is only a comment",
        "SELECT * FROM t /* DROP TABLE t */",
        "SELECT inserted_at, updated_by, deletion_reason FROM t",
        "SELECT count(*) FROM t WHERE created > now()",
      })
  void allowsReads(String sql) {
    assertThatCode(() -> ReadOnlyGuard.check(sql)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "INSERT INTO t VALUES (1)",
        "insert into t values (1)",
        "UPDATE t SET a = 1",
        "DELETE FROM t",
        "MERGE INTO t USING s ON 1=1 WHEN MATCHED THEN DELETE",
        "TRUNCATE TABLE t",
        "DROP TABLE t",
        "CREATE TABLE t (a int)",
        "ALTER TABLE t ADD b int",
        "GRANT ALL ON t TO x",
        "CALL do_things()",
        "EXEC sp_do_things",
        "SET search_path = x",
        "BEGIN",
        "COMMIT",
        "-- only a comment before a write\nDELETE FROM t",
        "/* c */ UPDATE t SET a = 1",
        "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x",
        "WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x",
        "SELECT * INTO new_table FROM t",
        "SELECT * FROM t FOR UPDATE",
        "",
        "   ",
        "-- nothing but a comment",
        "'a string on its own'",
      })
  void refusesWrites(String sql) {
    assertThatThrownBy(() -> ReadOnlyGuard.check(sql))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageStartingWith("Refused in read-only mode")
        .hasMessageContaining("readOnly=false")
        .extracting(e -> ((ManyfoldException) e).getSQLState())
        .isEqualTo(ManyfoldException.STATE_READ_ONLY);
  }

  @Test
  void reasonNamesTheOffendingKeyword() {
    assertThat(ReadOnlyGuard.refusalReason("SELECT * FROM t FOR UPDATE"))
        .isEqualTo("statement contains the keyword 'UPDATE'");
    assertThat(ReadOnlyGuard.refusalReason("Delete from t"))
        .isEqualTo("statement starts with 'Delete' rather than a query keyword");
    assertThat(ReadOnlyGuard.refusalReason("SELECT 1")).isNull();
  }

  @Test
  void unterminatedQuotesAndCommentsDoNotLoopOrThrow() {
    assertThat(ReadOnlyGuard.refusalReason("SELECT 'unterminated")).isNull();
    assertThat(ReadOnlyGuard.refusalReason("SELECT /* unterminated")).isNull();
    assertThat(ReadOnlyGuard.refusalReason("SELECT \"unterminated")).isNull();
    assertThat(ReadOnlyGuard.refusalReason("SELECT [unterminated")).isNull();
  }
}
