package com.xshellz.sandbox;

/**
 * {@link Sandbox#getOrCreate(String)} found an existing sandbox with the
 * requested name but no private key for it: none was passed explicitly and
 * the keystore has no entry (or is disabled). The message says where a key
 * was expected.
 */
public class MissingKeyException extends XshellzException {

    public MissingKeyException(String message) {
        super(message);
    }
}
