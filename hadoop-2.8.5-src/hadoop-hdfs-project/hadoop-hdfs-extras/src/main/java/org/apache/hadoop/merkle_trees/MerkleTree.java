package org.apache.hadoop.merkle_trees;

import java.util.ArrayList;

/**
 * Representation of a classic Merkle Tree created
 * from a given byte array. It has constant size,
 * and also keeps the raw data in chunks.
 */
public class MerkleTree{

    protected Node root;
    protected ArrayList<Node> leaves;

    protected class Node{
        private byte[] hash;
        private Node parent;
        private Node left, right;

        //Leaf Node
        public Node(byte[] data){
            // compute hash from data
            this.hash = Blake2s.digest(data);
            this.parent = null;
            this.left = null;
            this.right = null;
        }

        //Intermidiate or root node
        public Node(Node left, Node right){
            //compute hash from children Nodes
            this.hash = Blake2s.digest(Util.concatBytes(left.getHash(), right.getHash()));
            this.parent = null;
            this.left = left;
            this.right = right;

            left.parent = this;
            right.parent = this;
        }

        public byte[] getHash(){
            return this.hash;
        }

        public Node getParent(){
            return this.parent;
        }

        public Node getLeft(){
            return this.left;
        }

        public Node getRight(){
            return this.right;
        }
    }

    /**
     * Fill all the leaves with correpsonding chunks' hashes.
     * Chunks are formeds from repeating the sequence of bytes of the given block.
     * @param block The raw data
     * @param chunk_size The size of each chunk in bytes
     * @param chunk_count The number of chunks in which the data is split
     */
    public MerkleTree(byte[] block, int chunk_size, int chunk_count){
        assert(block.length <= chunk_count * chunk_size);
        // Just in case we have an empty block
        if(block.length == 0){
            // Initialize all zeroes
            block = new byte[chunk_size];
        }
        // init fields
        this.leaves = new ArrayList<Node>();
        this.root = null;
        // fill chunk and compute hash
        int block_index = 0;
        for (int i = 0; i < chunk_count; i++) {
            int chunk_index = 0;
            byte[] current_chunk = new byte[chunk_size];
            while (chunk_index < chunk_size) {
                int to_copy = Math.min(chunk_size - chunk_index, block.length - block_index);
                System.arraycopy(block, block_index, current_chunk, chunk_index, to_copy);
                chunk_index += to_copy;
                block_index += to_copy;
                if(block_index >= block.length){
                    block_index = 0;
                }
            }
            this.leaves.add(new Node(current_chunk));
        }
    }

    protected MerkleTree(){
        /* nothing */
    }

    /**
     * Builds the Merkle Tree from the initialized leaves.
     */
    public void build(){
        ArrayList<Node> current, prev;
        current = this.leaves;
        // Building the tree from bottom-up
        while (current.size() > 1) {
            prev = current;
            current = new ArrayList<Node>();
            for (int i = 0; i < prev.size(); i+=2) {
                current.add(new Node(prev.get(i), prev.get(i+1)));
            }
        }
        this.root = current.get(0);
    }

    public byte[] getRoot() {
        return this.root.getHash();
    }

}
