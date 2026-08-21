package com.example.platform.exception;

public class RepositoryCloneException extends RuntimeException {
    public RepositoryCloneException(String message) {
        super(message);
    }
    public RepositoryCloneException(String message, Throwable cause) {
        super(message, cause);
    }
}