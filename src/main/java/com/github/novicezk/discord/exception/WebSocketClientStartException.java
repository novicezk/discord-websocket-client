package com.github.novicezk.discord.exception;

public class WebSocketClientStartException extends Exception {

	public WebSocketClientStartException(String message) {
		super(message);
	}

	public WebSocketClientStartException(String message, Throwable cause) {
		super(message, cause);
	}

	public WebSocketClientStartException(Throwable cause) {
		super(cause);
	}
}
