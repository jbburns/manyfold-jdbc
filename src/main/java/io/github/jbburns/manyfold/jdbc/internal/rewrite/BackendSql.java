package io.github.jbburns.manyfold.jdbc.internal.rewrite;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The statement text for every backend, after the directives have been read and applied.
 *
 * @param original the statement exactly as the caller supplied it
 * @param sql the text to send to each backend, in URL order
 * @param substituted per backend, whether a substitution actually changed the text (the directive
 *     comments being stripped does not count)
 */
public record BackendSql(String original, List<String> sql, List<Boolean> substituted) {

  /** Copies the lists. */
  public BackendSql {
    sql = List.copyOf(sql);
    substituted = List.copyOf(substituted);
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
}
