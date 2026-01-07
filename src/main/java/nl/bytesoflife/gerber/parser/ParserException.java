package nl.bytesoflife.gerber.parser;

/**
 * Exception thrown during Gerber parsing.
 */
public class ParserException extends RuntimeException {

    private final int line;

    public ParserException(String message) {
        super(message);
        this.line = -1;
    }

    public ParserException(String message, int line) {
        super(String.format("Line %d: %s", line, message));
        this.line = line;
    }

    public ParserException(String message, Throwable cause) {
        super(message, cause);
        this.line = -1;
    }

    public int getLine() {
        return line;
    }
}
