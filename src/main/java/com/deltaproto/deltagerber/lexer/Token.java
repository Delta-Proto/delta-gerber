package com.deltaproto.deltagerber.lexer;

/**
 * A token from the Gerber lexer.
 */
public class Token {

    private final TokenType type;
    private final String content;
    private final int line;

    public Token(TokenType type, String content, int line) {
        this.type = type;
        this.content = content;
        this.line = line;
    }

    public TokenType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public int getLine() {
        return line;
    }

    @Override
    public String toString() {
        return String.format("Token[%s, '%s', line %d]", type, content, line);
    }
}
