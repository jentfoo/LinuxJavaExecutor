package com.jentfoo.exec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedList;

public class ProcessStream extends InputStream {
  private final LinkedList<ByteBuffer> dataStream;
  private boolean closed;
  
  protected ProcessStream() {
    dataStream = new LinkedList<ByteBuffer>();
    closed = false;
  }
  
  // should be synchronized on this before calling
  private void blockTillReadyToRead() {
    while (! closed && dataStream.isEmpty()) {
      try {
        this.wait();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  @Override
  public int read() throws IOException {
    synchronized (this) {
      blockTillReadyToRead();
      
      int result;
      if (! dataStream.isEmpty()) {
        ByteBuffer nextBuffer = dataStream.getFirst();
        result = nextBuffer.get() & 0xFF;
        if (! nextBuffer.hasRemaining()) {
          dataStream.removeFirst();
        }
      } else {  // stream is closed
        result = -1;
      }
      return result;
    }
  }

  @Override
  public int read(byte[] bytes) throws IOException {
    return read(bytes, 0, bytes.length);
  }

  @Override
  public int read(byte b[], int off, int len) throws IOException {
    if (b.length < off) {
      throw new IndexOutOfBoundsException("offset beyond array length");
    } else if (off + len > b.length) {
      throw new IndexOutOfBoundsException("length is beyond array length");
    }
    
    synchronized (this) {
      blockTillReadyToRead();
      
      int result;
      if (! dataStream.isEmpty()) {
        result = 0;
        while (! dataStream.isEmpty() && result < len) {
          ByteBuffer nextBuffer = dataStream.getFirst();
          int amountToCopy = Math.min(nextBuffer.remaining(), len - result);
          
          nextBuffer.get(b, off + result, amountToCopy);
          
          if (! nextBuffer.hasRemaining()) {
            dataStream.removeFirst();
          }
          
          result += amountToCopy;
        }
      } else {  // stream is closed
        result = -1;
      }
      return result;
    }
  }
  
  @Override
  public int available() {
    synchronized (this) {
      int result = 0;
      
      Iterator<ByteBuffer> it = dataStream.iterator();
      while (it.hasNext()) {
        result += it.next().remaining();
      }
      
      return result;
    }
  }

  public void append(byte[] data) {
    append(data, 0, data.length);
  }

  public void append(byte[] data, int offset, int length) {
    if (data.length < offset) {
      throw new IndexOutOfBoundsException("offset beyond array length");
    } else if (offset + length > data.length) {
      throw new IndexOutOfBoundsException("length is beyond array length");
    }
    
    synchronized (this) {
      ByteBuffer newBuffer = ByteBuffer.allocate(length);
      newBuffer.put(data, offset, length);
      newBuffer.flip();
      
      dataStream.addLast(newBuffer);
      
      this.notifyAll();
    }
  }
  
  @Override
  public void close() {
    synchronized (this) {
      closed = true;
      
      this.notifyAll();
    }
  }
  
  public boolean isClosed() {
    synchronized (this) {
      return closed;
    }
  }
  
  public OutputStream getOutputStream() {
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        append(new byte[] { (byte)b });
      }

      @Override
      public void write(byte[] bytes) throws IOException {
        write(bytes, 0, bytes.length);
      }

      @Override
      public void write(byte b[], int off, int len) throws IOException {
        append(b, off, len);
      }
      
      @Override
      public void close() {
        ProcessStream.this.close();
      }
    };
  }
}
