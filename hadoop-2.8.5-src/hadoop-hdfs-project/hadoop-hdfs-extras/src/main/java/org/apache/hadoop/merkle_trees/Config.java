package org.apache.hadoop.merkle_trees;

public class Config {
    // in bytes
    public static final int BLOCK_SIZE = 32 * 1024 * 1024;
    public static final int CHUNK_SIZE = 512;
    public static final int CHUNK_COUNT = BLOCK_SIZE / CHUNK_SIZE;
    public static final String ZOK_DIR = "/home/nick/Desktop/zok_test/";
}
