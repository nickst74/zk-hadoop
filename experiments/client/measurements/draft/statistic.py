import matplotlib.pyplot as plt
from sys import argv

source_file = argv[1]

data = {}
with open(source_file, 'r') as fp:
    categories = fp.readline().split(",")[1:]
    for c in categories:
        data[c] = []
    for line in fp:
        tokens = line.split(",")[1:]
        for c, t in zip(categories, tokens):
            data[c].append(int(t))
means = {}
for c in categories:
    data[c].sort()
    to_plot = [v//1000 for v in data[c]]
    plt.hist(to_plot)
    plt.show()
    low = int(input("Lower outliers to remove: "))
    high = int(input("Upper outliers to remove: "))
    if high == 0:
        tmp = data[c][low:]
    else:
        tmp = data[c][low:-high]
    means[c] = sum(tmp) // len(tmp)

print(means)
print(means.values())
