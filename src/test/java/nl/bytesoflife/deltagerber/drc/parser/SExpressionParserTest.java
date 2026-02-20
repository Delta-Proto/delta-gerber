package nl.bytesoflife.deltagerber.drc.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SExpressionParserTest {

    private final SExpressionParser parser = new SExpressionParser();

    @Test
    void parseSimpleList() {
        List<SNode> nodes = parser.parse("(version 1)");
        assertEquals(1, nodes.size());
        assertInstanceOf(SNode.SList.class, nodes.get(0));
        SNode.SList list = (SNode.SList) nodes.get(0);
        assertEquals(2, list.children().size());
        assertEquals("version", ((SNode.SAtom) list.children().get(0)).value());
        assertEquals("1", ((SNode.SAtom) list.children().get(1)).value());
    }

    @Test
    void parseNestedLists() {
        List<SNode> nodes = parser.parse("(constraint track_width (min 0.127mm))");
        assertEquals(1, nodes.size());
        SNode.SList list = (SNode.SList) nodes.get(0);
        assertEquals(3, list.children().size());
        assertInstanceOf(SNode.SList.class, list.children().get(2));
    }

    @Test
    void parseQuotedString() {
        List<SNode> nodes = parser.parse("(condition \"A.Type == 'track'\")");
        SNode.SList list = (SNode.SList) nodes.get(0);
        assertEquals("A.Type == 'track'", ((SNode.SAtom) list.children().get(1)).value());
    }

    @Test
    void skipComments() {
        String input = """
                # This is a comment
                (version 1)
                # Another comment
                (rule "test")
                """;
        List<SNode> nodes = parser.parse(input);
        assertEquals(2, nodes.size());
    }

    @Test
    void parseMultipleTopLevelExpressions() {
        String input = "(version 1)(rule \"test\"(constraint clearance (min 0.1mm)))";
        List<SNode> nodes = parser.parse(input);
        assertEquals(2, nodes.size());
    }

    @Test
    void parseEmptyInput() {
        List<SNode> nodes = parser.parse("");
        assertTrue(nodes.isEmpty());
    }

    @Test
    void parseWhitespaceOnlyInput() {
        List<SNode> nodes = parser.parse("   \n\n  # comment only\n  ");
        assertTrue(nodes.isEmpty());
    }

    @Test
    void parseQuotedStringWithLayerWildcard() {
        List<SNode> nodes = parser.parse("(layer \"?.Silkscreen\")");
        SNode.SList list = (SNode.SList) nodes.get(0);
        assertEquals("?.Silkscreen", ((SNode.SAtom) list.children().get(1)).value());
    }
}
