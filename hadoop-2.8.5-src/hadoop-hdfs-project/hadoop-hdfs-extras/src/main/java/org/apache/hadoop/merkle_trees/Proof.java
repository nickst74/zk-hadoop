package org.apache.hadoop.merkle_trees;

import java.math.BigInteger;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class Proof {
    public BigInteger a[];
    public BigInteger b[][];
    public BigInteger c[];

    public Proof(){
        this.a = new BigInteger[2];
        this.b = new BigInteger[2][2];
        this.a = new BigInteger[2];
    }

    public Proof(JSONObject obj){
        this.a = new BigInteger[2];
        this.b = new BigInteger[2][2];
        this.c = new BigInteger[2];
        JSONArray arr_a = (JSONArray) obj.get("a");
        JSONArray arr_b = (JSONArray) obj.get("b");
        JSONArray arr_c = (JSONArray) obj.get("c");
        JSONArray arr_b0 = (JSONArray) arr_b.get(0);
        JSONArray arr_b1 = (JSONArray) arr_b.get(1);
        a[0] = objToBigInt(arr_a.get(0));
        a[1] = objToBigInt(arr_a.get(1));
        b[0][0] = objToBigInt(arr_b0.get(0));
        b[0][1] = objToBigInt(arr_b0.get(1));
        b[1][0] = objToBigInt(arr_b1.get(0));
        b[1][1] = objToBigInt(arr_b1.get(1));
        c[0] = objToBigInt(arr_c.get(0));
        c[1] = objToBigInt(arr_c.get(1));
    }

    private BigInteger objToBigInt(Object obj){
        String str = (String) obj;
        // get rid of '0x' prefix
        return new BigInteger(str.substring(2), 16);
    }

}
