/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.CryptoProtocolVersion;
import org.apache.hadoop.fs.CanSetDropBehind;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSOutputSummer;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileEncryptionInfo;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Syncable;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream;
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream.SyncFlag;
import org.apache.hadoop.hdfs.client.impl.DfsClientConf;
import org.apache.hadoop.hdfs.protocol.DSQuotaExceededException;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.NSQuotaExceededException;
import org.apache.hadoop.hdfs.protocol.QuotaByStorageTypeExceededException;
import org.apache.hadoop.hdfs.protocol.SnapshotAccessControlException;
import org.apache.hadoop.hdfs.protocol.UnresolvedPathException;
import org.apache.hadoop.hdfs.protocol.datatransfer.PacketHeader;
import org.apache.hadoop.hdfs.protocol.datatransfer.PacketReceiver;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.server.datanode.CachingStrategy;
import org.apache.hadoop.hdfs.server.namenode.RetryStartFileException;
import org.apache.hadoop.hdfs.server.namenode.SafeModeException;
import org.apache.hadoop.hdfs.util.ByteArrayManager;
import org.apache.hadoop.io.EnumSetWritable;
import org.apache.hadoop.io.MultipleIOException;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.merkle_trees.MerkleTree;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.DataChecksum.Type;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.Time;
import org.apache.htrace.core.TraceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;


/****************************************************************
 * DFSOutputStream creates files from a stream of bytes.
 *
 * The client application writes data that is cached internally by
 * this stream. Data is broken up into packets, each packet is
 * typically 64K in size. A packet comprises of chunks. Each chunk
 * is typically 512 bytes and has an associated checksum with it.
 *
 * When a client application fills up the currentPacket, it is
 * enqueued into the dataQueue of DataStreamer. DataStreamer is a
 * thread that picks up packets from the dataQueue and sends it to
 * the first datanode in the pipeline.
 *
 ****************************************************************/
@InterfaceAudience.Private
public class DFSOutputStream extends FSOutputSummer
    implements Syncable, CanSetDropBehind {
  static final Logger LOG = LoggerFactory.getLogger(DFSOutputStream.class);
  /**
   * Number of times to retry creating a file when there are transient
   * errors (typically related to encryption zones and KeyProvider operations).
   */
  @VisibleForTesting
  static final int CREATE_RETRY_COUNT = 10;
  @VisibleForTesting
  static CryptoProtocolVersion[] SUPPORTED_CRYPTO_VERSIONS =
      CryptoProtocolVersion.supported();

  protected final DFSClient dfsClient;
  protected final ByteArrayManager byteArrayManager;
  // closed is accessed by different threads under different locks.
  protected volatile boolean closed = false;

  protected final String src;
  protected final long fileId;
  protected final long blockSize;
  protected final int bytesPerChecksum;

  protected DFSPacket currentPacket = null;
  private DataStreamer streamer;
  protected int packetSize = 0; // write packet size, not including the header.
  protected int chunksPerPacket = 0;
  protected long lastFlushOffset = 0; // offset when flush was invoked
  private long initialFileSize = 0; // at time of file open
  private final short blockReplication; // replication factor of file
  protected boolean shouldSyncBlock = false; // force blocks to disk upon close
  private final EnumSet<AddBlockFlag> addBlockFlags;
  protected final AtomicReference<CachingStrategy> cachingStrategy;
  private FileEncryptionInfo fileEncryptionInfo;
  private int writePacketSize;
  // a buffer used to store the block data
  protected ByteArrayOutputStream block_buffer;
  protected long mtree_total_time = 0;

  /** Use {@link ByteArrayManager} to create buffer for non-heartbeat packets.*/
  protected DFSPacket createPacket(int packetSize, int chunksPerPkt,
      long offsetInBlock, long seqno, boolean lastPacketInBlock)
      throws InterruptedIOException {
    final byte[] buf;
    final int bufferSize = PacketHeader.PKT_MAX_HEADER_LEN + packetSize;

    try {
      buf = byteArrayManager.newByteArray(bufferSize);
    } catch (InterruptedException ie) {
      final InterruptedIOException iioe = new InterruptedIOException(
          "seqno=" + seqno);
      iioe.initCause(ie);
      throw iioe;
    }

    return new DFSPacket(buf, chunksPerPkt, offsetInBlock, seqno,
                         getChecksumSize(), lastPacketInBlock);
  }

  @Override
  protected void checkClosed() throws IOException {
    if (isClosed()) {
      getStreamer().getLastException().throwException4Close();
    }
  }

  //
  // returns the list of targets, if any, that is being currently used.
  //
  @VisibleForTesting
  public synchronized DatanodeInfo[] getPipeline() {
    if (getStreamer().streamerClosed()) {
      return null;
    }
    DatanodeInfo[] currentNodes = getStreamer().getNodes();
    if (currentNodes == null) {
      return null;
    }
    DatanodeInfo[] value = new DatanodeInfo[currentNodes.length];
    System.arraycopy(currentNodes, 0, value, 0, currentNodes.length);
    return value;
  }

  /** 
   * @return the object for computing checksum.
   *         The type is NULL if checksum is not computed.
   */
  private static DataChecksum getChecksum4Compute(DataChecksum checksum,
      HdfsFileStatus stat) {
    if (DataStreamer.isLazyPersist(stat) && stat.getReplication() == 1) {
      // do not compute checksum for writing to single replica to memory
      return DataChecksum.newDataChecksum(Type.NULL,
          checksum.getBytesPerChecksum());
    }
    return checksum;
  }

  private DFSOutputStream(DFSClient dfsClient, String src,
      EnumSet<CreateFlag> flag,
      Progressable progress, HdfsFileStatus stat, DataChecksum checksum) {
    super(getChecksum4Compute(checksum, stat));
    this.dfsClient = dfsClient;
    this.src = src;
    this.fileId = stat.getFileId();
    this.blockSize = stat.getBlockSize();
    this.blockReplication = stat.getReplication();
    this.fileEncryptionInfo = stat.getFileEncryptionInfo();
    this.block_buffer = new ByteArrayOutputStream();
    this.cachingStrategy = new AtomicReference<>(
        dfsClient.getDefaultWriteCachingStrategy());
    this.addBlockFlags = EnumSet.noneOf(AddBlockFlag.class);
    if (flag.contains(CreateFlag.NO_LOCAL_WRITE)) {
      this.addBlockFlags.add(AddBlockFlag.NO_LOCAL_WRITE);
    }
    if (progress != null) {
      DFSClient.LOG.debug("Set non-null progress callback on DFSOutputStream "
          +"{}", src);
    }

    initWritePacketSize();

    this.bytesPerChecksum = checksum.getBytesPerChecksum();
    if (bytesPerChecksum <= 0) {
      throw new HadoopIllegalArgumentException(
          "Invalid value: bytesPerChecksum = " + bytesPerChecksum + " <= 0");
    }
    if (blockSize % bytesPerChecksum != 0) {
      throw new HadoopIllegalArgumentException("Invalid values: "
          + HdfsClientConfigKeys.DFS_BYTES_PER_CHECKSUM_KEY
          + " (=" + bytesPerChecksum + ") must divide block size (=" +
          blockSize + ").");
    }
    this.byteArrayManager = dfsClient.getClientContext().getByteArrayManager();
  }

  /**
   * Ensures the configured writePacketSize never exceeds
   * PacketReceiver.MAX_PACKET_SIZE.
   */
  private void initWritePacketSize() {
    writePacketSize = dfsClient.getConf().getWritePacketSize();
    if (writePacketSize > PacketReceiver.MAX_PACKET_SIZE) {
      LOG.warn(
          "Configured write packet exceeds {} bytes as max,"
              + " using {} bytes.",
          PacketReceiver.MAX_PACKET_SIZE, PacketReceiver.MAX_PACKET_SIZE);
      writePacketSize = PacketReceiver.MAX_PACKET_SIZE;
    }
  }

  /** Construct a new output stream for creating a file. */
  protected DFSOutputStream(DFSClient dfsClient, String src, HdfsFileStatus stat,
      EnumSet<CreateFlag> flag, Progressable progress,
      DataChecksum checksum, String[] favoredNodes) throws IOException {
    this(dfsClient, src, flag, progress, stat, checksum);
    this.shouldSyncBlock = flag.contains(CreateFlag.SYNC_BLOCK);

    computePacketChunkSize(dfsClient.getConf().getWritePacketSize(),
        bytesPerChecksum);

    streamer = new DataStreamer(stat, null, dfsClient, src, progress, checksum,
        cachingStrategy, byteArrayManager, favoredNodes, addBlockFlags);
  }

  static DFSOutputStream newStreamForCreate(DFSClient dfsClient, String src,
      FsPermission masked, EnumSet<CreateFlag> flag, boolean createParent,
      short replication, long blockSize, Progressable progress,
      DataChecksum checksum, String[] favoredNodes) throws IOException {
    try (TraceScope ignored =
             dfsClient.newPathTraceScope("newStreamForCreate", src)) {
      HdfsFileStatus stat = null;

      // Retry the create if we get a RetryStartFileException up to a maximum
      // number of times
      boolean shouldRetry = true;
      int retryCount = CREATE_RETRY_COUNT;
      while (shouldRetry) {
        shouldRetry = false;
        try {
          stat = dfsClient.namenode.create(src, masked, dfsClient.clientName,
              new EnumSetWritable<>(flag), createParent, replication,
              blockSize, SUPPORTED_CRYPTO_VERSIONS);
          break;
        } catch (RemoteException re) {
          IOException e = re.unwrapRemoteException(
              AccessControlException.class,
              DSQuotaExceededException.class,
              QuotaByStorageTypeExceededException.class,
              FileAlreadyExistsException.class,
              FileNotFoundException.class,
              ParentNotDirectoryException.class,
              NSQuotaExceededException.class,
              RetryStartFileException.class,
              SafeModeException.class,
              UnresolvedPathException.class,
              SnapshotAccessControlException.class,
              UnknownCryptoProtocolVersionException.class);
          if (e instanceof RetryStartFileException) {
            if (retryCount > 0) {
              shouldRetry = true;
              retryCount--;
            } else {
              throw new IOException("Too many retries because of encryption" +
                  " zone operations", e);
            }
          } else {
            throw e;
          }
        }
      }
      Preconditions.checkNotNull(stat, "HdfsFileStatus should not be null!");
      final DFSOutputStream out = new DFSOutputStream(dfsClient, src, stat,
          flag, progress, checksum, favoredNodes);
      out.start();
      return out;
    }
  }

  /** Construct a new output stream for append. */
  private DFSOutputStream(DFSClient dfsClient, String src,
      EnumSet<CreateFlag> flags, Progressable progress, LocatedBlock lastBlock,
      HdfsFileStatus stat, DataChecksum checksum, String[] favoredNodes)
          throws IOException {
    this(dfsClient, src, flags, progress, stat, checksum);
    initialFileSize = stat.getLen(); // length of file when opened
    this.shouldSyncBlock = flags.contains(CreateFlag.SYNC_BLOCK);

    boolean toNewBlock = flags.contains(CreateFlag.NEW_BLOCK);

    this.fileEncryptionInfo = stat.getFileEncryptionInfo();

    // The last partial block of the file has to be filled.
    if (!toNewBlock && lastBlock != null) {
      // indicate that we are appending to an existing block
      streamer = new DataStreamer(lastBlock, stat, dfsClient, src, progress,
          checksum, cachingStrategy, byteArrayManager);
      getStreamer().setBytesCurBlock(lastBlock.getBlockSize());
      adjustPacketChunkSize(stat);
      getStreamer().setPipelineInConstruction(lastBlock);
      // When appending to existing blocks reload data of last block (that got modified)
      // and calculate merkle root 
      FSDataInputStream in = null;
      Configuration conf = new Configuration();
      FileSystem fs = FileSystem.get(conf);
      Path inFile = new Path(src);
      in = fs.open(inFile);
      //take the data of the current block in order to fill it and create the total merkle tree
      IOUtils.copyLarge(in, this.block_buffer, initialFileSize-lastBlock.getBlockSize(), lastBlock.getBlockSize());
    } else {
      computePacketChunkSize(dfsClient.getConf().getWritePacketSize(),
          bytesPerChecksum);
      streamer = new DataStreamer(stat,
          lastBlock != null ? lastBlock.getBlock() : null, dfsClient, src,
          progress, checksum, cachingStrategy, byteArrayManager, favoredNodes,
          addBlockFlags);
    }
  }

  private void adjustPacketChunkSize(HdfsFileStatus stat) throws IOException{

    long usedInLastBlock = stat.getLen() % blockSize;
    int freeInLastBlock = (int)(blockSize - usedInLastBlock);

    // calculate the amount of free space in the pre-existing
    // last crc chunk
    int usedInCksum = (int)(stat.getLen() % bytesPerChecksum);
    int freeInCksum = bytesPerChecksum - usedInCksum;

    // if there is space in the last block, then we have to
    // append to that block
    if (freeInLastBlock == blockSize) {
      throw new IOException("The last block for file " +
          src + " is full.");
    }

    if (usedInCksum > 0 && freeInCksum > 0) {
      // if there is space in the last partial chunk, then
      // setup in such a way that the next packet will have only
      // one chunk that fills up the partial chunk.
      //
      computePacketChunkSize(0, freeInCksum);
      setChecksumBufSize(freeInCksum);
      getStreamer().setAppendChunk(true);
    } else {
      // if the remaining space in the block is smaller than
      // that expected size of of a packet, then create
      // smaller size packet.
      //
      computePacketChunkSize(
          Math.min(dfsClient.getConf().getWritePacketSize(), freeInLastBlock),
          bytesPerChecksum);
    }
  }

  static DFSOutputStream newStreamForAppend(DFSClient dfsClient, String src,
      EnumSet<CreateFlag> flags, Progressable progress, LocatedBlock lastBlock,
      HdfsFileStatus stat, DataChecksum checksum, String[] favoredNodes)
      throws IOException {
    try (TraceScope ignored =
             dfsClient.newPathTraceScope("newStreamForAppend", src)) {
      final DFSOutputStream out = new DFSOutputStream(dfsClient, src, flags,
          progress, lastBlock, stat, checksum, favoredNodes);
      out.start();
      return out;
    }
  }

  protected void computePacketChunkSize(int psize, int csize) {
    final int bodySize = psize - PacketHeader.PKT_MAX_HEADER_LEN;
    final int chunkSize = csize + getChecksumSize();
    chunksPerPacket = Math.max(bodySize/chunkSize, 1);
    packetSize = chunkSize*chunksPerPacket;
    DFSClient.LOG.debug("computePacketChunkSize: src={}, chunkSize={}, "
            + "chunksPerPacket={}, packetSize={}",
        src, chunkSize, chunksPerPacket, packetSize);
  }

  protected TraceScope createWriteTraceScope() {
    return dfsClient.newPathTraceScope("DFSOutputStream#write", src);
  }

  // @see FSOutputSummer#writeChunk()
  @Override
  protected synchronized void writeChunk(byte[] b, int offset, int len,
      byte[] checksum, int ckoff, int cklen) throws IOException {
    dfsClient.checkOpen();
    checkClosed();

    if (len > bytesPerChecksum) {
      throw new IOException("writeChunk() buffer size is " + len +
                            " is larger than supported  bytesPerChecksum " +
                            bytesPerChecksum);
    }
    if (cklen != 0 && cklen != getChecksumSize()) {
      throw new IOException("writeChunk() checksum size is supposed to be " +
                            getChecksumSize() + " but found to be " + cklen);
    }

    if (currentPacket == null) {
      currentPacket = createPacket(packetSize, chunksPerPacket, getStreamer()
          .getBytesCurBlock(), getStreamer().getAndIncCurrentSeqno(), false);
      DFSClient.LOG.debug("DFSClient writeChunk allocating new packet seqno={},"
              + " src={}, packetSize={}, chunksPerPacket={}, bytesCurBlock={}",
          currentPacket.getSeqno(), src, packetSize, chunksPerPacket,
          getStreamer().getBytesCurBlock());
    }

    currentPacket.writeChecksum(checksum, ckoff, cklen);
    currentPacket.writeData(b, offset, len);
    currentPacket.incNumChunks();
    getStreamer().incBytesCurBlock(len);
    // write the packet data to our buffer (accumulating the whole block)
    this.block_buffer.write(b, offset, len);
    // If packet is full, enqueue it for transmission
    //
    if (currentPacket.getNumChunks() == currentPacket.getMaxChunks() ||
        getStreamer().getBytesCurBlock() == blockSize) {
      enqueueCurrentPacketFull();
    }
  }

  void enqueueCurrentPacket() throws IOException {
    getStreamer().waitAndQueuePacket(currentPacket);
    currentPacket = null;
  }

  void enqueueCurrentPacketFull() throws IOException {
    LOG.debug("enqueue full {}, src={}, bytesCurBlock={}, blockSize={},"
        + " appendChunk={}, {}", currentPacket, src, getStreamer()
        .getBytesCurBlock(), blockSize, getStreamer().getAppendChunk(),
        getStreamer());
    enqueueCurrentPacket();
    adjustChunkBoundary();
    endBlock();
  }

  /** create an empty packet to mark the end of the block. */
  void setCurrentPacketToEmpty() throws InterruptedIOException {
    currentPacket = createPacket(0, 0, getStreamer().getBytesCurBlock(),
        getStreamer().getAndIncCurrentSeqno(), true);
    currentPacket.setSyncBlock(shouldSyncBlock);
  }

  /**
   * If the reopened file did not end at chunk boundary and the above
   * write filled up its partial chunk. Tell the summer to generate full
   * crc chunks from now on.
   */
  protected void adjustChunkBoundary() {
    if (getStreamer().getAppendChunk() &&
        getStreamer().getBytesCurBlock() % bytesPerChecksum == 0) {
      getStreamer().setAppendChunk(false);
      resetChecksumBufSize();
    }

    if (!getStreamer().getAppendChunk()) {
      final int psize = (int) Math
          .min(blockSize - getStreamer().getBytesCurBlock(), writePacketSize);
      computePacketChunkSize(psize, bytesPerChecksum);
    }
  }

  /**
   * Used in test only.
   */
  @VisibleForTesting
  void setAppendChunk(final boolean appendChunk) {
    getStreamer().setAppendChunk(appendChunk);
  }

  /**
   * Used in test only.
   */
  @VisibleForTesting
  void setBytesCurBlock(final long bytesCurBlock) {
    getStreamer().setBytesCurBlock(bytesCurBlock);
  }

  /**
   * if encountering a block boundary, send an empty packet to
   * indicate the end of block and reset bytesCurBlock.
   *
   * @throws IOException
   */
  protected void endBlock() throws IOException {
    if (getStreamer().getBytesCurBlock() == blockSize) {
    	long mtree_start = System.currentTimeMillis();
      MerkleTree tree = new MerkleTree(this.block_buffer.toByteArray(),
      																this.dfsClient.getConf().getDefaultChunkSize(),
      																this.dfsClient.getConf().getDefaultMerkleTreeHeight());
      this.block_buffer.reset();
      tree.build();
      mtree_total_time += System.currentTimeMillis() - mtree_start;
      //System.out.println("Generated hash from endblock : "+tree.getRoot());
      // add merkle root to queue for later (when block id is known)
      getStreamer().push_root_hash(tree.getRoot());
      setCurrentPacketToEmpty();
      enqueueCurrentPacket();
      getStreamer().setBytesCurBlock(0);
      lastFlushOffset = 0;
    }
  }

  @Deprecated
  public void sync() throws IOException {
    hflush();
  }
  
  /**
   * Flushes out to all replicas of the block. The data is in the buffers
   * of the DNs but not necessarily in the DN's OS buffers.
   *
   * It is a synchronous operation. When it returns,
   * it guarantees that flushed data become visible to new readers.
   * It is not guaranteed that data has been flushed to
   * persistent store on the datanode.
   * Block allocations are persisted on namenode.
   */
  @Override
  public void hflush() throws IOException {
    try (TraceScope ignored = dfsClient.newPathTraceScope("hflush", src)) {
      flushOrSync(false, EnumSet.noneOf(SyncFlag.class));
    }
  }

  @Override
  public void hsync() throws IOException {
    try (TraceScope ignored = dfsClient.newPathTraceScope("hsync", src)) {
      flushOrSync(true, EnumSet.noneOf(SyncFlag.class));
    }
  }
  
  /**
   * The expected semantics is all data have flushed out to all replicas
   * and all replicas have done posix fsync equivalent - ie the OS has
   * flushed it to the disk device (but the disk may have it in its cache).
   * 
   * Note that only the current block is flushed to the disk device.
   * To guarantee durable sync across block boundaries the stream should
   * be created with {@link CreateFlag#SYNC_BLOCK}.
   * 
   * @param syncFlags
   *          Indicate the semantic of the sync. Currently used to specify
   *          whether or not to update the block length in NameNode.
   */
  public void hsync(EnumSet<SyncFlag> syncFlags) throws IOException {
    try (TraceScope ignored = dfsClient.newPathTraceScope("hsync", src)) {
      flushOrSync(true, syncFlags);
    }
  }

  /**
   * Flush/Sync buffered data to DataNodes.
   * 
   * @param isSync
   *          Whether or not to require all replicas to flush data to the disk
   *          device
   * @param syncFlags
   *          Indicate extra detailed semantic of the flush/sync. Currently
   *          mainly used to specify whether or not to update the file length in
   *          the NameNode
   * @throws IOException
   */
  private void flushOrSync(boolean isSync, EnumSet<SyncFlag> syncFlags)
      throws IOException {
    dfsClient.checkOpen();
    checkClosed();
    try {
      long toWaitFor;
      long lastBlockLength = -1L;
      boolean updateLength = syncFlags.contains(SyncFlag.UPDATE_LENGTH);
      boolean endBlock = syncFlags.contains(SyncFlag.END_BLOCK);
      synchronized (this) {
        // flush checksum buffer, but keep checksum buffer intact if we do not
        // need to end the current block
        int numKept = flushBuffer(!endBlock, true);
        // bytesCurBlock potentially incremented if there was buffered data

        DFSClient.LOG.debug("DFSClient flush():  bytesCurBlock={}, "
                + "lastFlushOffset={}, createNewBlock={}",
            getStreamer().getBytesCurBlock(), lastFlushOffset, endBlock);
        // Flush only if we haven't already flushed till this offset.
        if (lastFlushOffset != getStreamer().getBytesCurBlock()) {
          assert getStreamer().getBytesCurBlock() > lastFlushOffset;
          // record the valid offset of this flush
          lastFlushOffset = getStreamer().getBytesCurBlock();
          if (isSync && currentPacket == null && !endBlock) {
            // Nothing to send right now,
            // but sync was requested.
            // Send an empty packet if we do not end the block right now
            currentPacket = createPacket(packetSize, chunksPerPacket,
                getStreamer().getBytesCurBlock(), getStreamer()
                    .getAndIncCurrentSeqno(), false);
          }
        } else {
          if (isSync && getStreamer().getBytesCurBlock() > 0 && !endBlock) {
            // Nothing to send right now,
            // and the block was partially written,
            // and sync was requested.
            // So send an empty sync packet if we do not end the block right
            // now
            currentPacket = createPacket(packetSize, chunksPerPacket,
                getStreamer().getBytesCurBlock(), getStreamer()
                    .getAndIncCurrentSeqno(), false);
          } else if (currentPacket != null) {
            // just discard the current packet since it is already been sent.
            currentPacket.releaseBuffer(byteArrayManager);
            currentPacket = null;
          }
        }
        if (currentPacket != null) {
          currentPacket.setSyncBlock(isSync);
          enqueueCurrentPacket();
        }
        if (endBlock && getStreamer().getBytesCurBlock() > 0) {
          // Need to end the current block, thus send an empty packet to
          // indicate this is the end of the block and reset bytesCurBlock
          currentPacket = createPacket(0, 0, getStreamer().getBytesCurBlock(),
              getStreamer().getAndIncCurrentSeqno(), true);
          currentPacket.setSyncBlock(shouldSyncBlock || isSync);
          enqueueCurrentPacket();
          getStreamer().setBytesCurBlock(0);
          lastFlushOffset = 0;
        } else {
          // Restore state of stream. Record the last flush offset
          // of the last full chunk that was flushed.
          getStreamer().setBytesCurBlock(
              getStreamer().getBytesCurBlock() - numKept);
        }

        toWaitFor = getStreamer().getLastQueuedSeqno();
      } // end synchronized

      getStreamer().waitForAckedSeqno(toWaitFor);

      // update the block length first time irrespective of flag
      if (updateLength || getStreamer().getPersistBlocks().get()) {
        synchronized (this) {
          final ExtendedBlock block = getStreamer().getBlock();
          if (!getStreamer().streamerClosed() && block != null) {
            lastBlockLength = block.getNumBytes();
          }
        }
      }
      // If 1) any new blocks were allocated since the last flush, or 2) to
      // update length in NN is required, then persist block locations on
      // namenode.
      if (getStreamer().getPersistBlocks().getAndSet(false) || updateLength) {
        try {
          dfsClient.namenode.fsync(src, fileId, dfsClient.clientName,
              lastBlockLength);
        } catch (IOException ioe) {
          DFSClient.LOG.warn("Unable to persist blocks in hflush for " + src,
              ioe);
          // If we got an error here, it might be because some other thread
          // called close before our hflush completed. In that case, we should
          // throw an exception that the stream is closed.
          checkClosed();
          // If we aren't closed but failed to sync, we should expose that to
          // the caller.
          throw ioe;
        }
      }

      synchronized(this) {
        if (!getStreamer().streamerClosed()) {
          getStreamer().setHflush();
        }
      }
    } catch (InterruptedIOException interrupt) {
      // This kind of error doesn't mean that the stream itself is broken - just
      // the flushing thread got interrupted. So, we shouldn't close down the
      // writer, but instead just propagate the error
      throw interrupt;
    } catch (IOException e) {
      DFSClient.LOG.warn("Error while syncing", e);
      synchronized (this) {
        if (!isClosed()) {
          getStreamer().getLastException().set(e);
          closeThreads(true);
        }
      }
      throw e;
    }
  }

  /**
   * @deprecated use {@link HdfsDataOutputStream#getCurrentBlockReplication()}.
   */
  @Deprecated
  public synchronized int getNumCurrentReplicas() throws IOException {
    return getCurrentBlockReplication();
  }

  /**
   * Note that this is not a public API;
   * use {@link HdfsDataOutputStream#getCurrentBlockReplication()} instead.
   * 
   * @return the number of valid replicas of the current block
   */
  public synchronized int getCurrentBlockReplication() throws IOException {
    dfsClient.checkOpen();
    checkClosed();
    if (getStreamer().streamerClosed()) {
      return blockReplication; // no pipeline, return repl factor of file
    }
    DatanodeInfo[] currentNodes = getStreamer().getNodes();
    if (currentNodes == null) {
      return blockReplication; // no pipeline, return repl factor of file
    }
    return currentNodes.length;
  }
  
  /**
   * Waits till all existing data is flushed and confirmations
   * received from datanodes.
   */
  protected void flushInternal() throws IOException {
    long toWaitFor;
    synchronized (this) {
      dfsClient.checkOpen();
      checkClosed();
      //
      // If there is data in the current buffer, send it across
      //
      long mtree_start = System.currentTimeMillis();
      MerkleTree tree = new MerkleTree(this.block_buffer.toByteArray(),
														      		this.dfsClient.getConf().getDefaultChunkSize(),
																			this.dfsClient.getConf().getDefaultMerkleTreeHeight());
      this.block_buffer.reset();
      tree.build();
      mtree_total_time += System.currentTimeMillis() - mtree_start;
      //System.out.println("Generated hash from flush interval "+tree.getRoot());
      // add merkle root to queue for later (when block id is known)
      getStreamer().push_root_hash(tree.getRoot());
      getStreamer().queuePacket(currentPacket);
      currentPacket = null;
      toWaitFor = getStreamer().getLastQueuedSeqno();
    }

    getStreamer().waitForAckedSeqno(toWaitFor);
  }

  protected synchronized void start() {
    getStreamer().start();
  }
  
  /**
   * Aborts this output stream and releases any system
   * resources associated with this stream.
   */
  void abort() throws IOException {
    final MultipleIOException.Builder b = new MultipleIOException.Builder();
    synchronized (this) {
      if (isClosed()) {
        return;
      }
      getStreamer().getLastException().set(
          new IOException("Lease timeout of "
              + (dfsClient.getConf().getHdfsTimeout() / 1000)
              + " seconds expired."));

      try {
        closeThreads(true);
      } catch (IOException e) {
        b.add(e);
      }
    }
    final IOException ioe = b.build();
    if (ioe != null) {
      throw ioe;
    }
  }

  boolean isClosed() {
    return closed || getStreamer().streamerClosed();
  }

  void setClosed() {
    closed = true;
    dfsClient.endFileLease(fileId);
    getStreamer().release();
  }

  // shutdown datastreamer and responseprocessor threads.
  // interrupt datastreamer if force is true
  protected void closeThreads(boolean force) throws IOException {
    try {
      getStreamer().close(force);
      getStreamer().join();
      getStreamer().closeSocket();
    } catch (InterruptedException e) {
      throw new IOException("Failed to shutdown streamer");
    } finally {
      getStreamer().setSocketToNull();
      setClosed();
    }
  }
  
  /**
   * Closes this output stream and releases any system
   * resources associated with this stream.
   */
  @Override
  public void close() throws IOException {
    final MultipleIOException.Builder b = new MultipleIOException.Builder();
    synchronized (this) {
      try (TraceScope ignored = dfsClient.newPathTraceScope(
          "DFSOutputStream#close", src)) {
        closeImpl();
      } catch (IOException e) {
        b.add(e);
      }
    }
    final IOException ioe = b.build();
    if (ioe != null) {
      throw ioe;
    }
  }

  protected synchronized void closeImpl() throws IOException {
    if (isClosed()) {
      LOG.debug("Closing an already closed stream. [Stream:{}, streamer:{}]",
          closed, getStreamer().streamerClosed());
      try {
        getStreamer().getLastException().check(true);
      } catch (IOException ioe) {
        cleanupAndRethrowIOException(ioe);
      } finally {
        if (!closed) {
          // If stream is not closed but streamer closed, clean up the stream.
          // Most importantly, end the file lease.
          closeThreads(true);
        }
      }
      return;
    }

    try {
      flushBuffer();       // flush from all upper layers

      if (currentPacket != null) {
        enqueueCurrentPacket();
      }

      if (getStreamer().getBytesCurBlock() != 0) {
        setCurrentPacketToEmpty();
      }

      try {
        flushInternal();             // flush all data to Datanodes
      } catch (IOException ioe) {
        cleanupAndRethrowIOException(ioe);
      }
      completeFile();
    } catch (ClosedChannelException ignored) {
    } finally {
      // Failures may happen when flushing data.
      // Streamers may keep waiting for the new block information.
      // Thus need to force closing these threads.
      // Don't need to call setClosed() because closeThreads(true)
      // calls setClosed() in the finally block.
      closeThreads(true);
    }
  }

  private void completeFile() throws IOException {
    // get last block before destroying the streamer
    ExtendedBlock lastBlock = getStreamer().getBlock();
    try (TraceScope ignored =
        dfsClient.getTracer().newScope("completeFile")) {
      completeFile(lastBlock);
    }
  }

  /**
   * Determines whether an IOException thrown needs extra cleanup on the stream.
   * Space quota exceptions will be thrown when getting new blocks, so the
   * open HDFS file need to be closed.
   *
   * @param ioe the IOException
   * @return whether the stream needs cleanup for the given IOException
   */
  private boolean exceptionNeedsCleanup(IOException ioe) {
    return ioe instanceof DSQuotaExceededException
        || ioe instanceof QuotaByStorageTypeExceededException;
  }

  private void cleanupAndRethrowIOException(IOException ioe)
      throws IOException {
    if (exceptionNeedsCleanup(ioe)) {
      final MultipleIOException.Builder b = new MultipleIOException.Builder();
      b.add(ioe);
      try {
        completeFile();
      } catch (IOException e) {
        b.add(e);
        throw b.build();
      }
    }
    throw ioe;
  }

  // should be called holding (this) lock since setTestFilename() may
  // be called during unit tests
  protected void completeFile(ExtendedBlock last) throws IOException {
    long localstart = Time.monotonicNow();
    final DfsClientConf conf = dfsClient.getConf();
    long sleeptime = conf.getBlockWriteLocateFollowingInitialDelayMs();
    boolean fileComplete = false;
    int retries = conf.getNumBlockWriteLocateFollowingRetry();
    // remove root hash of last block from list and upload
    this.dfsClient.getConnection().uploadHash(last.getBlockPoolId(), last.getBlockId(), getStreamer().remove_last_hash());
    // wait for all the merkle root uploads to complete before completing and finalizing the file
    //long wait_start = System.currentTimeMillis();
    this.dfsClient.getConnection().waitForUploads();
    //System.out.println("Time spent waiting until transaction sending completes: "+Long.toString(System.currentTimeMillis()-wait_start));
    System.out.println("Time spent building merkle trees: "+Long.toString(mtree_total_time));
    while (!fileComplete) {
      fileComplete =
          dfsClient.namenode.complete(src, dfsClient.clientName, last, fileId);
      if (!fileComplete) {
        final int hdfsTimeout = conf.getHdfsTimeout();
        if (!dfsClient.clientRunning
            || (hdfsTimeout > 0
                && localstart + hdfsTimeout < Time.monotonicNow())) {
          String msg = "Unable to close file because dfsclient " +
              " was unable to contact the HDFS servers. clientRunning " +
              dfsClient.clientRunning + " hdfsTimeout " + hdfsTimeout;
          DFSClient.LOG.info(msg);
          throw new IOException(msg);
        }
        try {
          if (retries == 0) {
            throw new IOException("Unable to close file because the last block"
                + last + " does not have enough number of replicas.");
          }
          retries--;
          Thread.sleep(sleeptime);
          sleeptime *= 2;
          if (Time.monotonicNow() - localstart > 5000) {
            DFSClient.LOG.info("Could not complete " + src + " retrying...");
          }
        } catch (InterruptedException ie) {
          DFSClient.LOG.warn("Caught exception ", ie);
        }
      }
    }
  }

  @VisibleForTesting
  public void setArtificialSlowdown(long period) {
    getStreamer().setArtificialSlowdown(period);
  }

  @VisibleForTesting
  public synchronized void setChunksPerPacket(int value) {
    chunksPerPacket = Math.min(chunksPerPacket, value);
    packetSize = (bytesPerChecksum + getChecksumSize()) * chunksPerPacket;
  }

  /**
   * Returns the size of a file as it was when this stream was opened
   */
  public long getInitialLen() {
    return initialFileSize;
  }

  protected EnumSet<AddBlockFlag> getAddBlockFlags() {
    return addBlockFlags;
  }

  /**
   * @return the FileEncryptionInfo for this stream, or null if not encrypted.
   */
  public FileEncryptionInfo getFileEncryptionInfo() {
    return fileEncryptionInfo;
  }

  /**
   * Returns the access token currently used by streamer, for testing only
   */
  synchronized Token<BlockTokenIdentifier> getBlockToken() {
    return getStreamer().getBlockToken();
  }

  @Override
  public void setDropBehind(Boolean dropBehind) throws IOException {
    CachingStrategy prevStrategy, nextStrategy;
    // CachingStrategy is immutable.  So build a new CachingStrategy with the
    // modifications we want, and compare-and-swap it in.
    do {
      prevStrategy = this.cachingStrategy.get();
      nextStrategy = new CachingStrategy.Builder(prevStrategy).
                        setDropBehind(dropBehind).build();
    } while (!this.cachingStrategy.compareAndSet(prevStrategy, nextStrategy));
  }

  @VisibleForTesting
  ExtendedBlock getBlock() {
    return getStreamer().getBlock();
  }

  @VisibleForTesting
  public long getFileId() {
    return fileId;
  }

  /**
   * Return the source of stream.
   */
  String getSrc() {
    return src;
  }

  /**
   * Returns the data streamer object.
   */
  protected DataStreamer getStreamer() {
    return streamer;
  }
}
