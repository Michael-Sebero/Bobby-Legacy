package com.michaelsebero.bobby.ext;

import com.michaelsebero.bobby.compat.IChunkStatusListener;

public interface ChunkProviderClientExt {
    IChunkStatusListener bobby_getListener();
    void bobby_suppressListener();
    IChunkStatusListener bobby_restoreListener();
}