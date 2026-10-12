package io.github.jbburns.manyfold.jdbc.internal.lex;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Splits a statement into coarse tokens, skipping whitespace and, unless asked to report them,
 * comments. It is shared by the read-only guard and the schema rewriter so both read quotes,
 * comments and brackets by exactly the same rules.
 *
 * <p>This is deliberately not a SQL parser. Tokens carry only their kind and their offsets in the
 * statement; nothing is copied until a caller asks for {@link #text}. The lexer stops early, with
 * {@link #unsupported} set, at quoting it cannot interpret safely, because dialects disagree on it:
 * PostgreSQL dollar quoting, MySQL {@code #} comments and {@code /*!} executable comments, a
 * backslash inside a string, {@code --} not followed by whitespace when the rest of the line
 * contains a quote character, a quote inside square brackets, control and non-ASCII characters
 * outside quotes, and unterminated comments, quotes and brackets.
 */
public final class SqlLexer {

  /** What a token is. */
  public enum Kind {
    /** A bare keyword or name, without quotes. */
    WORD,
    /** An unquoted, uncommented {@code ;}. */
    SEMICOLON,
    /** An opening parenthesis. */
    OPEN_PAREN,
    /** Anything else that is not whitespace or a comment: quoted text, numbers, operators. */
    OTHER,
    /** A complete line or block comment; only reported on request. */
    COMMENT
  }

  /** One token: what it is and where it sits in the statement. */
  public static final class Token {
    private final Kind kind;
    private final int start;
    private final int end;
    private final boolean dotted;

    /**
     * Creates a token.
     *
     * @param kind what it is
     * @param start offset of its first character
     * @param end offset just past its last character
     * @param dotted for a {@link Kind#WORD}: whether an identifier sits on the other side of an
     *     adjacent dot, so it is a part of a qualified name rather than a keyword
     */
    public Token(Kind kind, int start, int end, boolean dotted) {
      this.kind = kind;
      this.start = start;
      this.end = end;
      this.dotted = dotted;
    }

    /**
     * What the token is.
     *
     * @return the kind
     */
    public Kind kind() {
      return kind;
    }

    /**
     * Where the token starts.
     *
     * @return offset of its first character
     */
    public int start() {
      return start;
    }

    /**
     * Where the token ends.
     *
     * @return offset just past its last character
     */
    public int end() {
      return end;
    }

    /**
     * Whether a word is part of a qualified name.
     *
     * @return for a {@link Kind#WORD}, whether an identifier sits on the other side of an adjacent
     *     dot
     */
    public boolean dotted() {
      return dotted;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Token)) {
        return false;
      }
      Token other = (Token) o;
      return kind == other.kind
          && start == other.start
          && end == other.end
          && dotted == other.dotted;
    }

    @Override
    public int hashCode() {
      return Objects.hash(kind, start, end, dotted);
    }

    @Override
    public String toString() {
      return "Token[kind="
          + kind
          + ", start="
          + start
          + ", end="
          + end
          + ", dotted="
          + dotted
          + "]";
    }
  }

  private final String sql;
  private final boolean reportComments;
  private int pos;
  private @Nullable String unsupported;

  /**
   * Creates a lexer.
   *
   * @param sql the statement text
   * @param reportComments whether complete comments are returned as {@link Kind#COMMENT} tokens
   *     instead of being skipped
   */
  public SqlLexer(String sql, boolean reportComments) {
    this.sql = sql;
    this.reportComments = reportComments;
  }

  /**
   * Description of the unsupported construct that stopped the lexer, or null. Once set, {@link
   * #next} returns no more tokens; a token returned by the call that set it must be ignored.
   *
   * @return the description, phrased as a clause that starts with "statement"
   */
  public @Nullable String unsupported() {
    return unsupported;
  }

  /**
   * The text of a token, or its first characters.
   *
   * @param token a token of this lexer
   * @param max the most characters to return
   * @return the text
   */
  public String text(Token token, int max) {
    return sql.substring(token.start(), Math.min(token.end(), token.start() + max));
  }

  /**
   * The full text of a token.
   *
   * @param token a token of this lexer
   * @return the text
   */
  public String text(Token token) {
    return sql.substring(token.start(), token.end());
  }

  /**
   * Returns the next token.
   *
   * @return the token, or null at the end of the statement or once {@link #unsupported} is set
   */
  public @Nullable Token next() {
    int n = sql.length();
    while (pos < n && unsupported == null) {
      char c = sql.charAt(pos);
      if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
        pos++;
      } else if (c > 126 || c < 32) {
        // Dialects disagree on whether these are whitespace, part of a name or an error. The four
        // whitespace characters were handled above, so every control character left is refused.
        unsupported = "statement contains characters outside quotes that dialects read differently";
      } else if (c == '-' && peek(1) == '-' && endsComment(pos + 2)) {
        int start = pos;
        skipLine();
        if (reportComments) {
          return new Token(Kind.COMMENT, start, pos, false);
        }
      } else if (c == '-' && peek(1) == '-' && lineOpensQuote(pos + 2)) {
        unsupported = "statement has '--' not followed by whitespace before a quote";
      } else if (c == '/' && peek(1) == '*') {
        if (peek(2) == '!' || (peek(2) == 'M' && peek(3) == '!')) {
          unsupported = "statement contains an executable comment";
        } else {
          int start = pos;
          pos += 2;
          int end = sql.indexOf("*/", pos);
          if (end < 0) {
            unsupported = "statement has an unterminated comment";
          }
          pos = end < 0 ? n : end + 2;
          if (reportComments && end >= 0) {
            return new Token(Kind.COMMENT, start, pos, false);
          }
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
        return new Token(Kind.SEMICOLON, pos - 1, pos, false);
      } else if (c == '(') {
        pos++;
        return new Token(Kind.OPEN_PAREN, pos - 1, pos, false);
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
        return new Token(Kind.WORD, start, pos, before || after);
      } else {
        pos++;
        return other(pos - 1);
      }
    }
    return null;
  }

  private Token other(int start) {
    return new Token(Kind.OTHER, start, pos, false);
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
   * PostgreSQL always reads {@code --} as a comment, MySQL only before whitespace. When the dashes
   * are not a comment to MySQL, a quote or comment opener on the rest of the line would be hidden
   * from PostgreSQL but visible here, so such a line cannot be interpreted safely.
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
