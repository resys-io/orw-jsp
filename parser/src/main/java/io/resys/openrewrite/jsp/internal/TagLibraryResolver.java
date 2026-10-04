package io.resys.openrewrite.jsp.internal;

import io.resys.openrewrite.jsp.tree.TagLibrary;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.openrewrite.Tree.randomId;

/**
 * Resolves the {@code uri} of a {@code <%@ taglib %>} directive to its tag library descriptor (TLD),
 * trying, in order:
 * <ol>
 *     <li>an explicit mapping for the uri (to a {@code .tld} file, or to a jar or directory searched
 *     for a TLD declaring that {@code <uri>});</li>
 *     <li>the configured search path: directories and jars searched for a TLD declaring that
 *     {@code <uri>};</li>
 *     <li>what a servlet container does for the web application containing the page (the nearest
 *     ancestor directory with a {@code WEB-INF}): a uri that is itself a path to a TLD (or jar),
 *     a {@code <taglib>} mapping in {@code WEB-INF/web.xml}, and any TLD under {@code WEB-INF}
 *     (or in a {@code META-INF} of a jar in {@code WEB-INF/lib}) declaring that {@code <uri>}.</li>
 * </ol>
 * TLDs are read with external entities and DTD loading disabled. Results are cached, so one resolver
 * should be used per parse.
 */
public final class TagLibraryResolver {

    private final Map<String, Path> explicit;
    private final List<Path> searchPath;
    private final Path base;

    private @Nullable Map<String, TldSource> searchPathIndex;
    private final Map<Path, Map<String, TldSource>> webRootIndex = new HashMap<>();
    private final Map<Path, Optional<TagLibrary>> resolved = new HashMap<>();

    /**
     * Where a TLD's bytes live: a file, or an entry in a jar.
     */
    private record TldSource(Path file, @Nullable String entry) {
        String describe(Path base) {
            String path = (file.startsWith(base) ? base.relativize(file) : file).toString();
            return entry == null ? path : path + "!/" + entry;
        }
    }

    /**
     * @param explicit   uri to TLD, jar, or directory; relative paths are resolved against {@code base}.
     * @param searchPath directories and jars to search for TLDs; relative paths likewise.
     */
    public TagLibraryResolver(Map<String, Path> explicit, List<Path> searchPath, Path base) {
        this.base = base;
        this.explicit = new HashMap<>();
        explicit.forEach((uri, path) -> this.explicit.put(uri, base.resolve(path).normalize()));
        this.searchPath = searchPath.stream().map(p -> base.resolve(p).normalize()).toList();
    }

    /**
     * @param page the absolute path of the page (or included file) containing the directive.
     */
    public @Nullable TagLibrary resolve(String uri, Path page) {
        TldSource source = find(uri, page);
        return source == null ? null : load(source, uri);
    }

    private @Nullable TldSource find(String uri, Path page) {
        Path mapped = explicit.get(uri);
        if (mapped != null) {
            return isTld(mapped) ? new TldSource(mapped, null) : index(List.of(mapped)).get(uri);
        }
        if (searchPathIndex == null) {
            searchPathIndex = index(searchPath);
        }
        TldSource fromSearchPath = searchPathIndex.get(uri);
        if (fromSearchPath != null) {
            return fromSearchPath;
        }

        Path webRoot = webRoot(page);
        if (!uri.contains(":")) {
            // A path, not a URI: context-relative, or relative to the page.
            Path target = uri.startsWith("/") ?
                    (webRoot == null ? null : webRoot.resolve(uri.substring(1)).normalize()) :
                    page.resolveSibling(uri).normalize();
            if (target != null && Files.isRegularFile(target)) {
                return isTld(target) ? new TldSource(target, null) : jarTld(target);
            }
        }
        if (webRoot == null) {
            return null;
        }
        return webRootIndex.computeIfAbsent(webRoot, this::indexWebApplication).get(uri);
    }

    private static @Nullable Path webRoot(Path page) {
        for (Path dir = page.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("WEB-INF"))) {
                return dir;
            }
        }
        return null;
    }

    private Map<String, TldSource> indexWebApplication(Path webRoot) {
        Path webInf = webRoot.resolve("WEB-INF");
        // Precedence: web.xml mappings, then TLDs under WEB-INF, then TLDs in WEB-INF/lib jars.
        Map<String, TldSource> index = new HashMap<>();
        try (Stream<Path> files = Files.walk(webInf)) {
            for (Path tld : files.filter(TagLibraryResolver::isTld)
                    .filter(f -> !f.startsWith(webInf.resolve("classes")) && !f.startsWith(webInf.resolve("lib")))
                    .toList()) {
                indexTld(new TldSource(tld, null), index);
            }
        } catch (IOException ignored) {
            // Unreadable WEB-INF.
        }
        try (Stream<Path> files = Files.walk(webInf.resolve("lib"), 1)) {
            index(files.filter(f -> f.toString().endsWith(".jar")).toList()).forEach(index::putIfAbsent);
        } catch (IOException ignored) {
            // No WEB-INF/lib.
        }
        Path webXml = webInf.resolve("web.xml");
        if (Files.isRegularFile(webXml)) {
            Element root = readXml(webXml, null);
            if (root != null) {
                for (Element taglib : descendants(root, "taglib")) {
                    String uri = text(taglib, "taglib-uri");
                    String location = text(taglib, "taglib-location");
                    if (uri != null && location != null) {
                        Path target = location.startsWith("/") ?
                                webRoot.resolve(location.substring(1)).normalize() :
                                webInf.resolve(location).normalize();
                        TldSource source = isTld(target) ? new TldSource(target, null) : jarTld(target);
                        if (source != null) {
                            index.put(uri, source);
                        }
                    }
                }
            }
        }
        return index;
    }

    /**
     * @return every TLD found in the given directories (recursively) and jars, by its {@code <uri>}.
     */
    private Map<String, TldSource> index(List<Path> locations) {
        Map<String, TldSource> index = new HashMap<>();
        for (Path location : locations) {
            if (Files.isDirectory(location)) {
                try (Stream<Path> files = Files.walk(location)) {
                    List<Path> jars = new ArrayList<>();
                    for (Path file : files.toList()) {
                        if (isTld(file)) {
                            indexTld(new TldSource(file, null), index);
                        } else if (file.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                            jars.add(file);
                        }
                    }
                    index(jars).forEach(index::putIfAbsent);
                } catch (IOException ignored) {
                    // Skip unreadable locations.
                }
            } else if (isTld(location)) {
                indexTld(new TldSource(location, null), index);
            } else if (Files.isRegularFile(location)) {
                try (ZipFile jar = new ZipFile(location.toFile())) {
                    for (Enumeration<? extends ZipEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.getName().startsWith("META-INF/") && isTld(entry.getName())) {
                            indexTld(new TldSource(location, entry.getName()), index);
                        }
                    }
                } catch (IOException ignored) {
                    // Not a jar.
                }
            }
        }
        return index;
    }

    private void indexTld(TldSource source, Map<String, TldSource> index) {
        Element root = read(source);
        String uri = root == null ? null : text(root, "uri");
        if (uri != null) {
            index.putIfAbsent(uri, source);
        }
    }

    /**
     * @return the TLD a jar referenced directly by path declares at {@code META-INF/taglib.tld}.
     */
    private static @Nullable TldSource jarTld(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry("META-INF/taglib.tld") == null ? null : new TldSource(jar, "META-INF/taglib.tld");
        } catch (IOException e) {
            return null;
        }
    }

    private @Nullable TagLibrary load(TldSource source, String uri) {
        Path key = source.entry() == null ? source.file() : source.file().resolve(source.entry());
        return resolved.computeIfAbsent(key, k -> Optional.ofNullable(parseTld(source, uri)))
                .map(library -> library.withId(randomId()).withUri(uri))
                .orElse(null);
    }

    private @Nullable TagLibrary parseTld(TldSource source, String uri) {
        Element root = read(source);
        if (root == null || !"taglib".equals(name(root))) {
            return null;
        }
        List<TagLibrary.TagDescriptor> tags = new ArrayList<>();
        for (Element tag : children(root, "tag")) {
            String name = text(tag, "name");
            if (name == null) {
                continue;
            }
            List<TagLibrary.AttributeDescriptor> attributes = new ArrayList<>();
            for (Element attribute : children(tag, "attribute")) {
                String attributeName = text(attribute, "name");
                if (attributeName != null) {
                    attributes.add(new TagLibrary.AttributeDescriptor(attributeName, isTrue(text(attribute, "required"))));
                }
            }
            List<TagLibrary.VariableDescriptor> variables = new ArrayList<>();
            for (Element variable : children(tag, "variable")) {
                String scope = text(variable, "scope");
                variables.add(new TagLibrary.VariableDescriptor(text(variable, "name-given"),
                        text(variable, "name-from-attribute"), scope == null ? "NESTED" : scope));
            }
            String bodyContent = text(tag, "body-content");
            if (bodyContent == null) {
                bodyContent = text(tag, "bodycontent"); // JSP 1.1
            }
            tags.add(new TagLibrary.TagDescriptor(name, bodyContent == null ? "JSP" : bodyContent,
                    attributes, variables, isTrue(text(tag, "dynamic-attributes")),
                    text(tag, "tei-class") != null || text(tag, "teiclass") != null, false));
        }
        for (Element tagFile : children(root, "tag-file")) {
            String name = text(tagFile, "name");
            if (name != null) {
                tags.add(new TagLibrary.TagDescriptor(name, "scriptless", List.of(), List.of(), true, true, true));
            }
        }
        List<TagLibrary.FunctionDescriptor> functions = new ArrayList<>();
        for (Element function : children(root, "function")) {
            String name = text(function, "name");
            if (name != null) {
                String signature = text(function, "function-signature");
                functions.add(new TagLibrary.FunctionDescriptor(name, signature == null ? "" : signature));
            }
        }
        String shortName = text(root, "short-name");
        return new TagLibrary(randomId(), uri, source.describe(base),
                shortName == null ? text(root, "shortname") : shortName, tags, functions);
    }

    // -----------------------------------------------------------------------------------------
    // XML
    // -----------------------------------------------------------------------------------------

    private static @Nullable Element read(TldSource source) {
        if (source.entry() == null) {
            return readXml(source.file(), null);
        }
        try (ZipFile jar = new ZipFile(source.file().toFile())) {
            ZipEntry entry = jar.getEntry(source.entry());
            if (entry == null) {
                return null;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return readXml(null, in.readAllBytes());
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static @Nullable Element readXml(@Nullable Path file, byte @Nullable [] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Old TLDs carry a DOCTYPE pointing at a DTD URL: allow the declaration, never fetch anything.
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new DefaultHandler()); // fail on fatal errors without printing
            try (InputStream in = file != null ?
                    Files.newInputStream(file) : new ByteArrayInputStream(bytes == null ? new byte[0] : bytes)) {
                Document document = builder.parse(in);
                return document.getDocumentElement();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String name(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && name.equals(name(child))) {
                result.add((Element) child);
            }
        }
        return result;
    }

    private static List<Element> descendants(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                if (name.equals(name(child))) {
                    result.add((Element) child);
                }
                result.addAll(descendants((Element) child, name));
            }
        }
        return result;
    }

    private static @Nullable String text(Element parent, String name) {
        List<Element> matches = children(parent, name);
        if (matches.isEmpty()) {
            return null;
        }
        String text = matches.get(0).getTextContent().trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean isTrue(@Nullable String value) {
        return value != null && ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value));
    }

    private static boolean isTld(Path path) {
        return isTld(path.toString());
    }

    private static boolean isTld(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".tld");
    }
}
