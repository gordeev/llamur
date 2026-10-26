package org.motoai.runtime

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Small attachments travel in the same bounded RAM/encrypted-history envelope as messages.
 * No unencrypted cache copies, automatic downloads, execution, resizing or silent truncation. */
internal object ChatFiles {
    const val FILE_BYTES=3*1024*1024
    const val REQUEST_BYTES=8*1024*1024
    const val MESSAGE_BYTES=8*1024*1024
    private val imageTypes=setOf("image/png","image/jpeg","image/webp","image/gif")
    fun name(value:String)=value.substringAfterLast('/').substringAfterLast('\\').filter {it>=' ' && it!='\u007f'}.take(120).ifBlank {"file"}
    fun read(context:Context,uri:Uri,kind:String):JSONObject {
        require(uri.scheme=="content"){UiText.get(R.string.ui_choose_a_file_using_the_system_file)}
        var filename="file";context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use {c->if(c.moveToFirst()){filename=name(c.getString(0).orEmpty());if(!c.isNull(1))require(c.getLong(1) in 0..FILE_BYTES.toLong()){ UiText.get(R.string.ui_file_exceeds_3_mib_choose_a_smaller) }}}
        val bytes=context.contentResolver.openInputStream(uri)?.use {input->val out=ByteArrayOutputStream();val buffer=ByteArray(16384);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=FILE_BYTES){UiText.get(R.string.ui_file_exceeds_3_mib)};out.write(buffer,0,n)};out.toByteArray()}?:error(UiText.get(R.string.ui_file_unavailable))
        require(bytes.isNotEmpty()){UiText.get(R.string.ui_file_is_empty)}
        val result=JSONObject().put("name",filename)
        if(kind=="text") {require(bytes.size<=256*1024){UiText.get(R.string.ui_text_file_up_to_256_kib)};val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();require(!text.contains('\u0000')){UiText.get(R.string.ui_utf_8_text_required)};return result.put("mime","text/plain").put("text",text)}
        val mime=when {
            bytes.size>=8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))->"image/png"
            bytes.size>=3 && bytes[0]==255.toByte() && bytes[1]==216.toByte() && bytes[2]==255.toByte()->"image/jpeg"
            bytes.size>=12 && String(bytes,0,4,Charsets.US_ASCII)=="RIFF" && String(bytes,8,4,Charsets.US_ASCII)=="WEBP"->"image/webp"
            bytes.size>=6 && String(bytes,0,3,Charsets.US_ASCII)=="GIF"->"image/gif"
            bytes.size>=5 && String(bytes,0,5,Charsets.US_ASCII)=="%PDF-"->"application/pdf"
            else->error(UiText.get(R.string.ui_png_jpeg_webp_gif_and_pdf_are))
        }
        require(if(kind=="image")mime in imageTypes else mime=="application/pdf"){UiText.get(R.string.ui_selected_file_type_does_not_match)}
        return result.put("mime",mime).put("data",Base64.getEncoder().encodeToString(bytes))
    }
    fun validate(a:JSONArray,source:JSONObject?):JSONArray {
        require(a.length()<=4){UiText.get(R.string.ui_up_to_four_attachments)};require(a.toString().toByteArray().size<=6*1024*1024){UiText.get(R.string.ui_attachments_exceed_6_mib_in_total)}
        val result=JSONArray();for(i in 0 until a.length()) {
            val f=a.getJSONObject(i);val mime=f.getString("mime");val n=name(f.getString("name"));val v=JSONObject().put("name",n).put("mime",mime)
            if(mime=="text/plain"){val t=f.getString("text");require(t.toByteArray().size<=256*1024);v.put("text",t)}
            else {val c=source?.optJSONObject("capabilities");require(source!=null && if(mime in imageTypes)ModelCapabilities.has(c,"input","image")else mime=="application/pdf" && (ModelCapabilities.has(c,"input","file") || ModelCapabilities.has(c,"input","pdf"))){UiText.get(R.string.ui_model_support_for_this_attachment_is_not)}
                val data=f.getString("data");require(data.length<=4*1024*1024);val bytes=Base64.getDecoder().decode(data);require(bytes.size<=FILE_BYTES);v.put("data",data)}
            result.put(v)
        };return result
    }
    fun text(content:String,files:JSONArray?):String=buildString {append(content);if(files!=null)for(i in 0 until files.length()){val f=files.getJSONObject(i);if(f.optString("mime")=="text/plain")append("\n\n[Файл: ").append(name(f.getString("name"))).append("]\n").append(f.getString("text")).append("\n[Конец файла]\n")}}
    fun parts(content:String,files:JSONArray?,protocol:String):Any {
        val text=text(content,files);if(files==null || (0 until files.length()).none {files.getJSONObject(it).has("data")})return text
        val a=JSONArray().put(JSONObject().put("type",if(protocol=="responses")"input_text"else "text").put("text",text))
        for(i in 0 until files.length()) {val f=files.getJSONObject(i);if(!f.has("data"))continue;val mime=f.getString("mime");val data=f.getString("data");val url="data:$mime;base64,$data"
            a.put(when(protocol) {
                "responses"->if(mime in imageTypes)JSONObject().put("type","input_image").put("image_url",url)else JSONObject().put("type","input_file").put("filename",f.getString("name")).put("file_data",url)
                "anthropic"->JSONObject().put("type",if(mime in imageTypes)"image"else "document").put("source",JSONObject().put("type","base64").put("media_type",mime).put("data",data))
                else->if(mime in imageTypes)JSONObject().put("type","image_url").put("image_url",JSONObject().put("url",url))else JSONObject().put("type","file").put("file",JSONObject().put("filename",f.getString("name")).put("file_data",url))
            })
        };return a
    }
    fun generated(url:String,index:Int):JSONObject {
        require(url.length<=4*1024*1024){UiText.get(R.string.ui_response_image_exceeds_3_mib)}
        val mime=url.substringAfter("data:","").substringBefore(';');require(mime in imageTypes && url.startsWith("data:$mime;base64,")){UiText.get(R.string.ui_api_returned_an_unsupported_image_link_automatic)}
        val data=url.substringAfter(',');val bytes=Base64.getDecoder().decode(data);require(bytes.size<=FILE_BYTES)
        return JSONObject().put("name","image-${index+1}."+when(mime){"image/jpeg"->"jpg";"image/webp"->"webp";"image/gif"->"gif";else->"png"}).put("mime",mime).put("data",data)
    }
    fun exports(message:JSONObject):List<JSONObject> {
        val out=mutableListOf<JSONObject>();val a=message.optJSONArray("attachments")?:JSONArray();for(i in 0 until a.length())out.add(a.getJSONObject(i))
        if(message.optString("role")=="assistant")for(m in Regex("(?s)```([^\\n]*)\\n(.*?)```").findAll(message.optString("content")).take(8)) {
            val lang=m.groupValues[1].trim().lowercase().substringBefore(' ');val ext=mapOf("python" to "py","javascript" to "js","typescript" to "ts","kotlin" to "kt","bash" to "sh","shell" to "sh","json" to "json","html" to "html","css" to "css","svg" to "svg","xml" to "xml","markdown" to "md","csv" to "csv")[lang]?:"txt"
            out.add(JSONObject().put("name","code-${out.size+1}.$ext").put("mime","text/plain").put("text",m.groupValues[2]))
        };return out
    }
    fun bytes(file:JSONObject)=if(file.has("data"))Base64.getDecoder().decode(file.getString("data"))else file.getString("text").toByteArray(Charsets.UTF_8)
}
