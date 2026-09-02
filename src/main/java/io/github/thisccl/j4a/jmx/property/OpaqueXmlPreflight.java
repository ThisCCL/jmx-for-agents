package io.github.thisccl.j4a.jmx.property;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

final class OpaqueXmlPreflight {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DEPTH = 128;
    private static final int MAX_NODES = 20_000;
    private static final int MAX_ATTRIBUTES = 40_000;
    private static final int MAX_TEXT_CHARACTERS = 4 * 1024 * 1024;

    private OpaqueXmlPreflight() {
    }

    static void requireSafe(String observed, String submitted, String rootPropertyName) {
        Shape allowed = parse(observed);
        Shape candidate = parse(submitted);
        if (!allowed.rootElement.equals(candidate.rootElement)) {
            throw new IllegalArgumentException(
                    "opaque payload root element does not match current value");
        }
        if (allowed.rootName == null ? candidate.rootName != null
                : !allowed.rootName.equals(candidate.rootName)) {
            throw new IllegalArgumentException(
                    "opaque root property name does not match current value");
        }
        if (allowed.rootName != null && !rootPropertyName.equals(candidate.rootName)) {
            throw new IllegalArgumentException(
                    "opaque root property name does not match current value");
        }

        TreeSet<String> expansion = new TreeSet<String>(candidate.elementSelectors);
        expansion.removeAll(allowed.elementSelectors);
        TreeSet<String> metadataExpansion = new TreeSet<String>(candidate.classSelectors);
        metadataExpansion.removeAll(allowed.classSelectors);
        expansion.addAll(metadataExpansion);
        if (!expansion.isEmpty()) {
            throw new IllegalArgumentException(
                    "opaque payload expands serialized classes: " + expansion);
        }
    }

    private static Shape parse(String xml) {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("opaque payload exceeds XML byte limit");
        }
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            factory.setSchema(null);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            SAXParser parser = factory.newSAXParser();
            Handler handler = new Handler();
            parser.parse(new ByteArrayInputStream(bytes), handler);
            return handler.shape();
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException("secure opaque XML parser is unavailable", exception);
        } catch (SAXException exception) {
            throw new IllegalArgumentException("opaque payload is not safe XML", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("opaque payload cannot be read as XML", exception);
        }
    }

    private static boolean classSelector(String qualifiedName) {
        int separator = qualifiedName.indexOf(':');
        String localName = separator < 0
                ? qualifiedName
                : qualifiedName.substring(separator + 1);
        return "class".equals(localName)
                || "resolves-to".equals(localName)
                || "elementType".equals(localName)
                || "serialization".equals(localName)
                || "schemaLocation".equals(localName)
                || "noNamespaceSchemaLocation".equals(localName);
    }

    private static final class Shape {
        private final String rootElement;
        private final String rootName;
        private final Set<String> elementSelectors;
        private final Set<String> classSelectors;

        private Shape(
                String rootElement,
                String rootName,
                Set<String> elementSelectors,
                Set<String> classSelectors) {
            this.rootElement = rootElement;
            this.rootName = rootName;
            this.elementSelectors = elementSelectors;
            this.classSelectors = classSelectors;
        }
    }

    private static final class Handler extends DefaultHandler {
        private final Set<String> elementSelectors = new LinkedHashSet<String>();
        private final Set<String> classSelectors = new LinkedHashSet<String>();
        private String rootElement;
        private String rootName;
        private int depth;
        private int nodes;
        private int attributes;
        private int textCharacters;

        @Override
        public void startElement(
                String uri, String localName, String qualifiedName, Attributes values)
                throws SAXException {
            depth++;
            nodes++;
            attributes += values.getLength();
            requireWithin(depth, MAX_DEPTH, "depth");
            requireWithin(nodes, MAX_NODES, "node count");
            requireWithin(attributes, MAX_ATTRIBUTES, "attribute count");
            if (rootElement == null) {
                rootElement = qualifiedName;
                rootName = values.getValue("name");
            }
            elementSelectors.add("element:" + qualifiedName);
            for (int index = 0; index < values.getLength(); index++) {
                if (classSelector(values.getQName(index))) {
                    classSelectors.add("metadata:" + values.getQName(index)
                            + "=" + values.getValue(index));
                }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qualifiedName) {
            depth--;
        }

        @Override
        public void characters(char[] values, int start, int length) throws SAXException {
            textCharacters += length;
            requireWithin(textCharacters, MAX_TEXT_CHARACTERS, "text size");
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
            throw new SAXException("external entities are disabled");
        }

        private Shape shape() throws SAXException {
            if (rootElement == null) {
                throw new SAXException("opaque payload has no root element");
            }
            return new Shape(rootElement, rootName, elementSelectors, classSelectors);
        }

        private static void requireWithin(int value, int limit, String label) throws SAXException {
            if (value > limit) {
                throw new SAXException("opaque XML " + label + " limit exceeded");
            }
        }
    }
}
