package org.apache.hadoop.merkle_trees;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public class Util {

    private static final byte[] HEX_ARRAY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    
    public static String bytesToHex(byte[] bytes, int offset, int length) {
        assert(bytes.length >= offset + length);
        byte[] hexChars = new byte[length * 2];
        for (int j = 0; j < length; j++) {
            int v = bytes[offset + j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars, StandardCharsets.UTF_8);
    }

    public static String bytesToHex(byte[] bytes){
        return bytesToHex(bytes, 0, bytes.length);
    }

    /* s must be an even-length string, without the '0x' prefic. */
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public static int bytesToInt(byte[] arr, int offset){
        assert(offset + 4 <= arr.length);
		return ((arr[offset] & 0xFF) << 24) |
			    ((arr[offset + 1] & 0xFF) << 16) |
			    ((arr[offset + 2] & 0xFF) << 8) |
			    (arr[offset + 3] & 0xFF);
    }

    public static long bytesToLong(byte[] arr, int offset){
        assert(offset + 4 <= arr.length);
        return ((arr[offset] & 0xFFL) << 24) |
                ((arr[offset + 1] & 0xFFL) << 16) |
                ((arr[offset + 2] & 0xFFL) << 8) |
                (arr[offset + 3] & 0xFFL);
    }
    
    public static String[] rootToZokFields(byte[] arr) {
    	//assert(32 <= arr.length);
    	byte[] hb = new byte[17];
    	byte[] lb = new byte[17];
    	System.arraycopy(arr, 0, hb, 1, 16);
    	System.arraycopy(arr, 16, lb, 1, 16);
    	return new String[] {new BigInteger(hb).toString(), new BigInteger(lb).toString()};
    }

    public static byte[] intsToBytes(int[] arr){
        byte[] result = new byte[arr.length * 4];
        for (int i = 0; i < arr.length; i++) {
            result[i*4] = (byte) (arr[i] >>> 24);
            result[i*4+1] = (byte) (arr[i] >>> 16);
            result[i*4+2] = (byte) (arr[i] >>> 8);
            result[i*4+3] = (byte) (arr[i] >>> 0);
        }
        return result;
    }

    public static byte[] concatBytes(byte[] a, byte[] b){
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
    
    public static byte[] encode_packed(byte[] a, BigInteger _b){
        byte[] ret = new byte[32+a.length];
        byte[] b = _b.toByteArray();
        System.arraycopy(b, 0, ret, 32 - b.length, b.length);
        System.arraycopy(a, 0, ret, ret.length - a.length, a.length);
        return ret;
    }

    public static byte[] encode_packed(BigInteger _a, BigInteger _b){
        byte[] ret = new byte[64];
        byte[] a = _a.toByteArray(), b = _b.toByteArray();
        System.arraycopy(a, 0, ret, 32 - a.length, a.length);
        System.arraycopy(b, 0, ret, 64 - b.length, b.length);
        return ret;
    }

}
