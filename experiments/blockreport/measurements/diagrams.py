import matplotlib.pyplot as plt


source_file = "zkproofs.csv"
#with open(source_file, 'r') as fp:
#    fp.readline()
#    x = ["1", "2", "4"]
#    ys = {}
#    for line in fp:
#        tokens = line.split(",")
#        ys[tokens[0]] = [int(x) for x in tokens[1:]]
#    for key in ys.keys():
#        plt.plot(x, ys[key], label="chunk_"+key)
#    #plt.grid()
#    plt.legend()
#    plt.show()

# ram usage
#x = ["512", "1k", "2k", "4k", "8k"]
#y = [1.2, 1.6, 2.3, 3.7, 6.9]
# gas cost
x = ["1", "2", "4", "8"]
y = [284749, 541795, 1055776, 2083932]
#plt.bar(x, y, width=0.4)
#plt.show()

# and something interesting
#x = ["chunk_1k\n8_proofs", "chunk_2k\n4_proofs", "chunk_4k\n2_proofs", "chunk_8k\n1_proof"]
#y = [i//60000 for i in [9476251, 7888804, 5461012, 4950213]]
plt.bar(x, y, width=0.4)
plt.show()
