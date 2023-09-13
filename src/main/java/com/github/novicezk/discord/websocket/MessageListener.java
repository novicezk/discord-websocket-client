package com.github.novicezk.discord.websocket;

import org.json.JSONObject;

public interface MessageListener {
	void onMessage(JSONObject message) throws Exception;

	default void onClose(int code, String reason) {
	}

}
