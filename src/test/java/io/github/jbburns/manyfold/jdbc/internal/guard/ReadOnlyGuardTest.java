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
        "SELECT REPLACE(a, 'x', 'y') FROM t",
        "SELECT * FROM t WHERE set_at > now()",
        "SELECT * FROM t WHERE begin_date < commit_date",
        "SELECT 1;",
        "SELECT 1 ;  -- trailing comment\n  /* and another */ ",
        "SELECT 1; ",
        "SELECT a$1, t$2 FROM t WHERE x = $1",
        "SELECT 1 -- a comment\nFROM t",
        "SELECT 1 --",
        "SELECT 5 - -3",
        "SELECT 'a;b; DROP TABLE t' FROM t",
        "SELECT \"a;b\" FROM t",
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
        "ANALYZE t",
        "REPLACE INTO t VALUES (1)",
        "LOAD DATA INFILE 'x' INTO TABLE t",
        "START TRANSACTION",
        "ROLLBACK",
        "SAVEPOINT s",
        "IMPORT FOREIGN SCHEMA s FROM SERVER x INTO y",
        "SET x = 1",
        "SELECT 1; PRAGMA user_version = 5",
        "PRAGMA user_version = 5",
        "SELECT * FROM t WHERE x = 1 AND PRAGMA_x() = 1 OR ATTACH = 1",
        "SELECT lo_import('/etc/passwd') FROM t WHERE DO = 1",
        "SELECT 1 FROM t WHERE NOTIFY = 1",
        "SELECT 1 FROM t WHERE vacuum = 1",
        "SELECT 1 FROM t WHERE COMMENT = 1",
        "SELECT 1 FROM t WHERE checkpoint = 1",
        "SELECT 1 FROM t WHERE prepare = 1",
        "SELECT 1 FROM t WHERE discard = 1",
        "SELECT 1 FROM t WHERE refresh = 1",
        "SELECT 1 FROM t WHERE detach = 1",
        "SELECT 1 FROM t WHERE shutdown = 1",
        "SELECT 1 FROM t WHERE deallocate = 1",
        "SELECT 1 FROM t WHERE cluster = 1",
        "SELECT 1 FROM t WHERE reindex = 1",
      })
  void refusesWrites(String sql) {
    assertThatThrownBy(() -> ReadOnlyGuard.check(sql))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageStartingWith("Refused in read-only mode")
        .hasMessageContaining("readOnly=false")
        .extracting(e -> ((ManyfoldException) e).getSQLState())
        .isEqualTo(ManyfoldException.STATE_READ_ONLY);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT 1; DROP TABLE t",
        "SELECT 1;DROP TABLE t",
        "SELECT 1; SELECT 2",
        "SELECT 1; -- c\nSELECT 2",
        "SELECT 1; 2",
        "SELECT 1;;",
        "SELECT 1; 'x'",
        "SELECT 1; PRAGMA user_version = 5",
        "SELECT 1 FROM t; SET x = 1",
        "SELECT 1 /* c */ ; /* c */ BEGIN",
        ";SELECT 1",
        "SELECT 1--1; DROP TABLE t",
        "SELECT 1 --1\n; DROP TABLE t",
      })
  void refusesMoreThanOneStatement(String sql) {
    assertThat(ReadOnlyGuard.refusalReason(sql))
        .isEqualTo("statement contains more than one statement");
    assertThatThrownBy(() -> ReadOnlyGuard.check(sql)).isInstanceOf(ManyfoldException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // PostgreSQL dollar quoting hides the quote characters from a naive tokenizer.
        "SELECT $$'$$; DROP TABLE t; SELECT $$'$$",
        "SELECT $tag$'$tag$; DROP TABLE t; SELECT $tag$'$tag$",
        "SELECT $_t1$ x $_t1$",
        // MySQL # comment.
        "SELECT 1 #'\n; DROP TABLE t; SELECT '",
        "SELECT 1 # harmless",
        // MySQL executable comments run their body.
        "SELECT 1 /*! ; DROP TABLE t */",
        "SELECT 1 /*M! ; DROP TABLE t */",
        // -- not followed by whitespace is a comment to PostgreSQL but not to MySQL.
        "SELECT 1--'\n; DROP TABLE t; SELECT '",
        "SELECT 1--\"\n; DROP TABLE t; SELECT \"",
        "SELECT 1--/*\n; DROP TABLE t; --*/",
        // Backslash escapes inside string literals.
        "SELECT '\\''; DROP TABLE t; SELECT '",
        "SELECT E'\\'' FROM t",
        "SELECT 'a\\b' FROM t",
        "SELECT \"\\\"; DROP TABLE t; SELECT \" FROM t",
        // A quote inside square brackets is a string to PostgreSQL and text to SQL Server.
        "SELECT a[ ']' ]; DROP TABLE t; SELECT ' ] FROM t",
      })
  void refusesQuotingThatDialectsReadDifferently(String sql) {
    assertThat(ReadOnlyGuard.refusalReason(sql))
        .isNotNull()
        .contains("not supported in read-only mode");
    assertThatThrownBy(() -> ReadOnlyGuard.check(sql))
        .isInstanceOf(ManyfoldException.class)
        .hasMessageContaining("not supported in read-only mode");
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
