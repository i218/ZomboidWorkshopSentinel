package io.workshopsentinel;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

final class StageFiles {
    static final class CorruptMetadata extends IOException {CorruptMetadata(String message){super(message);}}
    static void noLinks(Path path)throws IOException {
        Path full=path.toAbsolutePath().normalize();
        for(Path part=full;part!=null;part=part.getParent())
            if(Files.exists(part,LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(part) || !part.toRealPath().equals(part.toAbsolutePath().normalize())))
                throw new IOException("Linked/reparse path refused: "+part);
    }
    static String hash(Path path)throws IOException {
        noLinks(path);
        try(InputStream in=Files.newInputStream(path)){
            MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;
            while((n=in.read(b))!=-1)md.update(b,0,n);
            StringBuilder out=new StringBuilder();for(byte v:md.digest())out.append(String.format("%02x",v&255));return out.toString();
        }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    static Map<String,String> inventory(Path root,long max)throws IOException {
        noLinks(root);if(!Files.isDirectory(root))throw new IOException("Missing content directory");
        Map<String,String> files=new TreeMap<>();long[] bytes={0};
        Files.walkFileTree(root,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a)throws IOException {noLinks(dir);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes a)throws IOException {
                noLinks(file);if(!a.isRegularFile())throw new IOException("Non-regular staged file");
                String name=root.relativize(file).toString().replace('\\','/');
                if(name.contains(":") || name.contains("\n") || name.contains("\r"))throw new IOException("Unsafe content filename");
                bytes[0]=Math.addExact(bytes[0],a.size());
                if(bytes[0]>max || files.size()>=100000)throw new IOException("Staging content limit exceeded");
                files.put(name,hash(file));return FileVisitResult.CONTINUE;
            }
        });
        if(files.isEmpty())throw new IOException("Empty staged item");return files;
    }
    static void copy(Path from,Path to,long max)throws IOException {
        Map<String,String> expected=inventory(from,max);noLinks(to);Files.createDirectories(to);
        for(String file:expected.keySet()) {
            Path destination=to.resolve(file).normalize();if(!destination.startsWith(to))throw new IOException("Path escaped staging");
            noLinks(destination);Files.createDirectories(destination.getParent());Files.copy(from.resolve(file),destination);
            force(destination);
        }
        if(!expected.equals(inventory(to,max)))throw new IOException("Content changed during copy");
    }
    static void atomic(Path file,String text)throws IOException {
        noLinks(file);Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),".sentinel-",".tmp");
        try {
            Files.writeString(temp,text);force(temp);
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
    }
    static void force(Path file)throws IOException {try(FileChannel channel=FileChannel.open(file,StandardOpenOption.WRITE)){channel.force(true);}}
    static Properties properties(Path file)throws IOException {
        noLinks(file);if(Files.size(file)>32*1024*1024)throw new CorruptMetadata("Staging metadata too large");
        Properties result=new Properties();try(Reader in=Files.newBufferedReader(file)){result.load(in);}
        catch(IllegalArgumentException malformed){throw new CorruptMetadata("Malformed staging metadata");}
        String checksum=(String)result.remove("_integrity");
        if(!digest(result).equals(checksum))throw new CorruptMetadata("Staging metadata checksum mismatch: "+file.getFileName());
        return result;
    }
    static void properties(Path file,Properties value)throws IOException {
        Properties signed=new Properties();signed.putAll(value);signed.remove("_integrity");signed.setProperty("_integrity",digest(signed));
        StringWriter out=new StringWriter();signed.store(out,"WorkshopSentinel schema 1");atomic(file,out.toString());
    }
    private static String digest(Properties p) {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            for(String key:new TreeSet<>(p.stringPropertyNames())){
                String value=p.getProperty(key);
                md.update((key.length()+":"+key+value.length()+":"+value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            StringBuilder result=new StringBuilder();for(byte b:md.digest())result.append(String.format("%02x",b&255));return result.toString();
        }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
}
