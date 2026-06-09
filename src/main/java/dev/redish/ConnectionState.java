package dev.redish;

import java.nio.ByteBuffer;

class ConnectionState{
    static final int BUFFER_SIZE = 1024 * 8;

    ByteBuffer readBuf= ByteBuffer.allocate(BUFFER_SIZE);
    ByteBuffer writeBuf= ByteBuffer.allocate(BUFFER_SIZE);
}


    
