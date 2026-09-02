package io.github.thisccl.j4a.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LocalJMeterLabelResolver {
    private static final Pattern RESOURCE_MARKER = Pattern.compile("^\\[res_key=([^]\\r\\n]+)]$");
    private static final Logger LOG = LoggerFactory.getLogger(LocalJMeterLabelResolver.class);

    private LocalJMeterLabelResolver() {
    }

    static String resolve(String runtimeLabel, String fallbackKey, String menuClassName,
            String suppliedFallback) {
        String trimmed = runtimeLabel == null ? "" : runtimeLabel.trim();
        if (!trimmed.isEmpty() && !looksLikeResourceMarker(trimmed)) {
            return runtimeLabel;
        }
        Matcher marker = RESOURCE_MARKER.matcher(trimmed);
        if (marker.matches()) {
            String key = marker.group(1);
            String resolved = JMeterUtils.getResString(key);
            if (isReadable(resolved) && !looksLikeResourceMarker(resolved.trim())) {
                return resolved;
            }
            return fallback("missing_resource", key, menuClassName, suppliedFallback, key);
        }
        if (!trimmed.isEmpty()) {
            return fallback("malformed_marker", fallbackKey, menuClassName, suppliedFallback,
                    simpleClassName(menuClassName));
        }
        return fallback("blank_label", fallbackKey, menuClassName, suppliedFallback,
                simpleClassName(menuClassName));
    }

    private static String fallback(String reason, String key, String menuClassName,
            String suppliedFallback, String humanizeSource) {
        String fallback = isReadable(suppliedFallback) && !looksLikeResourceMarker(suppliedFallback.trim())
                ? suppliedFallback : humanize(humanizeSource);
        if (!isReadable(fallback) || looksLikeResourceMarker(fallback.trim())) {
            fallback = "Component";
        }
        LOG.debug("JMeter label fallback reason={} key={} fqcn={}", reason, key, menuClassName);
        return fallback;
    }

    private static String simpleClassName(String menuClassName) {
        if (!isReadable(menuClassName)) {
            return "";
        }
        String simpleName = menuClassName.trim();
        int packageSeparator = Math.max(simpleName.lastIndexOf('.'), simpleName.lastIndexOf('$'));
        if (packageSeparator >= 0) {
            simpleName = simpleName.substring(packageSeparator + 1);
        }
        for (String suffix : new String[] {"GUI", "Gui", "Panel"}) {
            if (simpleName.endsWith(suffix) && simpleName.length() > suffix.length()) {
                return simpleName.substring(0, simpleName.length() - suffix.length());
            }
        }
        return simpleName;
    }

    private static String humanize(String value) {
        if (!isReadable(value)) {
            return "";
        }
        String words = value.trim().replaceFirst("_title$", "")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[_-]+", " ")
                .trim();
        List<String> titleWords = new ArrayList<>();
        for (String word : words.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            String lower = word.toLowerCase(Locale.ROOT);
            titleWords.add(lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1));
        }
        return String.join(" ", titleWords);
    }

    private static boolean isReadable(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean looksLikeResourceMarker(String value) {
        return value.startsWith("[res_key=");
    }
}
