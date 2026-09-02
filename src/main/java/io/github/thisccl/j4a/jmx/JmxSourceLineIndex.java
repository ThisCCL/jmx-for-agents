package io.github.thisccl.j4a.jmx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.apache.jmeter.testelement.TestElement;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public final class JmxSourceLineIndex {
    private static final JmxSourceLineIndex EMPTY = new JmxSourceLineIndex(Collections.<SourceElement>emptyList());

    private final List<SourceElement> elements;

    private JmxSourceLineIndex(List<SourceElement> elements) {
        this.elements = Collections.unmodifiableList(new ArrayList<>(elements));
    }

    public static JmxSourceLineIndex empty() {
        return EMPTY;
    }

    public static JmxSourceLineIndex from(Path jmxPath) throws IOException, SAXException {
        SourceHandler handler = new SourceHandler();
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        try {
            disableExternalEntities(factory);
            try (InputStream input = Files.newInputStream(jmxPath)) {
                factory.newSAXParser().parse(input, handler);
            }
        } catch (ParserConfigurationException exception) {
            throw new SAXException("Unable to configure JMX source parser", exception);
        }
        return new JmxSourceLineIndex(handler.elements());
    }

    public Cursor cursor() {
        return new Cursor(elements);
    }

    private static void disableExternalEntities(SAXParserFactory factory) throws ParserConfigurationException {
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
    }

    private static void setFeatureIfSupported(SAXParserFactory factory, String feature, boolean enabled)
            throws ParserConfigurationException {
        try {
            factory.setFeature(feature, enabled);
        } catch (SAXException ignored) {
        }
    }

    public static final class Cursor {
        private final List<SourceElement> elements;
        private int position;

        private Cursor(List<SourceElement> elements) {
            this.elements = elements;
        }

        public Optional<JmxSourceRange> consume(TestElement element) {
            Optional<SourceMatch> match = consumeSource(element);
            return match.isPresent() ? Optional.of(match.get().range()) : Optional.empty();
        }

        public Optional<SourceMatch> consumeSource(TestElement element) {
            Objects.requireNonNull(element, "element");
            for (int index = position; index < elements.size(); index++) {
                SourceElement candidate = elements.get(index);
                if (candidate.matches(element)) {
                    position = index + 1;
                    return Optional.of(new SourceMatch(candidate.range(), candidate.argumentElementTypes()));
                }
            }
            return Optional.empty();
        }
    }

    public static final class SourceMatch {
        private final JmxSourceRange range;
        private final Map<String, List<String>> argumentElementTypes;

        private SourceMatch(JmxSourceRange range, Map<String, List<String>> argumentElementTypes) {
            this.range = range;
            this.argumentElementTypes = argumentElementTypes;
        }

        public JmxSourceRange range() {
            return range;
        }

        public List<String> argumentElementTypes(String propertyName) {
            List<String> values = argumentElementTypes.get(propertyName);
            return values == null ? Collections.<String>emptyList() : values;
        }
    }

    private static final class SourceHandler extends DefaultHandler {
        private final Deque<String> tags = new ArrayDeque<>();
        private final Deque<Optional<SourceBuilder>> builders = new ArrayDeque<>();
        private final List<SourceBuilder> elements = new ArrayList<>();
        private final Deque<ArgumentCollectionCapture> argumentCollections = new ArrayDeque<>();
        private Locator locator;

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            String tag = qName == null || qName.isEmpty() ? localName : qName;
            boolean component = isComponentElement(tag);
            SourceBuilder builder = component
                    ? new SourceBuilder(tag, attributes.getValue("testclass"), attributes.getValue("testname"), line())
                    : null;
            if (builder != null) {
                elements.add(builder);
            }
            SourceBuilder componentBuilder = activeComponentBuilder(builder);
            if (componentBuilder != null && "elementProp".equals(tag)) {
                String elementType = attributes.getValue("elementType");
                if (isArgumentsType(elementType)) {
                    argumentCollections.push(new ArgumentCollectionCapture(
                            componentBuilder, attributes.getValue("name"), tags.size()));
                } else if (!argumentCollections.isEmpty()
                        && "collectionProp".equals(tags.peek())
                        && elementType != null
                        && !elementType.trim().isEmpty()) {
                    argumentCollections.peek().add(elementType);
                }
            }
            tags.push(tag);
            builders.push(Optional.ofNullable(builder));
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            SourceBuilder builder = builders.pop().orElse(null);
            if (builder != null) {
                builder.endLine(line());
            }
            if (!argumentCollections.isEmpty()
                    && "elementProp".equals(tags.peek())
                    && argumentCollections.peek().startDepth == tags.size() - 1) {
                argumentCollections.pop().finish();
            }
            tags.pop();
        }

        List<SourceElement> elements() {
            List<SourceElement> sourceElements = new ArrayList<>();
            for (SourceBuilder builder : elements) {
                sourceElements.add(builder.build());
            }
            return sourceElements;
        }

        private boolean isComponentElement(String tag) {
            return !tags.isEmpty() && "hashTree".equals(tags.peek()) && !"hashTree".equals(tag);
        }

        private SourceBuilder activeComponentBuilder(SourceBuilder current) {
            if (current != null) {
                return current;
            }
            for (Optional<SourceBuilder> candidate : builders) {
                if (candidate.isPresent()) {
                    return candidate.get();
                }
            }
            return null;
        }

        private static boolean isArgumentsType(String elementType) {
            return "Arguments".equals(elementType) || (elementType != null && elementType.endsWith(".Arguments"));
        }

        private int line() {
            return locator == null ? 1 : Math.max(1, locator.getLineNumber());
        }
    }

    private static final class ArgumentCollectionCapture {
        private final SourceBuilder builder;
        private final String propertyName;
        private final int startDepth;
        private final List<String> elementTypes = new ArrayList<>();

        private ArgumentCollectionCapture(SourceBuilder builder, String propertyName, int startDepth) {
            this.builder = builder;
            this.propertyName = propertyName;
            this.startDepth = startDepth;
        }

        private void add(String elementType) {
            elementTypes.add(elementType);
        }

        private void finish() {
            if (propertyName != null && !elementTypes.isEmpty()) {
                builder.argumentElementTypes.put(propertyName, new ArrayList<>(elementTypes));
            }
        }
    }

    private static final class SourceBuilder {
        private final String tagName;
        private final String testClass;
        private final String testName;
        private final int startLine;
        private final Map<String, List<String>> argumentElementTypes = new LinkedHashMap<>();
        private int endLine;

        private SourceBuilder(String tagName, String testClass, String testName, int startLine) {
            this.tagName = tagName;
            this.testClass = testClass;
            this.testName = testName;
            this.startLine = startLine;
            this.endLine = startLine;
        }

        private void endLine(int endLine) {
            this.endLine = endLine;
        }

        private SourceElement build() {
            return new SourceElement(
                    tagName, testClass, testName, new JmxSourceRange(startLine, endLine), argumentElementTypes);
        }
    }

    private static final class SourceElement {
        private final String tagName;
        private final String testClass;
        private final String testName;
        private final JmxSourceRange range;
        private final Map<String, List<String>> argumentElementTypes;

        private SourceElement(
                String tagName,
                String testClass,
                String testName,
                JmxSourceRange range,
                Map<String, List<String>> argumentElementTypes) {
            this.tagName = tagName;
            this.testClass = testClass;
            this.testName = testName;
            this.range = range;
            Map<String, List<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : argumentElementTypes.entrySet()) {
                copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
            this.argumentElementTypes = Collections.unmodifiableMap(copy);
        }

        private boolean matches(TestElement element) {
            return classMatches(element) && nameMatches(element);
        }

        private JmxSourceRange range() {
            return range;
        }

        private Map<String, List<String>> argumentElementTypes() {
            return argumentElementTypes;
        }

        private boolean classMatches(TestElement element) {
            String elementClass = simpleName(element.getPropertyAsString(TestElement.TEST_CLASS));
            if (elementClass.isEmpty()) {
                elementClass = element.getClass().getSimpleName();
            }
            String sourceClass = simpleName(testClass);
            return tagName.equals(elementClass) || (!sourceClass.isEmpty() && sourceClass.equals(elementClass));
        }

        private boolean nameMatches(TestElement element) {
            return testName == null || testName.isEmpty() || testName.equals(element.getName());
        }

        private static String simpleName(String value) {
            if (value == null || value.trim().isEmpty()) {
                return "";
            }
            int separator = value.lastIndexOf('.');
            return separator >= 0 ? value.substring(separator + 1) : value;
        }
    }
}
