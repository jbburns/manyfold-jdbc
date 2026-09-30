package io.github.jbburns.manyfold.jdbc.internal.rewrite;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import io.github.jbburns.manyfold.jdbc.internal.lex.SqlLexer;
import io.github.jbburns.manyfold.jdbc.internal.lex.SqlLexer.Kind;
import io.github.jbburns.manyfold.jdbc.internal.lex.SqlLexer.Token;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code manyfold} directives from the comments before the first token of a statement.
 *
 * <pre>{@code
 * -- manyfold dev2: zone1_prod=zone1_dev2, zone2_prod=zone2_dev2
 * select * from zone1_prod.orders
 * }</pre>
 *
 * <p>The block comment form is accepted too.
 *
 * <p>A comment is a directive when its trimmed text starts with the word {@code manyfold}, in any
 * case. The form is {@code manyfold <backend>: <from>=<to>[, <from>=<to>...]}, where the backend is
 * the logical name of a backend of the connection (compared ignoring case), {@code <from>} is a
 * plain or double-quoted identifier and {@code <to>} is a plain or double-quoted identifier or
 * empty. Anything else is refused with SQL state {@value ManyfoldException#STATE_SYNTAX_ERROR} and
 * a message that quotes the directive; a comment that starts with {@code manyfold} is never
 * ignored. Only comments before the first token count. Directive comments are removed from the
 * text, other comments stay.
 */
public final class Directives {

  private static final String KEYWORD = "manyfold";

  private Directives() {}

  /**
   * The outcome of reading directives.
   *
   * @param sql the statement with the directive comments removed
   * @param substitutions per backend in URL order: source identifier to replacement, each as
   *     written in the directive, the replacement being empty for a deletion
   */
  public record Parsed(String sql, List<Map<String, String>> substitutions) {}

  /**
   * Reads the directives of a statement.
   *
   * @param sql the statement text
   * @param names logical backend names in URL order
   * @return the stripped text and each backend's substitutions
   * @throws ManyfoldException with SQL state {@value ManyfoldException#STATE_SYNTAX_ERROR} if a
   *     directive is refused
   */
  public static Parsed parse(String sql, List<String> names) throws ManyfoldException {
    List<Map<String, String>> maps = new ArrayList<>(names.size());
    List<Set<String>> seen = new ArrayList<>(names.size());
    for (int i = 0; i < names.size(); i++) {
      maps.add(new LinkedHashMap<>());
      seen.add(new HashSet<>());
    }
    StringBuilder out = null;
    int copied = 0;
    int resume = 0;
    SqlLexer lexer = new SqlLexer(sql, true);
    Token token = lexer.next();
    for (; token != null && token.kind() == Kind.COMMENT; token = lexer.next()) {
      resume = token.end();
      String text = commentText(sql, token);
      if (!isDirective(text)) {
        continue;
      }
      apply(text, names, maps, seen);
      int end = token.end();
      if (sql.charAt(token.start()) == '-') {
        // A line comment takes its line break with it.
        if (end < sql.length() && sql.charAt(end) == '\r') {
          end++;
        }
        if (end < sql.length() && sql.charAt(end) == '\n') {
          end++;
        }
      }
      if (out == null) {
        out = new StringBuilder(sql.length());
      }
      out.append(sql, copied, token.start());
      copied = end;
    }
    if (token != null) {
      refuseHiddenDirective(sql, token.start());
    } else {
      // The lexer stopped inside something it could not read; look at where the last comment ended.
      while (resume < sql.length() && Character.isWhitespace(sql.charAt(resume))) {
        resume++;
      }
      refuseHiddenDirective(sql, resume);
    }
    List<Map<String, String>> result = new ArrayList<>(maps.size());
    for (Map<String, String> map : maps) {
      result.add(Map.copyOf(map));
    }
    String stripped = out == null ? sql : out.append(sql, copied, sql.length()).toString();
    return new Parsed(stripped, List.copyOf(result));
  }

  /**
   * Reads the directives of a statement and produces the text for every backend.
   *
   * @param sql the statement text
   * @param names logical backend names in URL order
   * @return the text to send to each backend
   * @throws ManyfoldException with SQL state {@value ManyfoldException#STATE_SYNTAX_ERROR} if a
   *     directive is refused or a rewrite is not possible
   */
  public static BackendSql plan(String sql, List<String> names) throws ManyfoldException {
    Parsed parsed = parse(sql, names);
    List<String> texts = new ArrayList<>(names.size());
    List<Boolean> substituted = new ArrayList<>(names.size());
    for (Map<String, String> map : parsed.substitutions()) {
      String text = new SchemaRewriter(map).apply(parsed.sql());
      texts.add(text);
      substituted.add(!map.isEmpty() && !text.equals(parsed.sql()));
    }
    return new BackendSql(sql, texts, substituted);
  }

  /** The trimmed text inside a comment. */
  private static String commentText(String sql, Token comment) {
    boolean line = sql.charAt(comment.start()) == '-';
    String body =
        line
            ? sql.substring(comment.start() + 2, comment.end())
            : sql.substring(comment.start() + 2, comment.end() - 2);
    return body.strip();
  }

  private static boolean isDirective(String text) {
    return text.regionMatches(true, 0, KEYWORD, 0, KEYWORD.length())
        && (text.length() == KEYWORD.length()
            || !Identifiers.isIdentChar(text.charAt(KEYWORD.length())));
  }

  /**
   * The lexer stops at a first token that is not a comment. Two things that look like directives do
   * not come out of it as comments: {@code --manyfold ...}, which MySQL does not read as a comment,
   * and a block comment that is never closed. Both are refused rather than sent on.
   */
  private static void refuseHiddenDirective(String sql, int at) throws ManyfoldException {
    if (sql.startsWith("--", at)) {
      int eol = at + 2;
      while (eol < sql.length() && sql.charAt(eol) != '\n' && sql.charAt(eol) != '\r') {
        eol++;
      }
      String text = sql.substring(at + 2, eol).strip();
      if (isDirective(text)) {
        throw refused(text, "'--' must be followed by a space for a directive");
      }
    } else if (sql.startsWith("/*", at)) {
      String text = sql.substring(at + 2).strip();
      if (isDirective(text)) {
        throw refused(text, "the comment is not closed");
      }
    }
  }

  private static void apply(
      String text, List<String> names, List<Map<String, String>> maps, List<Set<String>> seen)
      throws ManyfoldException {
    String rest = text.substring(KEYWORD.length()).strip();
    int backend = -1;
    int matched = -1;
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      if (name.length() > matched
          && rest.regionMatches(true, 0, name, 0, name.length())
          && rest.substring(name.length()).stripLeading().startsWith(":")) {
        backend = i;
        matched = name.length();
      }
    }
    if (backend < 0) {
      int colon = rest.indexOf(':');
      if (colon <= 0) {
        throw refused(text, "expected 'manyfold <backend>: <from>=<to>[, <from>=<to>...]'");
      }
      throw refused(
          text,
          "unknown backend '"
              + rest.substring(0, colon).strip()
              + "'; this connection has "
              + String.join(", ", names));
    }
    String list = rest.substring(rest.indexOf(':', matched) + 1);
    for (String piece : split(text, list)) {
      String pair = piece.strip();
      if (pair.isEmpty()) {
        throw refused(text, "empty substitution; expected <from>=<to>");
      }
      int eq = indexOutsideQuotes(pair, '=');
      if (eq < 0) {
        throw refused(text, "'" + pair + "' is not <from>=<to>");
      }
      String from = pair.substring(0, eq).strip();
      String to = pair.substring(eq + 1).strip();
      if (!Identifiers.isValid(from)) {
        throw refused(text, "'" + from + "' is not a valid identifier to replace");
      }
      if (!to.isEmpty() && !Identifiers.isValid(to)) {
        throw refused(text, "'" + to + "' is not a valid replacement identifier");
      }
      if (!seen.get(backend).add(Identifiers.key(from))) {
        throw refused(text, "'" + from + "' is mapped more than once for " + names.get(backend));
      }
      maps.get(backend).put(from, to);
    }
  }

  /** Splits on commas that are not inside a double-quoted identifier. */
  private static List<String> split(String directive, String list) throws ManyfoldException {
    List<String> pieces = new ArrayList<>();
    boolean quoted = false;
    int from = 0;
    for (int i = 0; i < list.length(); i++) {
      char c = list.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (c == ',' && !quoted) {
        pieces.add(list.substring(from, i));
        from = i + 1;
      }
    }
    if (quoted) {
      throw refused(directive, "a double-quoted identifier is not closed");
    }
    pieces.add(list.substring(from));
    return pieces;
  }

  private static int indexOutsideQuotes(String text, char wanted) {
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (c == wanted && !quoted) {
        return i;
      }
    }
    return -1;
  }

  private static ManyfoldException refused(String directive, String reason) {
    return new ManyfoldException(
        "Invalid manyfold directive '" + directive + "': " + reason,
        ManyfoldException.STATE_SYNTAX_ERROR);
  }
}
