from pathlib import Path
import subprocess,sys,tempfile,os
here=Path(__file__).resolve().parent
repo=here.parents[1]
production=repo/'app/build/tmp/kotlin-classes/debug'
if not (production/'com/lilac/anime/data/subtitle/translation/RealtimeSubtitleTranslator.class').exists():
 raise SystemExit('Compile the v10 debug Kotlin classes first using the repository workflow configuration.')
with tempfile.TemporaryDirectory(prefix='lilac-v10-audit-') as tmp:
 work=Path(tmp)
 subprocess.run([sys.executable,str(here/'download_dependencies.py'),str(work)],check=True)
 jars=[str(work/name) for name in ['stdlib.jar','coroutines.jar','jsoup.jar']]
 cp=os.pathsep.join(jars+[str(production)])
 sources=[str(p) for p in (here/'src').rglob('*.java')]
 subprocess.run(['java','--module','jdk.compiler/com.sun.tools.javac.Main','-cp',cp,'-d',str(work/'classes'),*sources],check=True)
 output=subprocess.check_output(['java','-cp',os.pathsep.join([str(work/'classes'),cp]),'Audit'],text=True)
 print(output,end='')
 if output.count('BUG_REPRODUCED')!=3 or output.count('CONTROL_PASS')!=1:
  raise SystemExit('The result differs from the recorded v10 counterexamples. Inspect the implementation and build inputs.')
