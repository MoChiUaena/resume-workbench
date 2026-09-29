package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ModelSettingsTest {
    @TempDir Path temp;
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private ModelSettings settings(){return new ModelSettings(mapper,temp.toString());}
    private ModelSettings.Edit edit(long revision,String provider,String url,String secret){return new ModelSettings.Edit(revision,"测试模型",provider,url,"qwen-plus",secret,false);}
    @Test void ciphertextPersistsWhileHttpViewNeverContainsTheSecret()throws Exception{
        var service=settings();var view=service.save(null,edit(0,"dashscope","https://dashscope.aliyuncs.com/compatible-mode/v1","secret-canary-key"));
        assertThat(view.enabled()).isFalse();assertThat(view.profiles()).hasSize(1);assertThat(view.profiles().getFirst().hasApiKey()).isTrue();
        assertThat(mapper.writeValueAsString(view)).doesNotContain("secret-canary-key","encryptedKey","master.key");
        assertThat(Files.readString(temp.resolve("model-settings/profiles.json"))).doesNotContain("secret-canary-key");
        var restarted=settings();assertThat(restarted.view()).isEqualTo(view);
        var profile=restarted.selected(view.defaultId(),view.revision(),false);assertThat(restarted.credential(profile)).isEqualTo("secret-canary-key");
    }
    @Test void emptyKeyKeepsSavedValueAndExplicitClearRemovesIt(){
        var service=settings();var first=service.save(null,edit(0,"deepseek","https://api.deepseek.com/v1","old-secret"));String id=first.defaultId();
        var preserved=service.save(id,edit(first.revision(),"deepseek","https://api.deepseek.com/v1",""));assertThat(service.credential(service.selected(id,preserved.revision(),false))).isEqualTo("old-secret");
        var cleared=service.save(id,new ModelSettings.Edit(preserved.revision(),"测试模型","deepseek","https://api.deepseek.com/v1","deepseek-chat","",true));
        assertThat(cleared.profiles().getFirst().hasApiKey()).isFalse();assertThatThrownBy(()->service.credential(service.selected(id,cleared.revision(),false))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("MODEL_KEY_REQUIRED"));
    }
    @Test void changesToProviderRequireNewCredentialsAndStaleUpdatesPreserveState(){
        var service=settings();var current=service.save(null,edit(0,"dashscope","https://dashscope.aliyuncs.com/compatible-mode/v1","secret-one"));
        assertThatThrownBy(()->service.save(current.defaultId(),edit(current.revision(),"deepseek","https://api.deepseek.com/v1",""))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("MODEL_KEY_REENTRY_REQUIRED"));
        assertThatThrownBy(()->service.save(null,edit(0,"deepseek","https://api.deepseek.com/v1","secret-two"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("MODEL_SETTINGS_CHANGED"));
        assertThat(service.view()).isEqualTo(current);
    }
    @Test void defaultSelectionDeletionAndExplicitEnableSurviveRestart(){
        var service=settings();var first=service.save(null,edit(0,"dashscope","https://dashscope.aliyuncs.com/compatible-mode/v1","secret-one"));
        var second=service.save(null,edit(first.revision(),"glm","https://open.bigmodel.cn/api/paas/v4","secret-two"));String other=second.profiles().getLast().id();
        var selected=service.select(other,second.revision());var enabled=service.enable(new ModelSettings.Enable(selected.revision(),true));
        assertThat(settings().view()).isEqualTo(enabled);
        var deleted=service.remove(other,enabled.revision());assertThat(deleted.defaultId()).isNull();assertThat(deleted.profiles()).hasSize(1);
    }
    @Test void corruptSettingsOrMissingMasterKeyPauseAiWithoutDiscardingFiles()throws Exception{
        var service=settings();service.save(null,edit(0,"dashscope","https://dashscope.aliyuncs.com/compatible-mode/v1","secret-one"));
        Path config=temp.resolve("model-settings/profiles.json");byte[] saved=Files.readAllBytes(config);Files.delete(temp.resolve("model-settings/master.key"));
        assertThat(settings().view().readable()).isFalse();assertThat(Files.readAllBytes(config)).isEqualTo(saved);
        Files.writeString(config,"invalid JSON");assertThat(settings().view().readable()).isFalse();assertThat(Files.readString(config)).isEqualTo("invalid JSON");
    }
    @Test void officialProvidersRejectWrongHostsAndUrlCredentials(){
        for(String url:new String[]{"http://dashscope.aliyuncs.com/v1","https://attacker.invalid/v1","https://key@dashscope.aliyuncs.com/v1","https://dashscope.aliyuncs.com/v1?token=key","https://dashscope.aliyuncs.com/a/../v1"})
            assertThatThrownBy(()->ModelSettings.validateUrl("dashscope",url)).isInstanceOf(ApiException.class);
        assertThat(ModelSettings.validateUrl("compatible","http://127.0.0.1:11435/v1/")).isEqualTo("http://127.0.0.1:11435/v1");
        assertThatThrownBy(()->ModelSettings.validateUrl("compatible","http://public.example.com/v1")).isInstanceOf(ApiException.class);
    }
}
