package nl.bytesoflife.gerber.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexer for Gerber files.
 * Tokenizes Gerber content into a stream of tokens.
 */
public class GerberLexer {

    // Multi-line pattern for extended commands (can span multiple lines)
    private static final Pattern EXTENDED_COMMAND = Pattern.compile("%([^%]+)%", Pattern.DOTALL);
    private static final Pattern COORD_PATTERN = Pattern.compile(
        "([XYIJ][+-]?\\d+)+(?:D0?([123]))?\\*"
    );
    private static final Pattern D_CODE_PATTERN = Pattern.compile("D(\\d+)\\*");
    private static final Pattern G_CODE_PATTERN = Pattern.compile("G(\\d{1,2})\\*?");
    private static final Pattern M_CODE_PATTERN = Pattern.compile("M(\\d{2})\\*");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("G04\\s*(.*)\\*");

    public List<Token> tokenize(String content) {
        List<Token> tokens = new ArrayList<>();

        // First pass: extract all extended commands from the entire content
        // and replace them with placeholders to track line numbers
        StringBuilder remaining = new StringBuilder();
        int lastEnd = 0;
        Matcher extMatcher = EXTENDED_COMMAND.matcher(content);

        while (extMatcher.find()) {
            // Add content before this extended command
            remaining.append(content, lastEnd, extMatcher.start());

            // Calculate line number at start of this extended command
            int lineNum = countLines(content, extMatcher.start());

            // Parse the extended command content (strip trailing * if present)
            String cmd = extMatcher.group(1);
            // Commands inside % are separated by *, and the whole block ends with *%
            // Remove trailing * before closing %
            if (cmd.endsWith("*")) {
                cmd = cmd.substring(0, cmd.length() - 1);
            }

            Token token = parseExtendedCommand(cmd, lineNum);
            if (token != null) {
                tokens.add(token);
            }

            lastEnd = extMatcher.end();
        }

        // Add remaining content after the last extended command
        remaining.append(content.substring(lastEnd));

        // Second pass: process remaining content line by line
        String[] lines = remaining.toString().split("\n");
        int lineNum = 0;

        for (String line : lines) {
            lineNum++;
            line = line.trim();
            if (line.isEmpty()) continue;

            // Parse simple commands
            tokenizeSimpleCommands(line, lineNum, tokens);
        }

        return tokens;
    }

    private int countLines(String content, int pos) {
        int lines = 1;
        for (int i = 0; i < pos && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private Token parseExtendedCommand(String cmd, int line) {
        // Normalize whitespace in command
        cmd = cmd.replaceAll("\\s+", " ").trim();

        if (cmd.startsWith("FS")) {
            return new Token(TokenType.FORMAT_SPEC, cmd, line);
        } else if (cmd.startsWith("MO")) {
            return new Token(TokenType.UNIT, cmd, line);
        } else if (cmd.startsWith("AD")) {
            if (cmd.length() > 3 && cmd.charAt(2) == 'D') {
                return new Token(TokenType.APERTURE_DEFINE, cmd, line);
            }
        } else if (cmd.startsWith("AM")) {
            return new Token(TokenType.APERTURE_MACRO, cmd, line);
        } else if (cmd.startsWith("LP")) {
            return new Token(TokenType.POLARITY, cmd, line);
        } else if (cmd.startsWith("LR")) {
            return new Token(TokenType.LOAD_ROTATION, cmd, line);
        } else if (cmd.startsWith("LS")) {
            return new Token(TokenType.LOAD_SCALING, cmd, line);
        } else if (cmd.startsWith("LM")) {
            return new Token(TokenType.LOAD_MIRRORING, cmd, line);
        } else if (cmd.startsWith("SR")) {
            return new Token(TokenType.STEP_REPEAT, cmd, line);
        } else if (cmd.startsWith("AB")) {
            return new Token(TokenType.BLOCK_APERTURE, cmd, line);
        } else if (cmd.startsWith("TF")) {
            return new Token(TokenType.FILE_ATTRIBUTE, cmd, line);
        } else if (cmd.startsWith("TA")) {
            return new Token(TokenType.APERTURE_ATTRIBUTE, cmd, line);
        } else if (cmd.startsWith("TO")) {
            return new Token(TokenType.OBJECT_ATTRIBUTE, cmd, line);
        } else if (cmd.startsWith("TD")) {
            return new Token(TokenType.DELETE_ATTRIBUTE, cmd, line);
        }
        return new Token(TokenType.UNKNOWN, cmd, line);
    }

    private void tokenizeSimpleCommands(String line, int lineNum, List<Token> tokens) {
        // Handle multiple commands on one line
        int pos = 0;
        while (pos < line.length()) {
            // Check for comment
            Matcher commentMatcher = COMMENT_PATTERN.matcher(line.substring(pos));
            if (commentMatcher.lookingAt()) {
                // Handle KiCad-style attribute comments: G04 #@! TF.xxx*
                String commentContent = commentMatcher.group(1);
                if (commentContent.startsWith("#@!")) {
                    String attrContent = commentContent.substring(3).trim();
                    if (attrContent.startsWith("TF.")) {
                        tokens.add(new Token(TokenType.FILE_ATTRIBUTE, attrContent, lineNum));
                    } else if (attrContent.startsWith("TA.")) {
                        tokens.add(new Token(TokenType.APERTURE_ATTRIBUTE, attrContent, lineNum));
                    } else if (attrContent.startsWith("TD")) {
                        tokens.add(new Token(TokenType.DELETE_ATTRIBUTE, attrContent, lineNum));
                    }
                } else {
                    tokens.add(new Token(TokenType.COMMENT, commentContent, lineNum));
                }
                pos += commentMatcher.end();
                continue;
            }

            // Check for G codes
            Matcher gMatcher = G_CODE_PATTERN.matcher(line.substring(pos));
            if (gMatcher.lookingAt()) {
                int gCode = Integer.parseInt(gMatcher.group(1));
                TokenType type = switch (gCode) {
                    case 1 -> TokenType.G01;
                    case 2 -> TokenType.G02;
                    case 3 -> TokenType.G03;
                    case 36 -> TokenType.G36;
                    case 37 -> TokenType.G37;
                    case 74 -> TokenType.G74;
                    case 75 -> TokenType.G75;
                    case 4 -> TokenType.COMMENT;
                    default -> TokenType.UNKNOWN;
                };
                tokens.add(new Token(type, "G" + gCode, lineNum));
                pos += gMatcher.end();
                continue;
            }

            // Check for M codes
            Matcher mMatcher = M_CODE_PATTERN.matcher(line.substring(pos));
            if (mMatcher.lookingAt()) {
                int mCode = Integer.parseInt(mMatcher.group(1));
                if (mCode == 2 || mCode == 0) {
                    tokens.add(new Token(TokenType.END_OF_FILE, "M" + mCode, lineNum));
                }
                pos += mMatcher.end();
                continue;
            }

            // Check for coordinates with optional D code
            Matcher coordMatcher = COORD_PATTERN.matcher(line.substring(pos));
            if (coordMatcher.lookingAt()) {
                String fullMatch = coordMatcher.group(0);
                // Remove trailing * and D code for coordinate token
                String coordPart = fullMatch.replaceAll("D0?[123]\\*$", "").replaceAll("\\*$", "");
                tokens.add(new Token(TokenType.COORDINATE, coordPart, lineNum));

                // Check for embedded D code
                String dCodeMatch = coordMatcher.group(2);
                if (dCodeMatch != null) {
                    int dCode = Integer.parseInt(dCodeMatch);
                    TokenType dType = switch (dCode) {
                        case 1 -> TokenType.D01;
                        case 2 -> TokenType.D02;
                        case 3 -> TokenType.D03;
                        default -> TokenType.UNKNOWN;
                    };
                    tokens.add(new Token(dType, "D0" + dCode, lineNum));
                }
                pos += coordMatcher.end();
                continue;
            }

            // Check for standalone D codes (D01, D02, D03, or aperture select Dnn)
            Matcher dMatcher = D_CODE_PATTERN.matcher(line.substring(pos));
            if (dMatcher.lookingAt()) {
                int dCode = Integer.parseInt(dMatcher.group(1));
                TokenType type = switch (dCode) {
                    case 1 -> TokenType.D01;
                    case 2 -> TokenType.D02;
                    case 3 -> TokenType.D03;
                    default -> TokenType.APERTURE_SELECT;
                };
                tokens.add(new Token(type, "D" + dCode, lineNum));
                pos += dMatcher.end();
                continue;
            }

            // Skip unknown character
            pos++;
        }
    }
}
