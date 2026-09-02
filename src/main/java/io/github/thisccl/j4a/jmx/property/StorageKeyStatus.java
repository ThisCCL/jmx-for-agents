package io.github.thisccl.j4a.jmx.property;

public enum StorageKeyStatus {
    KEY(true),
    NON_KEY(false);

    private final boolean key;

    StorageKeyStatus(boolean key) {
        this.key = key;
    }

    public boolean isKey() {
        return key;
    }
}
