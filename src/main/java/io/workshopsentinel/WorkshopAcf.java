package io.workshopsentinel;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded Valve KeyValues parser. Reject duplicate/ambiguous keys; preserve unknown fields. */
final class WorkshopAcf {
    final Map<String,Object> root;
    private WorkshopAcf(Map<String,Object> root){this.root=root;}
    static WorkshopAcf read(Path file)throws IOException {
        StageFiles.noLinks(file);
        if(Files.size(file)>16*1024*1024)throw new IOException("Workshop ACF too large");
        Parser p=new Parser(Files.readString(file));Map<String,Object> root=p.object(false,0);
        WorkshopAcf result=new WorkshopAcf(root);
        if(!"108600".equals(result.app().get("appid")))throw new IOException("Not a Project Zomboid Workshop manifest");
        return result;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> node(Map<String,Object> map,String key)throws IOException {
        Object value=map.get(key);if(!(value instanceof Map))throw new IOException("Missing ACF section: "+key);
        return (Map<String,Object>)value;
    }
    Map<String,Object> app()throws IOException{return node(root,"AppWorkshop");}
    Map<String,Object> items()throws IOException{return node(app(),"WorkshopItemsInstalled");}
    Map<String,Object> details()throws IOException{return node(app(),"WorkshopItemDetails");}
    String manifest(String id)throws IOException {
        String value=Objects.toString(node(items(),id).get("manifest"),"");
        if(!value.matches("[1-9][0-9]{0,19}"))throw new IOException("Invalid Steam manifest ID: "+id);
        return value;
    }
    long updated(String id)throws IOException {
        try {long n=Long.parseLong(Objects.toString(node(items(),id).get("timeupdated"),""));
            if(n<=0)throw new NumberFormatException();return n;
        }catch(NumberFormatException e){throw new IOException("Invalid installed revision: "+id,e);}
    }
    void verified(String id,long remote)throws IOException {
        Map<String,Object> info=node(details(),id);String manifest=manifest(id);
        if(updated(id)!=remote || !manifest.equals(info.get("manifest"))
            || (info.containsKey("latest_manifest") && !manifest.equals(info.get("latest_manifest")))
            || (info.containsKey("latest_timeupdated") && !Long.toString(remote).equals(info.get("latest_timeupdated"))))
            throw new IOException("SteamCMD did not install the current revision: "+id);
    }
    String encode(){StringBuilder out=new StringBuilder();write(root,out,0);return out.toString();}
    private static void write(Map<String,Object> map,StringBuilder out,int depth){
        for(Map.Entry<String,Object> e:map.entrySet()){
            out.append("\t".repeat(depth)).append(quote(e.getKey()));
            if(e.getValue() instanceof Map){out.append("\n").append("\t".repeat(depth)).append("{\n");
                @SuppressWarnings("unchecked") Map<String,Object> child=(Map<String,Object>)e.getValue();write(child,out,depth+1);
                out.append("\t".repeat(depth)).append("}\n");
            }else out.append("\t\t").append(quote((String)e.getValue())).append('\n');
        }
    }
    private static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static final class Parser {
        final String text;int at;Parser(String text){this.text=text.startsWith("\uFEFF")?text.substring(1):text;}
        void space(){while(at<text.length()){
            if(Character.isWhitespace(text.charAt(at))){at++;continue;}
            if(text.startsWith("//",at)){while(at<text.length() && text.charAt(at)!='\n')at++;continue;}break;
        }}
        String token()throws IOException {
            space();if(at>=text.length())return null;char c=text.charAt(at++);
            if(c=='{' || c=='}')return Character.toString(c);
            if(c!='"')throw new IOException("Expected quoted ACF token");StringBuilder out=new StringBuilder();
            while(at<text.length()) {c=text.charAt(at++);if(c=='"')return out.toString();
                if(c=='\\'){if(at>=text.length())break;c=text.charAt(at++);if(c!='\\' && c!='"')throw new IOException("Unsupported ACF escape");}
                out.append(c);
            }throw new IOException("Truncated ACF token");
        }
        Map<String,Object> object(boolean closing,int depth)throws IOException {
            if(depth>12)throw new IOException("ACF nesting limit");Map<String,Object> map=new LinkedHashMap<>();Set<String> seen=new HashSet<>();
            while(true){String key=token();if(key==null){if(closing)throw new IOException("Truncated ACF object");return map;}
                if(key.equals("}")){if(!closing)throw new IOException("Unexpected ACF brace");return map;}
                if(key.equals("{") || !seen.add(key.toLowerCase(Locale.ROOT)))throw new IOException("Ambiguous ACF key");
                String value=token();if(value==null || value.equals("}"))throw new IOException("Missing ACF value");
                map.put(key,value.equals("{")?object(true,depth+1):value);
            }
        }
    }
}
