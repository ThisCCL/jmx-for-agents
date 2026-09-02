package io.github.thisccl.j4a.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

final class LocalJMeterWorkerOrderCapability {
    interface Proof {
        void verify() throws Exception;
    }

    private enum Status {
        UNCHECKED,
        PROVEN,
        UNPROVEN
    }

    private static final String ROOT = "j4a-order-proof-root";
    private static final String FIRST = "j4a-order-proof-first";
    private static final String SECOND = "j4a-order-proof-second";
    private static final String FIRST_CHILD = "j4a-order-proof-first-child";
    private static final String SECOND_CHILD = "j4a-order-proof-second-child";

    private final Proof proof;
    private Status status = Status.UNCHECKED;
    private ReferenceFailure failure;

    LocalJMeterWorkerOrderCapability(Proof proof) {
        if (proof == null) {
            throw new NullPointerException("proof");
        }
        this.proof = proof;
    }

    static LocalJMeterWorkerOrderCapability production() {
        return new LocalJMeterWorkerOrderCapability(new Proof() {
            @Override
            public void verify() throws Exception {
                proveSaveServiceEncounterOrder();
            }
        });
    }

    synchronized void requireProven() throws ReferenceFailure {
        if (status == Status.PROVEN) {
            return;
        }
        if (status == Status.UNPROVEN) {
            throw failure;
        }
        try {
            proof.verify();
            status = Status.PROVEN;
        } catch (ReferenceFailure exception) {
            status = Status.UNPROVEN;
            failure = exception;
            throw failure;
        } catch (Exception exception) {
            status = Status.UNPROVEN;
            failure = ReferenceFailure.orderUnproven(exception);
            throw failure;
        } catch (LinkageError error) {
            status = Status.UNPROVEN;
            failure = ReferenceFailure.orderUnproven(error);
            throw failure;
        }
    }

    private static void proveSaveServiceEncounterOrder() throws Exception {
        ListedHashTree source = new ListedHashTree();
        TestPlan root = new TestPlan(ROOT);
        root.setEnabled(true);
        root.setProperty(TestElement.TEST_CLASS, TestPlan.class.getName());
        root.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.TestPlanGui");
        HashTree siblings = source.add(root);
        GenericController first = controller(FIRST);
        GenericController second = controller(SECOND);
        siblings.add(first).add(controller(FIRST_CHILD));
        siblings.add(second).add(controller(SECOND_CHILD));

        Path candidate = Files.createTempFile("jmx-agent-worker-order-proof-", ".jmx");
        try {
            LocalJMeterWorkerJmx.save(new io.github.thisccl.j4a.jmx.JmxTestPlan(source), candidate);
            HashTree reloaded = SaveService.loadTree(candidate.toFile());
            Object loadedRoot = requireSingle(reloaded.list(), ROOT, TestPlan.class);
            HashTree loadedSiblings = reloaded.getTree(loadedRoot);
            List<Object> orderedSiblings = new ArrayList<Object>(loadedSiblings.list());
            requireOrdered(orderedSiblings, FIRST, SECOND);
            requireSingle(loadedSiblings.getTree(orderedSiblings.get(0)).list(), FIRST_CHILD,
                    GenericController.class);
            requireSingle(loadedSiblings.getTree(orderedSiblings.get(1)).list(), SECOND_CHILD,
                    GenericController.class);
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    private static GenericController controller(String name) {
        GenericController controller = new GenericController();
        controller.setName(name);
        controller.setEnabled(true);
        controller.setProperty(TestElement.TEST_CLASS, GenericController.class.getName());
        controller.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.LogicControllerGui");
        return controller;
    }

    private static Object requireSingle(
            java.util.Collection<?> values, String expectedName, Class<?> expectedClass) {
        if (values.size() != 1) {
            throw new IllegalStateException("Expected one order-proof node but found " + values.size());
        }
        Object value = values.iterator().next();
        requireElement(value, expectedName, expectedClass);
        return value;
    }

    private static void requireOrdered(List<Object> values, String firstName, String secondName) {
        if (values.size() != 2) {
            throw new IllegalStateException("Expected two order-proof siblings but found " + values.size());
        }
        requireElement(values.get(0), firstName, GenericController.class);
        requireElement(values.get(1), secondName, GenericController.class);
    }

    private static void requireElement(Object value, String expectedName, Class<?> expectedClass) {
        if (!expectedClass.equals(value.getClass())
                || !(value instanceof TestElement)
                || !expectedName.equals(((TestElement) value).getName())) {
            throw new IllegalStateException("SaveService encounter order or subtree association changed at "
                    + expectedName);
        }
    }
}
