package com.coursework.cli;

import java.io.IOException;

public class BackendException extends IOException {
    private final int statusCode;
    public BackendException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
    public int statusCode() { return statusCode; }
}
