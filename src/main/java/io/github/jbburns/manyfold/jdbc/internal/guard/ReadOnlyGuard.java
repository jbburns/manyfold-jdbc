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
      if (tokens.unsupported != null) {
        break;
      }
      if (first == null && token.kind() == Kind.OPEN_PAREN) {
        continue;
      }
      if (first == null && token.kind() != Kind.WORD) {
        return "statement starts with '" + token.text() + "' rather than a query keyword";
      }
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
    /** An opening parenthesis, which may precede the first keyword. */
    OPEN_PAREN,
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
    private static final Token OPEN_PAREN = new Token(Kind.OPEN_PAREN, "(");
    private static final int SNIPPET = 24;

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
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
          pos++;
        } else if (c > 126 || c < 32) {
          // Dialects disagree on whether these are whitespace, part of a name or an error. The four
          // whitespace characters were handled above, so every control character left is refused.
          unsupported =
              "statement contains characters outside quotes that dialects read differently";
        } else if (c == '-' && peek(1) == '-' && endsComment(pos + 2)) {
          skipLine();
        } else if (c == '-' && peek(1) == '-' && lineOpensQuote(pos + 2)) {
          unsupported = "statement has '--' not followed by whitespace before a quote";
        } else if (c == '/' && peek(1) == '*') {
          if (peek(2) == '!' || (peek(2) == 'M' && peek(3) == '!')) {
            unsupported = "statement contains an executable comment";
          } else {
            pos += 2;
            int end = sql.indexOf("*/", pos);
            if (end < 0) {
              unsupported = "statement has an unterminated comment";
            }
            pos = end < 0 ? n : end + 2;
          }
        } else if (c == '#') {
          unsupported = "statement contains a '#' comment";
        } else if (c == '$' && startsDollarQuote(pos)) {
          unsupported = "statement uses dollar quoting";
        } else if (c == '\'' || c == '"' || c == '`') {
          int start = pos;
          skipQuoted(c);
          return other(start);
        } else if (c == '[') {
          int start = pos;
          int end = sql.indexOf(']', pos);
          int stop = end < 0 ? n : end;
          if (end < 0) {
            unsupported = "statement has an unterminated square bracket";
          }
          for (int i = pos + 1; i < stop; i++) {
            char inner = sql.charAt(i);
            if (inner == '\'' || inner == '"' || inner == '`') {
              unsupported = "statement has a quote inside square brackets";
            }
          }
          pos = end < 0 ? n : end + 1;
          return other(start);
        } else if (c == ';') {
          pos++;
          return SEMICOLON;
        } else if (c == '(') {
          pos++;
          return OPEN_PAREN;
        } else if (isWordStart(c)) {
          int start = pos;
          while (pos < n && isWordChar(sql.charAt(pos))) {
            pos++;
          }
          // A dotted name such as schema.delete is a qualified identifier, not a keyword, but only
          // when an identifier really sits on the other side of the dot.
          boolean after = pos + 1 < n && sql.charAt(pos) == '.' && isNameChar(sql.charAt(pos + 1));
          boolean before =
              start >= 2 && sql.charAt(start - 1) == '.' && isNameChar(sql.charAt(start - 2));
          if (before || after) {
            return other(start);
          }
          return new Token(Kind.WORD, sql.substring(start, pos));
        } else {
          pos++;
          return other(pos - 1);
        }
      }
      return null;
    }

    private Token other(int start) {
      return new Token(Kind.OTHER, sql.substring(start, Math.min(pos, start + SNIPPET)));
    }

    private static boolean isWordStart(char c) {
      return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isWordChar(char c) {
      return isWordStart(c) || (c >= '0' && c <= '9');
    }

    /** A letter, digit or underscore, or the closing quote or bracket of a quoted identifier. */
    private static boolean isNameChar(char c) {
      return isWordChar(c) || c == '"' || c == '`' || c == ']';
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

    /** Skips to the end of a line. PostgreSQL ends a line comment at a bare CR as well as LF. */
    private void skipLine() {
      int n = sql.length();
      while (pos < n && sql.charAt(pos) != '\n' && sql.charAt(pos) != '\r') {
        pos++;
      }
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
      unsupported = "statement has an unterminated quote";
    }
  }
}
