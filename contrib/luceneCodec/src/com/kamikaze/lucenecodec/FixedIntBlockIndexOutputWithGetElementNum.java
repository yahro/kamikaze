package com.kamikaze.lucenecodec;


import java.io.IOException;

import org.apache.lucene.index.codecs.sep.IntIndexOutput;
import org.apache.lucene.store.IndexOutput;

/** Abstract base class that writes fixed-size blocks of ints
 *  to an IndexOutput.  While this is a simple approach, a
 *  more performant approach would directly create an impl
 *  of IntIndexOutput inside Directory.  Wrapping a generic
 *  IndexInput will likely cost performance.
 *
 * @lucene.experimental
 */
public abstract class FixedIntBlockIndexOutputWithGetElementNum extends IntIndexOutput {

  protected final IndexOutput out;
  private final int blockSize;
  protected final int[] buffer;
  private int upto;

  // get the number of elements in the block that have the actual values
  public int getElementNum()
  {
    return upto;
  }
  
  protected FixedIntBlockIndexOutputWithGetElementNum(IndexOutput out, int fixedBlockSize) throws IOException {
    blockSize = fixedBlockSize;
    this.out = out;
    out.writeVInt(blockSize);
    buffer = new int[blockSize];
  }

  protected abstract void flushBlock() throws IOException;

  @Override
  public Index index() throws IOException {
    return new Index();
  }

  private class Index extends IntIndexOutput.Index {
    long fp;
    int upto;
    long lastFP;
    int lastUpto;

    @Override
    public void mark() throws IOException {
      fp = out.getFilePointer();
      upto = FixedIntBlockIndexOutputWithGetElementNum.this.upto;
    }

    @Override
    public void set(IntIndexOutput.Index other) throws IOException {
      Index idx = (Index) other;
      lastFP = fp = idx.fp;
      lastUpto = upto = idx.upto;
    }

    @Override
    public void write(IndexOutput indexOut, boolean absolute) throws IOException {
      if (absolute) {
        indexOut.writeVLong(fp);
        indexOut.writeVInt(upto);
      } else if (fp == lastFP) {
        // same block
        indexOut.writeVLong(0);
        assert upto >= lastUpto;
        indexOut.writeVInt(upto - lastUpto);
      } else {      
        // new block
        indexOut.writeVLong(fp - lastFP);
        indexOut.writeVInt(upto);
      }
      lastUpto = upto;
      lastFP = fp;
    }

    @Override
    public void write(IntIndexOutput indexOut, boolean absolute) throws IOException {
      if (absolute) {
        indexOut.writeVLong(fp);
        indexOut.write(upto);
      } else if (fp == lastFP) {
        // same block
        indexOut.writeVLong(0);
        assert upto >= lastUpto;
        indexOut.write(upto - lastUpto);
      } else {      
        // new block
        indexOut.writeVLong(fp - lastFP);
        indexOut.write(upto);
      }
      lastUpto = upto;
      lastFP = fp;
    }
  }

  @Override
  public void write(int v) throws IOException {
    buffer[upto++] = v;
    if (upto == blockSize) {
      flushBlock();
      upto = 0;
    }
  }

  @Override
  public void close() throws IOException {
    try {
      if (upto > 0) {
        // NOTE: entries in the block after current upto are
        // invalid
        flushBlock();
      }
    } finally {
      out.close();
    }
  }
}

