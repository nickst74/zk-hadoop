import matplotlib.pyplot as plt

source_file = "proof.csv"

hash_functions = ["BLAKE2s", "SHA2", "SHA3"]

colors = {
    "BLAKE2s": 'b',
    "SHA-256": 'r',
    "SHA-3": 'g'
}

markers = {
    "BLAKE2s": 's',
    "SHA-256": 'D',
    "SHA-3": 'o'
}

with open(source_file, 'r') as fp:
    fp.readline()
    x = ["64_bytes", "128_bytes", "256_bytes", "512_bytes", "1_kbyte"]
    ys = {}
    for line in fp:
        tokens = line.split(",")
        ys[tokens[0]] = [int(x)/1000 for x in tokens[1:]]
    for key in ys.keys():
        plt.plot(x, ys[key], color=colors[key], marker=markers[key], markersize=4, label=key)
    #plt.grid()
    plt.title("ZK-Proof of computation of a Hash Function")
    plt.xlabel("Input Size")
    plt.ylabel("Time (sec)")
    plt.legend()
    plt.show()
