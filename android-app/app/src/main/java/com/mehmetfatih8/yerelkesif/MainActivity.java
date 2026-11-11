package com.mehmetfatih8.yerelkesif;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity implements PeerService.Events {
    private WebView web; private String pendingPicker; private String username="Yerelkeşif"; private Uri downloadTree;
    private String pendingShareJson; private boolean pageLoaded;
    private int safeTop,safeRight,safeBottom,safeLeft;
    private static final int PICK_FILES=1, PICK_FOLDER=2, PICK_DOWNLOADS=3;
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(0xff0c1018);getWindow().setNavigationBarColor(0xff0c1018);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        username=getSharedPreferences("settings",MODE_PRIVATE).getString("username",defaultDeviceName()); String tree=getSharedPreferences("settings",MODE_PRIVATE).getString("downloadTree",null);if(tree!=null)downloadTree=Uri.parse(tree);
        web=new WebView(this);web.setBackgroundColor(0xff0c1018);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.addJavascriptInterface(new Bridge(),"AndroidBridge");
        web.setOnApplyWindowInsetsListener((view,insets)->{int l,t,r,bottom;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets sys=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());l=sys.left;t=sys.top;r=sys.right;bottom=sys.bottom;}else{l=insets.getSystemWindowInsetLeft();t=insets.getSystemWindowInsetTop();r=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();}float density=getResources().getDisplayMetrics().density;safeLeft=Math.round(l/density);safeTop=Math.round(t/density);safeRight=Math.round(r/density);safeBottom=Math.round(bottom/density);applySafeInsets();return insets;});
        web.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView view,String url){pageLoaded=true;applySafeInsets();deliverSharedFiles();}});
        setContentView(web);web.requestApplyInsets();web.loadUrl("file:///android_asset/index.html");
        captureShareIntent(getIntent());
        PeerService.events=this; Intent service=new Intent(this,PeerService.class).putExtra("username",username);if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=0)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},9);
        if(Build.VERSION.SDK_INT>=23&&Build.VERSION.SDK_INT<29&&checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")!=0)requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"},10);
    }
    private void applySafeInsets(){if(web!=null)web.evaluateJavascript("if(window.setAndroidSafeInsets)window.setAndroidSafeInsets("+safeTop+","+safeRight+","+safeBottom+","+safeLeft+")",null);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);captureShareIntent(intent);}
    private void captureShareIntent(Intent intent){
        if(intent==null)return;String action=intent.getAction();if(!Intent.ACTION_SEND.equals(action)&&!Intent.ACTION_SEND_MULTIPLE.equals(action))return;
        ArrayList<Uri> uris=new ArrayList<>();android.content.ClipData clip=intent.getClipData();
        if(clip!=null){for(int i=0;i<clip.getItemCount();i++){Uri uri=clip.getItemAt(i).getUri();if(uri!=null&&!uris.contains(uri))uris.add(uri);}}
        if(uris.isEmpty())try{if(Intent.ACTION_SEND_MULTIPLE.equals(action)){ArrayList<Uri> many=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(many!=null)for(Uri uri:many)if(uri!=null&&!uris.contains(uri))uris.add(uri);}else{Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(uri!=null)uris.add(uri);}}catch(Exception ignored){}
        ArrayList<JSONObject> files=new ArrayList<>();for(Uri uri:uris)addFile(files,uri,"");if(files.isEmpty())return;
        pendingShareJson=new JSONArray(files).toString();deliverSharedFiles();
    }
    private void deliverSharedFiles(){if(!pageLoaded||web==null||pendingShareJson==null)return;String files=pendingShareJson;pendingShareJson=null;js("if(window.sharedFilesReady)window.sharedFilesReady("+files+")");}
    private String defaultDeviceName(){String name=android.provider.Settings.Global.getString(getContentResolver(),"device_name");if(name!=null&&!name.trim().isEmpty())return name.trim();String model=Build.MODEL;return model==null||model.trim().isEmpty()?"Android cihazı":model.trim();}
    private void openDownloadFolder(){if(downloadTree!=null){Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(downloadTree,"vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(view);return;}catch(Exception ignored){}}
        Uri downloads=android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download");Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(downloads,"vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(view);}catch(Exception ignored){Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);if(Build.VERSION.SDK_INT>=26)pick.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,downloads);startActivityForResult(pick,PICK_DOWNLOADS);}}
    private void js(String expression){runOnUiThread(()->{if(web!=null)web.evaluateJavascript(expression,null);});}
    private void pickerDone(String id,String json){try{js("window.__pickerDone("+JSONObject.quote(id)+","+(json==null?"null":json)+")");}catch(Exception ignored){}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null){if(pendingPicker!=null)pickerDone(pendingPicker,null);return;}
        if(request==PICK_FILES||request==PICK_FOLDER){ArrayList<JSONObject> files=new ArrayList<>();if(request==PICK_FILES){if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)addFile(files,data.getClipData().getItemAt(i).getUri(),"");}else if(data.getData()!=null)addFile(files,data.getData(),"");}else if(data.getData()!=null){getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);Uri tree=data.getData();String rootName=directoryName(tree);walkTree(files,tree,rootName+"/");}
            JSONArray arr=new JSONArray(files);pickerDone(pendingPicker,arr.toString());pendingPicker=null;
        }else if(request==PICK_DOWNLOADS&&data.getData()!=null){downloadTree=data.getData();getContentResolver().takePersistableUriPermission(downloadTree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getSharedPreferences("settings",MODE_PRIVATE).edit().putString("downloadTree",downloadTree.toString()).apply();if(PeerService.instance!=null)PeerService.instance.setDownloadTree(downloadTree);if(pendingPicker!=null){pickerDone(pendingPicker,JSONObject.quote(downloadTree.toString()));pendingPicker=null;}}
    }
    private void addFile(List<JSONObject> out,Uri uri,String prefix){try{String name=queryName(uri);long size=0;try(android.content.res.AssetFileDescriptor fd=getContentResolver().openAssetFileDescriptor(uri,"r")){if(fd!=null)size=fd.getLength();}JSONObject f=new JSONObject();f.put("name",prefix+name);f.put("remoteName",prefix+name);f.put("path",uri.toString());f.put("size",size);out.add(f);}catch(Exception ignored){}}
    private String queryName(Uri uri){try(Cursor c=getContentResolver().query(uri,null,null,null,null)){if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0)return c.getString(i);}}return "dosya";}
    private String directoryName(Uri tree){String id=android.provider.DocumentsContract.getTreeDocumentId(tree);String name=queryName(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,id));if(name==null||name.isEmpty()||"dosya".equals(name)){int colon=id.lastIndexOf(':');name=colon>=0?id.substring(colon+1):id;int slash=name.lastIndexOf('/');if(slash>=0)name=name.substring(slash+1);}name=name.replace('\\','_').replace('/','_').trim();return name.isEmpty()||".".equals(name)||"..".equals(name)?"Paylaşılan klasör":name;}
    private void walkTree(List<JSONObject> out,Uri tree,String rel){try{String id=android.provider.DocumentsContract.getTreeDocumentId(tree);try(Cursor c=getContentResolver().query(android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,id),new String[]{"document_id","mime_type","_display_name"},null,null,null)){while(c!=null&&c.moveToNext()){String child=c.getString(0),mime=c.getString(1),name=c.getString(2);Uri uri=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,child);if("vnd.android.document/directory".equals(mime))walkTreeAt(out,tree,child,rel+name+"/");else addFile(out,uri,rel);}}}catch(Exception ignored){}}
    private void walkTreeAt(List<JSONObject> out,Uri tree,String id,String rel){try(Cursor c=getContentResolver().query(android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,id),new String[]{"document_id","mime_type","_display_name"},null,null,null)){while(c!=null&&c.moveToNext()){String child=c.getString(0),mime=c.getString(1),name=c.getString(2);Uri uri=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,child);if("vnd.android.document/directory".equals(mime))walkTreeAt(out,tree,child,rel+name+"/");else addFile(out,uri,rel);}}catch(Exception ignored){}}
    @Override public void onBackPressed(){web.evaluateJavascript("if(!document.getElementById('ayarlarModal').classList.contains('hidden'))ayarlarKapat();else if(!document.getElementById('modal').classList.contains('hidden'))ipModalKapat();else closeDevice();",null);}
    @Override public void device(String id,String name,String ip,boolean active){js("window.cihazEkle("+JSONObject.quote(id)+","+JSONObject.quote(name)+","+JSONObject.quote(ip)+");window.cihazDurumu("+JSONObject.quote(id)+","+active+")");}
    @Override public void lost(String id){js("window.cihazKayboldu("+JSONObject.quote(id)+")");}
    @Override public void message(String id,String text){js("window.mesajAl("+JSONObject.quote(id)+","+JSONObject.quote(text)+")");}
    @Override public void transfer(String id,boolean incoming,String name,int pct,String stage,String detail,int index,int total,boolean last){js("window.transferDurumu("+JSONObject.quote(id)+","+incoming+","+JSONObject.quote(name)+","+pct+","+JSONObject.quote(stage)+","+index+","+total+","+JSONObject.quote(detail)+","+last+")");}
    public class Bridge {
        @JavascriptInterface public String call(String method,String args){try{JSONArray a=new JSONArray(args);switch(method){case "cppAyarlarGet":return new JSONObject().put("username",username).put("downloadDirectory",downloadTree==null?"Downloads":"Seçilen klasör").toString();case "cppAyarlarKaydet":username=a.getString(0);getSharedPreferences("settings",MODE_PRIVATE).edit().putString("username",username).apply();startService(new Intent(MainActivity.this,PeerService.class).putExtra("username",username));return "true";case "cppIndirmeKlasoruAc":openDownloadFolder();return "true";case "cppIpEkle":String ip=a.getString(0);PeerService p=service();if(p!=null)p.probe(ip);return "null";case "cppMesajGonder":PeerService ps=service();return JSONObject.quote(ps==null?"Ağ servisi hazır değil":ps.sendMessage(a.getString(0),a.getString(1)));case "cppTransferBaslat":PeerService sx=service();if(sx!=null){String id=a.getString(0);ArrayList<String[]> files=new ArrayList<>();for(int i=1;i+1<a.length();i+=2)files.add(new String[]{a.getString(i),a.getString(i+1)});sx.sendFiles(id,files);}return "null";case "uygulamaHazir":return "null";default:return "null";}}catch(Exception e){return "null";}}
        @JavascriptInterface public void pick(String method,String id){pendingPicker=id;runOnUiThread(()->{Intent i;if("cppKlasorSec".equals(method)){i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK_FOLDER);}else{i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK_FILES);}});}
        @JavascriptInterface public void pickFolder(String id){pendingPicker=id;runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK_DOWNLOADS);});}
        private PeerService service(){return PeerService.instance;}
    }
}
