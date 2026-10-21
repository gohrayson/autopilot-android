package app.autopilot.android;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** JSON bytes accepted by mihomo's YAML reader, without changing any string value. */
public final class CoreConfigEncoding {
    private CoreConfigEncoding(){}
    public static byte[] encode(JSONObject configuration){
        String json=Objects.requireNonNull(configuration,"configuration").toString();
        if(json==null)throw new IllegalArgumentException("Cannot serialize core configuration");
        return normalizeOptionalSlashes(json).getBytes(StandardCharsets.UTF_8);
    }
    /**
     * Android's JSONObject emits optional JSON slash escapes which YAML rejects.
     * Consume every other escape pair atomically: a literal backslash followed by
     * a slash must keep its backslash, including inside passwords and paths.
     */
    static String normalizeOptionalSlashes(String json){
        Objects.requireNonNull(json,"json");StringBuilder output=new StringBuilder(json.length());
        for(int i=0;i<json.length();i++){
            char current=json.charAt(i);
            if(current=='\\'&&i+1<json.length()){
                char escaped=json.charAt(++i);
                if(escaped=='/')output.append('/');
                else output.append('\\').append(escaped);
            }else output.append(current);
        }
        return output.toString();
    }
}
