package io.github.thisccl.j4a.jmx;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.ListedHashTree;

public final class JmxInitialPlanFactory {
    public static final String DEFAULT_TEST_PLAN_NAME = "JMX Agent Test Plan";
    public static final String DEFAULT_THREAD_GROUP_NAME = "Thread Group";

    public JmxTestPlan create(String testPlanName, String threadGroupName) {
        TestPlan testPlan = testPlan(testPlanName);
        ThreadGroup threadGroup = threadGroup(threadGroupName);
        ListedHashTree tree = new ListedHashTree();
        ListedHashTree testPlanChildren = tree.add(testPlan);
        testPlanChildren.add(threadGroup);
        return new JmxTestPlan(tree);
    }

    private static TestPlan testPlan(String name) {
        TestPlan testPlan = new TestPlan();
        testPlan.setName(name);
        testPlan.setEnabled(true);
        testPlan.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.TestPlanGui");
        testPlan.setProperty(TestElement.TEST_CLASS, TestPlan.class.getName());
        testPlan.setProperty(new StringProperty("TestPlan.comments", ""));
        testPlan.setFunctionalMode(false);
        testPlan.setSerialized(false);
        testPlan.setTearDownOnShutdown(true);
        testPlan.setUserDefinedVariables(new org.apache.jmeter.config.Arguments());
        testPlan.setProperty(new StringProperty("TestPlan.user_define_classpath", ""));
        return testPlan;
    }

    private static ThreadGroup threadGroup(String name) {
        LoopController controller = new LoopController();
        controller.setName("Loop Controller");
        controller.setEnabled(true);
        controller.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.control.gui.LoopControlPanel");
        controller.setProperty(TestElement.TEST_CLASS, LoopController.class.getName());
        controller.setProperty(new IntegerProperty("LoopController.loops", 1));
        controller.setProperty(new BooleanProperty("LoopController.continue_forever", false));
        controller.initialize();

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName(name);
        threadGroup.setEnabled(true);
        threadGroup.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        threadGroup.setProperty(TestElement.TEST_CLASS, ThreadGroup.class.getName());
        threadGroup.setProperty(new StringProperty("ThreadGroup.on_sample_error", "continue"));
        threadGroup.setProperty(new IntegerProperty("ThreadGroup.num_threads", 1));
        threadGroup.setProperty(new IntegerProperty("ThreadGroup.ramp_time", 1));
        threadGroup.setProperty(new BooleanProperty("ThreadGroup.same_user_on_next_iteration", true));
        threadGroup.setProperty(new BooleanProperty("ThreadGroup.scheduler", false));
        threadGroup.setSamplerController(controller);
        return threadGroup;
    }
}
