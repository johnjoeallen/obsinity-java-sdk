package com.obsinity.telemetry.dispatch;

/** Thrown when a parameter binding fails (missing required key, bad format, etc.). */
public final class AttrBindingException extends RuntimeException {
	private final String key;
	public AttrBindingException(String key, String message) {
		super(message, null, true, false);
		this.key = key;
	}
	public String key() { return key; }
	@Override public synchronized Throwable fillInStackTrace() { return this; }
}
