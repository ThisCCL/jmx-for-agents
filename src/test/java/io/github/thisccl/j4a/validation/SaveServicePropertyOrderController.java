package io.github.thisccl.j4a.validation;

import org.apache.jmeter.control.GenericController;

public final class SaveServicePropertyOrderController extends GenericController {
    static final String CONSTRUCTOR_PROPERTY = "qa.constructor.property";

    public SaveServicePropertyOrderController() {
        setProperty(CONSTRUCTOR_PROPERTY, "constructor-value");
    }
}
