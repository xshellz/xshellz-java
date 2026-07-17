package com.xshellz.sandbox;

/**
 * {@link Sandbox#runCode(String, String)} was called with a language it does
 * not know how to run. The message lists the supported languages.
 */
public class UnsupportedLanguageException extends XshellzException {

    public UnsupportedLanguageException(String message) {
        super(message);
    }
}
