package io.github.thisccl.j4a.locator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LocatorIndex {
    private final Map<LocatorNode, String> locatorsByNode;
    private final Map<String, List<LocatorNode>> exactLocatorsByLocator;
    private final Map<String, List<LocatorNode>> baseLocatorsByLocator;
    private final List<String> warnings;

    LocatorIndex(
            Map<LocatorNode, String> locatorsByNode,
            Map<String, List<LocatorNode>> exactLocatorsByLocator,
            Map<String, List<LocatorNode>> baseLocatorsByLocator,
            List<String> warnings) {
        this.locatorsByNode = Collections.unmodifiableMap(new LinkedHashMap<>(locatorsByNode));
        this.exactLocatorsByLocator = copyLocatorMap(exactLocatorsByLocator);
        this.baseLocatorsByLocator = copyLocatorMap(baseLocatorsByLocator);
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    private static Map<String, List<LocatorNode>> copyLocatorMap(Map<String, List<LocatorNode>> source) {
        Map<String, List<LocatorNode>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<LocatorNode>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    public String locatorFor(LocatorNode node) {
        String locator = locatorsByNode.get(node);
        if (locator == null) {
            throw new IllegalArgumentException("Unknown node");
        }
        return locator;
    }

    public List<String> warnings() {
        return warnings;
    }

    public LocatorNode requireUnique(String locator) {
        List<LocatorNode> collidingNodes = baseLocatorsByLocator.get(locator);
        if (collidingNodes != null && collidingNodes.size() > 1) {
            throw new AmbiguousLocatorException("Ambiguous locator: " + locator);
        }

        List<LocatorNode> exactMatches = exactLocatorsByLocator.get(locator);
        if (exactMatches != null && exactMatches.size() == 1) {
            return exactMatches.get(0);
        }

        if (collidingNodes != null && collidingNodes.size() == 1) {
            return collidingNodes.get(0);
        }

        throw new LocatorNotFoundException(locator);
    }
}
