package io.resys.orw.jsp.tester;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * How a fixture's actual output is compared with its expected output.
 */
public enum Comparison {

    /** Character for character (line endings aside). */
    EXACT {
        @Override
        public String normalize(String output) {
            return output.replace("\r\n", "\n");
        }
    },

    /**
     * Ignoring differences in whitespace: every run of whitespace counts as one space, whitespace
     * between tags doesn't count, and leading/trailing whitespace doesn't count. A migration
     * usually changes whitespace (e.g. a scriptlet's line becoming a tag's), not the content.
     */
    WHITESPACE {
        @Override
        public String normalize(String output) {
            return output.replaceAll("\\s+", " ").replaceAll(">\\s+<", "><").trim();
        }
    },

    /**
     * As HTML documents: both outputs are parsed (as a browser would) and their element trees
     * compared, so differences in how the same HTML is written don't count - attribute order and
     * quoting, {@code <br>} vs {@code <br/>}, whitespace (runs of it collapse to one space, and text
     * is trimmed), and comments. For comparing outputs of different engines, e.g. a JSP and the
     * Thymeleaf template it was migrated to.
     */
    HTML {
        @Override
        public String normalize(String output) {
            StringBuilder canonical = new StringBuilder();
            for (Node node : Jsoup.parse(output).childNodes()) {
                canonical(node, 0, canonical);
            }
            return canonical.toString();
        }

        @Override
        public String display(String output) {
            return normalize(output);
        }

        private void canonical(Node node, int depth, StringBuilder out) {
            if (node instanceof TextNode) {
                String text = ((TextNode) node).getWholeText().replaceAll("\\s+", " ").strip();
                if (!text.isEmpty()) {
                    out.append("  ".repeat(depth)).append(text).append('\n');
                }
            } else if (node instanceof DataNode) {
                String data = ((DataNode) node).getWholeData().replaceAll("\\s+", " ").strip();
                if (!data.isEmpty()) {
                    out.append("  ".repeat(depth)).append(data).append('\n');
                }
            } else if (node instanceof Element) {
                Element element = (Element) node;
                List<Attribute> attributes = new ArrayList<>(element.attributes().asList());
                attributes.sort(Comparator.comparing(Attribute::getKey));
                out.append("  ".repeat(depth)).append('<').append(element.tagName());
                for (Attribute attribute : attributes) {
                    out.append(' ').append(attribute.getKey()).append("=\"").append(attribute.getValue()).append('"');
                }
                out.append(">\n");
                for (Node child : element.childNodes()) {
                    canonical(child, depth + 1, out);
                }
                out.append("  ".repeat(depth)).append("</").append(element.tagName()).append(">\n");
            }
            // Comments, doctypes, and processing instructions don't count.
        }
    };

    public abstract String normalize(String output);

    /**
     * How to show an output when the comparison fails: as it is, except where the normalized form
     * makes the difference easier to see.
     */
    public String display(String output) {
        return output;
    }
}
