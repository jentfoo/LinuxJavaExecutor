package com.jentfoo.exec;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;

public class ExecutorTest {
  public static void main(String args[]) throws IOException, InterruptedException, ExecutionException {
    if (args.length != 3) {
      throw new IllegalArgumentException("Expecting 3 args");
    }
    
    final String[] testCommand = {"/bin/dash", "-c", 
      		                   "ionice -c 2 -n 7 nice -19 /usr/bin/avconv -threads 2 -loop 1 -i " +
      		                   "'" + args[0] + "'  -vcodec rawvideo -f yuv4mpegpipe -pix_fmt yuv420p - | " +
      				               "ionice -c 2 -n 7 nice -19 /usr/bin/x264 -I 5 --demuxer y4m --fps 10.0 --input-res 640x480 -o - - | " +
      				               "ionice -c 2 -n 7 nice -19 /usr/bin/avconv -threads 2 -i '" + args[1] + 
      				               "' -i - -shortest -vcodec copy -acodec libfaac -b:a 128k -ar 22050 -y '" + args[2] + "'"
                           };
    
    System.out.println("Starting command: " + testCommand[2]);
    Process p = Runtime.getRuntime().exec(testCommand);
    
    InputStream in = p.getErrorStream();
    int val;
    while ((val = in.read()) != -1) {
      System.err.print((char)val);
    }
    int result = p.waitFor();
    System.out.println("Exited with result: " + result);
  }
}
