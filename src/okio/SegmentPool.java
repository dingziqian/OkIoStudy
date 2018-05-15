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


/**
 * A collection of unused segments, necessary to avoid GC churn and zero-fill.
 * This pool is a thread-safe static singleton.
 */
final class SegmentPool {
  /** The maximum number of bytes to pool. */
  // TODO: Is 64 KiB a good maximum size? Do we ever have that many idle segments?
  static final long MAX_SIZE = 64 * 1024; // 64 KiB.

  /** Singly-linked list of segments. */
  static Segment next;

  /** Total bytes in this pool. */
  static long byteCount;

  private SegmentPool() {
  }

  /**
   * take方法用来取数据
   * 如果池子为空就创建一个空对象 owner true | share false
   * @return
   */
  static Segment take() {
    synchronized (SegmentPool.class) {
      if (next != null) {
        Segment result = next;
        next = result.next;
        result.next = null;
        byteCount -= Segment.SIZE;
        return result;
      }
    }
    return new Segment(); // Pool is empty. Don't zero-fill while holding a lock.
  }

  /**
   * 如果当前要回收的segment有前后引用或者是共享的 那么就回收失败
   * 如果加入后的大小超过了最大大小 也会失败
   * 然后将新回收的next指向表头 也就是加到的链表的头 并且将回收的segment置为next也就是head
   * @param segment
   */
  static void recycle(Segment segment) {
    if (segment.next != null || segment.prev != null) throw new IllegalArgumentException();
    if (segment.shared) return; // This segment cannot be recycled.
    synchronized (SegmentPool.class) {
      if (byteCount + Segment.SIZE > MAX_SIZE) return; // Pool is full.
      byteCount += Segment.SIZE;
      segment.next = next;
      segment.pos = segment.limit = 0;
      next = segment;
    }
  }
}
