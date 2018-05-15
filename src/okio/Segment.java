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
 * A segment of a buffer.
 *
 * <p>Each segment in a buffer is a circularly-linked list node referencing the following and
 * preceding segments in the buffer.
 *
 * <p>Each segment in the pool is a singly-linked list node referencing the rest of segments in the
 * pool.
 *
 * <p>The underlying byte arrays of segments may be shared between buffers and byte strings. When a
 * segment's byte array is shared the segment may not be recycled, nor may its byte data be changed.
 * The lone exception is that the owner segment is allowed to append to the segment, writing data at
 * {@code limit} and beyond. There is a single owning segment for each byte array. Positions,
 * limits, prev, and next references are not shared.
 */
final class Segment {
  /** The size of all segments in bytes. */
  static final int SIZE = 8192;

  /** Segments will be shared when doing so avoids {@code arraycopy()} of this many bytes. */
  static final int SHARE_MINIMUM = 1024;

  /**
   * byte  内存array
   */
  final byte[] data;

  /**
   * 读操作指针,开始的位置
   * The next byte of application data byte to read in this segment.
   */
  int pos;

  /**
   * 写操作指针，结束的位置
   * The first byte of available data ready to be written to.
   */
  int limit;

  /**
   * data是否与其他Segment共享
   *
   * True if other segments or byte strings use the same byte array.
   */
  boolean shared;

  /**
   * data是否归属于Segment。
   * True if this segment owns the byte array and can append to it, extending {@code limit}.
   */
  boolean owner;

  /** Next segment in a linked or circularly-linked list. */
  Segment next;

  /** Previous segment in a circularly-linked list. */
  Segment prev;

  Segment() {
    this.data = new byte[SIZE];
    this.owner = true;
    this.shared = false;
  }

  Segment(byte[] data, int pos, int limit, boolean shared, boolean owner) {
    this.data = data;
    this.pos = pos;
    this.limit = limit;
    this.shared = shared;
    this.owner = owner;
  }

  /**
   * Returns a new segment that shares the underlying byte array with this. Adjusting pos and limit
   * are safe but writes are forbidden. This also marks the current segment as shared, which
   * prevents it from being pooled.
   */
  final Segment sharedCopy() {
    shared = true;
    return new Segment(data, pos, limit, true, false);
  }

  /** Returns a new segment that its own private copy of the underlying byte array. */
  final Segment unsharedCopy() {
    return new Segment(data.clone(), pos, limit, false, true);
  }

  /**
   * 双向链表，移除segment
   * 移除了自己，首先将自己的前后两个节点连接起来，然后将自己的前后引用置空，这样就脱离了整个双向链表，然后返回next
   * Removes this segment of a circularly-linked list and returns its successor.
   * Returns null if the list is now empty.
   */
  public final Segment pop() {
    Segment result = next != this ? next : null;
    prev.next = next;
    next.prev = prev;
    next = null;
    prev = null;
    return result;
  }

  /**
   * 双向链表，添加Segment
   * 在当前和next引用中间插入一个segment进来，并且返回插入的segment
   * Appends {@code segment} after this segment in the circularly-linked list.
   * Returns the pushed segment.
   */
  public final Segment push(Segment segment) {
    segment.prev = this;
    segment.next = next;
    next.prev = segment;
    next = segment;
    return segment;
  }

  /**
   * 将双向链表中的head分割成两个segment，
   * 第一个segment包含[pos..pos+byteCount)的数据 分割出去的segment
   * 第二个segment包含[pos+byteCount..limit)的数据
   * Splits this head of a circularly-linked list into two segments. The first
   * segment contains the data in {@code [pos..pos+byteCount)}. The second
   * segment contains the data in {@code [pos+byteCount..limit)}. This can be
   * useful when moving partial segments from one buffer to another.
   *
   * <p>Returns the new head of the circularly-linked list.
   */
  public final Segment split(int byteCount) {
    if (byteCount <= 0 || byteCount > limit - pos) throw new IllegalArgumentException();
    Segment prefix;

    // We have two competing performance goals:
    //  - Avoid copying data. We accomplish this by sharing segments.
    //  - Avoid short shared segments. These are bad for performance because they are readonly and
    //    may lead to long chains of short segments.
    // To balance these goals we only share segments when the copy will be large.
    if (byteCount >= SHARE_MINIMUM) { // 比较大的数据，共享segment
      prefix = sharedCopy();
    } else { // 比较小，直接内存copy
      prefix = SegmentPool.take();
      // 将 data 里面的数据 从pos - pos+byteCount 复制到 prefix.data
      System.arraycopy(data, pos, prefix.data, 0, byteCount);
    }

    prefix.limit = prefix.pos + byteCount;
    // 当前segment
    pos += byteCount;
    // 插入当前Segment的前面
    prev.push(prefix);
    return prefix;
  }

  /**
   * 压缩，防止十分短的数据占据一整个Segment而浪费空间的现象
   * Call this when the tail and its predecessor may both be less than half
   * full. This will copy data so that segments can be recycled.
   */
  public final void compact() {
    if (prev == this) throw new IllegalStateException();
    if (!prev.owner) return; // Cannot compact: prev isn't writable.
    int byteCount = limit - pos;
    // 剩余的大小
    int availableByteCount = SIZE - prev.limit + (prev.shared ? 0 : prev.pos);
    if (byteCount > availableByteCount) return; // Cannot compact: not enough writable space.
    // 将数据写入前一个Segment
    writeTo(prev, byteCount);
    // 回收自己
    pop();
    SegmentPool.recycle(this);
  }

  /**
   * 将此Segment中的数据移动到sink片段中
   * Moves {@code byteCount} bytes from this segment to {@code sink}.
   *
   */
  public final void writeTo(Segment sink, int byteCount) {
    if (!sink.owner) throw new IllegalArgumentException();
    if (sink.limit + byteCount > SIZE) { // limit和需要写的字节总和大于SIZE
      // We can't fit byteCount bytes at the sink's current position. Shift sink first.
      // 共享无法写
      if (sink.shared) throw new IllegalArgumentException();
      // 如果减去头依然比SIZE大 那么就无法写抛异常
      if (sink.limit + byteCount - sink.pos > SIZE) throw new IllegalArgumentException();
      // 否则我们需要先移动要写的文件地址  然后置limit pos的地址
      // 重新将pos - limit的数据赋值到 0 - (limit - pos)  数据移动 pos
      System.arraycopy(sink.data, sink.pos, sink.data, 0, sink.limit - sink.pos);
      sink.limit -= sink.pos;
      sink.pos = 0;
    }
    // 开始尾部写入 写完置limit地址
    // 将data里面的数据 copy到sink.data里面
    System.arraycopy(data, pos, sink.data, sink.limit, byteCount);
    sink.limit += byteCount;
    pos += byteCount;
  }
}
