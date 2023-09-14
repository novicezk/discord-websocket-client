package io.github.novicezk.discord.compress;

import io.github.novicezk.discord.enums.Compression;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

public class ZlibDecompressor implements Decompressor {
	private static final int Z_SYNC_FLUSH = 0x0000FFFF;

	private final int maxBufferSize;
	private final Inflater inflater = new Inflater();
	private ByteBuffer flushBuffer = null;
	private SoftReference<ByteArrayOutputStream> decompressBuffer = null;

	public ZlibDecompressor() {
		this(2048);
	}

	public ZlibDecompressor(int maxBufferSize) {
		this.maxBufferSize = maxBufferSize;
	}

	@Override
	public Compression type() {
		return Compression.ZLIB;
	}

	@Override
	public void reset() {
		this.inflater.reset();
	}

	@Override
	public byte[] decompress(byte[] data) throws DataFormatException {
		if (!isFlush(data)) {
			buffer(data);
			return null;
		} else if (this.flushBuffer != null) {
			buffer(data);
			byte[] arr = this.flushBuffer.array();
			data = new byte[this.flushBuffer.position()];
			System.arraycopy(arr, 0, data, 0, data.length);
			this.flushBuffer = null;
		}
		ByteArrayOutputStream buffer = getDecompressBuffer();
		try (InflaterOutputStream decompressor = new InflaterOutputStream(buffer, this.inflater)) {
			decompressor.write(data);
			return buffer.toByteArray();
		} catch (IOException e) {
			throw (DataFormatException) new DataFormatException("Malformed").initCause(e);
		} finally {
			if (buffer.size() > this.maxBufferSize) {
				this.decompressBuffer = newDecompressBuffer();
			} else {
				buffer.reset();
			}
		}
	}

	private SoftReference<ByteArrayOutputStream> newDecompressBuffer() {
		return new SoftReference<>(new ByteArrayOutputStream(Math.min(1024, this.maxBufferSize)));
	}

	private ByteArrayOutputStream getDecompressBuffer() {
		if (this.decompressBuffer == null) {
			this.decompressBuffer = newDecompressBuffer();
		}
		ByteArrayOutputStream buffer = this.decompressBuffer.get();
		if (buffer == null) {
			this.decompressBuffer = new SoftReference<>(buffer = new ByteArrayOutputStream(Math.min(1024, maxBufferSize)));
		}
		return buffer;
	}

	private boolean isFlush(byte[] data) {
		if (data.length < 4) {
			return false;
		}
		int suffix = getIntBigEndian(data, data.length - 4);
		return suffix == Z_SYNC_FLUSH;
	}

	private void buffer(byte[] data) {
		if (this.flushBuffer == null) {
			this.flushBuffer = ByteBuffer.allocate(data.length * 2);
		}
		if (this.flushBuffer.capacity() < data.length + this.flushBuffer.position()) {
			this.flushBuffer.flip();
			this.flushBuffer = reallocate(this.flushBuffer, (this.flushBuffer.capacity() + data.length) * 2);
		}
		this.flushBuffer.put(data);
	}

	private int getIntBigEndian(byte[] arr, int offset) {
		return arr[offset + 3] & 0xFF
				| (arr[offset + 2] & 0xFF) << 8
				| (arr[offset + 1] & 0xFF) << 16
				| (arr[offset] & 0xFF) << 24;
	}

	private ByteBuffer reallocate(ByteBuffer original, int length) {
		ByteBuffer buffer = ByteBuffer.allocate(length);
		buffer.put(original);
		return buffer;
	}

}
