package io.github.novicezk.discord.util;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.TimedCache;
import cn.hutool.core.thread.ThreadUtil;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AsyncLockUtils {
	private static final TimedCache<String, LockObject> LOCK_MAP = CacheUtil.newTimedCache(Duration.ofDays(1).toMillis());

	private AsyncLockUtils() {
	}

	public static synchronized LockObject getLock(String key) {
		return LOCK_MAP.get(key);
	}

	public static LockObject waitForLock(String key, Duration duration) throws TimeoutException {
		LockObject lockObject;
		synchronized (LOCK_MAP) {
			if (!LOCK_MAP.containsKey(key)) {
				LOCK_MAP.put(key, new LockObject(key));
			}
			lockObject = LOCK_MAP.get(key);
		}
		Future<?> future = ThreadUtil.execAsync(() -> {
			try {
				lockObject.sleep();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		try {
			future.get(duration.toMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			// do nothing
		} catch (TimeoutException e) {
			future.cancel(true);
			throw new TimeoutException("Wait Timeout");
		} finally {
			LOCK_MAP.remove(lockObject.getId());
		}
		return lockObject;
	}

	public static class LockObject {
		private String id;

		private Map<String, Object> properties;
		private final transient Object lock = new Object();

		public LockObject(String id) {
			this.id = id;
		}

		public String getId() {
			return this.id;
		}

		public void sleep() throws InterruptedException {
			synchronized (this.lock) {
				this.lock.wait();
			}
		}

		public void awake() {
			synchronized (this.lock) {
				this.lock.notifyAll();
			}
		}

		public LockObject setProperty(String name, Object value) {
			getProperties().put(name, value);
			return this;
		}

		public Object getProperty(String name) {
			return getProperties().get(name);
		}

		public <T> T getProperty(String name, Class<T> clz) {
			return getProperty(name, clz, null);
		}

		public <T> T getProperty(String name, Class<T> clz, T defaultValue) {
			Object value = getProperty(name);
			return value == null ? defaultValue : clz.cast(value);
		}

		public Map<String, Object> getProperties() {
			if (this.properties == null) {
				this.properties = new HashMap<>();
			}
			return this.properties;
		}

	}
}
