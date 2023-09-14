package io.github.novicezk.discord.websocket;


import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.RandomUtil;
import io.github.novicezk.discord.compress.Decompressor;
import io.github.novicezk.discord.compress.NoneDecompressor;
import io.github.novicezk.discord.compress.ZlibDecompressor;
import io.github.novicezk.discord.constants.WebSocketCode;
import io.github.novicezk.discord.enums.Compression;
import io.github.novicezk.discord.exception.WebSocketClientStartException;
import io.github.novicezk.discord.util.AsyncLockUtils;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import eu.bitwalker.useragentutils.UserAgent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class UserWebSocketClient {
	private static final Logger log = LoggerFactory.getLogger(UserWebSocketClient.class);
	private final WebSocketFactory webSocketFactory;
	private final Decompressor decompressor;
	private final Compression compression;
	private final String userToken;
	private final ScheduledExecutorService heartExecutor;
	private final JSONObject authData;
	private final MessageListener listener;
	private final WebSocketAdapter webSocketAdapter;
	private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36";
	private String gatewayVersion = "9";
	private int failRetryLimit = 3;
	private WebSocket socket = null;
	private String resumeGatewayUrl;
	private String sessionId;
	private Future<?> heartbeatInterval;
	private Future<?> heartbeatTimeout;
	private boolean heartbeatAck = false;
	private Object sequence = null;
	private long interval = 41250;
	private boolean trying = false;

	public UserWebSocketClient(String userToken, MessageListener listener) {
		this(Compression.NONE, userToken, listener);
	}

	public UserWebSocketClient(Compression compression, String userToken, MessageListener listener) {
		this.compression = compression;
		this.decompressor = compression == Compression.ZLIB ? new ZlibDecompressor() : new NoneDecompressor();
		this.userToken = userToken;
		this.listener = listener;
		this.webSocketFactory = new WebSocketFactory().setConnectionTimeout(10000);
		this.heartExecutor = Executors.newSingleThreadScheduledExecutor();
		this.authData = createAuthData();
		this.webSocketAdapter = new WebSocketAdapter() {
			@Override
			public void onBinaryMessage(WebSocket websocket, byte[] binary) throws Exception {
				UserWebSocketClient.this.onBinaryMessage(websocket, binary);
			}

			@Override
			public void handleCallbackError(WebSocket websocket, Throwable cause) throws Exception {
				UserWebSocketClient.this.handleCallbackError(websocket, cause);
			}

			@Override
			public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) throws Exception {
				UserWebSocketClient.this.onDisconnected(websocket, serverCloseFrame, clientCloseFrame, closedByServer);
			}
		};
	}

	public UserWebSocketClient setGatewayVersion(String gatewayVersion) {
		this.gatewayVersion = gatewayVersion;
		return this;
	}

	public UserWebSocketClient setUserAgent(String userAgent) {
		this.userAgent = userAgent;
		return this;
	}

	public WebSocketFactory getWebSocketFactory() {
		return this.webSocketFactory;
	}

	public void setFailRetryLimit(int failRetryLimit) {
		this.failRetryLimit = failRetryLimit;
	}

	public void connect() throws WebSocketClientStartException {
		try {
			this.trying = true;
			tryConnect();
			AsyncLockUtils.LockObject lock = AsyncLockUtils.waitForLock("wss:" + this.userToken, Duration.ofSeconds(30));
			if (lock.getProperty("code", Integer.class, 0) != 1) {
				throw new WebSocketClientStartException(lock.getProperty("description", String.class));
			}
		} catch (Exception e) {
			throw new WebSocketClientStartException(e);
		}
	}

	public void disconnect() {
		sendClose(5240, "trigger disconnect");
		clearAllStates();
	}

	private synchronized void tryConnect() throws Exception {
		if (this.decompressor != null) {
			this.decompressor.reset();
		}
		String gatewayServer = Optional.ofNullable(this.resumeGatewayUrl).orElse("wss://gateway.discord.gg");
		String gatewayUrl = String.format("%s/?encoding=json&v=%s", gatewayServer, this.gatewayVersion);
		if (this.compression != Compression.NONE) {
			gatewayUrl += "&compress=" + this.compression.getKey();
		}
		this.socket = this.webSocketFactory.createSocket(gatewayUrl);
		this.socket.addListener(this.webSocketAdapter);
		this.socket.addHeader("Accept-Encoding", "gzip, deflate, br")
				.addHeader("Accept-Language", "zh-CN,zh;q=0.9")
				.addHeader("Cache-Control", "no-cache")
				.addHeader("Pragma", "no-cache")
				.addHeader("Sec-Websocket-Extensions", "permessage-deflate; client_max_window_bits")
				.addHeader("User-Agent", this.userAgent);
		this.socket.connect();
	}

	private void onBinaryMessage(WebSocket websocket, byte[] binary) throws Exception {
		byte[] decompressBinary = this.decompressor.decompress(binary);
		if (decompressBinary == null) {
			return;
		}
		String json = new String(decompressBinary, StandardCharsets.UTF_8);
		JSONObject data = new JSONObject(json);
		int opCode = data.getInt("op");
		switch (opCode) {
			case WebSocketCode.HEARTBEAT:
				log.debug("[wss] Receive heartbeat.");
				handleHeartbeat();
				break;
			case WebSocketCode.HEARTBEAT_ACK:
				this.heartbeatAck = true;
				clearHeartbeatTimeout();
				break;
			case WebSocketCode.HELLO:
				handleHello(data);
				doResumeOrIdentify();
				break;
			case WebSocketCode.RESUME:
				log.debug("[wss] Receive resumed.");
				connectSuccess();
				break;
			case WebSocketCode.RECONNECT:
				sendReconnect("receive server reconnect");
				break;
			case WebSocketCode.INVALIDATE_SESSION:
				sendClose(1009, "receive session invalid");
				break;
			case WebSocketCode.DISPATCH:
				handleDispatch(data);
				break;
			default:
				log.debug("[wss] Receive unknown code: {}.", data);
		}
	}

	private void handleDispatch(JSONObject raw) {
		this.sequence = raw.opt("s");
		String t = raw.getString("t");
		if ("READY".equals(t)) {
			JSONObject content = raw.getJSONObject("d");
			this.sessionId = content.getString("session_id");
			this.resumeGatewayUrl = content.getString("resume_gateway_url");
			log.debug("[wss] Dispatch ready: identify.");
			connectSuccess();
			return;
		} else if ("RESUMED".equals(t)) {
			log.debug("[wss] Dispatch read: resumed.");
			connectSuccess();
			return;
		}
		try {
			this.listener.onMessage(raw);
		} catch (Exception e) {
			log.error("[wss] Handle message error", e);
		}
	}

	private void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) throws Exception {
		int code;
		String closeReason;
		if (closedByServer) {
			code = serverCloseFrame.getCloseCode();
			closeReason = serverCloseFrame.getCloseReason();
		} else {
			code = clientCloseFrame.getCloseCode();
			closeReason = clientCloseFrame.getCloseReason();
		}
		connectFinish(code, closeReason);
		if (this.trying) {
			return;
		}
		if (code == 5240) {
			// 隐式关闭wss
			clearAllStates();
		} else if (code >= 4000) {
			log.warn("[wss] Can't reconnect! Closed by {}({}).", code, closeReason);
			clearAllStates();
			this.listener.onClose(code, closeReason);
		} else if (code == 2001) {
			// reconnect
			log.warn("[wss] Waiting try reconnect...");
			tryReconnect();
		} else {
			log.warn("[wss] Closed by {}({}). Waiting try new connection...", code, closeReason);
			clearAllStates();
			tryNewConnect();
		}
	}

	private void tryReconnect() {
		clearSocketStates();
		try {
			this.trying = true;
			tryStart(true);
		} catch (Exception e) {
			if (e instanceof TimeoutException) {
				sendClose(5240, "try reconnect");
			}
			log.warn("[wss] Reconnect fail: {}, Waiting try new connection...", e.getMessage());
			ThreadUtil.sleep(1000);
			tryNewConnect();
		}
	}

	private void tryNewConnect() {
		this.trying = true;
		for (int i = 1; i <= this.failRetryLimit; i++) {
			clearAllStates();
			try {
				tryStart(false);
				return;
			} catch (Exception e) {
				if (e instanceof TimeoutException) {
					sendClose(5240, "try new connect");
				}
				log.warn("[wss] New connect fail ({}): {}", i, e.getMessage());
				ThreadUtil.sleep(5000);
			}
		}
		log.error("[wss] Close by 4072(Retried more than {} times)", this.failRetryLimit);
		this.listener.onClose(4072, "Retried more than " + this.failRetryLimit + " times");
	}

	public void tryStart(boolean reconnect) throws Exception {
		tryConnect();
		AsyncLockUtils.LockObject lock = AsyncLockUtils.waitForLock("wss:" + this.userToken, Duration.ofSeconds(20));
		int code = lock.getProperty("code", Integer.class, 0);
		if (code == 1) {
			log.debug("[wss] {} success.", reconnect ? "Reconnect" : "New connect");
			return;
		}
		throw new Exception(lock.getProperty("description", String.class));
	}

	private void handleCallbackError(WebSocket websocket, Throwable cause) {
		log.error("[wss] There was some websocket error.", cause);
	}

	private void handleHeartbeat() {
		send(WebSocketCode.HEARTBEAT, this.sequence);
		this.heartbeatTimeout = ThreadUtil.execAsync(() -> {
			ThreadUtil.sleep(this.interval);
			sendReconnect("heartbeat has not ack");
		});
	}

	private void clearHeartbeatTimeout() {
		if (this.heartbeatTimeout != null) {
			this.heartbeatTimeout.cancel(true);
			this.heartbeatTimeout = null;
		}
	}

	private void handleHello(JSONObject data) {
		clearHeartbeatInterval();
		this.interval = data.getJSONObject("d").getLong("heartbeat_interval");
		this.heartbeatAck = true;
		this.heartbeatInterval = this.heartExecutor.scheduleAtFixedRate(() -> {
			if (this.heartbeatAck) {
				this.heartbeatAck = false;
				send(WebSocketCode.HEARTBEAT, this.sequence);
			} else {
				sendReconnect("heartbeat has not ack interval");
			}
		}, (long) Math.floor(RandomUtil.randomDouble(0, 1) * this.interval), this.interval, TimeUnit.MILLISECONDS);
	}

	private void doResumeOrIdentify() {
		if (CharSequenceUtil.isBlank(this.sessionId)) {
			log.debug("[wss] Send identify msg.");
			send(WebSocketCode.IDENTIFY, this.authData);
		} else {
			log.debug("[wss] Send resume msg.");
			send(WebSocketCode.RESUME, new JSONObject().put("token", this.userToken)
					.put("session_id", this.sessionId).put("seq", this.sequence));
		}
	}

	private void clearHeartbeatInterval() {
		if (this.heartbeatInterval != null) {
			this.heartbeatInterval.cancel(true);
			this.heartbeatInterval = null;
		}
	}

	private void clearAllStates() {
		clearSocketStates();
		clearResumeStates();
	}

	private void clearSocketStates() {
		clearHeartbeatTimeout();
		clearHeartbeatInterval();
		this.socket = null;
	}

	private void clearResumeStates() {
		this.sessionId = null;
		this.sequence = null;
		this.resumeGatewayUrl = null;
	}

	private void sendReconnect(String reason) {
		sendClose(2001, reason);
	}

	private void sendClose(int code, String reason) {
		if (this.socket != null) {
			this.socket.sendClose(code, reason);
		}
	}

	private void send(int op, Object d) {
		if (this.socket != null) {
			this.socket.sendText(new JSONObject().put("op", op).put("d", d).toString());
		}
	}

	private void connectSuccess() {
		this.trying = false;
		connectFinish(1, "");
	}

	private void connectFinish(int code, String description) {
		AsyncLockUtils.LockObject lock = AsyncLockUtils.getLock("wss:" + this.userToken);
		if (lock != null) {
			lock.setProperty("code", code);
			lock.setProperty("description", description);
			lock.awake();
		}
	}

	private JSONObject createAuthData() {
		UserAgent agent = UserAgent.parseUserAgentString(this.userAgent);
		JSONObject connectionProperties = new JSONObject()
				.put("browser", agent.getBrowser().getGroup().getName())
				.put("browser_user_agent", this.userAgent)
				.put("browser_version", agent.getBrowserVersion().toString())
				.put("client_build_number", 222963)
				.put("device", "")
				.put("os", agent.getOperatingSystem().getName())
				.put("referring_domain_current", "")
				.put("release_channel", "stable")
				.put("system_locale", "zh-CN");
		JSONObject presence = new JSONObject()
				.put("activities", new JSONArray())
				.put("afk", false)
				.put("since", 0)
				.put("status", "online");
		JSONObject clientState = new JSONObject()
				.put("api_code_version", 0)
				.put("guild_versions", new JSONObject())
				.put("highest_last_message_id", "0")
				.put("private_channels_version", "0")
				.put("read_state_version", 0)
				.put("user_guild_settings_version", -1)
				.put("user_settings_version", -1);
		return new JSONObject()
				.put("capabilities", 16381)
				.put("client_state", clientState)
				.put("compress", false)
				.put("presence", presence)
				.put("properties", connectionProperties)
				.put("token", this.userToken);
	}

}
