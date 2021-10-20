package org.apache.hadoop.merkle_trees;

/**
 * Just a simple implementation of BLAKE2s hash function
 * @see <a href="https://blake2.net">blake2.net</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7693">rfc7693</a>
 */
public class Blake2s {

    private static final int BLOCK_SIZE = 64;
	private static final int[] p = new int[]{0, 0};
    private static final int IV[] = {
		0x6A09E667, 0XBB67AE85, 0X3C6EF372, 0xA54FF53A,
		0X510E527F, 0X9B05688C, 0x1F83D9AB, 0X5BE0CD19
	};
    private static final int[][] SIGMA = {
		{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
		{14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
		{11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
		{7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
		{9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
		{2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
		{12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
		{13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
		{6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
		{10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}
	};

    public static void mixing_g(int[] v, int a, int b, int c, int d, int x, int y){
        v[a] += v[b] + x;
		v[d] = Integer.rotateRight(v[d] ^ v[a], 16);
		v[c] += v[d];
		v[b] = Integer.rotateRight(v[b] ^ v[c], 12);
		v[a] += v[b] + y;
		v[d] = Integer.rotateRight(v[d] ^ v[a], 8);
		v[c] += v[d];
		v[b] = Integer.rotateRight(v[b] ^ v[c], 7);
    }

    public static void blake2s_compression(int[] h, int[] m, int t0, int t1, boolean last){
		int[] v = new int[16];
		System.arraycopy(h, 0, v, 0, 8);
		System.arraycopy(IV, 0, v, 8, 8);
		v[12] ^= t0;
		v[13] ^= t1;
		if(last)
			v[14] = ~v[14];
		for (int i = 0; i < 10; i++) {
			mixing_g(v, 0, 4,  8, 12, m[SIGMA[i][ 0]], m[SIGMA[i][ 1]]);
			mixing_g(v, 1, 5,  9, 13, m[SIGMA[i][ 2]], m[SIGMA[i][ 3]]);
			mixing_g(v, 2, 6, 10, 14, m[SIGMA[i][ 4]], m[SIGMA[i][ 5]]);
			mixing_g(v, 3, 7, 11, 15, m[SIGMA[i][ 6]], m[SIGMA[i][ 7]]);
			mixing_g(v, 0, 5, 10, 15, m[SIGMA[i][ 8]], m[SIGMA[i][ 9]]);
			mixing_g(v, 1, 6, 11, 12, m[SIGMA[i][10]], m[SIGMA[i][11]]);
			mixing_g(v, 2, 7,  8, 13, m[SIGMA[i][12]], m[SIGMA[i][13]]);
			mixing_g(v, 3, 4,  9, 14, m[SIGMA[i][14]], m[SIGMA[i][15]]);
		}
		for (int i = 0; i < 8; i++) {
			h[i] ^= v[i] ^ v[i+8];
		}
	}

	public static int[] blake2s_init(){
		int[] h = new int[]{
			IV[0] ^ 0x01010000 ^ 0x00000020,
			IV[1],
			IV[2],
			IV[3],
			IV[4],
			IV[5],
			IV[6] ^ p[0],
			IV[7] ^ p[1]
		};
		return h;
	}

    public static byte[] digest(byte[] input){
		assert(input.length % BLOCK_SIZE == 0);
		int offset = 0;
		int[] m = new int[16];
		int[] h = blake2s_init();
		int t0 = 0, t1 = 0;
		for (int i = 0; i < input.length / BLOCK_SIZE - 1; i++) {
			t0 += 64;
			if(t0 == 0)
				t1++;
			for (int j = 0; j < m.length; j++) {
				m[j] = Util.bytesToInt(input, offset);
				offset+=4;
			}
			blake2s_compression(h, m, t0, t1, false);
		}
		t0 += 64;
		if(t0 == 0)
			t1++;
		for (int j = 0; j < m.length; j++) {
			m[j] = Util.bytesToInt(input, offset);
			offset+=4;
		}
		blake2s_compression(h, m, t0, t1, true);
		return Util.intsToBytes(h);
	}

}
