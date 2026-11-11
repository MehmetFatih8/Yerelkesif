package com.mehmetfatih8.yerelkesif;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.net.wifi.WifiManager;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Local-network discovery and the existing Yerelkeşif TCP wire protocol. */
public class PeerService extends Service {
    static volatile PeerService instance;
    interface Events {
        void device(String id, String name, String ip, boolean active);
        void lost(String id);
        void message(String id, String text);
        void transfer(String id, boolean incoming, String name, int percent, String stage, String detail, int index, int total, boolean last);
    }
    static volatile Events events;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, String> ipIds = new ConcurrentHashMap<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Set<String> connecting = ConcurrentHashMap.newKeySet();
    private DatagramSocket udp;
    private ServerSocket mainServer, fileServer;
    private volatile boolean running = true;
    private WifiManager.MulticastLock multicastLock;
    private volatile String username = "Android", identity;
    private volatile File downloadDir;

    @Override public void onCreate() {
        super.onCreate();
        instance=this;
        username=getSharedPreferences("settings",MODE_PRIVATE).getString("username",defaultDeviceName());
        identity = getSharedPreferences("settings", MODE_PRIVATE).getString("identity", null);
        if (identity == null) { identity = UUID.randomUUID().toString().replace("-", ""); getSharedPreferences("settings", MODE_PRIVATE).edit().putString("identity", identity).apply(); }
        downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        String savedTree=getSharedPreferences("settings",MODE_PRIVATE).getString("downloadTree",null); if(savedTree!=null) try{downloadTree=Uri.parse(savedTree);}catch(Exception ignored){}
        createNotification();
        WifiManager wm = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wm.createMulticastLock("yerelkesif-discovery"); multicastLock.setReferenceCounted(false); multicastLock.acquire();
        startNetwork();
    }
    private void createNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("peer", "Yerelkeşif bağlantısı", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "peer").setContentTitle("Yerelkeşif").setContentText("Yerel ağda cihaz aranıyor").setSmallIcon(android.R.drawable.stat_sys_upload_done).build();
        startForeground(47, n);
    }
    void configure(String name, File folder) { username = name; if (folder != null) downloadDir = folder; announce(); }
    void setDownloadTree(Uri tree) { downloadTree=tree; }
    void probe(String ip) { io.execute(() -> {try { if(udp==null)return;byte[] b=("P2P_DISCOVER_HELLO:"+username+":"+identity).getBytes(StandardCharsets.UTF_8);udp.send(new DatagramPacket(b,b.length,InetAddress.getByName(ip),8888));}catch(Exception ignored){} }); }
    private void startNetwork() {
        io.execute(() -> { try { mainServer = new ServerSocket(9999); while (running) accept(mainServer, false); } catch (Exception ignored) {} });
        io.execute(() -> { try { fileServer = new ServerSocket(9998); while (running) accept(fileServer, true); } catch (Exception ignored) {} });
        io.execute(() -> { try { udp = new DatagramSocket(8888); udp.setBroadcast(true); byte[] b = new byte[2048]; while (running) { DatagramPacket p = new DatagramPacket(b,b.length); udp.receive(p); handleUdp(new String(p.getData(),p.getOffset(),p.getLength(),StandardCharsets.UTF_8),p.getAddress()); } } catch (Exception ignored) {} });
        io.execute(() -> { while (running) { announce(); try { Thread.sleep(500); } catch (InterruptedException e) { return; } } });
    }
    private void announce() {
        try { if (udp == null || udp.isClosed()) return; byte[] b=("P2P_DISCOVER_HELLO:"+username+":"+identity).getBytes(StandardCharsets.UTF_8); DatagramPacket p=new DatagramPacket(b,b.length,InetAddress.getByName("255.255.255.255"),8888); udp.send(p); } catch(Exception ignored) {}
    }
    private void handleUdp(String text, InetAddress address) {
        String ip=address.getHostAddress(); if (address.isLoopbackAddress() || text.endsWith(":"+identity)) return;
        if (text.startsWith("P2P_DISCOVER_HELLO:")) {
            String payload=text.substring("P2P_DISCOVER_HELLO:".length());int at=payload.lastIndexOf(':');if(at>=0){String id=payload.substring(at+1);if(!id.equals(identity)){ipIds.put(ip,id);names.put(id,payload.substring(0,at));Events e=events;if(e!=null)e.device(id,payload.substring(0,at),ip,isActive(id));connect(id,ip);}}
            sendMatch(address); return;
        }
        if (text.startsWith("P2P_MATCH_HELLO:")) {
            String payload=text.substring("P2P_MATCH_HELLO:".length()); int at=payload.lastIndexOf(':'); if(at<0)return;
            String name=payload.substring(0,at), id=payload.substring(at+1); if(id.equals(identity))return;
            ipIds.put(ip,id); names.put(id,name);
            Events e=events; if(e!=null)e.device(id,name,ip,isActive(id));connect(id,ip);
        }
    }
    private void sendMatch(InetAddress ip) { try { byte[] b=("P2P_MATCH_HELLO:"+username+":"+identity).getBytes(StandardCharsets.UTF_8); udp.send(new DatagramPacket(b,b.length,ip,8888)); } catch(Exception ignored){} }
    private String defaultDeviceName(){String n=android.provider.Settings.Global.getString(getContentResolver(),"device_name");if(n!=null&&!n.trim().isEmpty())return n.trim();String model=Build.MODEL;return model==null||model.trim().isEmpty()?"Android cihazı":model.trim();}
    private void accept(ServerSocket server, boolean file) throws IOException { Socket s=server.accept(); s.setKeepAlive(true); s.setTcpNoDelay(true); String ip=s.getInetAddress().getHostAddress(); io.execute(() -> attach(ip,file,s)); }
    private void attach(String ip, boolean file, Socket socket) {
        String id=ipIds.get(ip); if(id==null) id="legacy:"+ip;
        final String peerId=id;
        Peer p=peers.computeIfAbsent(peerId,k->new Peer(peerId,ip)); synchronized(p){ if(file)p.inFile=socket; else p.inMain=socket; p.active=isActive(p); }
        Events e=events; if(e!=null)e.device(id,names.getOrDefault(id,ip),ip,p.active);
        if(file) readFile(p,socket); else readMessages(p,socket);
    }
    private boolean isActive(String id){Peer p=peers.get(id);return p!=null&&isActive(p);}
    private boolean isActive(Peer p){return (connected(p.outMain)&&connected(p.outFile))||(connected(p.inMain)&&connected(p.inFile));}
    private boolean connected(Socket s){return s!=null&&s.isConnected()&&!s.isClosed();}
    private void readMessages(Peer p,Socket socket) {
        try { InputStream in=socket.getInputStream(); while(true){ String prefix=readAscii(in,4); if(!prefix.equals("MSG:"))throw new IOException(); int len=Integer.parseInt(readUntilColon(in,12)); byte[] b=in.readNBytes(len); if(b.length!=len)throw new EOFException(); Events e=events;if(e!=null)e.message(p.id,new String(b,StandardCharsets.UTF_8)); } }
        catch(Exception ex){ closePeer(p); }
    }
    private String readAscii(InputStream in,int n)throws IOException { byte[] b=in.readNBytes(n);if(b.length!=n)throw new EOFException();return new String(b,StandardCharsets.US_ASCII); }
    private String readUntilColon(InputStream in)throws IOException { return readUntilColon(in,1024); }
    private String readUntilColon(InputStream in,int maximumLength)throws IOException { StringBuilder s=new StringBuilder(); int c;while((c=in.read())!=':'&&c!=-1){if(s.length()>=maximumLength)throw new IOException("Çerçeve başlığı çok uzun");s.append((char)c);}if(c==-1)throw new EOFException();return s.toString(); }
    private void readFile(Peer p,Socket socket) {
        try {
            InputStream in=socket.getInputStream();
            while(true){
                if(!readAscii(in,9).equals("FILE_REQ:"))throw new IOException("Geçersiz dosya aktarım başlığı");
                String name=readUntilColon(in);long size=Long.parseLong(readUntilColon(in));
                receiveFileBody(p,socket,in,name,size);
            }
        }
        catch(Exception ex){ closePeer(p); }
    }
    private void receiveFileBody(Peer p,Socket socket,InputStream in,String name,long size)throws Exception {
        Uri uri=createReceiveUri(name);File fallback=safeTarget(name);
        if(uri==null){File parent=fallback.getParentFile();if(parent!=null)parent.mkdirs();}
        OutputStream out=uri==null?new FileOutputStream(fallback):getContentResolver().openOutputStream(uri,"w");
        if(out==null)throw new IOException("İndirme klasörü açılamadı");
        socket.getOutputStream().write("FILE_ACK".getBytes(StandardCharsets.US_ASCII));
        byte[] buf=new byte[65536];long got=0;
        try(out){while(got<size){int n=in.read(buf,0,(int)Math.min(buf.length,size-got));if(n<0)throw new EOFException();out.write(buf,0,n);got+=n;Events e=events;if(e!=null)e.transfer(p.id,true,name,(int)(got*100/Math.max(1,size)),"ilerleme","",1,1,true);}}
        Events e=events;if(e!=null)e.transfer(p.id,true,name,100,"tamam",name,1,1,true);
    }
    private Uri downloadTree;
    private Uri createReceiveUri(String remote) throws Exception {
        if(downloadTree==null){if(Build.VERSION.SDK_INT<29)return null;String clean=remote.replace('\\','/');ArrayList<String> parts=new ArrayList<>();for(String part:clean.split("/"))if(!part.isEmpty()&&!part.equals(".")&&!part.equals(".."))parts.add(part);if(parts.isEmpty())parts.add("dosya");String display=parts.remove(parts.size()-1);String relative=Environment.DIRECTORY_DOWNLOADS+(parts.isEmpty()?"":"/"+String.join("/",parts)+"/");android.content.ContentValues values=new android.content.ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,display);values.put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");values.put(MediaStore.Downloads.RELATIVE_PATH,relative);return getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);}
        String clean=remote.replace('\\','/'); ArrayList<String> parts=new ArrayList<>(); for(String part:clean.split("/")){if(part.isEmpty()||part.equals("."))continue;if(part.equals(".."))continue;parts.add(part);} if(parts.isEmpty())parts.add("dosya");
        String treeId=DocumentsContract.getTreeDocumentId(downloadTree); Uri parent=DocumentsContract.buildDocumentUriUsingTree(downloadTree,treeId);
        for(int i=0;i<parts.size()-1;i++)parent=findOrCreate(parent,parts.get(i),"vnd.android.document/directory");
        String name=parts.get(parts.size()-1),base=name,ext="";int dot=name.lastIndexOf('.');if(dot>0){base=name.substring(0,dot);ext=name.substring(dot);}String candidate=name;
        for(int i=1;findChild(parent,candidate)!=null;i++)candidate=base+" ("+i+")"+ext;
        return DocumentsContract.createDocument(getContentResolver(),parent,"application/octet-stream",candidate);
    }
    private Uri findOrCreate(Uri parent,String name,String mime)throws Exception { Uri existing=findChild(parent,name);return existing!=null?existing:DocumentsContract.createDocument(getContentResolver(),parent,mime,name); }
    private Uri findChild(Uri parent,String name) { String id=DocumentsContract.getDocumentId(parent); Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(downloadTree,id); try(android.database.Cursor c=getContentResolver().query(children,new String[]{"document_id","_display_name"},null,null,null)){while(c!=null&&c.moveToNext())if(name.equals(c.getString(1)))return DocumentsContract.buildDocumentUriUsingTree(downloadTree,c.getString(0));}catch(Exception ignored){}return null; }
    private File safeTarget(String name) { String cleaned=name.replace('\\','/'); File root=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); File f=new File(root,cleaned); try { if(!f.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))f=new File(root,"dosya"); } catch(IOException ignored){} if(f.exists()){String n=f.getName(),base=n,ext="";int dot=n.lastIndexOf('.');if(dot>0){base=n.substring(0,dot);ext=n.substring(dot);}int i=1;while(f.exists())f=new File(f.getParentFile(),base+" ("+(i++)+")"+ext);}return f; }
    private void closePeer(Peer p) { p.active=false;close(p.inMain);close(p.inFile);close(p.outMain);close(p.outFile);Events e=events;if(e!=null)e.lost(p.id); }
    private void close(Socket s){try{if(s!=null)s.close();}catch(IOException ignored){}}
    private void connect(String id,String ip){
        if(!connecting.add(id))return;
        Peer p=peers.computeIfAbsent(id,k->new Peer(id,ip));
        if(connected(p.outMain)&&connected(p.outFile)){connecting.remove(id);return;}
        io.execute(()->{try{
            Socket main=new Socket();main.setKeepAlive(true);main.setTcpNoDelay(true);main.connect(new InetSocketAddress(ip,9999),3000);
            Socket file=new Socket();file.setKeepAlive(true);file.setTcpNoDelay(true);file.connect(new InetSocketAddress(ip,9998),3000);
            p.outMain=main;p.outFile=file;p.active=isActive(p);
            Events e=events;if(e!=null)e.device(id,names.getOrDefault(id,ip),ip,p.active);
        }catch(Exception ex){close(p.outMain);close(p.outFile);p.outMain=null;p.outFile=null;p.active=isActive(p);}
        finally{connecting.remove(id);}});
    }
    String sendMessage(String id,String text) { Peer p=peers.get(id);if(p==null||!connected(p.outMain))return "Bağlantı yok";try{byte[] b=text.getBytes(StandardCharsets.UTF_8);synchronized(p.outMain){p.outMain.getOutputStream().write(("MSG:"+b.length+":").getBytes(StandardCharsets.US_ASCII));p.outMain.getOutputStream().write(b);}return "";}catch(Exception e){closePeer(p);return "Mesaj gönderilemedi";} }
    void sendFiles(String id, List<String[]> files) {
        Peer p=peers.get(id);
        if(p==null||!connected(p.outFile)){Events e=events;if(e!=null)e.transfer(id,false,"",0,"hata","Bağlantı yok",1,1,true);return;}
        io.execute(()->{for(int i=0;i<files.size();i++){
            String uri=files.get(i)[0],name=files.get(i)[1];boolean last=i==files.size()-1;
            try(InputStream in=getContentResolver().openInputStream(Uri.parse(uri));android.os.ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(Uri.parse(uri),"r")){
                if(in==null||fd==null||fd.getStatSize()<0)throw new IOException("Dosya boyutu okunamadı");long size=fd.getStatSize();
                synchronized(p.outFile){
                    OutputStream out=p.outFile.getOutputStream();InputStream socketIn=p.outFile.getInputStream();
                    out.write(("FILE_REQ:"+name+":"+size+":").getBytes(StandardCharsets.UTF_8));out.flush();
                    byte[] ack=socketIn.readNBytes(8);if(!"FILE_ACK".equals(new String(ack,StandardCharsets.US_ASCII)))throw new IOException("Dosya onayı alınamadı");
                    byte[] b=new byte[65536];long sent=0;int n;while((n=in.read(b))!=-1){out.write(b,0,n);sent+=n;Events e=events;if(e!=null)e.transfer(id,false,name,(int)(sent*100/Math.max(1,size)),"ilerleme","",i+1,files.size(),last);}
                }
                Events e=events;if(e!=null)e.transfer(id,false,name,100,"tamam","Gönderildi",i+1,files.size(),last);
            }catch(Exception ex){Events e=events;if(e!=null)e.transfer(id,false,name,0,"hata",ex.getMessage()==null?"Dosya gönderilemedi":ex.getMessage(),i+1,files.size(),last);}
        }});
    }
    private static class Peer { final String id,ip; volatile Socket inMain,inFile,outMain,outFile; volatile boolean active; Peer(String i,String a){id=i;ip=a;} }
    @Override public int onStartCommand(Intent i,int f,int id){if(i!=null&&i.hasExtra("username")){username=i.getStringExtra("username");announce();}return START_STICKY;}
    private void closeAllPeers(){for(Peer p:peers.values())closePeer(p);peers.clear();}
    @Override public void onDestroy(){running=false;closeAllPeers();instance=null;try{if(udp!=null)udp.close();if(mainServer!=null)mainServer.close();if(fileServer!=null)fileServer.close();}catch(IOException ignored){}if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();io.shutdownNow();super.onDestroy();}
    @Override public void onTaskRemoved(Intent root){closeAllPeers();stopSelf();super.onTaskRemoved(root);}
    @Override public IBinder onBind(Intent i){return null;}
}
