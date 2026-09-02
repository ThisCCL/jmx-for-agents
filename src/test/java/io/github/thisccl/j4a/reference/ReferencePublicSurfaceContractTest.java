package io.github.thisccl.j4a.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ReferencePublicSurfaceContractTest {
    @Test
    void callerFabricatedHandleCannotBecomeAResolution() {
        ResolvedNodeHandle fabricated = new ResolvedNodeHandle() { };

        assertThatThrownBy(() -> ReferenceResolution.resolved(fabricated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Resolved node handle is not owned by this reference module");

        Class<?> implementation = io.github.thisccl.j4a.validation.ExactNodeHandle.class;
        assertThat(Arrays.stream(implementation.getDeclaredConstructors()))
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers()));
        assertThat(Arrays.stream(implementation.getDeclaredMethods()))
                .noneMatch(method -> Modifier.isPublic(method.getModifiers()));
        assertThat(Arrays.stream(implementation.getDeclaredFields()))
                .noneMatch(field -> Modifier.isPublic(field.getModifiers()));
    }

    @Test
    void referencePackageExportsOnlyTheSharedCompositionSeam() throws Exception {
        Set<String> publicTypes = new TreeSet<String>();
        URI classesRoot = ComponentReferences.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        File packageDirectory = new File(classesRoot).toPath()
                .resolve("io/github/thisccl/j4a/reference")
                .toFile();
        File[] classFiles = packageDirectory.listFiles();
        assertThat(classFiles).isNotNull();
        for (File classFile : classFiles) {
            String name = classFile.getName();
            if (name.endsWith(".class") && name.indexOf('$') < 0) {
                Class<?> type = Class.forName(getClass().getPackage().getName()
                        + "." + name.substring(0, name.length() - ".class".length()));
                if (Modifier.isPublic(type.getModifiers())) {
                    publicTypes.add(type.getSimpleName());
                }
            }
        }

        assertThat(publicTypes).isEqualTo(new TreeSet<String>(Arrays.asList(
                "BoundReferences",
                "ComponentReferences",
                "ReferenceResolution",
                "ResolvedNodeHandle")));
    }

    @Test
    void publicReferenceSignaturesAreOpaqueToMutableImplementationTypes() {
        for (Class<?> type : Arrays.<Class<?>>asList(
                BoundReferences.class,
                ComponentReferences.class,
                ReferenceResolution.class,
                ResolvedNodeHandle.class)) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    assertOpaqueTypes(constructor.getGenericParameterTypes());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    assertOpaqueTypes(method.getGenericParameterTypes());
                    assertOpaqueType(method.getGenericReturnType());
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers())) {
                    assertOpaqueType(field.getGenericType());
                }
            }
        }
    }

    private static void assertOpaqueTypes(Type[] types) {
        for (Type type : types) {
            assertOpaqueType(type);
        }
    }

    private static void assertOpaqueType(Type type) {
        if (type instanceof Class<?>) {
            Class<?> inspected = (Class<?>) type;
            if (inspected.isArray()) {
                assertOpaqueType(inspected.getComponentType());
                return;
            }
            Package packageValue = inspected.getPackage();
            String packageName = packageValue == null ? "" : packageValue.getName();
            assertThat(packageName.startsWith("org.apache.jmeter")
                    || packageName.startsWith("org.apache.jorphan"))
                    .as("public reference signature must not expose mutable implementation type %s", inspected.getName())
                    .isFalse();
            if (packageName.startsWith("io.github.thisccl.j4a")) {
                assertThat(Modifier.isPublic(inspected.getModifiers()))
                        .as("public signature type %s", inspected.getName())
                        .isTrue();
                assertThat(allowedProjectSignatureTypes()).contains(inspected);
            }
            return;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            assertOpaqueType(parameterized.getRawType());
            assertOpaqueTypes(parameterized.getActualTypeArguments());
            return;
        }
        if (type instanceof GenericArrayType) {
            assertOpaqueType(((GenericArrayType) type).getGenericComponentType());
            return;
        }
        if (type instanceof WildcardType) {
            WildcardType wildcard = (WildcardType) type;
            assertOpaqueTypes(wildcard.getLowerBounds());
            assertOpaqueTypes(wildcard.getUpperBounds());
            return;
        }
        if (type instanceof TypeVariable<?>) {
            assertOpaqueTypes(((TypeVariable<?>) type).getBounds());
        }
    }

    private static Set<Class<?>> allowedProjectSignatureTypes() {
        return new HashSet<Class<?>>(Arrays.<Class<?>>asList(
                BoundReferences.class,
                ComponentReferences.class,
                ReferenceResolution.class,
                ReferenceResolution.Status.class,
                ReferenceResolution.UnavailableReason.class,
                ResolvedNodeHandle.class));
    }
}
