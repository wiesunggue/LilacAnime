import android.content.Context;
import com.lilac.anime.data.subtitle.translation.*;
import kotlinx.coroutines.*;
import kotlin.coroutines.*;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.jvm.functions.Function1;
import kotlin.ResultKt;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public class Audit {
 static <T> T await(Function1<Continuation<? super T>, Object> fn) throws Exception {
  CompletableFuture<T> f=new CompletableFuture<>();
  Object value=fn.invoke(new Continuation<T>(){public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;}public void resumeWith(Object v){try{ResultKt.throwOnFailure(v);f.complete((T)v);}catch(Throwable t){f.completeExceptionally(t);}}});
  if(value!=IntrinsicsKt.getCOROUTINE_SUSPENDED()) {ResultKt.throwOnFailure(value);return (T)value;}
  return f.get(10,TimeUnit.SECONDS);
 }
 static class Provider implements TranslationProvider {
  public String getId(){return "gemini";}public String getDisplayName(){return "deterministic audit";}
  public Object translateBatch(List<String> lines,Continuation<? super List<String>> c){List<String> r=new ArrayList<>();for(String s:lines)r.add("한국어 "+s);return r;}
 }
 static RealtimeSubtitleTranslator translator(){return new RealtimeSubtitleTranslator(new Context(),Dispatchers.getDefault(),id->new Provider(),id->false);}
 static List<RealtimeSubtitleTranslator.ReadyEvent> events(RealtimeSubtitleTranslator t)throws Exception{return await(c->t.consumeReadyTranslatedEvents(c));}
 static void run(String name,String srt,long start,long next,long expected, boolean shouldTranslate)throws Exception {
  RealtimeSubtitleTranslator t=translator();CoroutineScope scope=CoroutineScopeKt.CoroutineScope(Dispatchers.getDefault().plus(SupervisorKt.SupervisorJob(null)));
  Path p=Files.createTempFile("audit-", ".srt");Files.writeString(p,srt);
  try{
   await(c->t.ensurePrepared(p.toString(),start,scope,"gemini",c));Thread.sleep(600);
   await(c->t.updatePlaybackPosition(next,c));Thread.sleep(600);
   List<Long> ready=new ArrayList<>();for(var e:events(t))ready.add(e.getStartMs());
   boolean actual=ready.contains(expected);
   System.out.println(name+" | position="+next+" | expected cue="+expected+" | completed="+ready+" | "+(actual==shouldTranslate?"CONTROL_PASS":"BUG_REPRODUCED"));
   if(actual!=shouldTranslate && !shouldTranslate)throw new AssertionError("Unexpected control result");
  }finally{await(c->t.clear(c));CoroutineScopeKt.cancel(scope,null);Files.delete(p);}
 }
 public static void main(String[] args)throws Exception{
  run("overlap_long_sign", "1\n00:00:00,000 --> 00:01:00,000\nSign\n\n2\n00:00:01,000 --> 00:00:02,000\nDialogue",0,1100,1000,true);
  System.setProperty("audit.ahead", "10");
  StringBuilder overlap=new StringBuilder("1\n00:00:00,000 --> 00:01:00,000\nSign");
  for(int i=1;i<30;i++) overlap.append(String.format("\n\n%d\n00:00:%02d,000 --> 00:00:%02d,900\nDialogue %d",i+1,i,i,i));
  run("overlap_default_lookahead_10", overlap.toString(),0,22000,22000,true);
  System.setProperty("audit.ahead", "0");
  run("short_rewind_1000ms", "1\n00:00:00,000 --> 00:00:00,900\nEarlier\n\n2\n00:00:01,000 --> 00:00:01,900\nLater",1100,100,0,true);
  run("long_rewind_control", "1\n00:00:00,000 --> 00:00:00,900\nEarlier\n\n2\n00:00:04,000 --> 00:00:04,900\nLater",4100,100,0,true);
 }
}
