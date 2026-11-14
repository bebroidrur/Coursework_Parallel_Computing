package com.coursework.backend.index;

/** Failed or interrupted build; the previously published index remains active. */
public final class IndexBuildException extends RuntimeException {
    public IndexBuildException(String message, Throwable cause) { super(message, cause); }
}
