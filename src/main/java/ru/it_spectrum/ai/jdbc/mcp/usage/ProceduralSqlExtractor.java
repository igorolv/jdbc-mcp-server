package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.antlr.ProceduralSqlLexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extracts SQL statements embedded in procedural database objects.
 *
 * <p>This is intentionally a small ANTLR-based pre-extractor. It does not replace JSqlParser:
 * extracted statements are still fed into the existing query analysis pipeline. The lexer gives us
 * token-aware handling of comments and quoted strings, avoiding the worst failure mode of simple
 * regex splitting.
 */
@Service
public class ProceduralSqlExtractor {

    public List<ExtractedSqlStatement> extract(String source) {
        if (source == null || source.isBlank()) return List.of();

        CharStream input = CharStreams.fromString(source);
        ProceduralSqlLexer lexer = new ProceduralSqlLexer(input);
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();

        List<Token> tokens = stream.getTokens();
        List<ExtractedSqlStatement> out = new ArrayList<>();
        int depth = 0;
        int ordinal = 0;
        int index = 0;
        while (index < tokens.size()) {
            Token token = tokens.get(index);
            if (token.getType() == Token.EOF) break;
            if (token.getChannel() != Token.DEFAULT_CHANNEL) {
                index++;
                continue;
            }
            if (token.getType() == ProceduralSqlLexer.LPAREN) {
                depth++;
                index++;
                continue;
            }
            if (token.getType() == ProceduralSqlLexer.RPAREN) {
                depth = Math.max(0, depth - 1);
                index++;
                continue;
            }
            if (depth == 0 && isStatementStart(token)) {
                int end = findStatementEnd(tokens, index);
                String sql = source.substring(token.getStartIndex(), statementStopIndex(tokens, end)).trim();
                if (!sql.isBlank()) {
                    ordinal++;
                    out.add(new ExtractedSqlStatement(ordinal, statementKind(token), sql));
                }
                index = Math.max(end + 1, index + 1);
                continue;
            }
            index++;
        }
        return List.copyOf(out);
    }

    private int findStatementEnd(List<Token> tokens, int start) {
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.getType() == Token.EOF) return i;
            if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (token.getType() == ProceduralSqlLexer.LPAREN) {
                depth++;
            } else if (token.getType() == ProceduralSqlLexer.RPAREN) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && token.getType() == ProceduralSqlLexer.SEMICOLON) {
                return i;
            }
        }
        return tokens.size() - 1;
    }

    private int statementStopIndex(List<Token> tokens, int end) {
        Token token = tokens.get(end);
        if (token.getType() == ProceduralSqlLexer.SEMICOLON || token.getType() == Token.EOF) {
            for (int i = end - 1; i >= 0; i--) {
                Token previous = tokens.get(i);
                if (previous.getChannel() == Token.DEFAULT_CHANNEL) {
                    return previous.getStopIndex() + 1;
                }
            }
        }
        return token.getStopIndex() + 1;
    }

    private static boolean isStatementStart(Token token) {
        return switch (token.getType()) {
            case ProceduralSqlLexer.SELECT,
                 ProceduralSqlLexer.WITH,
                 ProceduralSqlLexer.INSERT,
                 ProceduralSqlLexer.UPDATE,
                 ProceduralSqlLexer.DELETE,
                 ProceduralSqlLexer.MERGE -> true;
            default -> false;
        };
    }

    private static String statementKind(Token token) {
        return token.getText().toUpperCase(Locale.ROOT);
    }
}
