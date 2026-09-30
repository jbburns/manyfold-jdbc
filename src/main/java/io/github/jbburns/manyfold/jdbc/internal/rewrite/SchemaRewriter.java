package io.github.jbburns.manyfold.jdbc.internal.rewrite;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import io.github.jbburns.manyfold.jdbc.internal.lex.SqlLexer;
import io.github.jbburns.manyfold.jdbc.internal.lex.SqlLexer.Kind;
import io.github.jbburns.manyfold.jdbc.internal.lex.SqlLexer.Token;
import java.util.HashMap;
import java.util.Map;

/**
 * Applies one backend's identifier substitutions to a statement.
 *
 * <p>Only an identifier in qualifier position is a candidate: one immediately followed by a dot,
 * with no whitespace between, as in {@code zone1_prod.orders}, {@code zone1_prod.dbo.orders} and
 * Sybase {@code zone1_prod..orders}. Columns, aliases, literals and everything inside strings,
 * comments, brackets and backticks are never touched; they are skipped with the same lexical rules
 * as {@link io.github.jbburns.manyfold.jdbc.internal.guard.ReadOnlyGuard}.
 *
 * <p>An unquoted source identifier matches an unquoted identifier ignoring case. A double-quoted
 * source identifier matches only a double-quoted identifier with exactly that text. A replacement
 * is written as given, so a quoted replacement keeps its quotes. An empty replacement deletes the
 * qualifier: the identifier and every dot that immediately follows it.
 *
 * <p>If the statement contains quoting the lexer cannot interpret safely, it is refused instead of
 * being rewritten on a guess.
 */
public final class SchemaRewriter {

  private final Map<String, String> replacements;

  /**
   * Creates a rewriter.
   *
   * @param substitutions source identifier to replacement, each written as in a directive: a plain
   *     or double-quoted identifier, and a replacement that is one of those or empty
   * @throws IllegalArgumentException if an identifier is invalid or two sources are the same
   *     identifier
   */
  public SchemaRewriter(Map<String, String> substitutions) {
    Map<String, String> map = new HashMap<>();
    for (Map.Entry<String, String> entry : substitutions.entrySet()) {
      String to = entry.getValue();
      if (!to.isEmpty() && !Identifiers.isValid(to)) {
        throw new IllegalArgumentException("not an identifier: " + to);
      }
      if (map.put(Identifiers.key(entry.getKey()), to) != null) {
        throw new IllegalArgumentException("mapped twice: " + entry.getKey());
      }
    }
    this.replacements = Map.copyOf(map);
  }

  /**
   * Rewrites a statement.
   *
   * @param sql the statement text
   * @return the rewritten text, or the same instance when nothing matched
   * @throws ManyfoldException with SQL state {@value ManyfoldException#STATE_SYNTAX_ERROR} if the
   *     statement has quoting that cannot be interpreted safely
   */
  public String apply(String sql) throws ManyfoldException {
    if (replacements.isEmpty()) {
      return sql;
    }
    int n = sql.length();
    StringBuilder out = null;
    int copied = 0;
    SqlLexer lexer = new SqlLexer(sql, false);
    for (Token token = lexer.next(); token != null; token = lexer.next()) {
      if (lexer.unsupported() != null) {
        break;
      }
      int start = token.start();
      int end = token.end();
      String key;
      if (token.kind() == Kind.WORD) {
        // The lexer stops words at $ and #, which a plain identifier may contain. Read the whole
        // run, and only consider it from its first character.
        if (start > 0 && Identifiers.isIdentChar(sql.charAt(start - 1))) {
          continue;
        }
        while (end < n && Identifiers.isIdentChar(sql.charAt(end))) {
          end++;
        }
        key = Identifiers.plainKey(sql.substring(start, end));
      } else if (token.kind() == Kind.OTHER && sql.charAt(start) == '"') {
        key = Identifiers.quotedKey(sql.substring(start, end));
      } else {
        continue;
      }
      if (end >= n || sql.charAt(end) != '.') {
        continue;
      }
      String replacement = replacements.get(key);
      if (replacement == null) {
        continue;
      }
      if (out == null) {
        out = new StringBuilder(n + 16);
      }
      out.append(sql, copied, start).append(replacement);
      copied = end;
      if (replacement.isEmpty()) {
        while (copied < n && sql.charAt(copied) == '.') {
          copied++;
        }
      }
    }
    String unsupported = lexer.unsupported();
    if (unsupported != null) {
      throw new ManyfoldException(
          "Cannot apply the schema substitutions of the manyfold directive: "
              + unsupported
              + ", so identifiers cannot be told from text",
          ManyfoldException.STATE_SYNTAX_ERROR);
    }
    return out == null ? sql : out.append(sql, copied, n).toString();
  }
}
