package nl.bytesoflife.deltagerber.drc.parser;

import java.util.List;

public sealed interface SNode permits SNode.SAtom, SNode.SList {

    record SAtom(String value) implements SNode {
        @Override
        public String toString() {
            return value;
        }
    }

    record SList(List<SNode> children) implements SNode {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(children.get(i));
            }
            sb.append(')');
            return sb.toString();
        }
    }
}
