package com.deltaproto.deltagerber.lexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexer for Gerber files.
 * Tokenizes Gerber content into a stream of tokens.
 */
public class GerberLexer {

    private static final Logger log = LoggerFactory.getLogger(GerberLexer.class);

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
        long startTime = System.currentTimeMillis();
        log.trace("Starting tokenization, content length: {} chars", content.length());

        List<Token> tokens = new ArrayList<>();

        // First pass: extract all extended commands from the entire content
        // Track their position for proper ordering, and build a mapping for position lookup
        List<PositionedToken> extendedTokens = new ArrayList<>();
        List<int[]> extRanges = new ArrayList<>(); // [start, length] of each extended command
        StringBuilder remaining = new StringBuilder();
        int lastEnd = 0;
        Matcher extMatcher = EXTENDED_COMMAND.matcher(content);

        while (extMatcher.find()) {
            // Add content before this extended command
            remaining.append(content, lastEnd, extMatcher.start());

            // Track this extended command's range for position mapping
            extRanges.add(new int[]{extMatcher.start(), extMatcher.end() - extMatcher.start()});

            // Calculate line number at start of this extended command
            int lineNum = countLines(content, extMatcher.start());
            int position = extMatcher.start();

            // Parse the extended command content (strip trailing * if present)
            String cmd = extMatcher.group(1);
            // Commands inside % are separated by *, and the whole block ends with *%
            // Remove trailing * before closing %
            if (cmd.endsWith("*")) {
                cmd = cmd.substring(0, cmd.length() - 1);
            }

            Token token = parseExtendedCommand(cmd, lineNum);
            if (token != null) {
                extendedTokens.add(new PositionedToken(token, position));
            }

            lastEnd = extMatcher.end();
        }

        // Add remaining content after the last extended command
        remaining.append(content.substring(lastEnd));

        // Pre-compute cumulative offset array for O(1) position lookup
        int[] cumulativeOffset = new int[extRanges.size() + 1];
        cumulativeOffset[0] = 0;
        for (int i = 0; i < extRanges.size(); i++) {
            cumulativeOffset[i + 1] = cumulativeOffset[i] + extRanges.get(i)[1];
        }

        // Second pass: process remaining content line by line
        // Track positions for simple commands too
        List<PositionedToken> simpleTokens = new ArrayList<>();
        String[] lines = remaining.toString().split("\n");
        int lineNum = 0;
        int charPos = 0;

        for (String line : lines) {
            lineNum++;
            String trimmedLine = line.trim();
            if (!trimmedLine.isEmpty()) {
                // Find position of this line in original content using pre-computed offsets
                int origPos = findOriginalPositionFast(charPos, extRanges, cumulativeOffset);
                tokenizeSimpleCommandsWithPosition(trimmedLine, lineNum, origPos, simpleTokens);
            }
            charPos += line.length() + 1; // +1 for newline
        }

        // Merge extended and simple tokens, sorted by position
        log.trace("Merging {} extended + {} simple tokens", extendedTokens.size(), simpleTokens.size());
        List<PositionedToken> allTokens = new ArrayList<>(extendedTokens);
        allTokens.addAll(simpleTokens);
        allTokens.sort((a, b) -> Integer.compare(a.position, b.position));

        for (PositionedToken pt : allTokens) {
            tokens.add(pt.token);
        }

        log.trace("Tokenization complete in {}ms: {} tokens", System.currentTimeMillis() - startTime, tokens.size());
        return tokens;
    }

    private static class PositionedToken {
        final Token token;
        final int position;

        PositionedToken(Token token, int position) {
            this.token = token;
            this.position = position;
        }
    }

    private int findOriginalPositionFast(int remainingPos, List<int[]> extRanges, int[] cumulativeOffset) {
        // Binary search to find how many extended commands are before this position
        int count = 0;
        int adjustedPos = remainingPos;
        for (int i = 0; i < extRanges.size(); i++) {
            int[] range = extRanges.get(i);
            int origStart = range[0];
            // The original start minus all previous removals gives us the position in remaining string
            int remainingStart = origStart - cumulativeOffset[i];
            if (remainingStart <= remainingPos) {
                count = i + 1;
            } else {
                break;
            }
        }
        return remainingPos + cumulativeOffset[count];
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

    private void tokenizeSimpleCommandsWithPosition(String line, int lineNum, int basePos, List<PositionedToken> tokens) {
        // Handle multiple commands on one line
        // Optimized: use character-based parsing for common patterns to avoid regex overhead
        int pos = 0;
        int len = line.length();

        while (pos < len) {
            char c = line.charAt(pos);

            // Fast path: coordinates starting with X, Y, I, J (most common case ~95%+ of lines)
            if (c == 'X' || c == 'Y' || c == 'I' || c == 'J') {
                int coordEnd = parseCoordinateFast(line, pos);
                if (coordEnd > pos) {
                    // Extract coordinate part (without D code and *)
                    int dPos = line.indexOf('D', pos);
                    int starPos = line.indexOf('*', pos);
                    int coordPartEnd = (dPos > pos && dPos < coordEnd) ? dPos :
                                       (starPos > pos && starPos < coordEnd) ? starPos : coordEnd;
                    String coordPart = line.substring(pos, coordPartEnd);
                    tokens.add(new PositionedToken(new Token(TokenType.COORDINATE, coordPart, lineNum), basePos + pos));

                    // Check for embedded D code (D01, D02, D03)
                    if (dPos > pos && dPos < coordEnd) {
                        int dCode = parseDCodeFast(line, dPos);
                        if (dCode >= 1 && dCode <= 3) {
                            TokenType dType = switch (dCode) {
                                case 1 -> TokenType.D01;
                                case 2 -> TokenType.D02;
                                case 3 -> TokenType.D03;
                                default -> TokenType.UNKNOWN;
                            };
                            tokens.add(new PositionedToken(new Token(dType, "D0" + dCode, lineNum), basePos + dPos));
                        }
                    }
                    pos = coordEnd;
                    continue;
                }
            }

            // Fast path: G codes
            if (c == 'G') {
                int gEnd = parseGCodeFast(line, pos);
                if (gEnd > pos) {
                    int gCode = parseNumberAt(line, pos + 1, gEnd);
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
                    // Handle G04 comments specially
                    if (gCode == 4) {
                        int starPos = line.indexOf('*', pos);
                        if (starPos > gEnd) {
                            String commentContent = line.substring(gEnd, starPos).trim();
                            if (commentContent.startsWith("#@!")) {
                                String attrContent = commentContent.substring(3).trim();
                                if (attrContent.startsWith("TF.")) {
                                    tokens.add(new PositionedToken(new Token(TokenType.FILE_ATTRIBUTE, attrContent, lineNum), basePos + pos));
                                } else if (attrContent.startsWith("TA.")) {
                                    tokens.add(new PositionedToken(new Token(TokenType.APERTURE_ATTRIBUTE, attrContent, lineNum), basePos + pos));
                                } else if (attrContent.startsWith("TD")) {
                                    tokens.add(new PositionedToken(new Token(TokenType.DELETE_ATTRIBUTE, attrContent, lineNum), basePos + pos));
                                }
                            } else {
                                tokens.add(new PositionedToken(new Token(TokenType.COMMENT, commentContent, lineNum), basePos + pos));
                            }
                            pos = starPos + 1;
                            continue;
                        }
                    }
                    tokens.add(new PositionedToken(new Token(type, "G" + gCode, lineNum), basePos + pos));
                    pos = gEnd;
                    continue;
                }
            }

            // Fast path: D codes (standalone)
            if (c == 'D') {
                int dEnd = parseDCodeEnd(line, pos);
                if (dEnd > pos) {
                    int dCode = parseNumberAt(line, pos + 1, dEnd - 1); // -1 to skip *
                    TokenType type = switch (dCode) {
                        case 1 -> TokenType.D01;
                        case 2 -> TokenType.D02;
                        case 3 -> TokenType.D03;
                        default -> TokenType.APERTURE_SELECT;
                    };
                    tokens.add(new PositionedToken(new Token(type, "D" + dCode, lineNum), basePos + pos));
                    pos = dEnd;
                    continue;
                }
            }

            // Fast path: M codes
            if (c == 'M') {
                int mEnd = parseMCodeEnd(line, pos);
                if (mEnd > pos) {
                    int mCode = parseNumberAt(line, pos + 1, mEnd - 1);
                    if (mCode == 0 || mCode == 2) {
                        tokens.add(new PositionedToken(new Token(TokenType.END_OF_FILE, "M" + mCode, lineNum), basePos + pos));
                    }
                    pos = mEnd;
                    continue;
                }
            }

            // Skip * and whitespace
            if (c == '*' || Character.isWhitespace(c)) {
                pos++;
                continue;
            }

            // Skip unknown character
            pos++;
        }
    }

    // Fast coordinate parsing: returns end position (after trailing *)
    private int parseCoordinateFast(String line, int start) {
        int pos = start;
        int len = line.length();
        boolean hasCoord = false;

        while (pos < len) {
            char c = line.charAt(pos);
            if (c == 'X' || c == 'Y' || c == 'I' || c == 'J') {
                pos++;
                // Skip optional sign
                if (pos < len && (line.charAt(pos) == '+' || line.charAt(pos) == '-')) pos++;
                // Must have at least one digit
                if (pos < len && Character.isDigit(line.charAt(pos))) {
                    while (pos < len && Character.isDigit(line.charAt(pos))) pos++;
                    hasCoord = true;
                } else {
                    return start; // Invalid
                }
            } else if (c == 'D') {
                // D code - check if D01, D02, D03
                int dStart = pos;
                pos++;
                if (pos < len && line.charAt(pos) == '0') pos++; // Optional leading 0
                if (pos < len && Character.isDigit(line.charAt(pos))) {
                    while (pos < len && Character.isDigit(line.charAt(pos))) pos++;
                }
                if (pos < len && line.charAt(pos) == '*') {
                    return pos + 1;
                }
                pos = dStart + 1; // Backtrack, might be aperture select
                break;
            } else if (c == '*') {
                return hasCoord ? pos + 1 : start;
            } else {
                break;
            }
        }
        return hasCoord ? pos : start;
    }

    // Parse D code value at position (e.g., "D01" returns 1)
    private int parseDCodeFast(String line, int dPos) {
        int pos = dPos + 1; // Skip 'D'
        int len = line.length();
        if (pos < len && line.charAt(pos) == '0') pos++; // Skip optional leading 0
        int val = 0;
        while (pos < len && Character.isDigit(line.charAt(pos))) {
            val = val * 10 + (line.charAt(pos) - '0');
            pos++;
        }
        return val;
    }

    // Parse G code end position (G followed by digits, optionally *)
    private int parseGCodeFast(String line, int start) {
        int pos = start + 1; // Skip 'G'
        int len = line.length();
        while (pos < len && Character.isDigit(line.charAt(pos))) pos++;
        if (pos == start + 1) return start; // No digits
        if (pos < len && line.charAt(pos) == '*') pos++;
        return pos;
    }

    // Parse number from start to end position
    private int parseNumberAt(String line, int start, int end) {
        int val = 0;
        for (int i = start; i < end && i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isDigit(c)) {
                val = val * 10 + (c - '0');
            }
        }
        return val;
    }

    // Parse D code end (D followed by digits and *)
    private int parseDCodeEnd(String line, int start) {
        int pos = start + 1; // Skip 'D'
        int len = line.length();
        while (pos < len && Character.isDigit(line.charAt(pos))) pos++;
        if (pos == start + 1) return start; // No digits
        if (pos < len && line.charAt(pos) == '*') return pos + 1;
        return start; // Must end with *
    }

    // Parse M code end (M followed by digits and *)
    private int parseMCodeEnd(String line, int start) {
        int pos = start + 1; // Skip 'M'
        int len = line.length();
        while (pos < len && Character.isDigit(line.charAt(pos))) pos++;
        if (pos == start + 1) return start; // No digits
        if (pos < len && line.charAt(pos) == '*') return pos + 1;
        return start; // Must end with *
    }
}
