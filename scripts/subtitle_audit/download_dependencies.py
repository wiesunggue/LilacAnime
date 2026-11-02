from pathlib import Path
import urllib.request,concurrent.futures
import sys
r=Path(sys.argv[1]);r.mkdir(parents=True,exist_ok=True)
paths={'stdlib.jar':'org/jetbrains/kotlin/kotlin-stdlib/2.2.0/kotlin-stdlib-2.2.0.jar','coroutines.jar':'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar','jsoup.jar':'org/jsoup/jsoup/1.23.1/jsoup-1.23.1.jar'}
def get(item):
 name,path=item
 with urllib.request.urlopen('https://repo.maven.apache.org/maven2/'+path,timeout=40) as f:(r/name).write_bytes(f.read())
with concurrent.futures.ThreadPoolExecutor() as e:list(e.map(get,paths.items()))
