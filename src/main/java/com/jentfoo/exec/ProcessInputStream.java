package com.jentfoo.exec;

import java.io.IOException;
import java.io.InputStream;

public class ProcessInputStream extends InputStream {
  protected ProcessInputStream() {
    
  }
  
  @Override
  public int read() throws IOException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public int read(byte[] bytes) throws IOException {
    return read(bytes, 0, bytes.length);
  }

  @Override
  public int read(byte b[], int off, int len) throws IOException {
    // TODO Auto-generated method stub
    return 0;
  }
}
