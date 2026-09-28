package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResumeLayoutTest {
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @Test void legacyDocumentsKeepTheirMarginsAndAppearanceWithoutChangingTheSource()throws Exception {
        ObjectNode old=mapper.valueToTree(ResumeDocument.sample("one"));old.put("schemaVersion",2);
        ObjectNode layout=(ObjectNode)old.get("layout");layout.remove("presentation");layout.put("marginMm",20);
        var recovered=mapper.treeToValue(old,ResumeDocument.class);
        assertThat(recovered.schemaVersion()).isEqualTo(3);
        assertThat(recovered.layout().presentation()).isEqualTo(ResumeDocument.Presentation.defaults(20));
        assertThat(old.get("schemaVersion").asInt()).isEqualTo(2);assertThat(layout.has("presentation")).isFalse();
    }
    @Test void rejectsUnboundedOrInjectablePresentationValues()throws Exception {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            for(String value:new String[]{"#fff;position:fixed","red","#1234567"}) {
                ObjectNode doc=mapper.valueToTree(ResumeDocument.sample("one"));
                ((ObjectNode)doc.path("layout").path("presentation")).put("accentColor",value);
                assertThat(validator.validate(mapper.treeToValue(doc,ResumeDocument.class))).isNotEmpty();
            }
            ObjectNode doc=mapper.valueToTree(ResumeDocument.sample("one"));
            ((ObjectNode)doc.path("layout").path("presentation")).put("marginTopMm",99);
            assertThat(validator.validate(mapper.treeToValue(doc,ResumeDocument.class))).isNotEmpty();
        }
    }
}
