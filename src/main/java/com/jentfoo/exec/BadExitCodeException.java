package com.jentfoo.exec;

public class BadExitCodeException extends RuntimeException {
  private static final long serialVersionUID = -3308322649397376264L;
  
  public final int exitCode;
  
  public BadExitCodeException(int exitCode) {
    this(exitCode, null);
  }
  
  public BadExitCodeException(int exitCode, String message) {
    super(message);
    this.exitCode = exitCode;
  }
}