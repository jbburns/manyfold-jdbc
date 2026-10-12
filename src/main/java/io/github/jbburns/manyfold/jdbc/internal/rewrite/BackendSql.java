package io.github.jbburns.manyfold.jdbc.internal.rewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The statement text for every backend, after the directives have been read and applied. */
public final class BackendSql {

  private final String original;
  private final List<String> sql;
  private final List<Boolean> substituted;

  /**
   * Creates the per-backend text; the lists are copied.
   *
   * @param original the statement exactly as the caller supplied it
   * @param sql the text to send to each backend, in URL order
   * @param substituted per backend, whether a substitution actually changed the text (the directive
   *     comments being stripped does not count)
   */
  public BackendSql(String original, List<String> sql, List<Boolean> substituted) {
    this.original = original;
    this.sql = List.copyOf(sql);
    this.substituted = List.copyOf(substituted);
  }

  /**
   * The statement as the caller supplied it.
   *
   * @return the original text
   */
  public String original() {
    return original;
  }

  /**
   * The text for every backend.
   *
   * @return the text to send to each backend, in URL order
   */
  public List<String> sql() {
    return sql;
  }

  /**
   * Which backends had a substitution applied.
   *
   * @return per backend, whether a substitution actually changed the text
   */
  public List<Boolean> substituted() {
    return substituted;
  }

  /**
   * The text for one backend.
   *
   * @param backend index in URL order
   * @return the text to send
   */
  public String sqlFor(int backend) {
    return sql.get(backend);
  }

  /**
   * What each backend was sent when a substitution changed it, for error messages.
   *
   * @return one entry per backend, null where no substitution was applied, or null when no backend
   *     had one
   */
  public @Nullable List<@Nullable String> sent() {
    List<@Nullable String> sent = new ArrayList<>(sql.size());
    boolean any = false;
    for (int i = 0; i < sql.size(); i++) {
      if (substituted.get(i)) {
        sent.add(sql.get(i));
        any = true;
      } else {
        sent.add(null);
      }
    }
    return any ? sent : null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BackendSql)) {
      return false;
    }
    BackendSql other = (BackendSql) o;
    return original.equals(other.original)
        && sql.equals(other.sql)
        && substituted.equals(other.substituted);
  }

  @Override
  public int hashCode() {
    return Objects.hash(original, sql, substituted);
  }

  @Override
  public String toString() {
    return "BackendSql[original="
        + original
        + ", sql="
        + sql
        + ", substituted="
        + substituted
        + "]";
  }
}
