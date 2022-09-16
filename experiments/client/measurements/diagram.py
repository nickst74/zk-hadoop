import matplotlib.pyplot as plt

source_file = "times_no_wait.csv"

values = {}
colors = {
    "not_modified": 'blue',
    "chunk_512": 'purple',
    "chunk_1k": 'green',
    "chunk_2k": 'red',
    "chunk_4k": 'orange',
    "chunk_8k": 'brown'
}
markers = {
    "not_modified": '',
    "chunk_512": 'D',
    "chunk_1k": '^',
    "chunk_2k": 'h',
    "chunk_4k": 'v',
    "chunk_8k": 's'
}
# read measured time from source file
with open(source_file, 'r') as fp:
    categories = fp.readline().split(",")[1:]
    for line in fp:
        tokens = line.split(",")
        values[tokens[0]] = [int(v)/1000 for v in tokens[1:]]
#print(values)

# plot total time
for k in values.keys():
    plt.plot(categories[:5], values[k][:5], color=colors[k], marker=markers[k], markersize=4, label=k)
plt.title("Client file upload (total time)")
plt.xlabel("File Size")
plt.ylabel("Time (sec)")
plt.ylim(0)
plt.legend()
plt.show()

# plot the merkle tree creation time
"""for k in values.keys():
    if k == "not_modified":
        continue
    plt.plot(categories[:5], values[k][5:], color=colors[k], marker=markers[k], markersize=4, label=k)
plt.title("Time spent creating Merkle trees")
plt.xlabel("File Size")
plt.ylabel("Time (sec)")
plt.ylim(0)
plt.legend()
plt.show()
"""