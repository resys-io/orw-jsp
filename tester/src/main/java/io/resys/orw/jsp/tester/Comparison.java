package io.resys.orw.jsp.tester;

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
    };

    public abstract String normalize(String output);
}
