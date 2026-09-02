import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.EntityResolver2;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

public final class JUnitXmlBatchParser {
    private static final String PROTOCOL = "J4A-JUNIT-V1";
    private static final Set<String> KNOWN = set("testsuite", "properties", "property", "testcase", "failure", "error", "skipped", "system-out", "system-err");
    private JUnitXmlBatchParser() {}

    public static void main(String[] args) {
        if (args.length == 0) failProcess(-1, "", "USAGE: expected one or more XML paths", 0);
        System.out.println(PROTOCOL + "\tBEGIN\t" + args.length);
        for (int index = 0; index < args.length; index += 1) {
            SecureResolver resolver = new SecureResolver();
            try {
                Handler result = parse(args[index], resolver);
                System.out.println(PROTOCOL + "\tROW\t" + index + "\t" + b64(args[index]) + "\t" + result.record() + "\t" + resolver.attempts);
            } catch (SAXParseException exception) {
                String message = message(exception, "XML parse failure");
                failProcess(index, args[index], (message.toLowerCase().contains("doctype") ? "SECURITY_DOCTYPE: " : "XML_WELL_FORMEDNESS: ") + message, resolver.attempts);
            } catch (SAXException exception) {
                failProcess(index, args[index], message(exception, "SAX failure"), resolver.attempts);
            } catch (Exception exception) {
                failProcess(index, args[index], "SETUP_OR_IO: " + exception.getClass().getSimpleName() + ": " + message(exception, "failure"), resolver.attempts);
            }
        }
        System.out.println(PROTOCOL + "\tEND\t" + args.length);
    }

    private static Handler parse(String path, SecureResolver resolver) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        feature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        feature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        feature(factory, "http://xml.org/sax/features/external-general-entities", false);
        feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        feature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        XMLReader reader = factory.newSAXParser().getXMLReader();
        readerFeature(reader, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        readerFeature(reader, "http://apache.org/xml/features/disallow-doctype-decl", true);
        readerFeature(reader, "http://xml.org/sax/features/external-general-entities", false);
        readerFeature(reader, "http://xml.org/sax/features/external-parameter-entities", false);
        readerFeature(reader, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        readerProperty(reader, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        readerProperty(reader, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Handler handler = new Handler();
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.setEntityResolver(resolver);
        readerProperty(reader, "http://xml.org/sax/properties/lexical-handler", handler);
        try (InputStream stream = new FileInputStream(path)) {
            InputSource input = new InputSource(stream);
            input.setSystemId("file:blocked-junit-input");
            reader.parse(input);
        }
        return handler;
    }

    private static final class Frame {
        private final String name;
        private String outcome;
        private Frame(String name) { this.name = name; }
    }

    private static final class Handler extends DefaultHandler implements LexicalHandler {
        private final Deque<Frame> stack = new ArrayDeque<Frame>();
        private boolean sawRoot, closedRoot, sawProperties, sawSystemOut, sawSystemErr;
        private int phase, tests, failures, errors, skipped, seenTests, seenFailures, seenErrors, seenSkipped;
        private String suiteName;

        @Override public void startElement(String uri, String local, String name, Attributes attributes) throws SAXException {
            String parent = stack.isEmpty() ? null : stack.peek().name;
            if (!KNOWN.contains(name)) reject("DIALECT_UNKNOWN_ELEMENT: <" + name + "> under " + parentName(parent));
            if (parent == null) {
                if (!"testsuite".equals(name) || sawRoot || closedRoot) reject("DIALECT_PARENT: duplicate or non-testsuite root <" + name + ">");
            } else if (!allowed(parent, name)) reject("DIALECT_PARENT: <" + name + "> under <" + parent + ">");
            validateAttributes(name, attributes);
            if ("testsuite".equals(name)) {
                sawRoot = true; suiteName = required(attributes, "name", name);
                tests = count(attributes, "tests"); failures = count(attributes, "failures");
                errors = count(attributes, "errors"); skipped = count(attributes, "skipped");
            } else if ("properties".equals(name)) {
                if (sawProperties || phase != 0) reject("DIALECT_DUPLICATE_OR_ORDER: properties");
                sawProperties = true;
            } else if ("testcase".equals(name)) {
                if (phase > 1) reject("DIALECT_ORDER: testcase after output container");
                phase = 1; required(attributes, "name", name);
                if (!suiteName.equals(required(attributes, "classname", name))) reject("DIALECT_ATTRIBUTE: testcase classname differs from suite name");
                seenTests += 1;
            } else if (outcome(name)) {
                Frame testcase = stack.peek();
                if (testcase.outcome != null) reject("DIALECT_MULTIPLE_OUTCOMES: " + testcase.outcome + " and " + name);
                testcase.outcome = name;
                if ("failure".equals(name)) seenFailures += 1; else if ("error".equals(name)) seenErrors += 1; else seenSkipped += 1;
            } else if ("system-out".equals(name)) {
                if (sawSystemOut || sawSystemErr) reject("DIALECT_DUPLICATE_OR_ORDER: system-out");
                sawSystemOut = true; phase = 2;
            } else if ("system-err".equals(name)) {
                if (sawSystemErr) reject("DIALECT_DUPLICATE_OR_ORDER: system-err");
                sawSystemErr = true; phase = 2;
            }
            stack.push(new Frame(name));
        }

        @Override public void endElement(String uri, String local, String name) throws SAXException {
            if (stack.isEmpty() || !stack.peek().name.equals(name)) reject("DIALECT_NESTING: closing </" + name + ">");
            stack.pop();
            if ("testsuite".equals(name)) {
                reconcile("tests", tests, seenTests); reconcile("failures", failures, seenFailures);
                reconcile("errors", errors, seenErrors); reconcile("skipped", skipped, seenSkipped); closedRoot = true;
            }
        }

        @Override public void characters(char[] chars, int start, int length) throws SAXException {
            boolean text = false;
            for (int index = start; index < start + length; index += 1) if (!Character.isWhitespace(chars[index])) text = true;
            String current = stack.isEmpty() ? null : stack.peek().name;
            if (text && !(outcome(current) || "system-out".equals(current) || "system-err".equals(current))) reject("DIALECT_TEXT: character data under " + parentName(current));
        }

        @Override public void processingInstruction(String target, String data) throws SAXException { reject("DIALECT_PROCESSING_INSTRUCTION: " + target); }
        @Override public void warning(SAXParseException exception) throws SAXException { throw exception; }
        @Override public void error(SAXParseException exception) throws SAXException { throw exception; }
        @Override public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
        @Override public void startDTD(String name, String publicId, String systemId) throws SAXException { reject("SECURITY_DOCTYPE: forbidden"); }
        @Override public void endDTD() {} @Override public void startEntity(String name) {} @Override public void endEntity(String name) {}
        @Override public void startCDATA() {} @Override public void endCDATA() {} @Override public void comment(char[] chars, int start, int length) {}

        private void validateAttributes(String element, Attributes attributes) throws SAXException {
            Set<String> allowed;
            if ("testsuite".equals(element)) allowed = set("name", "tests", "failures", "errors", "skipped", "timestamp", "hostname", "time");
            else if ("testcase".equals(element)) allowed = set("name", "classname", "time");
            else if ("property".equals(element)) allowed = set("name", "value");
            else if ("failure".equals(element) || "error".equals(element)) allowed = set("message", "type");
            else if ("skipped".equals(element)) allowed = set("message"); else allowed = Collections.emptySet();
            for (int index = 0; index < attributes.getLength(); index += 1) if (!allowed.contains(attributes.getQName(index))) reject("DIALECT_ATTRIBUTE: " + element + "." + attributes.getQName(index));
            if ("property".equals(element)) required(attributes, "name", element);
        }

        private String record() {
            return tests + "\t" + failures + "\t" + errors + "\t" + skipped + "\t" + b64(suiteName);
        }
    }

    private static final class SecureResolver implements EntityResolver2 {
        private int attempts;
        private InputSource blocked() throws SAXException { attempts += 1; throw new SAXException("SECURITY_ENTITY_RESOLUTION_ATTEMPT"); }
        @Override public InputSource getExternalSubset(String name, String base) throws SAXException { return blocked(); }
        @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException { return blocked(); }
        @Override public InputSource resolveEntity(String name, String publicId, String base, String systemId) throws SAXException { return blocked(); }
    }

    private static boolean allowed(String parent, String child) {
        if ("testsuite".equals(parent)) return "properties".equals(child) || "testcase".equals(child) || "system-out".equals(child) || "system-err".equals(child);
        if ("properties".equals(parent)) return "property".equals(child);
        return "testcase".equals(parent) && outcome(child);
    }
    private static boolean outcome(String name) { return "failure".equals(name) || "error".equals(name) || "skipped".equals(name); }
    private static void reconcile(String name, int expected, int observed) throws SAXException { if (expected != observed) reject("DIALECT_COUNTER_MISMATCH: " + name + "=" + expected + " observed=" + observed); }
    private static int count(Attributes attributes, String name) throws SAXException { String value = required(attributes, name, "testsuite"); if (!value.matches("(?:0|[1-9][0-9]*)")) reject("DIALECT_ATTRIBUTE: invalid count " + name); try { return Integer.parseInt(value); } catch (NumberFormatException exception) { reject("DIALECT_ATTRIBUTE: count overflow " + name); return -1; } }
    private static String required(Attributes attributes, String name, String element) throws SAXException { String value = attributes.getValue(name); if (value == null || value.length() == 0) reject("DIALECT_ATTRIBUTE: missing " + element + "." + name); return value; }
    private static void feature(SAXParserFactory factory, String name, boolean value) throws Exception { factory.setFeature(name, value); if (factory.getFeature(name) != value) throw new SAXException("SECURITY_FEATURE_NOT_ESTABLISHED: " + name); }
    private static void readerFeature(XMLReader reader, String name, boolean value) throws Exception { reader.setFeature(name, value); if (reader.getFeature(name) != value) throw new SAXException("SECURITY_FEATURE_NOT_ESTABLISHED: " + name); }
    private static void readerProperty(XMLReader reader, String name, Object value) throws Exception { reader.setProperty(name, value); }
    private static void reject(String reason) throws SAXException { throw new SAXException(reason); }
    private static String parentName(String name) { return name == null ? "document" : "<" + name + ">"; }
    private static Set<String> set(String... values) { return new HashSet<String>(Arrays.asList(values)); }
    private static String b64(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String message(Exception exception, String fallback) { return exception.getMessage() == null ? fallback : exception.getMessage(); }
    private static void failProcess(int index, String path, String reason, int attempts) { System.err.println(PROTOCOL + "\tERROR\t" + index + "\t" + b64(path) + "\t" + b64(reason) + "\t" + attempts); System.exit(2); }
}
