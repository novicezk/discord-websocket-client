package com.github.novicezk.discord.compress;


import com.github.novicezk.discord.enums.Compression;

import java.util.zip.DataFormatException;

public interface Decompressor {
	Compression type();

	void reset();

	byte[] decompress(byte[] data) throws DataFormatException;
}
