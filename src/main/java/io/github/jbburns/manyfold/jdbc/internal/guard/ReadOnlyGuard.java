package io.github.jbburns.manyfold.jdbc.internal.guard;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a statement may be forwarded when the connection is read-only.
 *
 * <p>This is deliberately not a SQL parser. It looks at the first keyword of the statement, after
 * skipping comments, and at the bare keywords that appear outside quotes. A statement is allowed
 * only when its first keyword is on a small allowlist and none of the modifying keywords appear
 * anywhere in it. Identifiers in double quotes and string literals are ignored. The heuristic errs
 * on the side of refusing: a {@code SELECT ... FOR UPDATE} is refused, as is an unquoted column
 * named {@code delete}.
 *
 * <p>What it cannot catch is a read-only-looking statement that calls a function with side effects.
 * For that the driver also asks every backend connection for a read-only transaction, which some
 * databases enforce and others treat as a hint.
 */
public final class ReadOnlyGuard {

  private static final Set<String> READ_FIRST_KEYWORDS =
      Set.of("SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "VALUES", "TABLE");

  private static final Set<String> MODIFYING_KEYWORDS =
      Set.of(
          "INSERT",
          "UPDATE",
          "DELETE",
          "MERGE",
          "UPSERT",
          "REPLACE",
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
          "SET",
          "LOCK",
          "VACUUM",
          "ANALYZE",
          "REINDEX",
          "COPY",
          "LOAD",
          "IMPORT",
          "COMMIT",
          "ROLLBACK",
          "SAVEPOINT",
          "BEGIN",
          "START");

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
  static @org.jspecify.annotations.Nullable String refusalReason(String sql) {
    String first = null;
    Tokenizer tokens = new Tokenizer(sql);
    for (String token = tokens.next(); token != null; token = tokens.next()) {
      String upper = token.toUpperCase(Locale.ROOT);
      if (first == null) {
        first = upper;
        if (!READ_FIRST_KEYWORDS.contains(upper)) {
          return "statement starts with '" + token + "' rather than a query keyword";
        }
        continue;
      }
      if (MODIFYING_KEYWORDS.contains(upper)) {
        return "statement contains the keyword '" + token + "'";
      }
    }
    if (first == null) {
      return "statement is empty";
    }
    return null;
  }

  /** Yields bare words outside comments, string literals and quoted identifiers. */
  private static final class Tokenizer {
    private final String sql;
    private int pos;

    Tokenizer(String sql) {
      this.sql = sql;
    }

    @org.jspecify.annotations.Nullable
    String next() {
      int n = sql.length();
      while (pos < n) {
        char c = sql.charAt(pos);
        if (c == '-' && peek(1) == '-') {
          skipUntil('\n');
        } else if (c == '/' && peek(1) == '*') {
          pos += 2;
          int end = sql.indexOf("*/", pos);
          pos = end < 0 ? n : end + 2;
        } else if (c == '\'' || c == '"' || c == '`') {
          skipQuoted(c);
        } else if (c == '[') {
          skipUntil(']');
        } else if (Character.isLetter(c) || c == '_') {
          int start = pos;
          while (pos < n
              && (Character.isLetterOrDigit(sql.charAt(pos)) || sql.charAt(pos) == '_')) {
            pos++;
          }
          // A dotted name such as schema.delete is a qualified identifier, not a keyword.
          String word = sql.substring(start, pos);
          if (start > 0 && sql.charAt(start - 1) == '.') {
            continue;
          }
          if (pos < n && sql.charAt(pos) == '.') {
            continue;
          }
          return word;
        } else {
          pos++;
        }
      }
      return null;
    }

    private char peek(int offset) {
      int i = pos + offset;
      return i < sql.length() ? sql.charAt(i) : '\0';
    }

    private void skipUntil(char terminator) {
      int end = sql.indexOf(terminator, pos);
      pos = end < 0 ? sql.length() : end + 1;
    }

    private void skipQuoted(char quote) {
      pos++;
      int n = sql.length();
      while (pos < n) {
        char c = sql.charAt(pos);
        if (c == quote) {
          if (peek(1) == quote) {
            pos += 2;
            continue;
          }
          pos++;
          return;
        }
        pos++;
      }
    }
  }
}
