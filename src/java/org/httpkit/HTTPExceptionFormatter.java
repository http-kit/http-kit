package org.httpkit;

public interface HTTPExceptionFormatter {
    public String errorMessage(int status, HTTPException e);
}
