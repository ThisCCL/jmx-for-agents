package io.github.thisccl.j4a.locator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class StructuralLocator {
    private final Function<String, String> hasher;

    private StructuralLocator(Function<String, String> hasher) {
        this.hasher = hasher;
    }

    public static StructuralLocator defaultLocator() {
        return new StructuralLocator(StructuralLocator::md5Hex12);
    }

    public static StructuralLocator withHasher(Function<String, String> hasher) {
        return new StructuralLocator(Objects.requireNonNull(hasher, "hasher"));
    }

    public LocatorIndex locate(LocatorNode root) {
        Map<LocatorNode, String> locatorsByNode = new HashMap<>();
        Map<String, List<LocatorNode>> exactLocatorsByLocator = new HashMap<>();
        Map<String, List<LocatorNode>> baseLocatorsByLocator = new HashMap<>();
        List<String> warnings = new ArrayList<>();

        traverse(root, 0, new ArrayDeque<>(), locatorsByNode, exactLocatorsByLocator, baseLocatorsByLocator, warnings);

        return new LocatorIndex(locatorsByNode, exactLocatorsByLocator, baseLocatorsByLocator, warnings);
    }

    private void traverse(
            LocatorNode node,
            int siblingIndex,
            Deque<String> pathSegments,
            Map<LocatorNode, String> locatorsByNode,
            Map<String, List<LocatorNode>> exactLocatorsByLocator,
            Map<String, List<LocatorNode>> baseLocatorsByLocator,
            List<String> warnings) {
        String segment = node.componentClass() + "#" + siblingIndex;
        pathSegments.addLast(segment);

        String canonicalPath = String.join("/", pathSegments);
        String baseLocator = "jmx_" + hasher.apply(canonicalPath);
        List<LocatorNode> collidingNodes = baseLocatorsByLocator.computeIfAbsent(baseLocator, ignored -> new ArrayList<>());
        collidingNodes.add(node);

        String assignedLocator = baseLocator;
        if (collidingNodes.size() > 1) {
            assignedLocator = baseLocator + "_" + collidingNodes.size();
            if (collidingNodes.size() == 2) {
                warnings.add("- **Warning:** locator collision for `" + baseLocator + "`; assigned suffixed locators.");
            }
        }

        locatorsByNode.put(node, assignedLocator);
        exactLocatorsByLocator.computeIfAbsent(assignedLocator, ignored -> new ArrayList<>()).add(node);

        Map<String, Integer> siblingPositions = new HashMap<>();
        for (LocatorNode child : node.children()) {
            int childIndex = siblingPositions.getOrDefault(child.componentClass(), 0);
            siblingPositions.put(child.componentClass(), childIndex + 1);
            traverse(child, childIndex, pathSegments, locatorsByNode, exactLocatorsByLocator, baseLocatorsByLocator, warnings);
        }

        pathSegments.removeLast();
    }

    private static String md5Hex12(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte current : bytes) {
                hex.append(Character.forDigit((current >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(current & 0x0f, 16));
            }
            return hex.substring(0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 unavailable", exception);
        }
    }
}
