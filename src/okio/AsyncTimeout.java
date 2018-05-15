/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okio;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

import static okio.Util.checkOffsetAndCount;

/**
 * This timeout uses a background thread to take action exactly when the timeout occurs. Use this to
 * implement timeouts where they aren't supported natively, such as to sockets that are blocked on
 * writing.
 *
 * <p>Subclasses should override {@link #timedOut} to take action when a timeout occurs. This method
 * will be invoked by the shared watchdog thread so it should not do any long-running operations.
 * Otherwise we risk starving other timeouts from being triggered.
 *
 * <p>Use {@link #sink} and {@link #source} to apply this timeout to a stream. The returned value
 * will apply the timeout to each operation on the wrapped stream.
 *
 * <p>Callers should call {@link #enter} before doing work that is subject to timeouts, and {@link
 * #exit} afterwards. The return value of {@link #exit} indicates whether a timeout was triggered.
 * Note that the call to {@link #timedOut} is asynchronous, and may be called after {@link #exit}.
 */
public class AsyncTimeout extends Timeout {
  /**
   * Don't write more than 64 KiB of data at a time, give or take a segment. Otherwise slow
   * connections may suffer timeouts even when they're making (slow) progress. Without this, writing
   * a single 1 MiB buffer may never succeed on a sufficiently slow connection.
   * 最大的写的值 64K
   */
  private static final int TIMEOUT_WRITE_SIZE = 64 * 1024;

  /** Duration for the watchdog thread to be idle before it shuts itself down. */
  private static final long IDLE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
  private static final long IDLE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(IDLE_TIMEOUT_MILLIS);

  /**
   * The watchdog thread processes a linked list of pending timeouts, sorted in the order to be
   * triggered. This class synchronizes on AsyncTimeout.class. This lock guards the queue.
   *
   * <p>Head's 'next' points to the first element of the linked list. The first element is the next
   * node to time out, or null if the queue is empty. The head is null until the watchdog thread is
   * started and also after being idle for {@link #IDLE_TIMEOUT_MILLIS}.
   */
  static  AsyncTimeout head;

  /** True if this node is currently in the queue. */
  private boolean inQueue;

  /** The next node in the linked list. */
  private AsyncTimeout next;

  /**
   * If scheduled, this is the time that the watchdog should time this out.
   * 超时时间
   */
  private long timeoutAt;

  public final void enter() {
    if (inQueue) throw new IllegalStateException("Unbalanced enter/exit");
    long timeoutNanos = timeoutNanos();
    boolean hasDeadline = hasDeadline();
    if (timeoutNanos == 0 && !hasDeadline) {
      return; // No timeout and no deadline? Don't bother with the queue.
    }
    inQueue = true;
    scheduleTimeout(this, timeoutNanos, hasDeadline);
  }

  /**
   * 将一个检测可能会超时逻辑操作的AsyncTimeout对象加入队列中
   * @param node
   * @param timeoutNanos
   * @param hasDeadline
   */
  private static synchronized void scheduleTimeout(
      AsyncTimeout node, long timeoutNanos, boolean hasDeadline) {
    // Start the watchdog thread and create the head node when the first timeout is scheduled.
    // 首次执行
    if (head == null) {
      head = new AsyncTimeout();
      new Watchdog().start();
    }
    // 计算超时时间
    long now = System.nanoTime();
    if (timeoutNanos != 0 && hasDeadline) {
      // Compute the earliest event; either timeout or deadline. Because nanoTime can wrap around,
      // Math.min() is undefined for absolute values, but meaningful for relative ones.
      node.timeoutAt = now + Math.min(timeoutNanos, node.deadlineNanoTime() - now);
    } else if (timeoutNanos != 0) {
      node.timeoutAt = now + timeoutNanos;
    } else if (hasDeadline) {
      node.timeoutAt = node.deadlineNanoTime();
    } else {
      throw new AssertionError();
    }

    // Insert the node in sorted order.
    // 按照剩余的超时时间来进行排序的，快到超时的节点排在表头，依次往后递增
    // 剩余的时间
    long remainingNanos = node.remainingNanos(now);
    for (AsyncTimeout prev = head; true; prev = prev.next) {
      //  如果下一个为null或者剩余时间比下一个短 就插入node
      if (prev.next == null || remainingNanos < prev.next.remainingNanos(now)) {
        node.next = prev.next;
        prev.next = node;
        // 如果加入的节点位于队列的第一个，即head之后的节点，则需要唤醒等待的线程（在介绍watchdog部分统一介绍）
        if (prev == head) {
          AsyncTimeout.class.notify(); // Wake up the watchdog when inserting at the front.
        }
        break;
      }
    }
  }

  /**
   * 如果超时，就会return true
   * Returns true if the timeout occurred. */
  public final boolean exit() {
    if (!inQueue) return false;
    inQueue = false;
    return cancelScheduledTimeout(this);
  }

  /** Returns true if the timeout occurred. */
  private static synchronized boolean cancelScheduledTimeout(AsyncTimeout node) {
    // Remove the node from the linked list.
    // 将节点移除队列
    for (AsyncTimeout prev = head; prev != null; prev = prev.next) {
      if (prev.next == node) {
        prev.next = node.next;
        node.next = null;
        return false;
      }
    }

    // The node wasn't found in the linked list: it must have timed out!
    // 如果节点没有找到，就可以判断为超时
    return true;
  }

  /**
   * Returns the amount of time left until the time out. This will be negative if the timeout has
   * elapsed and the timeout should occur immediately.
   */
  private long remainingNanos(long now) {
    return timeoutAt - now;
  }

  /**
   * Invoked by the watchdog thread when the time between calls to {@link #enter()} and {@link
   * #exit()} has exceeded the timeout.
   */
  protected void timedOut() {
  }

  /**
   * Returns a new sink that delegates to {@code sink}, using this to implement timeouts. This works
   * best if {@link #timedOut} is overridden to interrupt {@code sink}'s current operation.
   */
  public final Sink sink(final Sink sink) {
    return new Sink() {
      @Override public void write(Buffer source, long byteCount) throws IOException {
        checkOffsetAndCount(source.size, 0, byteCount);

        while (byteCount > 0L) {
          // Count how many bytes to write. This loop guarantees we split on a segment boundary.
          long toWrite = 0L;
          for (Segment s = source.head; toWrite < TIMEOUT_WRITE_SIZE; s = s.next) {
            int segmentSize = s.limit - s.pos;
            toWrite += segmentSize;
            if (toWrite >= byteCount) {
              toWrite = byteCount;
              break;
            }
          }

          // Emit one write. Only this section is subject to the timeout.
          boolean throwOnTimeout = false;
          enter();
          try {
            sink.write(source, toWrite);
            byteCount -= toWrite;
            throwOnTimeout = true;
          } catch (IOException e) {
            throw exit(e);
          } finally {
            exit(throwOnTimeout);
          }
        }
      }

      @Override public void flush() throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          sink.flush();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public void close() throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          sink.close();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public Timeout timeout() {
        return AsyncTimeout.this;
      }

      @Override public String toString() {
        return "AsyncTimeout.sink(" + sink + ")";
      }
    };
  }

  /**
   * Returns a new source that delegates to {@code source}, using this to implement timeouts. This
   * works best if {@link #timedOut} is overridden to interrupt {@code sink}'s current operation.
   */
  public final Source source(final Source source) {
    return new Source() {
      @Override public long read(Buffer sink, long byteCount) throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          long result = source.read(sink, byteCount);
          throwOnTimeout = true;
          return result;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public void close() throws IOException {
        boolean throwOnTimeout = false;
        try {
          source.close();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public Timeout timeout() {
        return AsyncTimeout.this;
      }

      @Override public String toString() {
        return "AsyncTimeout.source(" + source + ")";
      }
    };
  }

  /**
   * Throws an IOException if {@code throwOnTimeout} is {@code true} and a timeout occurred. See
   * {@link #newTimeoutException(IOException)} for the type of exception thrown.
   */
  final void exit(boolean throwOnTimeout) throws IOException {
    boolean timedOut = exit();
    if (timedOut && throwOnTimeout) throw newTimeoutException(null);
  }

  /**
   * Returns either {@code cause} or an IOException that's caused by {@code cause} if a timeout
   * occurred. See {@link #newTimeoutException(IOException)} for the type of exception
   * returned.
   */
  final IOException exit(IOException cause) throws IOException {
    if (!exit()) return cause;
    return newTimeoutException(cause);
  }

  /**
   * Returns an {@link IOException} to represent a timeout. By default this method returns {@link
   * InterruptedIOException}. If {@code cause} is non-null it is set as the cause of the
   * returned exception.
   */
  protected IOException newTimeoutException(IOException cause) {
    InterruptedIOException e = new InterruptedIOException("timeout");
    if (cause != null) {
      e.initCause(cause);
    }
    return e;
  }

  private static final class Watchdog extends Thread {
    Watchdog() {
      super("Okio Watchdog");
      setDaemon(true);
    }

    public void run() {
      while (true) {
        try {
          AsyncTimeout timedOut;
          synchronized (AsyncTimeout.class) {
            // 等待
            timedOut = awaitTimeout();

            // Didn't find a node to interrupt. Try again.
            // 没有找到一个node来interrupt 继续
            if (timedOut == null) continue;

            // 这个队列已经完全空了，需要让这个线程退出，并且让另外的watchdog线程创建
            // The queue is completely empty. Let this thread exit and let another watchdog thread
            // get created on the next call to scheduleTimeout().
            if (timedOut == head) {
              head = null;
              return;
            }
          }

          // Close the timed out node.
          // b.某个节点已经超时，这里timeOut()方法在AsyncTimeout中是空方法，可以通过覆写该方法定义超时以后需要处理的逻辑
          timedOut.timedOut();
        } catch (InterruptedException ignored) {
        }
      }
    }
  }

  /**
   * Removes and returns the node at the head of the list, waiting for it to time out if necessary.
   * This returns {@link #head} if there was no node at the head of the list when starting, and
   * there continues to be no node after waiting {@code IDLE_TIMEOUT_NANOS}. It returns null if a
   * new node was inserted while waiting. Otherwise this returns the node being waited on that has
   * been removed.
   */
  static AsyncTimeout awaitTimeout() throws InterruptedException {
    // Get the next eligible node.
    // 拿到下一个节点
    AsyncTimeout node = head.next;

    // The queue is empty. Wait until either something is enqueued or the idle timeout elapses.
    // head后面的一个队列，如果queue为空，等待直到有node进队，或者触发IDLE_TIMEOUT_MILLS
    if (node == null) {
      // 在空队列的情况下，等待一个空闲时间，此间没有入队的对象将线程唤醒，空闲时间过后如果依然时空队列，则返回head，
      // 则对应上面的a. 特殊情况执行return，结束循环并结束检测线程
      // 如果等待空闲时间内，有节点入队，此时检测线程被唤醒，这里返回null， 则上面的while循环会执行下一次循环，去检测第一个节点

      long startNanos = System.nanoTime();
      AsyncTimeout.class.wait(IDLE_TIMEOUT_MILLIS);
      return head.next == null && (System.nanoTime() - startNanos) >= IDLE_TIMEOUT_NANOS
          ? head  // The idle timeout elapsed.
          : null; // The situation has changed.
    }

    //1. 计算第一个节点的超时剩余时间
    long waitNanos = node.remainingNanos(System.nanoTime());

    // The head of the queue hasn't timed out yet. Await that.
    // 这个head依然还没有超时，继续等待
    if (waitNanos > 0) {
      // Waiting is made complicated by the fact that we work in nanoseconds,
      // but the API wants (millis, nanos) in two arguments.
      long waitMillis = waitNanos / 1000000L;
      waitNanos -= (waitMillis * 1000000L);
      //2. 等待一个超时时间
      AsyncTimeout.class.wait(waitMillis, (int) waitNanos);
      return null;
    }

    // The head of the queue has timed out. Remove it.
    // 如果队列的第一个节点已经超时了，则返回该节点，此时会走到上面的b情形，去执行该节点的timeout()方法
    head.next = node.next;
    node.next = null;
    return node;
  }
}
