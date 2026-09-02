package io.github.thisccl.j4a.validation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;

final class LocalJMeterMenuRegistry {
    enum RegistrationKind {
        METADATA_TEST_ELEMENT,
        TEST_BEAN,
        GUI_COMPONENT
    }

    private final List<Entry> entries;
    private final List<Category> categories;
    private final Map<String, String> categoryLabels;
    private final Map<String, List<Entry>> byComponent;
    private final Map<String, List<Entry>> byActualClass;
    private final RuntimeComponentIdentityResolver identityResolver;

    private LocalJMeterMenuRegistry(
            List<Entry> entries, List<Category> categories, Map<String, String> categoryLabels) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.categories = Collections.unmodifiableList(new ArrayList<>(categories));
        this.categoryLabels = Collections.unmodifiableMap(new LinkedHashMap<>(categoryLabels));
        Map<String, List<Entry>> index = new LinkedHashMap<>();
        for (Entry entry : entries) {
            List<Entry> matches = index.get(entry.component());
            if (matches == null) {
                matches = new ArrayList<>();
                index.put(entry.component(), matches);
            }
            matches.add(entry);
        }
        Map<String, List<Entry>> immutableIndex = new LinkedHashMap<>();
        for (Map.Entry<String, List<Entry>> item : index.entrySet()) {
            immutableIndex.put(item.getKey(), Collections.unmodifiableList(new ArrayList<>(item.getValue())));
        }
        this.byComponent = Collections.unmodifiableMap(immutableIndex);
        Map<String, List<Entry>> actualIndex = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.kind() == RegistrationKind.GUI_COMPONENT) {
                continue;
            }
            List<Entry> matches = actualIndex.get(entry.menuClassName());
            if (matches == null) {
                matches = new ArrayList<>();
                actualIndex.put(entry.menuClassName(), matches);
            }
            matches.add(entry);
        }
        Map<String, List<Entry>> immutableActualIndex = new LinkedHashMap<>();
        for (Map.Entry<String, List<Entry>> item : actualIndex.entrySet()) {
            immutableActualIndex.put(
                    item.getKey(),
                    Collections.unmodifiableList(new ArrayList<>(item.getValue())));
        }
        this.byActualClass = Collections.unmodifiableMap(immutableActualIndex);
        this.identityResolver = new RuntimeComponentIdentityResolver(this);
        for (Entry entry : entries) {
            entry.attach(identityResolver);
        }
    }

    static LocalJMeterMenuRegistry current() {
        try {
            return reflect(Class.forName("org.apache.jmeter.gui.util.MenuFactory", true,
                    LocalJMeterValidationWorker.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError exception) {
            throw incompatible("MenuFactory class is unavailable", exception);
        }
    }

    static LocalJMeterMenuRegistry reflect(Class<?> menuFactory) {
        Object value;
        try {
            Method getMenuMap = menuFactory.getDeclaredMethod("getMenuMap");
            getMenuMap.setAccessible(true);
            value = getMenuMap.invoke(null);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException
                | RuntimeException exception) {
            throw incompatible("required package-private getMenuMap() capability is unavailable", exception);
        }
        if (!(value instanceof Map)) {
            throw incompatible("getMenuMap() did not return Map", null);
        }
        return fromMenuMap((Map<?, ?>) value);
    }

    private static LocalJMeterMenuRegistry fromMenuMap(Map<?, ?> menus) {
        List<Entry> entries = new ArrayList<>();
        List<Category> categories = new ArrayList<>();
        Map<String, String> categoryLabels = new LinkedHashMap<>();
        for (String rawGroup : LocalComponentCategoryCatalog.knownRawGroups()) {
            categoryLabels.put(rawGroup, resolveCategoryLabel(rawGroup));
        }
        for (Map.Entry<?, ?> menu : menus.entrySet()) {
            if (!(menu.getKey() instanceof String) || !(menu.getValue() instanceof List)) {
                throw incompatible("getMenuMap() contains an incompatible group shape", null);
            }
            String group = (String) menu.getKey();
            int count = 0;
            for (Object menuInfo : (List<?>) menu.getValue()) {
                if (menuInfo == null || isSeparator(menuInfo)) {
                    continue;
                }
                entries.add(readEntry(group, menuInfo));
                count++;
            }
            if (count > 0) {
                String label = categoryLabels.get(group);
                if (label == null) {
                    label = resolveCategoryLabel(group);
                    categoryLabels.put(group, label);
                }
                categories.add(new Category(group, label, count));
            }
        }
        return new LocalJMeterMenuRegistry(entries, categories, categoryLabels);
    }

    private static String resolveCategoryLabel(String group) {
        String fallback = LocalComponentCategoryCatalog.fallbackLabel(group).orElse(null);
        return LocalJMeterLabelResolver.resolve(
                JMeterUtils.RES_KEY_PFX + group + "]", group, null, fallback);
    }

    private static Entry readEntry(String group, Object menuInfo) {
        try {
            Method getLabel = menuInfo.getClass().getMethod("getLabel");
            Method getClassName = menuInfo.getClass().getMethod("getClassName");
            getLabel.setAccessible(true);
            getClassName.setAccessible(true);
            Object label = getLabel.invoke(menuInfo);
            Object className = getClassName.invoke(menuInfo);
            if (!(label instanceof String) || !(className instanceof String)) {
                throw incompatible("MenuInfo accessors returned incompatible values", null);
            }
            return entry(group, LocalJMeterLabelResolver.resolve(
                    (String) label, null, (String) className, null), (String) className, menuInfo);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw incompatible("required public MenuInfo accessors are unavailable", exception);
        }
    }

    private static Entry entry(String group, String label, String menuClassName, Object menuInfo) {
        Class<?> menuClass;
        try {
            menuClass = Class.forName(menuClassName, false, LocalJMeterValidationWorker.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError exception) {
            throw incompatible("registered MenuInfo class cannot be loaded: " + menuClassName, exception);
        }
        RegistrationKind kind;
        if (menuClass.getAnnotation(TestElementMetadata.class) != null
                && TestElement.class.isAssignableFrom(menuClass)) {
            kind = RegistrationKind.METADATA_TEST_ELEMENT;
        } else if (TestBean.class.isAssignableFrom(menuClass)) {
            kind = RegistrationKind.TEST_BEAN;
        } else if (JMeterGUIComponent.class.isAssignableFrom(menuClass)) {
            kind = RegistrationKind.GUI_COMPONENT;
        } else {
            throw incompatible("registered MenuInfo class has no supported registration kind: " + menuClassName, null);
        }
        Class<?> fallback = TestElement.class.isAssignableFrom(menuClass)
                && !Modifier.isAbstract(menuClass.getModifiers()) ? menuClass : null;
        return new Entry(group, label, menuClassName, kind, fallback, menuInfo);
    }

    private static boolean isSeparator(Object menuInfo) {
        return "MenuSeparatorInfo".equals(menuInfo.getClass().getSimpleName())
                || menuInfo.getClass().getSimpleName().endsWith("SeparatorInfo");
    }

    private static IncompatibleRegistryException incompatible(String detail, Throwable cause) {
        return new IncompatibleRegistryException("JMeter MenuFactory registry incompatible: " + detail, cause);
    }

    List<Entry> entries() {
        return entries;
    }

    List<Category> categories() {
        return categories;
    }

    Map<String, String> categoryLabels() {
        return categoryLabels;
    }

    Optional<Entry> resolve(String component) {
        List<Entry> matches = byComponent.get(component);
        return matches != null && matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    Optional<String> resolveActualClass(String actualClass) {
        List<Entry> matches = byActualClass.get(actualClass);
        if (matches == null || matches.isEmpty()) {
            return Optional.empty();
        }
        String component = matches.get(0).component();
        for (Entry match : matches) {
            if (!component.equals(match.component())) {
                throw incompatible(
                        "actual TestElement class has ambiguous registered identities: "
                                + actualClass,
                        null);
            }
        }
        return Optional.of(component);
    }

    RuntimeComponentIdentityResolver identityResolver() {
        return identityResolver;
    }

    boolean owns(Entry entry) {
        for (Entry candidate : entries) {
            if (candidate == entry) {
                return true;
            }
        }
        return false;
    }

    static final class Entry {
        private final String group;
        private final String label;
        private final String menuClassName;
        private final RegistrationKind kind;
        private final Class<?> fallbackTestElementClass;
        private final Object menuInfo;
        private RuntimeComponentIdentityResolver identityResolver;

        private Entry(String group, String label, String menuClassName, RegistrationKind kind,
                Class<?> fallbackTestElementClass, Object menuInfo) {
            this.group = group;
            this.label = label;
            this.menuClassName = menuClassName;
            this.kind = kind;
            this.fallbackTestElementClass = fallbackTestElementClass;
            this.menuInfo = menuInfo;
        }

        String group() { return group; }
        String label() { return label; }
        String component() { return menuClassName; }
        String menuClassName() { return menuClassName; }
        RegistrationKind kind() { return kind; }
        Class<?> fallbackTestElementClass() { return fallbackTestElementClass; }
        RuntimeComponentIdentityResolver identityResolver() {
            if (identityResolver == null) {
                throw new IllegalStateException("Component registration is not attached to a runtime registry");
            }
            return identityResolver;
        }

        private void attach(RuntimeComponentIdentityResolver resolver) {
            if (identityResolver != null) {
                throw new IllegalStateException("Component registration is already attached");
            }
            identityResolver = resolver;
        }

        boolean addEnabled() {
            try {
                Method getEnabled = menuInfo.getClass().getMethod("getEnabled", String.class);
                getEnabled.setAccessible(true);
                return ((Boolean) getEnabled.invoke(menuInfo, org.apache.jmeter.gui.action.ActionNames.ADD)).booleanValue();
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException
                    | ClassCastException exception) {
                throw incompatible("required MenuInfo.getEnabled(String) capability is unavailable", exception);
            }
        }
    }

    static final class Category {
        private final String group;
        private final String label;
        private final int componentCount;

        Category(String group, String label, int componentCount) {
            this.group = group;
            this.label = label;
            this.componentCount = componentCount;
        }

        String group() { return group; }
        String label() { return label; }
        int componentCount() { return componentCount; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Category)) return false;
            Category category = (Category) other;
            return componentCount == category.componentCount
                    && group.equals(category.group) && label.equals(category.label);
        }

        @Override
        public int hashCode() {
            return Objects.hash(group, label, componentCount);
        }

        @Override
        public String toString() {
            return group + "(" + componentCount + ")";
        }
    }

    static final class IncompatibleRegistryException extends IllegalStateException {
        private IncompatibleRegistryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
