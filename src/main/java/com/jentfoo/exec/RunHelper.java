package com.jentfoo.exec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

import org.threadly.util.ExceptionUtils;
import org.threadly.util.StringUtils;

public class RunHelper {
  private static final boolean VERBOSE = false;
  private static final int STD_BUFFER_SIZE = 1024;
  private static final String EXEC_NOTIFY_STRING = "b675817dbcb7675b93341b69991ddaf39ff7c80a"; // echo "RUNNING FOR THE WIN" | sha1sum -
  private static int MAX_CONCURRENT_FORKS = 1;
  private static String DEFAULT_SHELL = "/bin/dash";
  private static final String SHELL_EXECUTE_FLAG = "-c";
  
  /**
   * this does not limit how many programs can be running at once, 
   * but rather how many can be forked at a time before exec is called 
   * This is to prevent the memory overhead of fork being called 
   * multiple times before exec has been called to reduce the memory pressure
   * 
   * @param val maximum processes that can be forked but not exec'ed
   */
  public static void setMaxConcurrentForks(int val) {
    if (val < 1) {
      throw new IllegalArgumentException("must be >= 1");
    }
    
    MAX_CONCURRENT_FORKS = val;
  }
  
  public static void setDefaultShell(String path) {
    path = path == null ? null : path.trim();
    if (path == null || path.length() == 0) {
      throw new IllegalArgumentException("Must provide a valid shell path");
    }
    // TODO - do we want to verify it exists and can execute?
    
    DEFAULT_SHELL = path;
  }
  
  public static RunningProcess execCommand(Executor executor, 
                                           String command, 
                                           boolean storeStdOut) throws IOException, 
                                                                       InterruptedException {
    return execCommand(executor, command, storeStdOut, false);
  }
  
  public static RunningProcess execCommand(Executor executor, 
                                           String command, 
                                           boolean storeStdOut, 
                                           boolean forceLog) throws IOException, 
                                                                    InterruptedException {
    String[] shellCommand = {DEFAULT_SHELL,
                             SHELL_EXECUTE_FLAG,
                             command
                            };
    
    return execCommand(executor, shellCommand, storeStdOut, forceLog);
  }
  
  public static RunningProcess execCommand(Executor executor, 
                                           String[] command, 
                                           boolean storeStdOut) throws IOException, 
                                                                       InterruptedException {
    return execCommand(executor, command, storeStdOut, false);
  }
  
  public static RunningProcess execCommand(Executor executor, 
                                           String[] originalCommand, 
                                           boolean storeStdOut, 
                                           boolean forceLog) throws IOException, 
                                                                    InterruptedException {
    ForkLock forkLock = getAndAcquireForkLock(originalCommand);  // lock is released by ExecResult when it consumes stdOut
    maybeLog(originalCommand, forceLog);
    try {
      return new RunningProcess(executor, storeStdOut, forkLock);
    } catch (IOException e) {
      // release on error
      forkLock.release();

      throw e;
    } catch (Throwable t) {
      // release on error
      forkLock.release();

      throw ExceptionUtils.makeRuntime(t);
    }
  }
  
  private static ForkLock getAndAcquireForkLock(String[] originalCommand) throws InterruptedException {
    ForkLock fl = new ForkLock(originalCommand);
    fl.acquire();
    return fl;
  }
  
  private static boolean startsWithShell(String[] command) {
    return command[0].endsWith("sh") || 
           command[0].endsWith("bash") || 
           command[0].endsWith("dash") || 
           command[0].endsWith("zsh") || 
           command[0].endsWith("ksh") || 
           command[0].endsWith("ash");
  }
  
  private static void maybeLog(String[] command, boolean forceLog) {
    if (forceLog || VERBOSE) {
      if (startsWithShell(command) && command.length == 3) {
        System.out.println("Running command: " + command[2]);
      } else {
        System.out.println("Running command: " + StringUtils.NEW_LINE + getCommandStr(command, true));
      }
    }
  }
  
  private static String getCommandStr(String[] command, boolean formatedForLogging) {
    return getCommandStr(command, 0, formatedForLogging);
  }
  
  private static String getCommandStr(String[] command, int startIndex, 
                                      boolean formatedForLogging) {
    if (startIndex > command.length) {
      throw new IndexOutOfBoundsException(startIndex + " is beyond array of length " + command.length);
    }
    
    boolean first = true;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < command.length; i++) {
      if (i < startIndex) {
        continue;
      }
      if (! first) {
        if (formatedForLogging) {
          sb.append(StringUtils.NEW_LINE);
        } else {
          sb.append(' ');
        }
      } else {
        first = false;
      }
      if (formatedForLogging) {
        sb.append('\t');
      }
      sb.append(command[i]);
    }
    return sb.toString();
  }
  
  private static class ForkLock {
    private static final Object forkLock = new Object();
    private static int currentForkQty = 0;

    private final String lockNotifyStr;
    private boolean acquired;
    private boolean released;
    private final String[] commandWithLock;
    
    public ForkLock(String[] originalCommand) {
      lockNotifyStr = EXEC_NOTIFY_STRING;
      acquired = false;
      released = false;
      
      String lockEchoCommand = "echo -n \'" + lockNotifyStr + "\' ; ";
      if (startsWithShell(originalCommand)) {
        if (! originalCommand[1].trim().equals(SHELL_EXECUTE_FLAG)) {
          throw new IllegalStateException("Unexpected command input, " +
                                            "expected shell followed by " + SHELL_EXECUTE_FLAG + ", " +
                                            "got: \n" + getCommandStr(originalCommand, true));
        }
        
        commandWithLock = new String[3];
        commandWithLock[0] = originalCommand[0];
        commandWithLock[1] = originalCommand[1];
        commandWithLock[2] = lockEchoCommand + getCommandStr(originalCommand, 2, false);
      } else {
        commandWithLock = new String[3];
        commandWithLock[0] = DEFAULT_SHELL;
        commandWithLock[1] = SHELL_EXECUTE_FLAG;
        commandWithLock[2] = lockEchoCommand + getCommandStr(originalCommand, 0, false);
      }
    }
    
    public void acquire() throws InterruptedException {
      synchronized (forkLock) {
        if (acquired) { // prevent acquiring multiple times
          if (released) {
            throw new IllegalStateException("Lock already acquired and released, " +
                                              "don't reuse lock objects");
          }
          return;
        }
        
        while (currentForkQty >= MAX_CONCURRENT_FORKS) {
          forkLock.wait();
        }
        
        currentForkQty++;
        acquired = true;
      }
    }

    
    private void release() {
      synchronized (forkLock) {
        if (! acquired) {
          throw new IllegalStateException("Can not release lock thas has never been acquired");
        }

        if (released) { // prevent being released multiple times
          return;
        }

        currentForkQty--;
        released = true;
        forkLock.notify();
      }
    }
    
    @Override
    protected void finalize() {
      if (acquired && ! released) {
        System.err.println("ForkLock was acquired and never releaed, releasing in finalizer");
        release();
      }
    }
  }
  
  public static class RunningProcess {
    private final Executor executor;
    private final ExecOutput output;
    private final Process process;
    private String stdOutStr;
    private String stdErrStr;
    private volatile Integer exitValue;
    
    private RunningProcess(Executor executor, 
                           Process p, 
                           boolean storeStdOut) {
      this(executor, p, storeStdOut, null);
    }
    
    private RunningProcess(Executor executor, 
                           boolean storeStdOut, 
                           ForkLock forkLock) throws IOException {
      this(executor, 
           Runtime.getRuntime().exec(forkLock.commandWithLock), 
           storeStdOut, forkLock);
    }
    
    private RunningProcess(Executor executor, 
                           final Process p, 
                           final boolean storeStdOut, 
                           final ForkLock forkLock) {
      if (forkLock != null && ! forkLock.acquired) {
        throw new IllegalStateException("Provided a ForkLock that is not acquired yet");
      }
      
      this.executor = executor;
      output = new ExecOutput();
      process = p;
      stdOutStr = null;
      stdErrStr = null;
      exitValue = null;
      if (storeStdOut) {
        executor.execute(new StreamPiper(process.getInputStream(), 
                                         true, 
                                         output.stdOut.getOutputStream(),
                                         true, 
                                         new Runnable() {
                                           @Override
                                           public void run() {
                                             output.stdOutClosed();
                                           }
                                         }, forkLock));
      } else {
        output.stdOutClosed();
        executor.execute(new StreamConsumer(process.getInputStream(), forkLock));
      }
      executor.execute(new StreamPiper(process.getErrorStream(), 
                                       true, 
                                       output.stdErr.getOutputStream(), 
                                       true, 
                                       new Runnable() {
                                         @Override
                                         public void run() {
                                           output.stdErrClosed();
                                         }
                                       }, null));
    }

    public void blockTillFinished() throws InterruptedException {
      if (exitValue != null) {
        return;
      }
      
      // block till we have results from both std out and std error
      output.blockTillStdStreamsDone();
        
      exitValue = process.waitFor();
    }
    
    public int exitValue() throws InterruptedException {
      blockTillFinished();
      
      return exitValue;
    }
    
    public void checkExitValue() throws InterruptedException {
      checkExitValue(null);
    }
    
    public void checkExitValue(String errorMsg) throws InterruptedException {
      if (exitValue() != 0) {
        throw new BadExitCodeException(exitValue, errorMsg);
      }
    }
    
    public synchronized String stdOutStr() throws InterruptedException, IOException {
      if (stdOutStr == null) {
        blockTillFinished();
        
        stdOutStr = streamToString(output.stdOut);
      }
      
      return stdOutStr;
    }
    
    public synchronized String stdErrStr() throws InterruptedException, IOException {
      if (stdErrStr == null) {
        blockTillFinished();
        
        stdErrStr = streamToString(output.stdErr);
      }
      
      return stdErrStr;
    }
    
    /*
     * TODO - implement these in a way that wont conflict with stdOutStr
    public InputStream stdOut() {
      return output.stdOut;
    }
    
    public InputStream stdErr() {
      return output.stdErr;
    }*/
    
    public void pipeToStdIn(InputStream stream) {
      executor.execute(new StreamPiper(stream, true, process.getOutputStream(), true, null, null));
    }
    
    private static String streamToString(InputStream in) throws IOException {
      StringBuffer resultSB = new StringBuffer();
      
      byte[] buffer = new byte[STD_BUFFER_SIZE];
      int c = 0;
      while ((c = in.read(buffer)) != -1) {
        resultSB.append(new String(buffer, 0, c));
      }
      
      return resultSB.toString();
    }
  }
  
  private static class ExecOutput {
    private final ProcessStream stdOut;
    private final ProcessStream stdErr;
    private boolean stdOutDone;
    private boolean stdErrDone;
    
    public ExecOutput() {
      stdOut = new ProcessStream();
      stdErr = new ProcessStream();
      stdOutDone = false;
      stdErrDone = false;
    }
    
    public void blockTillStdStreamsDone() throws InterruptedException {
      synchronized (this) {
        while (! stdOutDone || ! stdErrDone) {
          wait();
        }
      }
    }

    public void stdOutClosed() {
      synchronized (this) {
        stdOutDone = true;
        
        notifyAll();
      }
    }
    
    public void stdErrClosed() {
      synchronized (this) {
        stdErrDone = true;
        
        notifyAll();
      }
    }
  }
  
  private static class StreamConsumer implements Runnable {
    private final InputStream stream;
    private final ForkLock toReleaseLock;
    
    private StreamConsumer(InputStream stream, 
                           ForkLock toReleaseLock) {
      this.stream = stream;
      this.toReleaseLock = toReleaseLock;
    }

    @Override
    public void run() {
      try {
        try {
          byte[] buffer = new byte[STD_BUFFER_SIZE];
          StringBuffer tempSB = null;
          boolean needToReleaseForkLock = false;
          if (toReleaseLock != null) {
            needToReleaseForkLock = true;
            tempSB = new StringBuffer();
          }
          int readCount;
          while ((readCount = stream.read(buffer)) != -1) {
            if (needToReleaseForkLock) {
              tempSB.append(new String(buffer, 0, readCount));
              String currStr = tempSB.toString();
              
              if (currStr.startsWith(toReleaseLock.lockNotifyStr)) {
                toReleaseLock.release();
                
                needToReleaseForkLock = false;
                tempSB = null;  // no longer needed
              }
            }
          }
          
          if (needToReleaseForkLock) {
            throw new IllegalStateException("Never found lock key: " + toReleaseLock + 
                                              ", stdOut: \n\t" + tempSB.toString());
          }
        } finally {
          stream.close();
        }
      } catch (IOException e) {
        throw ExceptionUtils.makeRuntime(e);
      }
    }
  }
  
  private static class StreamPiper implements Runnable {
    private final InputStream inStream;
    private final boolean closeInputWhenDone;
    private final OutputStream outStream;
    private final boolean closeOutputWhenDone;
    private final Runnable finishRunnable;
    private final ForkLock toReleaseLock;
    
    private StreamPiper(InputStream inStream, 
                        boolean closeInputWhenDone, 
                        OutputStream outStream, 
                        boolean closeOutputWhenDone, 
                        Runnable finishRunnable, 
                        ForkLock toReleaseLock) {
      this.inStream = inStream;
      this.closeInputWhenDone = closeInputWhenDone;
      this.outStream = outStream;
      this.closeOutputWhenDone = closeOutputWhenDone;
      this.finishRunnable = finishRunnable;
      this.toReleaseLock = toReleaseLock;
    }

    @Override
    public void run() {
      try {
        try {
          byte[] buffer = new byte[STD_BUFFER_SIZE];
          StringBuffer tempSB = null;
          boolean needToReleaseForkLock = false;
          if (toReleaseLock != null) {
            needToReleaseForkLock = true;
            tempSB = new StringBuffer();
          }
          int readCount;
          while ((readCount = inStream.read(buffer)) != -1) {
            if (needToReleaseForkLock) {
              tempSB.append(new String(buffer, 0, readCount));
              String currStr = tempSB.toString();
              
              if (currStr.startsWith(toReleaseLock.lockNotifyStr)) {
                toReleaseLock.release();
                
                needToReleaseForkLock = false;
                
                // if we read more than our lock string put it into the result stream
                if (currStr.length() != toReleaseLock.lockNotifyStr.length()) {
                  outStream.write(currStr.substring(toReleaseLock.lockNotifyStr.length()).getBytes());
                }
                
                tempSB = null;  // no longer needed
              }
            } else {
              outStream.write(buffer, 0, readCount);
            }
          }
        } finally {
          try {
            try {
              if (closeInputWhenDone) {
                inStream.close();
              }
            } finally {
              if (closeOutputWhenDone) {
                outStream.close();
              }
            }
          } finally {
            if (finishRunnable != null) {
              finishRunnable.run();
            }
          }
        }
      } catch (IOException e) {
        throw ExceptionUtils.makeRuntime(e);
      }
    }
  }
}
