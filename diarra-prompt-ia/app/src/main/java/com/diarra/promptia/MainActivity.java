package com.diarra.promptia;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebResourceResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService jobs = Executors.newCachedThreadPool();
    private final Object taskLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean simpleBusy = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Set<String> livePollers = new HashSet<>();
    private final Set<String> liveSubmissions = new HashSet<>();
    private static final String TASKS_PREF = "diarra_tasks_v3";
    private static final long MAX_POLL_MS = 45L * 60 * 1000;
    private ValueCallback<Uri[]> fileCallback;
    private static final int PICK_FILE = 77;
    private static final int PICK_OLD_VIDEO = 78;
    private static final int PICK_LIBRARY_VIDEO = 79;
    private static final int PICK_SIMPLE_VOICE = 80;
    private static final String SIMPLE_PREF = "diarra_simple_v6";
    private static final String LIBRARY_PREF = "diarra_mp4_library_v5";
    private String selectedOldSceneId="", selectedOldIndex="";
    private static final String OLD_VIDEO_FOLDER="imported_videos";
    private static final String BASE_URL = "https://app.diarra.invalid/";
    private static final String PREF = "diarra_secure_key";
    private static final String ALIAS = "diarra_fal_aes_v1";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff0e1713);
        getWindow().setNavigationBarColor(0xff0b1210);
        web = new WebView(this);
        setContentView(web);
        WebSettings settings = web.getSettings();
        // V9 : adapter la page à la largeur réelle du téléphone, sans zoom/panoramique involontaire.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        web.setHorizontalScrollBarEnabled(false);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Pour importer un projet JSON choisi par l’utilisateur
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !BASE_URL.equals(request.getUrl().toString());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u=request.getUrl();
                if(!"https".equals(u.getScheme()) || !"app.diarra.invalid".equals(u.getHost())
                    || u.getPath()==null || !u.getPath().startsWith("/legacy-video/"))return null;
                try{return localVideoResponse(u.getLastPathSegment(), request.getRequestHeaders().get("Range"));}
                catch(Exception e){return new WebResourceResponse("text/plain","UTF-8",
                    new java.io.ByteArrayInputStream("Vidéo locale indisponible".getBytes(StandardCharsets.UTF_8)));}
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent chooser = params.createIntent();
                    chooser.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(chooser, PICK_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        web.addJavascriptInterface(new NativeFal(), "NativeFal");
        try (InputStream in = getAssets().open("index.html")) {
            String html = new String(readAll(in, 3_000_000), StandardCharsets.UTF_8);
            web.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null);
        } catch (Exception e) { web.loadData("<h2>Impossible de charger l’application.</h2>", "text/html", "UTF-8"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(resultCode == RESULT_OK ? WebChromeClient.FileChooserParams.parseResult(resultCode, data) : null);
            fileCallback = null;
        } else if(requestCode == PICK_SIMPLE_VOICE) {
            if(resultCode==RESULT_OK && data!=null && data.getData()!=null){
                final Uri voice=data.getData();jobs.execute(()->copySimpleVoice(voice));
            } else {event("","","simple","voice","Choix de voix annulé.",null,null);}
        } else if(requestCode == PICK_LIBRARY_VIDEO) {
            if(resultCode==RESULT_OK && data!=null && data.getData()!=null){
                final Uri chosen=data.getData();
                jobs.execute(()->importVideoToLibrary(chosen));
            } else {
                event("","","library","paused","Sélection du MP4 annulée.",null,null);
            }
        } else if(requestCode == PICK_OLD_VIDEO) {
            final String sid=selectedOldSceneId, idx=selectedOldIndex;
            selectedOldSceneId="";selectedOldIndex="";
            if(resultCode==RESULT_OK && data!=null && data.getData()!=null){
                Uri input=data.getData();jobs.execute(()->importLocalVideo(sid,idx,input));
            } else {
                event(sid,idx,"legacy","paused","Import du MP4 annulé.",null,null);
            }
        }
    }

    private SecretKey keyMaterial() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry)store.getEntry(ALIAS,null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setKeySize(256).build());
        return generator.generateKey();
    }
    private SharedPreferences pref() { return getSharedPreferences(PREF,MODE_PRIVATE); }
    private synchronized void encryptKey(String clear) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,keyMaterial());
        byte[] encrypted = c.doFinal(clear.getBytes(StandardCharsets.UTF_8));
        pref().edit().putString("ct", Base64.encodeToString(encrypted,Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(c.getIV(),Base64.NO_WRAP)).apply();
    }
    private synchronized String decryptKey() throws Exception {
        String ct=pref().getString("ct", ""); String iv=pref().getString("iv","");
        if (ct.isEmpty() || iv.isEmpty()) throw new Exception("Clé fal.ai absente");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,keyMaterial(),new GCMParameterSpec(128, Base64.decode(iv,Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(ct,Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    // Aucun secret dans les événements envoyés au WebView.
    private void event(JSONObject data) {
        try {
            String arg=JSONObject.quote(data.toString());
            runOnUiThread(()->{if(web != null) web.evaluateJavascript("window.diarraApiResult && window.diarraApiResult(" + arg + ")",null);});
        } catch(Exception ignored) {}
    }
    private void event(String sceneId,String index,String kind,String status,String message,String url,String requestId) {
        try {
            JSONObject o=new JSONObject().put("sceneId",sceneId).put("index",index)
                .put("kind",kind).put("status",status).put("message",message);
            if(url!=null)o.put("url",url);
            if(requestId!=null)o.put("requestId",requestId);
            event(o);
        }catch(Exception ignored){}
    }
    // Ancienne forme conservée pour les boutons de téléchargement.
    private void event(String index,String kind,String status,String message,String url) {
        event("",index,kind,status,message,url,null);
    }
    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int count=0,n;
        while((n=in.read(b))!=-1){count+=n;if(count>limit)throw new IOException("Fichier trop volumineux");out.write(b,0,n);}return out.toByteArray();
    }
    private static boolean apiURL(String s) {
        try {URL u=new URL(s);return "https".equals(u.getProtocol())
            && "queue.fal.run".equals(u.getHost()) && u.getPort()==-1
            && s.length()<2000 && u.getUserInfo()==null;}
        catch(Exception e){return false;}
    }
    private static HttpURLConnection open(String url,String method,String apiKey) throws Exception {
        if (!apiURL(url)) throw new IOException("Adresse API fal.ai non reconnue");
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);
        c.setConnectTimeout(20000);c.setReadTimeout(45000);c.setUseCaches(false);
        c.setRequestProperty("Authorization","Key "+apiKey);
        c.setRequestProperty("Accept","application/json");
        c.setInstanceFollowRedirects(false);
        return c;
    }
    private static JSONObject requestJSON(String url,String method,String apiKey,JSONObject body) throws Exception {
        HttpURLConnection c=open(url,method,apiKey);
        try {
            if(body != null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
                try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();InputStream stream=code<400?c.getInputStream():c.getErrorStream();
            String result=stream == null?"":new String(readAll(stream,2_000_000),StandardCharsets.UTF_8);
            if(code>=300)throw new IOException("API "+code+" : "+ (result.length()>230?result.substring(0,230):result));
            return new JSONObject(result);
        }finally{c.disconnect();}
    }
    // Le registre persistant est distinct de la clé API, et ne contient jamais la clé.
    private JSONObject allTasks(){
        synchronized(taskLock){try{return new JSONObject(getSharedPreferences(TASKS_PREF,MODE_PRIVATE).getString("items","{}"));}
        catch(Exception e){return new JSONObject();}}
    }
    private JSONObject task(String key){synchronized(taskLock){return allTasks().optJSONObject(key);}}
    private void storeTask(String key,JSONObject value){
        synchronized(taskLock){JSONObject all=allTasks();try{all.put(key,value);}catch(Exception ignored){}
            getSharedPreferences(TASKS_PREF,MODE_PRIVATE).edit().putString("items",all.toString()).commit();}
    }
    private void removeTask(String key){
        synchronized(taskLock){JSONObject all=allTasks();all.remove(key);
            getSharedPreferences(TASKS_PREF,MODE_PRIVATE).edit().putString("items",all.toString()).commit();}
    }
    private static boolean finished(String state){return "DONE".equals(state)||"FAILED".equals(state)||"UNAVAILABLE".equals(state);}
    private void setTaskState(String key,String state,String message,String url){
        JSONObject o;
        synchronized(taskLock){o=task(key);if(o==null)return;
            try{o.put("state",state).put("message",message).put("updatedAt",System.currentTimeMillis());
                if(url!=null)o.put("url",url);}catch(Exception ignored){}
            storeTask(key,o);
        }
        event(o.optString("sceneId"),o.optString("index"),o.optString("kind"),
            "DONE".equals(state)?"done":"FAILED".equals(state)?"error":"PAUSED".equals(state)||"UNKNOWN".equals(state)?"paused":"progress",
            message,url,o.optString("requestId"));
    }
    // Une clé stable par scène empêche le renvoi facturable lors des doubles clics et réouvertures.
    private String launch(boolean image,String sceneId,String index,String prompt,String imageUrl,String format){
        if(sceneId==null||!sceneId.matches("[a-zA-Z0-9_-]{8,80}"))return "ID de scène invalide";
        if(index==null||!index.matches("[0-9]{1,2}"))return "Numéro de scène invalide";
        if(prompt==null||prompt.trim().isEmpty()||prompt.length()>16000)return "Prompt vide ou trop long";
        if(!image&&(imageUrl==null||!imageUrl.startsWith("https://")))return "Créez d’abord l’image";
        String kind=image?"image":"animation",id=sceneId+":"+kind;
        synchronized(taskLock){
            JSONObject previous=task(id);
            if(previous!=null)return "Déjà suivi : "+previous.optString("state")+". Aucune nouvelle demande facturable envoyée.";
            try{
                JSONObject rec=new JSONObject().put("sceneId",sceneId).put("index",index).put("kind",kind)
                    .put("state","SUBMITTING").put("message","Envoi au service…").put("createdAt",System.currentTimeMillis())
                    .put("updatedAt",System.currentTimeMillis());
                storeTask(id,rec);
                liveSubmissions.add(id);
            }catch(Exception e){return "Impossible de sauvegarder la demande";}
        }
        event(sceneId,index,kind,"progress","Préparation de la demande, ne pas appuyer de nouveau…",null,null);
        jobs.execute(()->submit(image,id,prompt,imageUrl,format));
        return "Demande démarrée. Ne relancez pas la même scène.";
    }
    private void submit(boolean image,String key,String prompt,String imageUrl,String format){
        JSONObject rec=task(key);if(rec==null)return;
        String sceneId=rec.optString("sceneId"),idx=rec.optString("index"),kind=rec.optString("kind");
        try{
            String apiKey=decryptKey();
            String endpoint=image?"https://queue.fal.run/fal-ai/flux/schnell":
                "https://queue.fal.run/fal-ai/wan/v2.2-a14b/image-to-video";
            JSONObject input=new JSONObject().put("prompt",prompt.trim());
            if(image){input.put("image_size","16:9".equals(format)?"landscape_16_9":"1:1".equals(format)?"square_hd":"portrait_16_9")
                .put("num_images",1).put("output_format","jpeg");}
            else{input.put("image_url",imageUrl).put("resolution","480p").put("num_frames",81)
                .put("frames_per_second",16).put("aspect_ratio",("16:9".equals(format)||"1:1".equals(format))?format:"9:16");}
            JSONObject reply=requestJSON(endpoint,"POST",apiKey,input);
            String statusUrl=reply.optString("status_url",""),resultUrl=reply.optString("response_url","");
            if(!apiURL(statusUrl)||!apiURL(resultUrl))throw new IOException("Réponse de file d’attente non reconnue. Consultez l'historique fal.ai.");
            synchronized(taskLock){rec=task(key);if(rec==null)return;
                rec.put("requestId",reply.optString("request_id",""));rec.put("statusUrl",statusUrl);
                rec.put("resultUrl",resultUrl);rec.put("state","IN_QUEUE");rec.put("message","Demande acceptée par fal.ai");
                storeTask(key,rec);
            }
            event(sceneId,idx,kind,"queued","Demande acceptée : suivi automatique en cours.",null,reply.optString("request_id",""));
            synchronized(taskLock){liveSubmissions.remove(key);}
            startPoller(key);
        }catch(Exception e){
            // Une requête POST peut réussir côté serveur puis échouer côté téléphone.
            // Dans le doute, ne jamais l'envoyer une seconde fois automatiquement.
            synchronized(taskLock){liveSubmissions.remove(key);}
            setTaskState(key,"UNKNOWN","État d'envoi incertain : "+safeError(e)+". Vérifiez Requests sur fal.ai avant toute nouvelle génération.",null);
        }
    }
    private static String safeError(Exception e){String s=e.getMessage();return s==null?"Erreur réseau":s.substring(0,Math.min(s.length(),250));}
    private void startPoller(String key){
        synchronized(taskLock){if(livePollers.contains(key))return;
            JSONObject t=task(key);if(t==null||!apiURL(t.optString("statusUrl"))||!apiURL(t.optString("resultUrl")))return;
            if(finished(t.optString("state")))return;
            livePollers.add(key);
        }
        jobs.execute(()->{try{poll(key);}finally{synchronized(taskLock){livePollers.remove(key);}}});
    }
    private void poll(String key){
        long started=System.currentTimeMillis();int retry=0;
        while(System.currentTimeMillis()-started<MAX_POLL_MS&&!Thread.currentThread().isInterrupted()){
            JSONObject t=task(key);if(t==null||finished(t.optString("state")))return;
            String statusUrl=t.optString("statusUrl",""),resultUrl=t.optString("resultUrl","");
            if(!apiURL(statusUrl)||!apiURL(resultUrl))return;
            try{
                JSONObject info=requestJSON(statusUrl,"GET",decryptKey(),null);
                String state=info.optString("status","IN_QUEUE");retry=0;
                if("COMPLETED".equals(state)){
                    String err=info.optString("error","");
                    if(!err.isEmpty()&&!"null".equals(err)){setTaskState(key,"FAILED","fal.ai : "+err,null);return;}
                    // Récupération immédiate : fal.ai ne conserve pas indéfiniment les réponses en file.
                    JSONObject result=requestJSON(resultUrl,"GET",decryptKey(),null);
                    String url="";
                    if("image".equals(t.optString("kind"))){JSONArray imgs=result.optJSONArray("images");
                        if(imgs!=null&&imgs.length()>0)url=imgs.getJSONObject(0).optString("url","");}
                    else {JSONObject v=result.optJSONObject("video");if(v!=null)url=v.optString("url","");}
                    if(!url.startsWith("https://"))throw new IOException("Aucun média dans le résultat fal.ai");
                    setTaskState(key,"DONE","Fichier prêt. Résultat enregistré et disponible dans l'application.",url);
                    if("animation".equals(t.optString("kind")))autoSaveVideo(key,url);
                    return;
                }
                String msg="IN_PROGRESS".equals(state)?"Animation / image en cours chez fal.ai…":"En attente dans la file fal.ai…";
                int n=info.optInt("queue_position",-1);if("IN_QUEUE".equals(state)&&n>=0)msg+=" Position : "+n;
                long minutes=(System.currentTimeMillis()-t.optLong("createdAt",System.currentTimeMillis()))/60000;
                msg+=" ("+minutes+" min). Aucune nouvelle demande envoyée.";
                setTaskState(key,state,msg,null);
            }catch(Exception e){
                retry++;
                setTaskState(key,"PAUSED","Connexion ou récupération indisponible ("+safeError(e)+"). Nouvelle vérification sans facturation…",null);
                if(retry>=18){setTaskState(key,"PAUSED","Suivi mis en pause après plusieurs erreurs réseau. Appuyez sur Reprendre le suivi (gratuit).",null);return;}
            }
            try{Thread.sleep(retry>0?20000:12000);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
        }
        setTaskState(key,"PAUSED","Suivi mis en pause. Réouvrir l'application ou appuyer sur Reprendre le suivi (sans facturation).",null);
    }
    private void autoSaveVideo(String key,String url){
        try{
            JSONObject t=task(key);if(t==null||t.optBoolean("saved",false))return;
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(20000);
            c.setReadTimeout(120000);c.setInstanceFollowRedirects(true);
            try{
                if(c.getResponseCode()!=200)return;
                String name="Diarra_Scene_"+t.optString("index","1")+"_"+t.optString("requestId", "video").replaceAll("[^a-zA-Z0-9_-]","")+".mp4";
                Uri dst=createDownload(name,"video/mp4");
                try{
                    try(InputStream in=c.getInputStream();OutputStream out=getContentResolver().openOutputStream(dst)){
                        if(out==null)throw new IOException("Écriture refusée");byte[] bytes=new byte[16384];int n;long size=0;
                        while((n=in.read(bytes))!=-1){size+=n;if(size>300_000_000)throw new IOException("Vidéo trop volumineuse");out.write(bytes,0,n);}
                    }
                    ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);
                    getContentResolver().update(dst,done,null,null);
                    synchronized(taskLock){JSONObject record=task(key);if(record!=null){record.put("saved",true);storeTask(key,record);}}
                    // V9 : une seule copie locale dans la bibliothèque rend le MP4 immédiatement montable.
                    // La copie ne déclenche jamais de nouvelle génération fal.ai.
                    try{
                        String mediaId="video_"+java.util.UUID.randomUUID().toString().replace("-","");
                        File target=new File(oldVideoDir(),mediaId+".mp4");
                        long bytes=0;
                        try(InputStream in=getContentResolver().openInputStream(dst);OutputStream out=new FileOutputStream(target)){
                            if(in==null)throw new IOException("MP4 téléchargé inaccessible");
                            byte[] buffer=new byte[16384];int n;
                            while((n=in.read(buffer))!=-1){bytes+=n;if(bytes>300_000_000L)throw new IOException("Vidéo trop volumineuse");out.write(buffer,0,n);}
                        }
                        if(bytes<16)throw new IOException("MP4 vide");
                        JSONObject entry=new JSONObject().put("id",mediaId).put("url",localVideoURL(mediaId))
                            .put("name",name).put("size",bytes).put("addedAt",System.currentTimeMillis());
                        rememberVideo(entry);
                        simplePrefs().edit().putString("onlyVideoForNextMontage",mediaId).commit();
                        event("","","library","done","Vidéo IA ajoutée automatiquement à votre bibliothèque.",localVideoURL(mediaId),null);
                    }catch(Exception copyError){
                        event("","","library","error","MP4 enregistré, mais bibliothèque : "+safeError(copyError),null,null);
                    }
                    event(t.optString("sceneId"),t.optString("index"),"animation","saved","MP4 automatiquement enregistré dans Téléchargements/DiarraPromptIA.",null,t.optString("requestId"));
                }catch(Exception e){getContentResolver().delete(dst,null,null);throw e;}
            }finally{c.disconnect();}
        }catch(Exception e){JSONObject t=task(key);if(t!=null)event(t.optString("sceneId"),t.optString("index"),"animation","progress",
            "Vidéo prête. Enregistrement automatique non terminé : utilisez Enregistrer MP4.",null,t.optString("requestId"));}
    }
    private static String oldResultURL(String requestId){
        return "https://queue.fal.run/fal-ai/wan/v2.2-a14b/requests/"+requestId;
    }
    // GET exclusivement. Ne jamais POST pour retrouver une vidéo ancienne.
    private String recoverLegacy(String sceneId,String idx,String id){
        if(sceneId==null||!sceneId.matches("[a-zA-Z0-9_-]{8,80}"))return "Scène invalide";
        if(idx==null||!idx.matches("[0-9]{1,2}"))return "Numéro de scène invalide";
        if(id==null||!id.matches("[a-fA-F0-9-]{30,80}"))return "Identifiant fal.ai invalide";
        String taskId=sceneId+":animation";
        synchronized(taskLock){if(task(taskId)!=null)return "Cette scène a déjà une animation suivie. Choisissez une autre scène ou utilisez son suivi existant.";
            try{JSONObject rec=new JSONObject().put("sceneId",sceneId).put("index",idx).put("kind","animation")
                .put("requestId",id).put("statusUrl",oldResultURL(id)+"/status")
                .put("resultUrl",oldResultURL(id)).put("state","RECOVERING")
                .put("message","Recherche de la demande existante, sans génération…")
                .put("createdAt",System.currentTimeMillis()).put("updatedAt",System.currentTimeMillis());
                storeTask(taskId,rec);
            }catch(Exception e){return "Impossible de mémoriser la recherche";}
        }
        jobs.execute(()->{
            try{
                JSONObject info=requestJSON(oldResultURL(id)+"/status","GET",decryptKey(),null);
                String state=info.optString("status","");
                if("COMPLETED".equals(state)){
                    String err=info.optString("error","");
                    if(!err.isEmpty()&&!"null".equals(err)){setTaskState(taskId,"FAILED",err,null);return;}
                    JSONObject res=requestJSON(oldResultURL(id),"GET",decryptKey(),null);
                    JSONObject video=res.optJSONObject("video");String url=video==null?"":video.optString("url","");
                    if(!url.startsWith("https://"))throw new IOException("Aucun lien MP4 dans la réponse");
                    setTaskState(taskId,"DONE","Ancienne vidéo retrouvée, aucune génération facturée.",url);
                    autoSaveVideo(taskId,url);
                } else if("IN_QUEUE".equals(state)||"IN_PROGRESS".equals(state)){
                    setTaskState(taskId,state,"Demande ancienne retrouvée : suivi gratuit en cours…",null);
                    startPoller(taskId);
                } else setTaskState(taskId,"UNAVAILABLE","Statut indisponible. Téléchargez le MP4 depuis Requests, puis importez-le.",null);
            }catch(Exception ex){
                setTaskState(taskId,"UNAVAILABLE","Résultat non accessible par API ("+safeError(ex)+"). Téléchargez le MP4 depuis fal.ai, puis importez-le.",null);
            }
        });
        return "Recherche de l’ancienne vidéo lancée (GET seulement, sans nouvelle génération).";
    }
    private File oldVideoDir(){File dir=new File(getFilesDir(), OLD_VIDEO_FOLDER);if(!dir.exists())dir.mkdirs();return dir;}
    private static String localVideoURL(String sceneId){return BASE_URL+"legacy-video/"+sceneId+".mp4";}
    private WebResourceResponse localVideoResponse(String name,String range) throws Exception {
        if(name==null||!name.matches("[a-zA-Z0-9_-]{8,80}[.]mp4"))throw new IOException("Nom interdit");
        File f=new File(oldVideoDir(),name);if(!f.isFile())throw new IOException("Vidéo absente");
        long length=f.length(),begin=0,end=length-1;boolean partial=false;
        if(range!=null&&range.startsWith("bytes=")){
            String spec=range.substring(6).split(",",2)[0].trim();String[] ab=spec.split("-",-1);
            if(ab.length==2){
                if(!ab[0].isEmpty())begin=Long.parseLong(ab[0]);
                if(!ab[1].isEmpty())end=Math.min(end,Long.parseLong(ab[1]));
                if(begin<0||begin>=length||end<begin)throw new IOException("Plage invalide");
                partial=true;
            }
        }
        FileInputStream fis=new FileInputStream(f);fis.getChannel().position(begin);
        final long bytesToRead=end-begin+1;
        FilterInputStream limited=new FilterInputStream(fis){private long remaining=bytesToRead;
            @Override public int read()throws IOException{if(remaining<=0)return -1;int n=super.read();if(n>=0)remaining--;return n;}
            @Override public int read(byte[] b,int off,int len)throws IOException{if(remaining<=0)return -1;int n=super.read(b,off,(int)Math.min((long)len,remaining));if(n>0)remaining-=n;return n;}
        };
        Map<String,String> headers=new HashMap<>();headers.put("Accept-Ranges","bytes");
        headers.put("Content-Length",String.valueOf(bytesToRead));
        if(partial)headers.put("Content-Range","bytes "+begin+"-"+end+"/"+length);
        return new WebResourceResponse("video/mp4",null,partial?206:200,partial?"Partial Content":"OK",headers,limited);
    }
    private void importLocalVideo(String sceneId,String index,Uri src){
        File tmp=null;
        try{
            if(sceneId==null||!sceneId.matches("[a-zA-Z0-9_-]{8,80}"))throw new IOException("Scène invalide");
            String key=sceneId+":animation";JSONObject old=task(key);
            if(old!=null&&!finished(old.optString("state")))throw new IOException("Une génération est encore en cours pour cette scène");
            File dest=new File(oldVideoDir(),sceneId+".mp4");
            tmp=new File(oldVideoDir(),sceneId+".tmp");
            try(InputStream in=getContentResolver().openInputStream(src);OutputStream out=new FileOutputStream(tmp)){
                if(in==null)throw new IOException("Fichier MP4 inaccessible");
                byte[] b=new byte[16384];int n;long size=0;
                while((n=in.read(b))!=-1){size+=n;if(size>300_000_000L)throw new IOException("Vidéo trop volumineuse (max 300 Mo)");out.write(b,0,n);}
                if(size<16)throw new IOException("Fichier vide");
            }
            // Vérifier l'en-tête ISO Base Media / MP4.
            try(FileInputStream check=new FileInputStream(tmp)){byte[] h=new byte[12];if(check.read(h)!=12||h[4]!='f'||h[5]!='t'||h[6]!='y'||h[7]!='p')
                throw new IOException("Le fichier sélectionné ne semble pas être un MP4");}
            if(dest.exists()&&!dest.delete())throw new IOException("Impossible de remplacer la vidéo locale");
            if(!tmp.renameTo(dest))throw new IOException("Copie locale impossible");
            JSONObject rec=new JSONObject().put("sceneId",sceneId).put("index",index).put("kind","animation")
                .put("state","DONE").put("saved",true).put("url",localVideoURL(sceneId))
                .put("message","Ancien MP4 importé et conservé dans l’application sur ce téléphone.")
                .put("createdAt",System.currentTimeMillis()).put("updatedAt",System.currentTimeMillis());
            storeTask(key,rec);
            event(sceneId,index,"legacy","done","Ancien MP4 importé : vidéo disponible dans la scène.",localVideoURL(sceneId),null);
        }catch(Exception ex){event(sceneId,index,"legacy","error","Import impossible : "+safeError(ex),null,null);}
        finally{if(tmp!=null&&tmp.exists())tmp.delete();}
    }
    private SharedPreferences simplePrefs(){return getSharedPreferences(SIMPLE_PREF,MODE_PRIVATE);}
    private void copySimpleVoice(Uri uri){
        try{
            String filename=libraryName(uri);
            String ending=".mp3";
            String lower=filename.toLowerCase(java.util.Locale.ROOT);
            if(lower.endsWith(".m4a")||lower.endsWith(".aac"))ending=".m4a";
            else if(lower.endsWith(".wav"))ending=".wav";
            File target=new File(oldVideoDir(),"voix_simple_v6"+ending);
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(target)){
                if(in==null)throw new IOException("Le fichier audio est inaccessible");
                byte[] b=new byte[16384];int n;long size=0;
                while((n=in.read(b))!=-1){size+=n;if(size>35_000_000L)throw new IOException("La voix est trop volumineuse (35 Mo max)");out.write(b,0,n);}
            }
            simplePrefs().edit().putString("voice",target.getAbsolutePath()).putString("voiceName",filename).apply();
            event("","","simple","voice","Voix off ajoutée : "+filename,null,null);
        }catch(Exception ex){event("","","simple","error","Import voix off : "+safeError(ex),null,null);}
    }
    private void createSimpleMontage(){ createSelectedMontage("[]"); }
    private void createSelectedMontage(String selectedJson){
        JSONArray wanted;
        try { wanted=new JSONArray(selectedJson==null?"[]":selectedJson); }
        catch(Exception e){event("","","simple","error","Sélection de montage invalide.",null,null);return;}
        // La sélection est une liste d'identifiants internes uniquement ; pas de chemins utilisateurs.
        final java.util.List<String> requested=new java.util.ArrayList<>();
        for(int i=0;i<wanted.length()&&i<24;i++){
            String id=wanted.optString(i,"");
            if(id.matches("video_[a-fA-F0-9]{32}")&&!requested.contains(id))requested.add(id);
        }
        if(simpleBusy.compareAndSet(false,true)){
            jobs.execute(()->{
                try{
                    JSONArray data=getVideoLibrary();java.util.List<File> clips=new java.util.ArrayList<>();
                    if(!requested.isEmpty()){
                        for(String id:requested){File file=new File(oldVideoDir(),id+".mp4");if(file.isFile())clips.add(file);}
                    }else{
                        for(int i=0;i<data.length();i++){
                            JSONObject item=data.optJSONObject(i);if(item==null)continue;
                            String id=item.optString("id","");if(!id.matches("video_[a-fA-F0-9]{32}"))continue;
                            File file=new File(oldVideoDir(),id+".mp4");if(file.isFile())clips.add(file);
                        }
                    }
                    // Compatibilité avec les versions précédentes : montage auto d'une nouvelle animation.
                    String only=simplePrefs().getString("onlyVideoForNextMontage","");
                    if(requested.isEmpty() && only.matches("video_[a-fA-F0-9]{32}")){
                        File one=new File(oldVideoDir(),only+".mp4");
                        if(one.isFile()){clips.clear();clips.add(one);}
                    }
                    if(clips.isEmpty())throw new IOException("Aucune vidéo MP4. Importez un MP4 ou générez une animation d’abord.");
                    String voicePath=simplePrefs().getString("voice","");
                    File voice=voicePath.isEmpty()?null:new File(voicePath);
                    if(voice!=null&&!voice.isFile())throw new IOException("Votre voix off n'est plus disponible. Choisissez-la de nouveau.");
                    String id="montage_"+java.util.UUID.randomUUID().toString().replace("-","");
                    File result=new File(oldVideoDir(),id+".mp4");
                    event("","","simple","progress","Préparation des "+clips.size()+" vidéos…",null,null);
                    SimpleVideoMaker.create(this,clips,voice,result,(msg,percent)->{
                        try{event(new JSONObject().put("kind","simple").put("status","progress").put("message",msg).put("percent",percent));}
                        catch(Exception ignored){}
                    });
                    String name="DIARRA_MONTAGE_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",java.util.Locale.US).format(new java.util.Date())+".mp4";
                    String saveMessage="Montage prêt.";
                    String savedUri="";
                    try{
                        Uri uri=SimpleVideoMaker.exportDownloads(this,result,name);
                        savedUri=uri.toString();
                        saveMessage="Montage enregistré dans Téléchargements / DiarraPromptIA.";
                    }catch(Exception e){saveMessage="Montage prêt, mais copie dans Téléchargements impossible : "+safeError(e);}
                    simplePrefs().edit().putString("lastVideo",id).putString("lastName",name).putString("lastSavedUri",savedUri)
                        .remove("onlyVideoForNextMontage").commit();
                    event("","","simple","done",saveMessage,localVideoURL(id),null);
                }catch(Exception e){event("","","simple","error","Montage : "+safeError(e),null,null);}
                finally{simpleBusy.set(false);}
            });
        }else event("","","simple","progress","Montage déjà en cours. Ne relancez pas.",null,null);
    }
    // La lecture en lecteur Android évite la lecture WebView à 0:00 avec certaines vidéos MP4.
    // Fonctionnement local : aucun appel à fal.ai et aucun débit de crédits.
    private Uri getLastSavedVideoUri() throws Exception {
        String saved=simplePrefs().getString("lastSavedUri","");
        if(saved.startsWith("content://"))return Uri.parse(saved);
        // Compatibilité avec les montages exportés par la V6 avant l'ajout de lastSavedUri.
        String name=simplePrefs().getString("lastName","");
        if(name.isEmpty())throw new IOException("Aucun montage enregistré. Créez votre vidéo d'abord.");
        try(Cursor c=getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            new String[]{MediaStore.MediaColumns._ID},
            MediaStore.MediaColumns.DISPLAY_NAME+"=?",new String[]{name},
            MediaStore.MediaColumns.DATE_ADDED+" DESC")) {
            if(c!=null&&c.moveToFirst())return android.content.ContentUris.withAppendedId(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,c.getLong(0));
        }
        throw new IOException("Fichier introuvable dans Téléchargements / DiarraPromptIA.");
    }

    private void openSimpleMontageInPlayer(){
        runOnUiThread(()->{
            try{
                Uri uri=getLastSavedVideoUri();
                Intent play=new Intent(Intent.ACTION_VIEW);
                play.setDataAndType(uri,"video/mp4");
                play.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(play,"Lire ma vidéo"));
            }catch(Exception e){
                event("","","simple","error","Lecture impossible : "+safeError(e),null,null);
            }
        });
    }

    private String simpleStatus(){
        try{
            JSONObject response=new JSONObject();JSONArray lib=getVideoLibrary();int count=0;
            for(int i=0;i<lib.length();i++){JSONObject obj=lib.optJSONObject(i);if(obj==null)continue;
                String id=obj.optString("id");if(id.matches("video_[a-fA-F0-9]{32}")&&new File(oldVideoDir(),id+".mp4").exists())count++;}
            String v=simplePrefs().getString("voice","");String last=simplePrefs().getString("lastVideo","");
            response.put("count",count).put("voiceName",!v.isEmpty()&&new File(v).exists()?simplePrefs().getString("voiceName","Voix off"):"");
            response.put("lastUrl",last.matches("montage_[a-fA-F0-9]{32}")&&new File(oldVideoDir(),last+".mp4").isFile()?localVideoURL(last):"");
            response.put("lastName",simplePrefs().getString("lastName",""));
            return response.toString();
        }catch(Exception ignored){return "{}";}
    }
    // Bibliothèque vidéo indépendante des scènes. Toutes ces opérations sont locales : aucun POST fal.ai.
    private JSONArray getVideoLibrary(){
        try{return new JSONArray(getSharedPreferences(LIBRARY_PREF,MODE_PRIVATE).getString("videos","[]"));}
        catch(Exception ignored){return new JSONArray();}
    }
    private synchronized void rememberVideo(JSONObject item){
        JSONArray list=getVideoLibrary();list.put(item);
        getSharedPreferences(LIBRARY_PREF,MODE_PRIVATE).edit().putString("videos",list.toString()).commit();
    }
    private String libraryName(Uri src){
        try(Cursor cursor=getContentResolver().query(src,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){
                String label=cursor.getString(0);
                if(label!=null&&!label.trim().isEmpty())return label.substring(0,Math.min(label.length(),80));
            }
        }catch(Exception ignored){}
        return "Vidéo importée";
    }
    private void importVideoToLibrary(Uri src){
        File temporary=null;
        try{
            String mediaId="video_"+java.util.UUID.randomUUID().toString().replace("-","");
            temporary=new File(oldVideoDir(),mediaId+".tmp");
            File destination=new File(oldVideoDir(),mediaId+".mp4");
            String originalName=libraryName(src);
            long size=0;
            try(InputStream in=getContentResolver().openInputStream(src);OutputStream out=new FileOutputStream(temporary)){
                if(in==null)throw new IOException("Le fichier sélectionné est inaccessible");
                byte[] b=new byte[16384];int n;
                while((n=in.read(b))!=-1){size+=n;
                    if(size>300_000_000L)throw new IOException("Vidéo trop volumineuse (maximum 300 Mo)");
                    out.write(b,0,n);
                }
            }
            if(size<16)throw new IOException("MP4 vide");
            try(FileInputStream check=new FileInputStream(temporary)){
                byte[] h=new byte[12];
                if(check.read(h)!=12||h[4]!='f'||h[5]!='t'||h[6]!='y'||h[7]!='p')
                    throw new IOException("Le fichier choisi n'est pas un MP4 valide");
            }
            if(!temporary.renameTo(destination))throw new IOException("Copie de la vidéo impossible");
            JSONObject item=new JSONObject().put("id",mediaId).put("url",localVideoURL(mediaId))
                .put("name",originalName).put("size",size).put("addedAt",System.currentTimeMillis());
            rememberVideo(item);
            event("","","library","done","Vidéo MP4 importée dans la bibliothèque, sans facturation.",localVideoURL(mediaId),null);
        }catch(Exception e){event("","","library","error","Import MP4 : "+safeError(e),null,null);}
        finally{if(temporary!=null&&temporary.exists())temporary.delete();}
    }
    private String attachLibraryVideo(String mediaId,String sceneId,String index){
        if(mediaId==null||!mediaId.matches("video_[a-fA-F0-9]{32}")||
            sceneId==null||!sceneId.matches("[a-zA-Z0-9_-]{8,80}")||
            index==null||!index.matches("[0-9]{1,2}"))return "Identifiant de vidéo ou de scène invalide";
        File src=new File(oldVideoDir(),mediaId+".mp4");
        if(!src.isFile())return "MP4 absent de la bibliothèque";
        String taskId=sceneId+":animation";
        JSONObject existing=task(taskId);
        if(existing!=null&&!finished(existing.optString("state")))
            return "Cette scène a une animation en cours ; la vidéo n'a pas été remplacée.";
        File dest=new File(oldVideoDir(),sceneId+".mp4");
        File tmp=new File(oldVideoDir(),sceneId+".copying");
        try{
            try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(tmp)){
                byte[] b=new byte[16384];int n;
                while((n=in.read(b))!=-1)out.write(b,0,n);
            }
            if(dest.exists()&&!dest.delete())throw new IOException("Impossible de remplacer l'ancienne copie");
            if(!tmp.renameTo(dest))throw new IOException("Copie locale impossible");
            JSONObject record=new JSONObject().put("sceneId",sceneId).put("index",index)
                .put("kind","animation").put("state","DONE").put("saved",true)
                .put("url",localVideoURL(sceneId)).put("requestId","")
                .put("message","MP4 de la bibliothèque associé à cette scène, sans génération payante.")
                .put("createdAt",System.currentTimeMillis()).put("updatedAt",System.currentTimeMillis());
            storeTask(taskId,record);
            event(sceneId,index,"legacy","done","MP4 ajouté à la scène "+index+".",localVideoURL(sceneId),null);
            return "Vidéo associée à la scène "+index+". Ouvrez Mes scènes pour la lire.";
        }catch(Exception e){return "Association impossible : "+safeError(e);}
        finally{if(tmp.exists())tmp.delete();}
    }
    private void resumeTasks(){
        JSONObject all=allTasks();Iterator<String> it=all.keys();
        while(it.hasNext()){
            String key=it.next();JSONObject t=all.optJSONObject(key);if(t==null)continue;
            if("SUBMITTING".equals(t.optString("state"))){
                synchronized(taskLock){if(!liveSubmissions.contains(key)&&!livePollers.contains(key)&&t.optString("statusUrl").isEmpty())
                    setTaskState(key,"UNKNOWN","Envoi interrompu avant réception de l'identifiant : vérifiez Requests dans fal.ai.",null);}
            }else if(!finished(t.optString("state")))startPoller(key);
        }
    }
    private boolean forgetTask(String sceneId,String kind){
        String key=sceneId+":"+kind;
        synchronized(taskLock){JSONObject t=task(key);if(t==null)return true;
            if(livePollers.contains(key)||liveSubmissions.contains(key)||"SUBMITTING".equals(t.optString("state"))||
                "IN_QUEUE".equals(t.optString("state"))||"IN_PROGRESS".equals(t.optString("state")))return false;
            if("image".equals(kind)){
                String other=sceneId+":animation";JSONObject video=task(other);
                if(video!=null && !finished(video.optString("state")) && !"UNKNOWN".equals(video.optString("state")))return false;
            }
            removeTask(key);return true;}
    }
    private Uri createDownload(String name,String mime) throws Exception {
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/DiarraPromptIA");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(u==null)throw new IOException("Impossible de créer le fichier Téléchargements");
        return u;
    }
    private void saveBytes(String filename,String mime,byte[] bytes) throws Exception {
        Uri u=createDownload(filename,mime);
        try {
            try(OutputStream out=getContentResolver().openOutputStream(u)){if(out==null)throw new IOException("Impossible d’écrire le fichier");out.write(bytes);}
            ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(u,done,null,null);
        } catch(Exception e){getContentResolver().delete(u,null,null);throw e;}
    }
    private static String sanitize(String name){return name==null?"document.txt":name.replaceAll("[^A-Za-z0-9._-]","_").substring(0,Math.min(80,name.length()));}
    public class NativeFal {
        @JavascriptInterface public String simpleStatus(){return MainActivity.this.simpleStatus();}
        @JavascriptInterface public void chooseSimpleVoice(){runOnUiThread(()->{
            try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");startActivityForResult(i,PICK_SIMPLE_VOICE);
            }catch(Exception e){event("","","simple","error","Impossible d’ouvrir les fichiers audio",null,null);}
        });}
        @JavascriptInterface public void removeSimpleVoice(){simplePrefs().edit().remove("voice").remove("voiceName").apply();
            event("","","simple","voice","Voix off retirée.",null,null);}
        @JavascriptInterface public void createSimpleMontage(){MainActivity.this.createSimpleMontage();}
        @JavascriptInterface public void createMontageWithVideos(String jsonIds){MainActivity.this.createSelectedMontage(jsonIds);}
        @JavascriptInterface public void openSimpleMontage(){MainActivity.this.openSimpleMontageInPlayer();}
        @JavascriptInterface public boolean hasKey(){return pref().contains("ct");}
        @JavascriptInterface public void saveKey(String k){if(k==null||k.trim().length()<12)throw new IllegalArgumentException("Clé trop courte");try{encryptKey(k.trim());}catch(Exception e){throw new IllegalStateException("Échec du chiffrement local");}}
        @JavascriptInterface public void forgetKey(){pref().edit().remove("ct").remove("iv").apply();}
        @JavascriptInterface public String createImage(String sceneId,String index,String prompt,String format){return launch(true,sceneId,index,prompt,null,format);}
        @JavascriptInterface public String createAnimation(String sceneId,String index,String prompt,String imageUrl,String format){return launch(false,sceneId,index,prompt,imageUrl,format);}
        @JavascriptInterface public String tasksSnapshot(){return allTasks().toString();}
        @JavascriptInterface public void resumePending(){resumeTasks();}
        @JavascriptInterface public String recoverLegacyRequest(String sceneId,String index,String requestId){return recoverLegacy(sceneId,index,requestId);}
        @JavascriptInterface public String chooseLegacyVideo(String sceneId,String index){
            if(sceneId==null||!sceneId.matches("[a-zA-Z0-9_-]{8,80}")||index==null||!index.matches("[0-9]{1,2}"))
                return "Scène invalide";
            selectedOldSceneId=sceneId;selectedOldIndex=index;
            runOnUiThread(()->{try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("video/mp4");
                startActivityForResult(i,PICK_OLD_VIDEO);
            }catch(Exception ex){event(sceneId,index,"legacy","error","Impossible d’ouvrir les fichiers : "+safeError(ex),null,null);}});
            return "Choisissez le MP4 existant sur votre téléphone. Aucun crédit fal.ai utilisé.";
        }
        @JavascriptInterface public String chooseVideoForLibrary(){
            runOnUiThread(()->{try{
                Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("video/mp4");
                startActivityForResult(i,PICK_LIBRARY_VIDEO);
            }catch(Exception e){event("","","library","error","Ouverture des fichiers : "+safeError(e),null,null);}});
            return "Sélectionnez un MP4 dans les fichiers de votre téléphone. Aucun crédit fal.ai utilisé.";
        }
        @JavascriptInterface public String librarySnapshot(){return getVideoLibrary().toString();}
        @JavascriptInterface public String associateLibraryVideo(String mediaId,String sceneId,String index){return attachLibraryVideo(mediaId,sceneId,index);}
        @JavascriptInterface public boolean releaseTask(String sceneId,String kind){return forgetTask(sceneId,kind);}
        @JavascriptInterface public void saveText(String filename,String content,String mime){jobs.execute(()->{try{saveBytes(sanitize(filename),"application/json".equals(mime.split(";")[0])?"application/json":"text/plain",content.getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}});}
        @JavascriptInterface public void downloadMedia(String url,String kind,String index){jobs.execute(()->{
            try {
                if(url==null||!url.startsWith("https://"))throw new IOException("URL de fichier invalide");
                if(url.startsWith(BASE_URL+"legacy-video/")){
                    String name=url.substring((BASE_URL+"legacy-video/").length());
                    if(!name.matches("[a-zA-Z0-9_-]{8,80}[.]mp4"))throw new IOException("Nom invalide");
                    File source=new File(oldVideoDir(),name);
                    if(!source.isFile())throw new IOException("MP4 local introuvable");
                    Uri dst=createDownload("Diarra_Scene_"+index+"_importe.mp4","video/mp4");
                    try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(dst)){
                        if(out==null)throw new IOException("Enregistrement refusé");byte[] b=new byte[16384];int n;
                        while((n=in.read(b))!=-1)out.write(b,0,n);
                    }catch(Exception ex){getContentResolver().delete(dst,null,null);throw ex;}
                    ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(dst,done,null,null);
                    event(index,kind,"saved","MP4 copié dans Téléchargements/DiarraPromptIA",null);return;
                }
                event(index,kind,"progress","Téléchargement en cours…",null);
                HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(20000);c.setReadTimeout(120000);c.setInstanceFollowRedirects(true);
                try {
                    if(c.getResponseCode()!=200)throw new IOException("Téléchargement HTTP "+c.getResponseCode());
                    Uri dst=createDownload("Diarra_Scene_"+index+("video".equals(kind)?".mp4":".jpg"),"video".equals(kind)?"video/mp4":"image/jpeg");
                    try {
                        try(InputStream in=c.getInputStream();OutputStream out=getContentResolver().openOutputStream(dst)) {
                            if(out==null)throw new IOException("Écriture indisponible");byte[] b=new byte[16384];int n;long size=0;
                            while((n=in.read(b))!=-1){size+=n;if(size>300_000_000)throw new IOException("Fichier trop gros");out.write(b,0,n);}
                        }
                        ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(dst,done,null,null);
                    }catch(Exception e){getContentResolver().delete(dst,null,null);throw e;}
                    event(index,kind,"saved","Téléchargement terminé",null);
                }finally{c.disconnect();}
            } catch(Exception e){event(index,kind,"error","Téléchargement : "+e.getMessage(),null);}
        });}
    }
    @Override protected void onDestroy(){jobs.shutdownNow();if(web!=null){web.removeJavascriptInterface("NativeFal");web.destroy();web=null;}super.onDestroy();}
}
