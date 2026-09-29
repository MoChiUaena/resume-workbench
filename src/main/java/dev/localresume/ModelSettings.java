package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/** Instance-local provider settings. The HTTP view never contains stored credentials. */
@Service
public class ModelSettings {
    public record Preset(String id,String name,String baseUrl,String model) {}
    public record Profile(String id,String name,String provider,String baseUrl,String model,String encryptedKey) {}
    public record Stored(int version,long revision,boolean enabled,String defaultId,List<Profile> profiles) {}
    public record ViewProfile(String id,String name,String provider,String baseUrl,String model,boolean hasApiKey) {}
    public record View(long revision,boolean enabled,String defaultId,boolean readable,List<ViewProfile> profiles,List<Preset> presets) {}
    public record Edit(@Min(0) long expectedRevision,@NotBlank @Size(max=50) String name,
                       @NotNull @Pattern(regexp="dashscope|deepseek|glm|compatible") String provider,
                       @NotBlank @Size(max=300) String baseUrl,@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:/-]{0,79}") String model,
                       @Size(max=512) String apiKey,boolean clearKey) {}
    public record Revision(@Min(0) long expectedRevision) {}
    public record Enable(@Min(0) long expectedRevision,@NotNull Boolean enabled) {}
    static final List<Preset> PRESETS=List.of(
        new Preset("dashscope","DashScope · 阿里云","https://dashscope.aliyuncs.com/compatible-mode/v1","qwen-plus"),
        new Preset("deepseek","DeepSeek","https://api.deepseek.com/v1","deepseek-chat"),
        new Preset("glm","GLM · 智谱","https://open.bigmodel.cn/api/paas/v4","glm-5"),
        new Preset("compatible","其他兼容服务","https://api.example.com/v1",""));
    private final ObjectMapper mapper;
    private final Path directory,file,keyFile;
    private final SecureRandom random=new SecureRandom();
    private Stored stored=new Stored(1,0,false,null,List.of());
    private boolean readable=true;
    private byte[] key;
    public ModelSettings(ObjectMapper mapper,@Value("${resume.data-dir}") String data){
        this.mapper=mapper;directory=Path.of(data).toAbsolutePath().resolve("model-settings");file=directory.resolve("profiles.json");keyFile=directory.resolve("master.key");
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))try{
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>131072)throw new IllegalArgumentException();
            var value=mapper.readValue(file.toFile(),Stored.class);
            if(value.version()!=1||value.revision()<1||value.profiles()==null||value.profiles().size()>20)throw new IllegalArgumentException();
            var ids=new HashSet<String>();
            for(var p:value.profiles()){
                requireId(p.id());if(!ids.add(p.id())||p.name()==null||p.name().isBlank()||p.name().length()>50||p.model()==null||!p.model().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,79}"))throw new IllegalArgumentException();
                validateUrl(p.provider(),p.baseUrl());if(p.encryptedKey()!=null)decrypt(p.encryptedKey());
            }
            if(value.defaultId()!=null&&!ids.contains(value.defaultId()))throw new IllegalArgumentException();stored=value;
        }catch(Exception failure){readable=false;}
    }
    public synchronized View view(){return new View(stored.revision(),stored.enabled(),stored.defaultId(),readable,
        stored.profiles().stream().map(p->new ViewProfile(p.id(),p.name(),p.provider(),p.baseUrl(),p.model(),p.encryptedKey()!=null)).toList(),PRESETS);}
    public synchronized View save(String id,Edit input){
        ready(input.expectedRevision());if(input.name()==null||input.name().isBlank()||input.name().length()>50||input.model()==null||!input.model().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,79}"))throw invalid();
        String url=validateUrl(input.provider(),input.baseUrl());var profiles=new ArrayList<>(stored.profiles());Profile previous=null;
        if(id==null){if(profiles.size()>=20)throw new ApiException("MODEL_LIMIT","最多保存 20 个模型配置。",422);id=UUID.randomUUID().toString();}
        else {requireId(id);previous=profile(id);}
        boolean newKey=input.apiKey()!=null&&!input.apiKey().isBlank();
        if(previous!=null&&previous.encryptedKey()!=null&&!newKey&&!input.clearKey()
            &&(!previous.provider().equals(input.provider())||!URI.create(previous.baseUrl()).getAuthority().equalsIgnoreCase(URI.create(url).getAuthority())))
            throw new ApiException("MODEL_KEY_REENTRY_REQUIRED","更换服务商或地址时，请重新输入或清除 API Key。",422);
        String encrypted=input.clearKey()?null:previous==null?null:previous.encryptedKey();
        if(input.apiKey()!=null&&!input.apiKey().isBlank()){
            String secret=input.apiKey().trim();if(secret.length()>512||!secret.matches("[!-~]+"))throw new ApiException("MODEL_KEY_INVALID","请输入 API Key 本身，不能包含空格或换行。",422);
            encrypted=encrypt(secret);
        }
        var next=new Profile(id,input.name().trim(),input.provider(),url,input.model(),encrypted);profiles.removeIf(p->p.id().equals(next.id()));profiles.add(next);
        persist(new Stored(1,stored.revision()+1,stored.enabled(),stored.defaultId()==null?id:stored.defaultId(),List.copyOf(profiles)));return view();
    }
    public synchronized View remove(String id,long revision){
        ready(revision);profile(id);var list=stored.profiles().stream().filter(p->!p.id().equals(id)).toList();
        persist(new Stored(1,stored.revision()+1,stored.enabled(),Objects.equals(stored.defaultId(),id)?null:stored.defaultId(),list));return view();
    }
    public synchronized View select(String id,long revision){ready(revision);profile(id);persist(new Stored(1,stored.revision()+1,stored.enabled(),id,stored.profiles()));return view();}
    public synchronized View enable(Enable input){ready(input.expectedRevision());if(input.enabled()==null)throw invalid();persist(new Stored(1,stored.revision()+1,input.enabled(),stored.defaultId(),stored.profiles()));return view();}
    public synchronized Profile selected(String id,long revision,boolean requireEnabled){
        ready(revision);if(requireEnabled&&!stored.enabled())throw new ApiException("AI_DISABLED","请在模型设置中启用文字润色。",422);
        return profile(id);
    }
    public synchronized String credential(Profile profile){if(profile.encryptedKey()==null)throw new ApiException("MODEL_KEY_REQUIRED","请先为此模型配置 API Key。",422);return decrypt(profile.encryptedKey());}
    private Profile profile(String id){requireId(id);return stored.profiles().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new ApiException("MODEL_NOT_FOUND","模型配置不存在，请重新选择。",404));}
    private void ready(long revision){if(!readable)throw new ApiException("MODEL_SETTINGS_UNREADABLE","模型设置无法读取，请保留原文件并检查本机数据目录。",503);if(revision!=stored.revision())throw new ApiException("MODEL_SETTINGS_CHANGED","模型设置已在其他页面修改，请刷新后重试。",409);}
    static String validateUrl(String provider,String input){
        if(input==null||input.length()>300||PRESETS.stream().noneMatch(p->p.id().equals(provider)))throw invalid();
        try{
            URI uri=URI.create(input.trim());String host=uri.getHost(),path=Objects.requireNonNullElse(uri.getRawPath(),"");
            if(host==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.normalize().getRawPath().equals(path)||path.contains("%")||path.contains("\\")||uri.getPort()==0||uri.getPort()>65535)throw invalid();
            boolean local=host.equals("localhost")||host.equals("127.0.0.1")||host.equals("[::1]")||host.equals("::1")||host.matches("10(?:\\.[0-9]{1,3}){3}")||host.matches("192\\.168(?:\\.[0-9]{1,3}){2}")||host.matches("172\\.(?:1[6-9]|2[0-9]|3[01])(?:\\.[0-9]{1,3}){2}");
            if(!uri.getScheme().equalsIgnoreCase("https")&&!(provider.equals("compatible")&&uri.getScheme().equalsIgnoreCase("http")&&local))throw invalid();
            if(!provider.equals("compatible")){
                Set<String> hosts=switch(provider){case "dashscope"->Set.of("dashscope.aliyuncs.com","dashscope-intl.aliyuncs.com","dashscope-us.aliyuncs.com");case "deepseek"->Set.of("api.deepseek.com");case "glm"->Set.of("open.bigmodel.cn","api.z.ai");default->Set.of();};
                if(!hosts.contains(host.toLowerCase(Locale.ROOT))||(uri.getPort()!=-1&&uri.getPort()!=443))throw invalid();
            }
            if(host.equals("169.254.169.254")||host.equals("0.0.0.0"))throw invalid();
            return uri.toString().replaceAll("/+$","");
        }catch(ApiException e){throw e;}catch(Exception e){throw invalid();}
    }
    private byte[] master(boolean create)throws Exception{
        if(key!=null)return key;
        if(Files.exists(keyFile,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(keyFile,LinkOption.NOFOLLOW_LINKS)||Files.size(keyFile)!=32)throw new IllegalArgumentException();key=Files.readAllBytes(keyFile);
        }else{
            if(!create)throw new IllegalArgumentException();Files.createDirectories(directory);byte[] generated=new byte[32];random.nextBytes(generated);
            Path temp=Files.createTempFile(directory,"key-",".tmp");
            try{privateFile(temp);Files.write(temp,generated);Files.move(temp,keyFile,StandardCopyOption.ATOMIC_MOVE);key=generated;}
            finally{Files.deleteIfExists(temp);}
        }
        return key;
    }
    private String encrypt(String secret){try{byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(master(true),"AES"),new GCMParameterSpec(128,nonce));byte[] encrypted=cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));byte[] combined=new byte[nonce.length+encrypted.length];System.arraycopy(nonce,0,combined,0,nonce.length);System.arraycopy(encrypted,0,combined,nonce.length,encrypted.length);return Base64.getEncoder().encodeToString(combined);}catch(Exception e){throw new ApiException("MODEL_SETTINGS_WRITE_FAILED","模型密钥无法保存，请检查本机数据目录。",503);}}
    private String decrypt(String value){try{byte[] bytes=Base64.getDecoder().decode(value);if(bytes.length<29||bytes.length>1024)throw new IllegalArgumentException();var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(master(false),"AES"),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);}catch(Exception e){throw new ApiException("MODEL_SETTINGS_UNREADABLE","模型密钥无法读取，请保留原文件并检查本机数据目录。",503);}}
    private void persist(Stored next){
        Path temp=null;try{Files.createDirectories(directory);temp=Files.createTempFile(directory,"profiles-",".tmp");privateFile(temp);mapper.writeValue(temp.toFile(),next);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);stored=next;}
        catch(Exception e){throw new ApiException("MODEL_SETTINGS_WRITE_FAILED","模型设置无法保存，原有设置保留，请检查本机数据目录。",503);}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
    }
    private static void privateFile(Path file)throws java.io.IOException{try{Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}
    private static void requireId(String id){if(id==null||!id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new ApiException("MODEL_NOT_FOUND","模型配置不存在，请重新选择。",404);}
    private static ApiException invalid(){return new ApiException("MODEL_CONFIG_INVALID","请检查模型名称、服务商与地址。云服务使用 HTTPS，HTTP 仅用于其他兼容服务的本机或内网地址。",422);}
}
