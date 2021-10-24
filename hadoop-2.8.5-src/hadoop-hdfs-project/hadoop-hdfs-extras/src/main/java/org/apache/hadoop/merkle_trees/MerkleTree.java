package org.apache.hadoop.merkle_trees;

//import java.math.BigInteger;
//import java.nio.file.Files;
//import java.nio.file.Paths;
//import java.util.List;
import java.util.ArrayList;

/**
 * Representation of a classic Merkle Tree created
 * from a given byte array. It has constant size,
 * and also keeps the raw data in chunks.
 */
public class MerkleTree{

    protected Node root;
    protected ArrayList<Node> leaves;
    protected ArrayList<byte[]> chunks;

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
     * Fills all chunks with the
     * repeating byte sequence from the given block of data.
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
        this.chunks = new ArrayList<byte[]>();
        this.root = null;
        // fill all chunks with the repeating byte sequence from input/block
        int block_index = 0;
        for (int i = 0; i < chunk_count; i++) {
            int chunk_index = 0;
            byte[] ch = new byte[chunk_size];
            while (chunk_index < chunk_size) {
                int to_copy = Math.min(chunk_size - chunk_index, block.length - block_index);
                System.arraycopy(block, block_index, ch, chunk_index, to_copy);
                chunk_index += to_copy;
                block_index += to_copy;
                if(block_index >= block.length){
                    block_index = 0;
                }
            }
            this.chunks.add(ch);
        }
    }

    /**
     * Builds the Merkle Tree from the stored raw data.
     */
    public void build(){
        ArrayList<Node> current, prev;
        // Firstly initialize leaves
        for (int i = 0; i < this.chunks.size(); i++) {
            this.leaves.add(new Node(this.chunks.get(i)));
        }
        current = this.leaves;
        // Building the rest of the tree from bottom-up
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

/*
    public static void main( String[] args )
    {
        try {    
            byte[] d = Files.readAllBytes(Paths.get("/home/nick/Desktop/zok_test/empty.txt"));
            //System.out.println(d.length);
            MerkleProof tr = new MerkleProof(d, Config.CHUNK_SIZE, Config.CHUNK_COUNT, BigInteger.valueOf(1092L), BigInteger.valueOf(1));
            tr.build();
            byte[] result = tr.getRoot();
            System.out.println(Util.bytesToHex(result));
            for (int i = 0; i < result.length; i+=4) {
                System.out.print(Long.toString(Util.bytesToLong(result, i))+" ");
            }
            System.out.println();
            //Proof proof = tr.produce_proof_single(33187);
            List<Proof> proofs = tr.produce_proofs(2);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }*/
}
