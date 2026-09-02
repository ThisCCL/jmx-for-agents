package io.github.thisccl.j4a.jmx;

public final class JmxSourceRange {
    private final int startLine;
    private final int endLine;

    public JmxSourceRange(int startLine, int endLine) {
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be 1 or greater");
        }
        if (endLine < startLine) {
            throw new IllegalArgumentException("endLine must be greater than or equal to startLine");
        }
        this.startLine = startLine;
        this.endLine = endLine;
    }

    public int startLine() {
        return startLine;
    }

    public int endLine() {
        return endLine;
    }
}
