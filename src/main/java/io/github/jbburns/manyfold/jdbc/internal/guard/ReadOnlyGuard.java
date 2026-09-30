package io.github.jbburns.manyfold.jdbc.internal.guard;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
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
    Tokenizer tokens = new Tokenizer(sql);
    for (Token token = tokens.next(); token != null; token = tokens.next()) {
      if (ended) {
        return "statement contains more than one statement";
      }
      if (token.kind() == Kind.SEMICOLON) {
        ended = true;
        continue;
      }
      if (token.kind() != Kind.WORD) {
        continue;
      }
      String upper = token.text().toUpperCase(Locale.ROOT);
      if (first == null) {
        first = upper;
        if (!READ_FIRST_KEYWORDS.contains(upper)) {
          return "statement starts with '" + token.text() + "' rather than a query keyword";
        }
        continue;
      }
      if (MODIFYING_KEYWORDS.contains(upper)) {
        return "statement contains the keyword '" + token.text() + "'";
      }
    }
    if (tokens.unsupported != null) {
      return tokens.unsupported + ", which is not supported in read-only mode";
    }
    if (first == null) {
      return "statement is empty";
    }
    return null;
  }

  private enum Kind {
    /** A bare keyword or unqualified name. */
    WORD,
    /** An unquoted, uncommented {@code ;}. */
    SEMICOLON,
    /** Anything else that is not whitespace or a comment: quoted text, numbers, operators. */
    OTHER
  }

  private record Token(Kind kind, String text) {}

  /**
   * Yields tokens outside comments. Stops early, with {@link #unsupported} set, at quoting the
   * guard cannot interpret safely.
   */
  private static final class Tokenizer {
    private static final Token SEMICOLON = new Token(Kind.SEMICOLON, ";");
    private static final Token OTHER = new Token(Kind.OTHER, "");

    private final String sql;
    private int pos;

    /** Description of the unsupported construct that stopped tokenizing, or null. */
    @Nullable String unsupported;

    Tokenizer(String sql) {
      this.sql = sql;
    }

    @Nullable Token next() {
      int n = sql.length();
      while (pos < n && unsupported == null) {
        char c = sql.charAt(pos);
        if (Character.isWhitespace(c)) {
          pos++;
        } else if (c == '-' && peek(1) == '-' && endsComment(pos + 2)) {
          skipUntil('\n');
        } else if (c == '-' && peek(1) == '-' && lineOpensQuote(pos + 2)) {
          unsupported = "statement has '--' not followed by whitespace before a quote";
        } else if (c == '/' && peek(1) == '*') {
          if (peek(2) == '!' || (peek(2) == 'M' && peek(3) == '!')) {
            unsupported = "statement contains an executable comment";
          } else {
            pos += 2;
            int end = sql.indexOf("*/", pos);
            pos = end < 0 ? n : end + 2;
          }
        } else if (c == '#') {
          unsupported = "statement contains a '#' comment";
        } else if (c == '$' && startsDollarQuote(pos)) {
          unsupported = "statement uses dollar quoting";
        } else if (c == '\'' || c == '"' || c == '`') {
          skipQuoted(c);
          return OTHER;
        } else if (c == '[') {
          int end = sql.indexOf(']', pos);
          int stop = end < 0 ? n : end;
          for (int i = pos + 1; i < stop; i++) {
            char inner = sql.charAt(i);
            if (inner == '\'' || inner == '"' || inner == '`') {
              unsupported = "statement has a quote inside square brackets";
            }
          }
          pos = end < 0 ? n : end + 1;
          return OTHER;
        } else if (c == ';') {
          pos++;
          return SEMICOLON;
        } else if (Character.isLetter(c) || c == '_') {
          int start = pos;
          while (pos < n
              && (Character.isLetterOrDigit(sql.charAt(pos)) || sql.charAt(pos) == '_')) {
            pos++;
          }
          // A dotted name such as schema.delete is a qualified identifier, not a keyword.
          if ((start > 0 && sql.charAt(start - 1) == '.') || (pos < n && sql.charAt(pos) == '.')) {
            return OTHER;
          }
          return new Token(Kind.WORD, sql.substring(start, pos));
        } else {
          pos++;
          return OTHER;
        }
      }
      return null;
    }

    private char peek(int offset) {
      int i = pos + offset;
      return i < sql.length() ? sql.charAt(i) : '\0';
    }

    /** MySQL reads {@code --} as a comment only when whitespace or the end of input follows. */
    private boolean endsComment(int index) {
      return index >= sql.length()
          || Character.isWhitespace(sql.charAt(index))
          || Character.isISOControl(sql.charAt(index));
    }

    /**
     * PostgreSQL always reads {@code --} as a comment, MySQL only before whitespace. When the
     * dashes are not a comment to MySQL, a quote or comment opener on the rest of the line would be
     * hidden from PostgreSQL but visible here, so such a line cannot be interpreted safely.
     */
    private boolean lineOpensQuote(int from) {
      for (int i = from; i < sql.length() && sql.charAt(i) != '\n'; i++) {
        char c = sql.charAt(i);
        if (c == '\''
            || c == '"'
            || c == '`'
            || c == '['
            || (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*')) {
          return true;
        }
      }
      return false;
    }

    // A dollar sign directly followed by another, or by a tag and then another.
    private boolean startsDollarQuote(int index) {
      int i = index + 1;
      if (i < sql.length() && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
        while (i < sql.length()
            && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
          i++;
        }
      }
      return i < sql.length() && sql.charAt(i) == '$';
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
        if (c == '\\' && quote != '`') {
          unsupported = "statement has a backslash inside a quoted string";
          return;
        }
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
