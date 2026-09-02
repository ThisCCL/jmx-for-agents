package io.github.thisccl.j4a.validation;

public final class UnknownComponentCategoryException extends IllegalArgumentException {
    public static final String SUGGESTED_ACTION =
            "rerun categories ls to list valid category ids, then retry components --category.";

    public UnknownComponentCategoryException(String category) {
        super("Unknown component category: " + category);
    }
}
