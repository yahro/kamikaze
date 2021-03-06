package com.kamikaze.lucenecodec;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.codecs.Codec;
import org.apache.lucene.index.codecs.FieldsConsumer;
import org.apache.lucene.index.codecs.FieldsProducer;
import org.apache.lucene.index.codecs.sep.IntStreamFactory;
import org.apache.lucene.index.codecs.sep.IntIndexInput;
import org.apache.lucene.index.codecs.sep.IntIndexOutput;
import org.apache.lucene.index.codecs.sep.SepPostingsReaderImpl;
import org.apache.lucene.index.codecs.sep.SepPostingsWriterImpl;
import org.apache.lucene.index.codecs.intblock.FixedIntBlockIndexInput;
import org.apache.lucene.index.codecs.FixedGapTermsIndexReader;
import org.apache.lucene.index.codecs.FixedGapTermsIndexWriter;
import org.apache.lucene.index.codecs.PostingsWriterBase;
import org.apache.lucene.index.codecs.PostingsReaderBase;
import org.apache.lucene.index.codecs.PrefixCodedTermsReader;
import org.apache.lucene.index.codecs.PrefixCodedTermsWriter;
import org.apache.lucene.index.codecs.TermsIndexReaderBase;
import org.apache.lucene.index.codecs.TermsIndexWriterBase;
import org.apache.lucene.index.codecs.standard.StandardCodec;
import org.apache.lucene.store.*;
import org.apache.lucene.util.BytesRef;

import com.kamikaze.pfordelta.PForDelta;


/**
 * A codec for fixed sized int block encoders. The int encoder
 * used here writes each block as data encoded by PForDelta.
 */

public class PForDeltaFixedIntBlockCodec extends Codec {

  private final int blockSize;

  public PForDeltaFixedIntBlockCodec(int blockSize) {
    this.blockSize = blockSize;
    name = "PForDeltaFixedIntBlock";
  }

  @Override
  public String toString() {
    return name + "(blockSize=" + blockSize + ")";
  }

  /**
   * Encode a block of integers using PForDelta and 
   * @param block the input block to be compressed
   * @param elementNum the number of elements in the block to be compressed 
   * @return the compressed size in the number of integers of the compressed data
   * @throws Exception
   */
  private int[] encodeOneBlockWithPForDelta(final int[] block, int elementNum) 
  {
    int[] compressedBlock = PForDelta.compressOneBlockOpt(block, elementNum);
    return compressedBlock;
  }
    
    /**
     * Decode a block of compressed data (using PForDelta) into a block of elementNum uncompressed integers
     * @param block the input block to be decompressed
     * @param elementNum the number of elements in the block to be compressed 
     */
    private int[] decodeOneBlockWithPForDelta(final int[] block, int elementNum)
    {
      int[] decompressedBlock = new int[elementNum];
      PForDelta.decompressOneBlock(decompressedBlock, block, elementNum);
      return decompressedBlock;
    }
    
    public IntStreamFactory getIntFactory() {
      return new PForDeltaIntFactory();
    }

    private class PForDeltaIntFactory extends IntStreamFactory {

      @Override
      public IntIndexInput openInput(Directory dir, String fileName, int readBufferSize) throws IOException {
        return new FixedIntBlockIndexInput(dir.openInput(fileName, readBufferSize)) {

          @Override
          protected BlockReader getBlockReader(final IndexInput in, final int[] buffer) throws IOException {
            return new BlockReader() {
              public void seek(long pos) {}
              public void readBlock() throws IOException {
                if(buffer != null)
                {
                  int[] compBuffer = null;
                  // retrieve the compressed size in ints
                  final int compressedSizeInInt = in.readInt();
                  // read the compressed data (compressedSizeInInt ints)
                  for(int i=0;i<compressedSizeInInt;i++) {
                    compBuffer[i] = in.readInt();
                  }
                  try
                  {
                    int[] decompBuffer = decodeOneBlockWithPForDelta(buffer, blockSize);
                    System.arraycopy(decompBuffer, 0, buffer, 0, decompBuffer.length);
                  }
                  catch(Exception e)
                  {
                    e.printStackTrace();
                  }
                }
              }
            };
          }
        };
      }

      @Override
      public IntIndexOutput createOutput(Directory dir, String fileName) throws IOException {
        return new FixedIntBlockIndexOutputWithGetElementNum(dir.createOutput(fileName), blockSize) {
          @Override
          protected void flushBlock() throws IOException {
            if(buffer != null && buffer.length>0)
            {
              // retrieve the number of actual elements in the block
              int numberOfElements = getElementNum();
              int[] compBuffer = null;
              // pad 0s after the actual elements
              if(numberOfElements < blockSize)
              {
                Arrays.fill(buffer, numberOfElements, blockSize, 0);
              }
              // compress the data
              try{
                compBuffer = encodeOneBlockWithPForDelta(buffer, blockSize);
              }
              catch(Exception e)
              {
                e.printStackTrace();
              }
              // write out the compressed size in ints 
              out.writeInt(compBuffer.length);
              // write out the compressed data
              for(int i=0;i<compBuffer.length;i++) {
                out.writeInt(buffer[i]);
              }
            }
          }
        };
      }
    }

  @Override
  public FieldsConsumer fieldsConsumer(SegmentWriteState state) throws IOException {
    PostingsWriterBase postingsWriter = new SepPostingsWriterImpl(state, new PForDeltaIntFactory());

    boolean success = false;
    TermsIndexWriterBase indexWriter;
    try {
      indexWriter = new FixedGapTermsIndexWriter(state);
      success = true;
    } finally {
      if (!success) {
        postingsWriter.close();
      }
    }

    success = false;
    try {
      FieldsConsumer ret = new PrefixCodedTermsWriter(indexWriter, state, postingsWriter, BytesRef.getUTF8SortedAsUnicodeComparator());
      success = true;
      return ret;
    } finally {
      if (!success) {
        try {
          postingsWriter.close();
        } finally {
          indexWriter.close();
        }
      }
    }
  }

  @Override
  public FieldsProducer fieldsProducer(SegmentReadState state) throws IOException {
    PostingsReaderBase postingsReader = new SepPostingsReaderImpl(state.dir,
                                                                      state.segmentInfo,
                                                                      state.readBufferSize,
                                                                      new PForDeltaIntFactory(), state.codecId);

    TermsIndexReaderBase indexReader;
    boolean success = false;
    try {
      indexReader = new FixedGapTermsIndexReader(state.dir,
                                                       state.fieldInfos,
                                                       state.segmentInfo.name,
                                                       state.termsIndexDivisor,
                                                       BytesRef.getUTF8SortedAsUnicodeComparator(), state.codecId);
      success = true;
    } finally {
      if (!success) {
        postingsReader.close();
      }
    }

    success = false;
    try {
      FieldsProducer ret = new PrefixCodedTermsReader(indexReader,
                                                       state.dir,
                                                       state.fieldInfos,
                                                       state.segmentInfo.name,
                                                       postingsReader,
                                                       state.readBufferSize,
                                                       BytesRef.getUTF8SortedAsUnicodeComparator(),
                                                       StandardCodec.TERMS_CACHE_SIZE,
                                                       state.codecId);
      success = true;
      return ret;
    } finally {
      if (!success) {
        try {
          postingsReader.close();
        } finally {
          indexReader.close();
        }
      }
    }
  }

  @Override
  public void files(Directory dir, SegmentInfo segmentInfo, String codecId, Set<String> files) {
    SepPostingsReaderImpl.files(segmentInfo, codecId, files);
    PrefixCodedTermsReader.files(dir, segmentInfo, codecId, files);
    FixedGapTermsIndexReader.files(dir, segmentInfo, codecId, files);
  }

  @Override
  public void getExtensions(Set<String> extensions) {
    SepPostingsWriterImpl.getExtensions(extensions);
    PrefixCodedTermsReader.getExtensions(extensions);
    FixedGapTermsIndexReader.getIndexExtensions(extensions);
  }
}


