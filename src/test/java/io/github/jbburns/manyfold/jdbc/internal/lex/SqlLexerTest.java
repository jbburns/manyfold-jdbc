package io.github.jbburns.manyfold.jdbc.internal.lex;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jbburns.manyfold.jdbc.internal.lex.SqlLexer.Kind;
import io.github.jbburns.manyfold.jdbc.internal.lex.SqlLexer.Token;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlLexerTest {

  private static List<String> tokens(String sql, boolean comments) {
    SqlLexer lexer = new SqlLexer(sql, comments);
    List<String> out = new ArrayList<>();
    for (Token t = lexer.next(); t != null; t = lexer.next()) {
      if (lexer.unsupported() != null) {
        break;
      }
      out.add(t.kind() + ":" + lexer.text(t));
    }
    return out;
  }

  @Test
  void commentsAreSkippedUnlessRequested() {
    String sql = "-- a\nselect /* b */ 1";

    assertThat(tokens(sql, false)).containsExactly("WORD:select", "OTHER:1");
    assertThat(tokens(sql, true))
        .containsExactly("COMMENT:-- a", "WORD:select", "COMMENT:/* b */", "OTHER:1");
  }

  @Test
  void quotesBracketsAndDottedNamesAreSingleTokens() {
    SqlLexer lexer = new SqlLexer("'a b' \"c d\" [e f] `g h` x.y", false);

    List<String> seen = new ArrayList<>();
    for (Token t = lexer.next(); t != null; t = lexer.next()) {
      seen.add(t.kind() + ":" + lexer.text(t) + ":" + t.dotted());
    }

    assertThat(seen)
        .containsExactly(
            "OTHER:'a b':false",
            "OTHER:\"c d\":false",
            "OTHER:[e f]:false",
            "OTHER:`g h`:false",
            "WORD:x:true",
            "OTHER:.:false",
            "WORD:y:true");
  }

  @Test
  void anUnterminatedCommentStopsTheLexerAndIsNotReported() {
    SqlLexer lexer = new SqlLexer("/* open", true);

    assertThat(lexer.next()).isNull();
    assertThat(lexer.unsupported()).contains("unterminated comment");
  }

  @Test
  void theTokenOffsetsCoverTheText() {
    SqlLexer lexer = new SqlLexer("  ab;", false);

    Token word = lexer.next();
    Token semicolon = lexer.next();

    assertThat(word).isEqualTo(new Token(Kind.WORD, 2, 4, false));
    assertThat(semicolon).isEqualTo(new Token(Kind.SEMICOLON, 4, 5, false));
    assertThat(lexer.text(word, 1)).isEqualTo("a");
  }
}
