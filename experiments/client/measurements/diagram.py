import matplotlib.pyplot as plt

source_file = "times.csv"

values = {}
colors = {
    "not_modified": 'blue',
    "chunk_512": 'orange',
    "chunk_1k": 'green',
    "chunk_2k": 'red',
    "chunk_4k": 'purple',
    "chunk_8k": 'brown'
}
# read measured time from source file
with open(source_file, 'r') as fp:
    categories = fp.readline().split(",")[1:]
    for line in fp:
        tokens = line.split(",")
        values[tokens[0]] = [int(v) for v in tokens[1:]]
#print(values)

# plot total time
for k in values.keys():
    plt.plot(categories[:5], values[k][:5], color=colors[k], label=k)
plt.legend()
plt.show()

# plot the merkle tree creation time
for k in values.keys():
    if k == "not_modified":
        continue
    plt.plot(categories[:5], values[k][5:], color=colors[k], label=k)
plt.legend()
plt.show()
