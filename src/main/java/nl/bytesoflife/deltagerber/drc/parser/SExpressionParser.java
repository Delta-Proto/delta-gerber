package nl.bytesoflife.deltagerber.drc.parser;

import java.util.ArrayList;
import java.util.List;

public class SExpressionParser {

    private String input;
    private int pos;

    public List<SNode> parse(String text) {
        this.input = text;
        this.pos = 0;
        List<SNode> nodes = new ArrayList<>();
        while (pos < input.length()) {
            skipWhitespaceAndComments();
            if (pos >= input.length()) break;
            if (input.charAt(pos) == '(') {
                nodes.add(parseList());
            } else {
                break;
            }
        }
        return nodes;
    }

    private SNode.SList parseList() {
        expect('(');
        List<SNode> children = new ArrayList<>();
        while (pos < input.length()) {
            skipWhitespaceAndComments();
            if (pos >= input.length()) {
                throw new ParseException("Unexpected end of input, expected ')'", pos);
            }
            char c = input.charAt(pos);
            if (c == ')') {
                pos++;
                return new SNode.SList(children);
            } else if (c == '(') {
                children.add(parseList());
            } else if (c == '"') {
                children.add(parseQuotedString());
            } else {
                children.add(parseAtom());
            }
        }
        throw new ParseException("Unexpected end of input, expected ')'", pos);
    }

    private SNode.SAtom parseQuotedString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '"') {
                pos++;
                return new SNode.SAtom(sb.toString());
            }
            if (c == '\\' && pos + 1 < input.length()) {
                pos++;
                sb.append(input.charAt(pos));
            } else {
                sb.append(c);
            }
            pos++;
        }
        throw new ParseException("Unterminated quoted string", pos);
    }

    private SNode.SAtom parseAtom() {
        int start = pos;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '(' || c == ')' || c == '"' || Character.isWhitespace(c)) {
                break;
            }
            pos++;
        }
        if (pos == start) {
            throw new ParseException("Expected atom at position " + pos, pos);
        }
        return new SNode.SAtom(input.substring(start, pos));
    }

    private void skipWhitespaceAndComments() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '#') {
                while (pos < input.length() && input.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                break;
            }
        }
    }

    private void expect(char expected) {
        if (pos >= input.length() || input.charAt(pos) != expected) {
            throw new ParseException("Expected '" + expected + "' at position " + pos, pos);
        }
        pos++;
    }

    public static class ParseException extends RuntimeException {
        private final int position;

        public ParseException(String message, int position) {
            super(message);
            this.position = position;
        }

        public int getPosition() {
            return position;
        }
    }
}
