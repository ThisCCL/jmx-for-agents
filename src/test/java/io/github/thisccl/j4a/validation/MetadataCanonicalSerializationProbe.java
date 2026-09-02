package io.github.thisccl.j4a.validation;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class MetadataCanonicalSerializationProbe {
    private MetadataCanonicalSerializationProbe() { }

    public static void main(String[] args) throws Exception {
        Path home = Paths.get(args[0]);
        Path xml = Paths.get(args[1]);
        LocalJMeterWorkerJmx.initializeJMeter(home);
        LocalJMeterMenuRegistry.Entry entry = LocalJMeterMenuRegistry
                .reflect(LocalApplyRuntimeAuthorityTest.MetadataMenuFactory.class)
                .resolve(org.apache.jmeter.config.CSVDataSet.class.getName()).get();
        org.apache.jmeter.testelement.TestElement element = LocalJMeterElementMaterializer.create(entry);

        System.out.println("testBean=" + org.apache.jmeter.testbeans.TestBean.class.isAssignableFrom(element.getClass()));
        System.out.println("class=" + element.getClass().getName());
        System.out.println("testClass=" + element.getPropertyAsString(
                org.apache.jmeter.testelement.TestElement.TEST_CLASS));
        System.out.println("guiClass=" + element.getPropertyAsString(
                org.apache.jmeter.testelement.TestElement.GUI_CLASS));
        System.out.println("name=" + element.getName());
        System.out.println("enabled=" + element.isEnabled());
        System.out.println("filenameInitiallyAbsent=" + (element.getPropertyOrNull("filename") == null));
        System.out.println("delimiter=" + element.getPropertyAsString("delimiter"));
        System.out.println("recycle=" + element.getPropertyAsBoolean("recycle"));
        System.out.println("stopThread=" + element.getPropertyAsBoolean("stopThread"));
        System.out.println("shareMode=" + element.getPropertyAsString("shareMode"));

        element.setProperty("filename", "data.csv");
        org.apache.jorphan.collections.ListedHashTree tree = new org.apache.jorphan.collections.ListedHashTree();
        tree.add(element);
        try (OutputStream output = Files.newOutputStream(xml)) {
            org.apache.jmeter.save.SaveService.saveTree(tree, output);
        }
    }
}
