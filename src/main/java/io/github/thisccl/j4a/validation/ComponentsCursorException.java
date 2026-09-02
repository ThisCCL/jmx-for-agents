package io.github.thisccl.j4a.validation;

final class ComponentsCursorException extends IllegalArgumentException {
    static final String SUGGESTED_ACTION =
            "start category details again without cursor, then use the returned cursor unchanged.";
    private static final String SIGNING_KEY_ACTION =
            "restore access to the local J4A state directory, then retry category details.";
    private static final String COMPONENT_TOKEN_ACTION =
            "restart category details without cursor, then use the returned componentToken unchanged as the sole selector.";

    private final String suggestedAction;

    ComponentsCursorException(String message) {
        this(message, SUGGESTED_ACTION);
    }

    private ComponentsCursorException(String message, String suggestedAction) {
        super(message);
        this.suggestedAction = suggestedAction;
    }

    static ComponentsCursorException signingKeyUnavailable() {
        return new ComponentsCursorException(
                "Components cursor signing key is unavailable", SIGNING_KEY_ACTION);
    }

    static ComponentsCursorException componentTokenInvalid(String message) {
        return new ComponentsCursorException(message, COMPONENT_TOKEN_ACTION);
    }

    String suggestedAction() {
        return suggestedAction;
    }
}
