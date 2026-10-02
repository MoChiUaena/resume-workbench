package dev.localresume;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.Channels;
import java.util.Set;
import java.io.*;
class QuarantineFs {
    private static final LinkOption NOFOLLOW=LinkOption.NOFOLLOW_LINKS;
    private final Path root;
    QuarantineFs(Path root){this.root=root.toAbsolutePath().normalize();}
    Path root(){return root;}
    Path path(String relative)throws IOException {Path value=root.resolve(relative).normalize();checked(value);return value;}
    void checked(Path value)throws IOException {
        Path absolute=value.toAbsolutePath().normalize();if(!absolute.startsWith(root))throw conflict();
        Path cursor=absolute.getRoot();for(Path part:absolute){cursor=cursor.resolve(part);if(present(cursor)){
            var attr=Files.readAttributes(cursor,BasicFileAttributes.class,NOFOLLOW);
            if(attr.isSymbolicLink()||attr.isOther()||!cursor.toRealPath(NOFOLLOW).equals(cursor.toRealPath()))throw conflict();
            if(!cursor.equals(absolute)&&!attr.isDirectory())throw conflict();
        }}
    }
    void directory(Path value)throws IOException {
        checked(value);if(present(value)){ordinaryDirectory(value);return;}
        if(value.equals(root)){Files.createDirectories(root);ordinaryDirectory(root);return;}
        Path parent=value.getParent();if(parent==null)throw conflict();directory(parent);Files.createDirectory(value);ordinaryDirectory(value);
    }
    void ordinaryDirectory(Path value)throws IOException {checked(value);var attr=Files.readAttributes(value,BasicFileAttributes.class,NOFOLLOW);if(!attr.isDirectory()||attr.isSymbolicLink()||attr.isOther())throw conflict();}
    BasicFileAttributes regular(Path value)throws IOException {checked(value);var attr=Files.readAttributes(value,BasicFileAttributes.class,NOFOLLOW);if(!attr.isRegularFile()||attr.isSymbolicLink()||attr.isOther())throw conflict();return attr;}
    void sameStore(Path source,Path destination)throws IOException {
        checked(source);checked(destination);Path parent=destination.getParent();while(parent!=null&&!present(parent))parent=parent.getParent();
        if(parent==null||!parent.startsWith(root))throw conflict();ordinaryDirectory(parent);if(!Files.getFileStore(source).equals(Files.getFileStore(parent)))throw conflict();
    }
    static boolean present(Path value){return Files.exists(value,NOFOLLOW);}
    static InputStream open(Path value)throws IOException {return Channels.newInputStream(Files.newByteChannel(value,Set.of(StandardOpenOption.READ,NOFOLLOW)));}
    static ApiException conflict(){return new ApiException("QUARANTINE_CONFLICT","文件或暂存状态已变化，请刷新后重试；现有文件已保留。",409);}
}
