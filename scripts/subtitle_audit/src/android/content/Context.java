package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;
 public SharedPreferences getSharedPreferences(String n,int mode){return new SharedPreferences(){public int getInt(String k,int d){return k.equals("pref_ai_prefetch_ahead")?Integer.getInteger("audit.ahead",0):d;} public boolean getBoolean(String k,boolean d){return d;}};}
}
