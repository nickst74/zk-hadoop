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
    //private final BigInteger seed;
    private BigInteger next;
    
    public MerkleProof(byte[] block, BigInteger block_id, BigInteger seed) {
        super(block);
        this.block_id = block_id;
        //this.seed = seed;
        this.next = seed;
    }

    private byte[] encode_packed(BigInteger _a, BigInteger _b){
        byte[] ret = new byte[64];
        byte[] a = _a.toByteArray(), b = _b.toByteArray();
        System.arraycopy(a, 0, ret, 32 - a.length, a.length);
        System.arraycopy(b, 0, ret, 64 - b.length, b.length);
        return ret;
    }

    private int next_challenge(){
        this.next = new BigInteger(Hash.sha3(encode_packed(this.next, this.block_id)));
        return this.next.mod(BigInteger.valueOf(Config.CHUNK_COUNT)).intValue();
    }

    private void appendToList(List<String> ls, byte[] items){
        for (int i = 0; i < items.length; i+=4) {
            ls.add(Long.toString(Util.bytesToLong(items, i)));
        }
    }

    /**
     * Find the input for zokrates to compute-witness
     * @param index Position of requested chunk in the block
     * @return List with inputs for zokrates executable
     */
    private LinkedList<String> findWitnessInput(int index) {
        assert(index < Config.CHUNK_COUNT);
        LinkedList<String> acc = new LinkedList<String>();
        List<String> path = new LinkedList<String>();
        // First add the index to the list
        acc.addLast(Integer.toString(index));
        // Second goes the root hash
        appendToList(acc, this.getRoot());
        // Then goes the chunk of data
        appendToList(acc, this.chunks.get(index));
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
    public Proof produce_proof_single(int index) throws IOException, InterruptedException, ParseException{
        // create input
        LinkedList<String> command = findWitnessInput(index);
        // compute witness
        command.addFirst("-a");
        command.addFirst("compute-witness");
        command.addFirst("./zokrates");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(Config.ZOK_DIR));
        pb.inheritIO();

        // only one at a time can use zokrates executable
        MerkleProof.lock.lock();

        Process pr = pb.start();
        pr.waitFor();
        // generate proof
        command = new LinkedList<String>();
        command.add("./zokrates");
        command.add("generate-proof");
        pb = new ProcessBuilder(command);
        pb.directory(new File(Config.ZOK_DIR));
        pb.inheritIO();
        pr = pb.start();
        pr.waitFor();

        // get result from proof.json file
        JSONParser parser = new JSONParser();
        JSONObject obj = (JSONObject) parser.parse(new FileReader(Config.ZOK_DIR+"proof.json"));
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
    public List<Proof> produce_proofs(int count){
        List<Proof> proofs = new ArrayList<>();
        // if one proof fails then no need to get the rest
        // as the verification for the specific block will fail anyway
        try {
            while (proofs.size() < count) {
                proofs.add(produce_proof_single(next_challenge()));
            }
        } catch (Exception e) {
            // if an exception occured fill remaining proofs
            // with something
            // (so the following block verifications won't fail)
            while (proofs.size() < count) {
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

}
