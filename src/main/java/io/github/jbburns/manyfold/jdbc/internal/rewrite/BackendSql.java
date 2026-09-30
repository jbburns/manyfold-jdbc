package io.github.jbburns.manyfold.jdbc.internal.rewrite;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The statement text for every backend, after the directives have been read and applied.
 *
 * @param original the statement exactly as the caller supplied it
 * @param sql the text to send to each backend, in URL order
 */
public record BackendSql(String original, List<String> sql) {

  /** Copies the list. */
  public BackendSql {
    sql = List.copyOf(sql);
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
   * What each backend was sent when it is not what the caller supplied, for error messages.
   *
   * @return one entry per backend, null where the text is unchanged, or null when no backend
   *     received changed text
   */
  public @Nullable List<@Nullable String> sent() {
    List<@Nullable String> sent = new ArrayList<>(sql.size());
    boolean any = false;
    for (String text : sql) {
      if (text.equals(original)) {
        sent.add(null);
      } else {
        sent.add(text);
        any = true;
      }
    }
    return any ? sent : null;
  }
}
