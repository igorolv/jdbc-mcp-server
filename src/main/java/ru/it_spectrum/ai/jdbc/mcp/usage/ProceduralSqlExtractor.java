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

    public List<ExtractedRoutineSqlStatement> extractOraclePackageBody(String source) {
        if (source == null || source.isBlank()) return List.of();

        CharStream input = CharStreams.fromString(source);
        ProceduralSqlLexer lexer = new ProceduralSqlLexer(input);
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();

        List<Token> tokens = stream.getTokens();
        List<ExtractedRoutineSqlStatement> out = new ArrayList<>();
        int scanStart = packageBodyContentStart(tokens);
        if (scanStart < 0) {
            return List.of();
        }

        int index = scanStart;
        while (index < tokens.size()) {
            Token token = tokens.get(index);
            if (token.getType() == Token.EOF) break;
            if (token.getChannel() != Token.DEFAULT_CHANNEL) {
                index++;
                continue;
            }
            String keyword = upper(token);
            if (!"FUNCTION".equals(keyword) && !"PROCEDURE".equals(keyword)) {
                index++;
                continue;
            }
            int nameIndex = nextDefault(tokens, index + 1);
            if (nameIndex < 0 || tokens.get(nameIndex).getType() == Token.EOF) {
                index++;
                continue;
            }
            String routineName = tokens.get(nameIndex).getText();
            int end = findRoutineBodyEnd(tokens, index);
            if (end < 0) {
                index++;
                continue;
            }
            String body = source.substring(token.getStartIndex(), statementStopIndex(tokens, end)).trim();
            List<ExtractedSqlStatement> statements = extract(body);
            for (ExtractedSqlStatement statement : statements) {
                out.add(new ExtractedRoutineSqlStatement(
                        routineName,
                        keyword,
                        statement.ordinal(),
                        statement.kind(),
                        statement.sql()));
            }
            index = Math.max(end + 1, index + 1);
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

    private int packageBodyContentStart(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.getType() == Token.EOF) break;
            if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (!"PACKAGE".equals(upper(token))) continue;

            int body = nextDefault(tokens, i + 1);
            if (body < 0 || !"BODY".equals(upper(tokens.get(body)))) continue;
            for (int j = body + 1; j < tokens.size(); j++) {
                Token candidate = tokens.get(j);
                if (candidate.getType() == Token.EOF) break;
                if (candidate.getChannel() != Token.DEFAULT_CHANNEL) continue;
                String text = upper(candidate);
                if ("AS".equals(text) || "IS".equals(text)) {
                    int next = nextDefault(tokens, j + 1);
                    return next < 0 ? j + 1 : next;
                }
            }
        }
        return -1;
    }

    private int findRoutineBodyEnd(List<Token> tokens, int start) {
        int begin = findRoutineBegin(tokens, start);
        if (begin < 0) return -1;
        int depth = 1;
        for (int i = begin + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.getType() == Token.EOF) return -1;
            if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
            String text = upper(token);
            if ("BEGIN".equals(text)) {
                depth++;
                continue;
            }
            if (!"END".equals(text)) continue;
            int next = nextDefault(tokens, i + 1);
            String endQualifier = next < 0 ? "" : upper(tokens.get(next));
            if ("IF".equals(endQualifier) || "LOOP".equals(endQualifier) || "CASE".equals(endQualifier)) {
                continue;
            }
            depth--;
            if (depth == 0) {
                int semicolon = findSemicolonAfterEnd(tokens, i);
                return semicolon < 0 ? i : semicolon;
            }
        }
        return -1;
    }

    private int findRoutineBegin(List<Token> tokens, int start) {
        int parenDepth = 0;
        boolean sawBodyMarker = false;
        for (int i = start + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.getType() == Token.EOF) return -1;
            if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (token.getType() == ProceduralSqlLexer.LPAREN) {
                parenDepth++;
                continue;
            }
            if (token.getType() == ProceduralSqlLexer.RPAREN) {
                parenDepth = Math.max(0, parenDepth - 1);
                continue;
            }
            if (parenDepth != 0) continue;
            String text = upper(token);
            if ("IS".equals(text) || "AS".equals(text)) {
                sawBodyMarker = true;
                continue;
            }
            if (token.getType() == ProceduralSqlLexer.SEMICOLON && !sawBodyMarker) {
                return -1;
            }
            if ("BEGIN".equals(text) && sawBodyMarker) {
                return i;
            }
        }
        return -1;
    }

    private int findSemicolonAfterEnd(List<Token> tokens, int endIndex) {
        for (int i = endIndex + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.getType() == Token.EOF) return -1;
            if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (token.getType() == ProceduralSqlLexer.SEMICOLON) return i;
        }
        return -1;
    }

    private int nextDefault(List<Token> tokens, int from) {
        for (int i = from; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.getChannel() == Token.DEFAULT_CHANNEL) return i;
        }
        return -1;
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

    private static String upper(Token token) {
        return token == null ? "" : token.getText().toUpperCase(Locale.ROOT);
    }
}
