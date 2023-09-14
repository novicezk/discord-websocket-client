package io.github.novicezk.discord.compress;

import io.github.novicezk.discord.enums.Compression;


public class NoneDecompressor implements Decompressor {
	@Override
	public Compression type() {
		return Compression.NONE;
	}

	@Override
	public void reset() {
	}

	@Override
	public byte[] decompress(byte[] data) {
		return data;
	}
}