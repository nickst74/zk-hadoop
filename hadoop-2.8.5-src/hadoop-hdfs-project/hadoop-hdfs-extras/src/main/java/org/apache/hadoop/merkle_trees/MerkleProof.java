package org.apache.hadoop.merkle_trees;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.lang.ProcessBuilder;
import java.math.BigInteger;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import org.web3j.crypto.Hash;

/**
 * Extends the Merkle Tree class by adding proof generation.
 * Used by Datanodes during block reports.
 */
public class MerkleProof extends MerkleTree{

    // Add lock if we use multithreaded proof computation
    private static final ReentrantLock lock = new ReentrantLock();
    private final BigInteger block_id;
    private final ArrayList<Integer> challenges;
    private final ArrayList<byte[]> chosen_chunks;
    
    public MerkleProof(byte[] block, int chunk_size, int chunk_count, BigInteger block_id, BigInteger seed, int chall_count) {
        assert(block.length <= chunk_count * chunk_size);
        // Just in case we have an empty block
        if(block.length == 0){
            // Initialize all zeroes
            block = new byte[chunk_size];
        }
        // init fields
        this.block_id = block_id;
        this.root = null;
        this.leaves = new ArrayList<Node>();
        this.chosen_chunks = new ArrayList<byte[]>();
        this.challenges = gen_challenges(seed, block_id, chall_count, chunk_count);
        ArrayList<byte[]> chunks = new ArrayList<>();
        // fill chunk with the repeating byte sequence from input/block
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
            // keep chunk in list and create corresponding Leaf
            chunks.add(ch);
            this.leaves.add(new Node(ch));
        }
        // at the end keep only the required chunks for the proofs int the specified order
        for (int chall : this.challenges) {
            this.chosen_chunks.add(chunks.get(chall));
        }
    }

    private byte[] encode_packed(BigInteger _a, BigInteger _b){
        byte[] ret = new byte[64];
        byte[] a = _a.toByteArray(), b = _b.toByteArray();
        System.arraycopy(a, 0, ret, 32 - a.length, a.length);
        System.arraycopy(b, 0, ret, 64 - b.length, b.length);
        return ret;
    }

    private ArrayList<Integer> gen_challenges(BigInteger seed, BigInteger block_id, int chall_count, int chunk_count){
        ArrayList<Integer> challenges = new ArrayList<>();
        BigInteger tmp = seed;
        for (int i = 0; i < chall_count; i++) {
            tmp = new BigInteger(Hash.sha3(encode_packed(tmp, block_id)));
            challenges.add(tmp.mod(BigInteger.valueOf(chunk_count)).intValue());
        }
        return challenges;
    }

    private void appendToList(List<String> ls, byte[] items){
        for (int i = 0; i < items.length; i+=4) {
            ls.add(Long.toString(Util.bytesToLong(items, i)));
        }
    }

    /**
     * Find the input for zokrates to compute-witness
     * @param index Position of requested chunk in the block
     * @param chunk The raw data of the specified chunk
     * @return List with inputs for zokrates executable
     */
    private LinkedList<String> findWitnessInput(int index, byte[] chunk) {
        assert(index < this.leaves.size());
        LinkedList<String> acc = new LinkedList<String>();
        List<String> path = new LinkedList<String>();
        // First add the index to the list
        acc.addLast(Integer.toString(index));
        // Second goes the root hash
        appendToList(acc, this.getRoot());
        // Then goes the chunk of data
        appendToList(acc, chunk);
        // Followed by the sibling hashed in the path
        // Also store the path directions
        Node prev, current = this.leaves.get(index);
        while (current.getParent() != null) {
            prev = current;
            current = prev.getParent();
            if(current.getLeft() == prev){
                // if we came from left child, take hash from right
                appendToList(acc, current.getRight().getHash());
                path.add("0");
            } else {
                // if we came from right child, take hash from left
                appendToList(acc, current.getLeft().getHash());
                path.add("1");
            }
        }
        // At last add the path directions
        acc.addAll(path);
        return acc;
    }

    /**
     * Produces one proof that corresponds to the given index/challenge.
     * @param index
     * @return The generated proof
     * @throws IOException
     * @throws InterruptedException
     * @throws ParseException
     */
    public Proof produce_proof_single(int index, byte[] chunk, String zokDir) throws IOException, InterruptedException, ParseException{
        // create input
        LinkedList<String> command = findWitnessInput(index, chunk);
        // compute witness
        command.addFirst("-a");
        command.addFirst("compute-witness");
        command.addFirst("./zokrates");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(zokDir));
        //pb.inheritIO();

        // only one at a time can use zokrates executable
        MerkleProof.lock.lock();

        Process pr = pb.start();
        pr.waitFor();
        // generate proof
        command = new LinkedList<String>();
        command.add("./zokrates");
        command.add("generate-proof");
        pb = new ProcessBuilder(command);
        pb.directory(new File(zokDir));
        //pb.inheritIO();
        pr = pb.start();
        pr.waitFor();

        // get result from proof.json file
        JSONParser parser = new JSONParser();
        JSONObject obj = (JSONObject) parser.parse(new FileReader(zokDir+"/proof.json"));
        Proof proof = new Proof((JSONObject) obj.get("proof"));

        // After getting the result, unlock
        MerkleProof.lock.unlock();

        return proof;
    }

    /**
     * Generates the required number of proofs, while using the seed
     * to determin the correct indices.
     * @param count Requested number of proofs
     * @return A list with the generated proofs
     */
    public List<Proof> produce_proofs(String zokDir){
        List<Proof> proofs = new ArrayList<>();
        // if one proof fails then no need to get the rest
        // as the verification for the specific block will fail anyway
        try {
            for (int i = 0; i < this.challenges.size(); i++) {
                proofs.add(produce_proof_single(this.challenges.get(i), this.chosen_chunks.get(i), zokDir));
            }
        } catch (Exception e) {
            // if an exception occured fill remaining proofs
            // with something
            // (so the following block verifications won't fail)
            while (proofs.size() < this.challenges.size()) {
                Proof dummy = new Proof();
                dummy.a = new BigInteger[]{
                    BigInteger.valueOf(0),
                    BigInteger.valueOf(0)
                };
                dummy.b = new BigInteger[][]{
                    dummy.a,
                    dummy.a
                };
                dummy.c = dummy.a;
                proofs.add(dummy);
            }
        }
        return proofs;
    }

    public BigInteger getBlock_id(){
        return this.block_id;
    }

}
