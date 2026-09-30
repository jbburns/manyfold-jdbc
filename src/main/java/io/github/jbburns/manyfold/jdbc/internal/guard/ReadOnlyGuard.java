package io.github.jbburns.manyfold.jdbc.internal.guard;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import io.github.jbburns.manyfold.jdbc.internal.lex.SqlLexer;
import io.github.jbburns.manyfold.jdbc.internal.lex.SqlLexer.Kind;
import io.github.jbburns.manyfold.jdbc.internal.lex.SqlLexer.Token;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Decides whether a statement may be forwarded when the connection is read-only.
 *
 * <p>This is deliberately not a SQL parser. It looks at the first keyword of the statement, after
 * skipping comments, and at the bare keywords that appear outside quotes. A statement is allowed
 * only when it is a single statement, its first keyword is on a small allowlist and none of the
 * modifying keywords appear anywhere in it. Identifiers in double quotes and string literals are
 * ignored. The heuristic errs on the side of refusing: a {@code SELECT ... FOR UPDATE} is refused,
 * as is an unquoted column named {@code delete}.
 *
 * <p>An unquoted {@code ;} ends a statement, and anything after it other than comments and
 * whitespace is refused. Quoting rules differ between databases, so constructs that one database
 * reads as a quote or comment and another does not are refused outright: PostgreSQL dollar quoting,
 * MySQL {@code #} comments and {@code /*!} executable comments, a backslash inside a string
 * literal, and {@code --} not followed by whitespace when the rest of that line contains a quote
 * character.
 *
 * <p>The first significant token must be a bare word on the allowlist; only opening parentheses may
 * precede it, so {@code (SELECT 1)} is allowed but a quoted name, a dotted name or punctuation at
 * the start is refused. Any non-ASCII or control character outside quotes and comments is refused,
 * because dialects disagree on whether it is whitespace or part of a name. An unterminated comment
 * or quote is refused instead of being read as running to the end of the statement.
 *
 * <p>What it cannot catch is a read-only-looking statement that calls a function with side effects.
 * For that the driver also asks every backend connection for a read-only transaction, which some
 * databases enforce and others treat as a hint.
 */
public final class ReadOnlyGuard {

  private static final Set<String> READ_FIRST_KEYWORDS =
      Set.of("SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "VALUES", "TABLE");

  /**
   * Refused wherever they appear. Statements that start with SET, BEGIN, COMMIT, ROLLBACK,
   * SAVEPOINT, START, LOAD, IMPORT, REPLACE or ANALYZE are already refused by the first-keyword
   * allowlist, and the single-statement rule stops them being smuggled in after a query, so those
   * words stay usable as function and column names.
   */
  private static final Set<String> MODIFYING_KEYWORDS =
      Set.of(
          "INSERT",
          "UPDATE",
          "DELETE",
          "MERGE",
          "UPSERT",
          "INTO",
          "CREATE",
          "DROP",
          "ALTER",
          "TRUNCATE",
          "RENAME",
          "GRANT",
          "REVOKE",
          "EXEC",
          "EXECUTE",
          "CALL",
          "LOCK",
          "PRAGMA",
          "ATTACH",
          "DETACH",
          "REFRESH",
          "COPY",
          "DO",
          "NOTIFY",
          "VACUUM",
          "REINDEX",
          "CLUSTER",
          "CHECKPOINT",
          "SHUTDOWN",
          "DISCARD",
          "PREPARE",
          "DEALLOCATE",
          "COMMENT");

  /** How much of an unrecognised token is quoted in a refusal message. */
  private static final int SNIPPET = 24;

  private ReadOnlyGuard() {}

  /**
   * Throws unless the statement is a read.
   *
   * @param sql the statement text exactly as the caller supplied it
   * @throws ManyfoldException with SQL state {@value ManyfoldException#STATE_READ_ONLY} if refused
   */
  public static void check(String sql) throws ManyfoldException {
    String reason = refusalReason(sql);
    if (reason != null) {
      throw refusal(reason);
    }
  }

  /**
   * Builds the exception for a refused operation.
   *
   * @param reason what was refused, as a clause such as "executeUpdate is not allowed"
   * @return the exception to throw
   */
  public static ManyfoldException refusal(String reason) {
    return new ManyfoldException(
        "Refused in read-only mode: "
            + reason
            + ". Set readOnly=false in the manyfold URL to forward writes to every backend.",
        ManyfoldException.STATE_READ_ONLY);
  }

  /**
   * Explains why a statement would be refused, or returns null when it is a read.
   *
   * @param sql the statement text
   * @return a reason or null
   */
  static @Nullable String refusalReason(String sql) {
    String first = null;
    boolean ended = false;
    SqlLexer tokens = new SqlLexer(sql, false);
    for (Token token = tokens.next(); token != null; token = tokens.next()) {
      if (tokens.unsupported() != null) {
        break;
      }
      // A word that is part of a dotted name such as schema.delete is not a keyword.
      Kind kind = token.kind() == Kind.WORD && token.dotted() ? Kind.OTHER : token.kind();
      String text = kind == Kind.WORD ? tokens.text(token) : tokens.text(token, SNIPPET);
      if (first == null && kind == Kind.OPEN_PAREN) {
        continue;
      }
      if (first == null && kind != Kind.WORD) {
        return "statement starts with '" + text + "' rather than a query keyword";
      }
      if (ended) {
        return "statement contains more than one statement";
      }
      if (kind == Kind.SEMICOLON) {
        ended = true;
        continue;
      }
      if (kind != Kind.WORD) {
        continue;
      }
      String upper = text.toUpperCase(Locale.ROOT);
      if (first == null) {
        first = upper;
        if (!READ_FIRST_KEYWORDS.contains(upper)) {
          return "statement starts with '" + text + "' rather than a query keyword";
        }
        continue;
      }
      if (MODIFYING_KEYWORDS.contains(upper)) {
        return "statement contains the keyword '" + text + "'";
      }
    }
    String unsupported = tokens.unsupported();
    if (unsupported != null) {
      return unsupported + ", which is not supported in read-only mode";
    }
    if (first == null) {
      return "statement is empty";
    }
    return null;
  }
}
