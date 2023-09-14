package io.github.novicezk.discord.compress;


import io.github.novicezk.discord.enums.Compression;

import java.util.zip.DataFormatException;

public interface Decompressor {
	Compression type();

	void reset();

	byte[] decompress(byte[] data) throws DataFormatException;
}
