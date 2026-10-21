package org.dravet.guard;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class LocalRepository extends SQLiteOpenHelper {
    private final SharedPreferences preferences;
    LocalRepository(Context context) {
        super(context, "guard-local.sqlite3", null, 4);
        preferences = context.getSharedPreferences("guard-private", Context.MODE_PRIVATE);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cache (name TEXT PRIMARY KEY, body TEXT NOT NULL, received INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE notification_seen (id TEXT PRIMARY KEY)");
        createObservations(db);createSamples(db);createAlerts(db);
    }
    private void createObservations(SQLiteDatabase db) { db.execSQL("CREATE TABLE IF NOT EXISTS observations (id TEXT PRIMARY KEY, body TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0)"); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { if(oldVersion<2)createObservations(db);if(oldVersion<3)createSamples(db);if(oldVersion<4)createAlerts(db); }
    private void createAlerts(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS pulse_alerts (id TEXT PRIMARY KEY, body TEXT NOT NULL)");}
    synchronized void saveAlert(JSONObject event){ContentValues v=new ContentValues();v.put("id",event.optString("id"));v.put("body",event.toString());getWritableDatabase().insertWithOnConflict("pulse_alerts",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    synchronized org.json.JSONArray alerts() throws Exception {org.json.JSONArray result=new org.json.JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM pulse_alerts ORDER BY rowid DESC LIMIT 200",null)){while(c.moveToNext())result.put(new JSONObject(c.getString(0)));}return result;}
    synchronized void saveRuleResult(JSONObject states,org.json.JSONArray events) throws Exception {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);ContentValues v=new ContentValues();v.put("id",event.getString("id"));v.put("body",event.toString());if(db.insertWithOnConflict("pulse_alerts",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("提醒记录保存失败");}
            JSONObject high=states.optJSONObject("pulse_high");
            for(String key:new String[]{"metric_rules","pulse_rule"}){ContentValues v=new ContentValues();v.put("name",key);v.put("body",key.equals("metric_rules")?states.toString():high==null?"{}":high.toString());v.put("received",System.currentTimeMillis());if(db.insertWithOnConflict("cache",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("规则状态保存失败");}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    synchronized JSONObject alert(String id) throws Exception {try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM pulse_alerts WHERE id=?",new String[]{id})){if(!c.moveToFirst())throw new Exception("未找到提醒记录");return new JSONObject(c.getString(0));}}
    private void createSamples(SQLiteDatabase db) { db.execSQL("CREATE TABLE IF NOT EXISTS samples (id TEXT PRIMARY KEY, source TEXT NOT NULL, stamp TEXT NOT NULL, body TEXT NOT NULL)");db.execSQL("CREATE INDEX IF NOT EXISTS sample_source_time ON samples(source,stamp)"); }
    synchronized void saveSamples(org.json.JSONArray samples) throws Exception {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(int i=0;i<samples.length();i++){JSONObject item=samples.getJSONObject(i);ContentValues v=new ContentValues();v.put("id",item.getString("id"));v.put("source",item.getString("source"));v.put("stamp",item.getString("observed_at"));v.put("body",item.toString());db.insertWithOnConflict("samples",null,v,SQLiteDatabase.CONFLICT_IGNORE);}db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    synchronized org.json.JSONArray samples(String source) throws Exception {
        org.json.JSONArray rows=new org.json.JSONArray();try(Cursor cursor=getReadableDatabase().rawQuery("SELECT body FROM samples WHERE source=? ORDER BY stamp DESC LIMIT 2400",new String[]{source})){while(cursor.moveToNext())rows.put(new JSONObject(cursor.getString(0)));}return rows;
    }
    synchronized org.json.JSONArray pendingObservations() {
        org.json.JSONArray rows=new org.json.JSONArray();try(Cursor cursor=getReadableDatabase().rawQuery("SELECT body FROM observations WHERE synced=0 ORDER BY rowid LIMIT 200",null)){while(cursor.moveToNext())try{rows.put(new JSONObject(cursor.getString(0)));}catch(Exception ignored){}}return rows;
    }
    synchronized void saveObservation(JSONObject record) {
        ContentValues value=new ContentValues();value.put("id",record.optString("id"));value.put("body",record.toString());value.put("synced",0);
        getWritableDatabase().insertOrThrow("observations",null,value);
    }
    synchronized org.json.JSONArray observations() {
        org.json.JSONArray rows=new org.json.JSONArray();
        try(Cursor cursor=getReadableDatabase().rawQuery("SELECT body,synced FROM observations ORDER BY rowid DESC LIMIT 200",null)){
            while(cursor.moveToNext())try{rows.put(new JSONObject(cursor.getString(0)).put("synced",cursor.getInt(1)==1));}catch(Exception ignored){}
        }
        return rows;
    }
    synchronized void markSynced(String id) { ContentValues value=new ContentValues();value.put("synced",1);getWritableDatabase().update("observations",value,"id=?",new String[]{id}); }
    synchronized void save(String name, JSONObject body) {
        ContentValues values = new ContentValues(); values.put("name", name); values.put("body", body.toString()); values.put("received", System.currentTimeMillis());
        if(getWritableDatabase().insertWithOnConflict("cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("本机保存失败，请检查存储空间");
    }
    synchronized JSONObject read(String name) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT body FROM cache WHERE name=?", new String[]{name})) {
            return cursor.moveToFirst() ? new JSONObject(cursor.getString(0)) : new JSONObject();
        } catch(Exception e) { return new JSONObject(); }
    }
    synchronized boolean seen(String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id FROM notification_seen WHERE id=?", new String[]{id})) { return cursor.moveToFirst(); }
    }
    synchronized void markSeen(String id) {
        ContentValues value = new ContentValues(); value.put("id", id); getWritableDatabase().insertWithOnConflict("notification_seen", null, value, SQLiteDatabase.CONFLICT_IGNORE);
    }
    SharedPreferences preferences() { return preferences; }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(!store.containsAlias("guard-api-token")) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("guard-api-token", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry)store.getEntry("guard-api-token", null)).getSecretKey();
    }
    void configure(String base, String token, boolean watch) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if(!preferences.edit().putString("base", base).putString("encrypted", encrypted).putBoolean("watch", watch).commit()) throw new IllegalStateException("Configuration save failed");
    }
    String token() throws Exception {
        String value = preferences.getString("encrypted", ""); if(value.isEmpty()) throw new IllegalStateException("请先保存连接设置");
        String[] parts = value.split(":", 2); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    String base() { return preferences.getString("base", "http://127.0.0.1:8765"); }
}
