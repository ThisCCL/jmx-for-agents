package io.github.thisccl.j4a.jmx.property;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.beans.SimpleBeanInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElementSchema;
import org.apache.jmeter.testelement.schema.BaseTestElementSchema;
import org.apache.jmeter.testelement.schema.StringPropertyDescriptor;

final class PropertyGraphDiscoveryFixtures {
    private PropertyGraphDiscoveryFixtures() {
    }

    public static final class ConflictHeaderManager extends HeaderManager implements TestBean {
        public String getDeclaredHeaders() {
            return "bean-info-value";
        }

        public void setDeclaredHeaders(String ignored) {
        }
    }

    public static final class ConflictHeaderManagerBeanInfo extends SimpleBeanInfo {
        @Override
        public PropertyDescriptor[] getPropertyDescriptors() {
            try {
                return new PropertyDescriptor[] {new PropertyDescriptor(
                        "HeaderManager.headers",
                        ConflictHeaderManager.class,
                        "getDeclaredHeaders",
                        "setDeclaredHeaders")};
            } catch (IntrospectionException exception) {
                throw new IllegalStateException("Unable to create conflict BeanInfo", exception);
            }
        }
    }

    static final class AdapterIdentityConflictElement extends AbstractTestElement {
    }

    static final class MalformedSchemaElement extends AbstractTestElement {
        private static final MalformedSchema SCHEMA = new MalformedSchema();

        @Override
        public TestElementSchema getSchema() {
            return SCHEMA;
        }
    }

    static final class MalformedSchema extends TestElementSchema {
        MalformedSchema() {
            try {
                Field field = BaseTestElementSchema.class.getDeclaredField("propertyDescriptors");
                field.setAccessible(true);
                Object descriptors = field.get(this);
                Method clear = Map.class.getMethod("clear");
                Method put = Map.class.getMethod("put", Object.class, Object.class);
                clear.invoke(descriptors);
                put.invoke(descriptors, "qa.null", null);
                put.invoke(descriptors, "qa.invalid", "not-a-property-descriptor");
                put.invoke(descriptors, "qa.after", new StringPropertyDescriptor<MalformedSchema>(
                        "after", "qa.after", "schema-default"));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to build malformed schema fixture", exception);
            }
        }
    }
}
