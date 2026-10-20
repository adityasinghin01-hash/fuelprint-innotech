package fuelprint.core.command;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
class SensitivePayloadTest {
    final ObjectMapper json=new ObjectMapper();
    @ParameterizedTest @ValueSource(strings={"Call +919612345678","Call +91 96123 45678","Call 9612345678","Call +1 (202) 555-0123","Call ＋９１９６１２３４５６７８"})
    void undeclaredNestedContactTextIsRedactedAndKeyed(String text)throws Exception {
        ObjectNode payload=json.createObjectNode().put("public",true);payload.putObject("extra").putArray("notes").add(text);
        var command=new Command("fp/1",UUID.randomUUID().toString(),"test_note",null,null,payload);
        var digest=new RequestDigest();var result=digest.of(command,Set.of(),false);
        assertThat(result.storedPayload().path("extra").asText()).isEqualTo("(private)");
        assertThat(result.storedPayload().path("public").asBoolean()).isTrue();assertThat(result.digest()).startsWith("hmac:");
        assertThat(digest.of(command,Set.of(),false).digest()).isEqualTo(result.digest());
    }
    @Test void numericMeasurementsDatesUuidsAndHashesRemainPublic()throws Exception {
        ObjectNode payload=json.createObjectNode().put("measurement",9612345678L).put("time","2026-10-19T10:00:00.000Z")
                .put("id","01234567-8901-4234-8567-916612345678")
                .put("sha256","aaaaaaaaaaaaaaaaaaaaaaa9612345678bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        var command=new Command("fp/1",UUID.randomUUID().toString(),"test_note",null,null,payload);
        var result=new RequestDigest().of(command,Set.of(),false);
        assertThat(result.storedPayload()).isEqualTo(payload);assertThat(result.digest()).startsWith("sha256:");
    }
    @Test void privateChangesAreDifferentRequestsWithoutKeepingEitherValue()throws Exception {
        var digest=new RequestDigest();String id=UUID.randomUUID().toString();
        var first=digest.of(new Command("fp/1",id,"test_note",null,null,json.createObjectNode().put("reason","Call +919612345678")),Set.of(),false);
        var second=digest.of(new Command("fp/1",id,"test_note",null,null,json.createObjectNode().put("reason","Call +919612345679")),Set.of(),false);
        assertThat(first.digest()).startsWith("hmac:").isNotEqualTo(second.digest());
        assertThat(first.storedPayload()).isEqualTo(second.storedPayload());
    }
    @Test void contactTextInNestedObjectKeysIsAlsoPrivate() {
        var payload=json.createObjectNode();payload.putObject("notes").put("Call +919612345678",true);
        var command=new Command("fp/1",UUID.randomUUID().toString(),"test_note",null,null,payload);
        var result=new RequestDigest().of(command,Set.of(),false);
        assertThat(result.storedPayload().path("notes").asText()).isEqualTo("(private)");
        assertThat(result.digest()).startsWith("hmac:");
    }
    @Test void contactTextInTopLevelKeysIsRemovedWithoutLosingOrdinaryFields() {
        String contact="Call +919612345678";
        var payload=json.createObjectNode().put(contact,true).put("public",42).put("_private_field_","keep");
        var command=new Command("fp/1",UUID.randomUUID().toString(),"test_note",null,null,payload);
        var result=new RequestDigest().of(command,Set.of(),false);
        assertThat(result.storedPayload().has(contact)).isFalse();
        assertThat(result.storedPayload().path("public").asInt()).isEqualTo(42);
        assertThat(result.storedPayload().path("_private_field_").asText()).isEqualTo("keep");
        assertThat(result.digest()).startsWith("hmac:");
    }
    @ParameterizedTest @ValueSource(strings={"phone_number","phoneNumber","ＭＯＢＩＬＥ","contact-number","msisdn"})
    void explicitContactKeysArePrivateEvenWhenNumbersLookLikeMeasurements(String key){
        for(boolean nested:List.of(false,true)){
            var payload=json.createObjectNode().put("measurement",9812345678L);
            if(nested)payload.putObject("qc_inputs").put(key,9812345678L);else payload.put(key,9812345678L);
            var command=new Command("fp/1",UUID.randomUUID().toString(),"request_lab",null,null,payload);
            var digest=new RequestDigest();var result=digest.of(command,Set.of(),false);
            assertThat(result.storedPayload().path(nested?"qc_inputs":key).asText()).isEqualTo("(private)");
            assertThat(result.storedPayload().path("measurement").longValue()).isEqualTo(9812345678L);
            assertThat(result.digest()).startsWith("hmac:");
            assertThat(digest.of(command,Set.of(),false).digest()).isEqualTo(result.digest());
            var changed=payload.deepCopy();if(nested)((ObjectNode)changed.path("qc_inputs")).put(key,9812345679L);else changed.put(key,9812345679L);
            assertThat(digest.of(new Command("fp/1",command.commandId(),"request_lab",null,null,changed),Set.of(),false).digest()).isNotEqualTo(result.digest());
        }
    }

}
