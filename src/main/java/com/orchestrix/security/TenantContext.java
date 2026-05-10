package com.orchestrix.security;

import com.orchestrix.domain.entity.Tenant;

/**
 * Thread-local holder for the authenticated tenant.
 * Filters set this; downstream services read it. Cleared in finally blocks.
 */
public final class TenantContext {

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Tenant tenant) {
        CURRENT.set(tenant);
    }

    public static Tenant get() {
        return CURRENT.get();
    }

    public static Tenant require() {
        Tenant t = CURRENT.get();
        if (t == null) {
            throw new IllegalStateException("tenant context not set");
        }
        return t;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
