package com.xshellz.sandbox;

/**
 * The account's sandbox quota or plan entitlement blocks the operation.
 *
 * <p>The control plane returns HTTP 403 both when the plan's concurrent
 * sandbox limit is reached ("agent shell limit") and when the plan does not
 * include sandboxes at all. On the free tier the limit is 1 concurrent box -
 * use {@link Sandbox#list()} + {@link Sandbox#connect(String, String)} to
 * attach to the existing one instead of creating a new box.
 */
public class QuotaException extends XshellzException {

    public QuotaException(String message) {
        super(message);
    }
}
