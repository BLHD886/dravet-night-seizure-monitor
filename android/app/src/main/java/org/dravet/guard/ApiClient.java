package org.dravet.guard;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

final class ApiClient {
    private final String base, token;
    ApiClient(LocalRepository repository) throws Exception { this.base=repository.base(); this.token=repository.token(); validate(base); }
    static void validate(String base) throws Exception {
        URI uri = new URI(base); String host=uri.getHost();
        if(host==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !(uri.getPath()==null || uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new Exception("地址只填写协议、主机和端口");
        boolean privateIp=false;
        if(host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")){
            String[] pieces=host.split("\\.");int[] address=new int[4];boolean valid=true;
            for(int i=0;i<4;i++){address[i]=Integer.parseInt(pieces[i]);if(address[i]>255)valid=false;}
            privateIp=valid&&(address[0]==10||(address[0]==192&&address[1]==168)||(address[0]==172&&address[1]>=16&&address[1]<=31));
        }
        boolean local = host.equals("127.0.0.1") || host.equals("localhost") || privateIp;
        if(!"https".equals(uri.getScheme()) && !(BuildConfig.DEBUG && "http".equals(uri.getScheme()) && local)) throw new Exception("正式连接需要 HTTPS；调试 HTTP 仅限本机或局域网 IP");
    }
    JSONObject request(String path, JSONObject payload) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URI(base.replaceAll("/+$", "")+path).toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(path.equals("/v1/assistant")?30000:8000);
        connection.setRequestProperty("Authorization", "Bearer "+token);
        try {
            if(payload!=null){ connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                try(java.io.OutputStream output=connection.getOutputStream()){ output.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }}
            int code=connection.getResponseCode(); if(code<200 || code>=300) throw new Exception("服务返回 HTTP "+code+"；检查访问码、地址或启用状态");
            try(InputStream input=connection.getInputStream(); ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192]; int count;
                while((count=input.read(buffer))!=-1){if(output.size()+count>2_000_000)throw new Exception("响应过大");output.write(buffer,0,count);}
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { connection.disconnect(); }
    }
}
