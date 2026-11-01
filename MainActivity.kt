package de.datenzentrale.app

import android.app.Activity
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val pickCode = 41
    private var tree: Uri? = null
    private var server: FileServer? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply { text = "DATENZENTRALE"; textSize = 28f }
        status = TextView(this).apply { text = "Noch kein USB-Ordner ausgewählt."; textSize = 16f; setPadding(0,24,0,24) }
        val choose = Button(this).apply { text = "USB-Ordner auswählen"; setOnClickListener { chooseFolder() } }
        val start = Button(this).apply { text = "Server starten"; setOnClickListener { startServer() } }
        val stop = Button(this).apply { text = "Server stoppen"; setOnClickListener { stopServer() } }
        root.addView(title); root.addView(status); root.addView(choose); root.addView(start); root.addView(stop)
        setContentView(root)
        tree = getPreferences(0).getString("tree", null)?.let { Uri.parse(it) }
        if (tree != null) status.text = "USB-Ordner ist gespeichert. Server noch nicht gestartet."
    }

    private fun chooseFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, pickCode)
    }

    @Deprecated("Deprecated by Android API, retained for broad compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            tree = uri
            getPreferences(0).edit().putString("tree", uri.toString()).apply()
            status.text = "USB-Ordner ausgewählt. Jetzt Server starten."
        }
    }

    private fun startServer() {
        val t = tree
        if (t == null) { Toast.makeText(this, "Bitte zuerst den USB-Ordner auswählen.", Toast.LENGTH_LONG).show(); return }
        try {
            if (server == null) server = FileServer(t).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val ip = localIp()
            status.text = "Server läuft.\nAdresse für iPhone und HP:\nhttp://$ip:8765\n\nGeräte müssen im selben WLAN/Hotspot sein."
        } catch (e: Exception) {
            status.text = "Startfehler: ${e.message}"
        }
    }

    private fun stopServer() { server?.stop(); server = null; status.text = "Server gestoppt." }

    private fun localIp(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (ni in interfaces) for (a in ni.inetAddresses) {
            if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: "127.0.0.1"
        }
        return "127.0.0.1"
    }

    override fun onDestroy() { stopServer(); super.onDestroy() }

    inner class FileServer(private val root: Uri) : NanoHTTPD("0.0.0.0", 8765) {
        private fun doc(uri: Uri, path: String): Uri? {
            var current = DocumentsContract.getTreeDocumentId(root)
            var currentUri = DocumentsContract.buildDocumentUriUsingTree(root, current)
            if (path.isBlank()) return currentUri
            for (part in path.split("/").filter { it.isNotBlank() }) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, current)
                val c = contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null) ?: return null
                var found: String? = null
                c.use { while (it.moveToNext()) if (it.getString(1) == part) found = it.getString(0) }
                current = found ?: return null
                currentUri = DocumentsContract.buildDocumentUriUsingTree(root, current)
            }
            return currentUri
        }

        override fun serve(session: IHTTPSession): Response {
            val path = URLDecoder.decode(session.uri.removePrefix("/"), "UTF-8")
            if (session.method == Method.GET && (path.isEmpty() || path == "index.html")) {
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
            }
            if (path == "api/list" && session.method == Method.GET) {
                val folder = session.parameters["path"]?.firstOrNull() ?: ""
                val target = doc(root, folder) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Ordner nicht gefunden")
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(target))
                val arr = JSONArray()
                val cur = contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)
                cur?.use { while (it.moveToNext()) {
                    val name = it.getString(1); val mime = it.getString(2)
                    arr.put(JSONObject().put("name", name).put("dir", mime == DocumentsContract.Document.MIME_TYPE_DIR).put("size", if (it.isNull(3)) 0 else it.getLong(3)))
                } }
                return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString())
            }
            if (path == "api/upload" && session.method == Method.POST) {
                val folder = session.parameters["path"]?.firstOrNull() ?: ""
                val name = session.parameters["name"]?.firstOrNull()?.substringAfterLast('/') ?: "upload.bin"
                val target = doc(root, folder) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Zielordner fehlt")
                val parentId = DocumentsContract.getDocumentId(target)
                val created = DocumentsContract.createDocument(contentResolver, target, "application/octet-stream", name)
                    ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Datei konnte nicht angelegt werden")
                val temp = session.tempFileManager
                val body = session.inputStream
                contentResolver.openOutputStream(created, "w")!!.use { out -> body.copyTo(out) }
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true,"name":${JSONObject.quote(name)}}""")
            }
            if (path == "api/download" && session.method == Method.GET) {
                val rel = session.parameters["file"]?.firstOrNull() ?: ""
                val target = doc(root, rel) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Datei nicht gefunden")
                val mime = contentResolver.getType(target) ?: "application/octet-stream"
                val stream = contentResolver.openInputStream(target) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Nicht lesbar")
                return newChunkedResponse(Response.Status.OK, mime, stream)
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Nicht gefunden")
        }

        private val html = """<!doctype html><html lang="de"><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8"><title>DATENZENTRALE</title>
<style>body{font:17px Arial;margin:18px;background:#f4f6f8;color:#17202a}h1{font-size:26px}button{padding:12px;margin:5px;border:0;border-radius:8px;background:#185abc;color:white}#files{background:white;padding:12px;border-radius:10px}li{padding:9px;border-bottom:1px solid #ddd;word-break:break-word}.muted{color:#596579}</style>
<h1>DATENZENTRALE</h1><p class="muted">USB-Speicher auf dem Motorola</p><p id="where">/</p><button onclick="up()">Eine Ebene hoch</button><button onclick="refresh()">Aktualisieren</button><p><input id="pick" type="file" multiple><button onclick="upload()">Ausgewählte Dateien hochladen</button></p><p id="status"></p><ul id="files"></ul>
<script>let folder='';function enc(s){return encodeURIComponent(s)}async function refresh(){document.getElementById('where').textContent='/'+folder;let r=await fetch('/api/list?path='+enc(folder));let a=await r.json();let ul=document.getElementById('files');ul.innerHTML='';a.forEach(x=>{let li=document.createElement('li');let b=document.createElement('button');b.textContent=x.dir?'Öffnen':'Herunterladen';b.onclick=()=>{if(x.dir){folder=folder?folder+'/'+x.name:x.name;refresh()}else location.href='/api/download?file='+enc(folder?folder+'/'+x.name:x.name)};li.append(b,document.createTextNode(' '+x.name+(x.dir?'':' ('+x.size+' Bytes)')));ul.append(li)})}function up(){folder=folder.includes('/')?folder.substring(0,folder.lastIndexOf('/')):'';refresh()}async function upload(){let fs=document.getElementById('pick').files;if(!fs.length)return;for(let i=0;i<fs.length;i++){let f=fs[i];document.getElementById('status').textContent='Übertrage '+(i+1)+'/'+fs.length+': '+f.name;let r=await fetch('/api/upload?path='+enc(folder)+'&name='+enc(f.name),{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:f});if(!r.ok){document.getElementById('status').textContent='Fehler bei '+f.name;return}}document.getElementById('status').textContent=fs.length+' Datei(en) übertragen.';document.getElementById('pick').value='';refresh()}refresh();</script></html>"""
    }
}
