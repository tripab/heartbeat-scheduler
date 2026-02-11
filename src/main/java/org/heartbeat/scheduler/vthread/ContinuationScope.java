package org.heartbeat.scheduler.vthread;

import java.util.Objects;

/**
 * Scoped continuation support for Heartbeat tasks.
 * Wraps the concept of a continuation scope.
 * <p>
 * In the paper's terminology, this represents the scope in which
 * parallel computations can yield and be promoted.
 */
public record ContinuationScope(String name, ContinuationScope parent) {
    /**
     * Create a new continuation scope with the given name.
     */
    public ContinuationScope(String name) {
        this(name, null);
    }

    /**
     * Create a new continuation scope with a parent scope.
     * Allows for nested scopes.
     */
    public ContinuationScope {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Scope name cannot be null or empty");
        }
    }

    /**
     * Get the name of this scope.
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * Get the parent scope, or null if this is a root scope.
     */
    @Override
    public ContinuationScope parent() {
        return parent;
    }

    /**
     * Check if this scope has a parent.
     */
    public boolean hasParent() {
        return parent != null;
    }

    /**
     * Get the full scope path (parent.parent.name).
     */
    public String getFullPath() {
        if (parent == null) {
            return name;
        }
        return parent.getFullPath() + "." + name;
    }

    @Override
    public String toString() {
        return "ContinuationScope[" + getFullPath() + "]";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ContinuationScope other)) return false;

        return name.equals(other.name) &&
                (Objects.equals(parent, other.parent));
    }

    /**
     * Convert to JDK internal scope for actual continuation usage.
     * This requires access to jdk.internal.vm.
     */
    jdk.internal.vm.ContinuationScope toJdkScope() {
        return new jdk.internal.vm.ContinuationScope(name);
    }

    /**
     * Create a default scope for heartbeat tasks.
     */
    public static ContinuationScope createDefault() {
        return new ContinuationScope("heartbeat-default");
    }

    /**
     * Create a named scope for a specific task.
     */
    public static ContinuationScope forTask(String taskName) {
        return new ContinuationScope("task-" + taskName);
    }
}
