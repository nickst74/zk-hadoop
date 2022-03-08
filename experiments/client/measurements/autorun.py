import subprocess
import time
from sys import argv

output_file = argv[1]
repeats = 10
times = {}

file_size_l = ["512m", "1g", "2g", "4g", "8g"]

for file_size in file_size_l:
    times[file_size] = {}
    #f_waiting = 0
    merkle_trees = 0
    f_merkle = 0
    f_total = 0
    for i in range(repeats):
        start = time.time()
        result = subprocess.run(["/home/nick/Desktop/hadoopss/hadoop-2.8.5-src/hadoop-dist/target/hadoop-2.8.5/bin/hadoop", "fs", "-put", "/home/nick/Desktop/foo_random/random_"+file_size, "hdfs://localhost:9000/"], stdout=subprocess.PIPE)
        total = int((time.time() - start) * 1000)
        f_total += total
        toprocess = result.stdout
        subprocess.run(["/home/nick/Desktop/hadoopss/hadoop-2.8.5-src/hadoop-dist/target/hadoop-2.8.5/bin/hadoop", "fs", "-rm", "hdfs://localhost:9000/random_"+file_size])
        for line in toprocess.decode("UTF-8").split("\n"):
            #if "Time spent waiting until transaction sending completes:" in line:
            #    waiting = int(line.split(" ")[-1])
            #    f_waiting += waiting
            if "Time spent building merkle trees:" in line:
                merkle_trees = int(line.split(" ")[-1])
                f_merkle += merkle_trees
        times[file_size]["attempt_"+str(i)] = {
            #'waiting': waiting,
            'merkle': merkle_trees,
            'total': total
        }
    #times[file_size]['waiting'] = f_waiting//repeats
    #times[file_size]['merkle'] = f_merkle//repeats
    #times[file_size]['total'] = f_total//repeats
    #break

with open(output_file, 'w') as fp:
    fp.write("FileSize,512m,1g,2g,4g,8g,512m_merkle,1g_merkle,2g_merkle,4g_merkle,8g_merkle\n")
    for i in range(repeats):
        line = "attempt_"+str(i)
        suffix = ""
        for fsize in file_size_l:
            line += ","+str(times[fsize]["attempt_"+str(i)]["total"])
            suffix += ","+str(times[fsize]["attempt_"+str(i)]["merkle"])
        fp.write(line+suffix+"\n")
# just a beep to notify when finished
print("\a")

