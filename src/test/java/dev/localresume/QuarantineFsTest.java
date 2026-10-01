package dev.localresume;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class QuarantineFsTest {
    @TempDir Path temp;
    @Test void rejectsOutsideRootAndRegularAncestorsBeforeCreatingAnything()throws Exception {
        Path root=temp.resolve("data");var fs=new QuarantineFs(root);assertThat(root).doesNotExist();assertThatThrownBy(()->fs.path("../outside")).isInstanceOf(ApiException.class);fs.directory(root);Files.writeString(root.resolve("parent"),"canary");assertThatThrownBy(()->fs.directory(root.resolve("parent/child"))).isInstanceOf(ApiException.class);assertThat(Files.readString(root.resolve("parent"))).isEqualTo("canary");
    }
    @Test void directoryAndRegularChecksKeepNoFollowBoundary()throws Exception {
        var fs=new QuarantineFs(temp);fs.directory(temp.resolve("nested/leaf"));Files.writeString(temp.resolve("nested/file"),"canary");assertThat(fs.regular(temp.resolve("nested/file")).size()).isEqualTo(6);assertThatThrownBy(()->fs.regular(temp.resolve("nested/leaf"))).isInstanceOf(ApiException.class);assertThatThrownBy(()->fs.ordinaryDirectory(temp.resolve("nested/file"))).isInstanceOf(ApiException.class);fs.sameStore(temp.resolve("nested/file"),temp.resolve("new/missing"));
    }
    @Test void junctionOrDirectorySymlinkIsRejectedWithoutFollowingCanary()throws Exception {
        Path target=temp.resolve("target"),link=temp.resolve("link");Files.createDirectory(target);Files.writeString(target.resolve("canary"),"untouched");
        try{Files.createSymbolicLink(link,target);}catch(FileSystemException|UnsupportedOperationException ex){Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes());assertThat(process.waitFor()).withFailMessage(output).isZero();}
        try{var fs=new QuarantineFs(temp);assertThatThrownBy(()->fs.regular(link.resolve("canary"))).isInstanceOf(ApiException.class);assertThatThrownBy(()->new QuarantineFs(link).checked(link)).isInstanceOf(ApiException.class);assertThat(Files.readString(target.resolve("canary"))).isEqualTo("untouched");}finally{Files.delete(link);}
    }
}
