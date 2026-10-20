package fuelprint.ops;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
/** Literal SHA-256 oracle computed outside Java from the printed canonical UTF-8 bytes. */
class MissionDependencyTest {
    static final String VECTOR = "{\"analysis_ref\":{\"id\":\"22222222-2222-4222-8222-222222222222\",\"revision\":7,\"type\":\"analysis\"},\"basis_ref\":{\"id\":\"11111111-1111-4111-8111-111111111111\",\"revision\":2,\"type\":\"survey\"},\"proposal_ref\":{\"id\":\"33333333-3333-4333-8333-333333333333\",\"revision\":9,\"type\":\"proposal\"},\"route\":[{\"east\":0,\"north\":100,\"up\":35},{\"east\":280,\"north\":140,\"up\":45}],\"wind_direction_bucket\":27,\"wind_speed_bucket\":6}";
    Class<?> implementation(){Class<?> found=null;try{found=Class.forName("fuelprint.ops.MissionDependencies");}catch(ClassNotFoundException ignored){}assertThat(found).as("mission dependency implementation").isNotNull();return found;}
    Object call(String name,Class<?> type,Object value) throws Exception {var m=implementation().getDeclaredMethod(name,type);m.setAccessible(true);return m.invoke(null,value);}
    @Test void digestUsesAllDeclaredRouteNodesAndExactReferenceRevisions() throws Exception {
        var j=new ObjectMapper();assertThat(call("hash",JsonNode.class,j.readTree(VECTOR))).isEqualTo("559bf8fe0605f702d7368788ac34c76923f29eddd7f53342961215886e9310c2");
        // Different object insertion order and decimal formatting have identical canonical meaning.
        assertThat(call("hash",JsonNode.class,j.readTree(VECTOR.replace("100","100.0")))).isEqualTo("559bf8fe0605f702d7368788ac34c76923f29eddd7f53342961215886e9310c2");
    }
    @ParameterizedTest @CsvSource({"264.999,26","265,27","274.999,27","275,28","354.999,35","355,0","359.999,0","360,0","-5,0","-5.001,35","0,0"})
    void directionBucketsHonorTenDegreeMidpointsAndCircularWrap(double input,long expected) throws Exception {assertThat(call("directionBucket",double.class,input)).isEqualTo(expected);}
    @ParameterizedTest @CsvSource({"2.74999,5","2.75,6","3.24999,6","3.25,7","0,0"})
    void speedBucketsHonorHalfMeterMidpoints(double input,long expected) throws Exception {assertThat(call("speedBucket",double.class,input)).isEqualTo(expected);}
    @Test void unknownNonfiniteWindIsRejected() {for(var v:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})for(String name:new String[]{"directionBucket","speedBucket"})assertThatThrownBy(()->call(name,double.class,v)).isInstanceOf(InvocationTargetException.class).hasCauseInstanceOf(IllegalArgumentException.class);}
}
