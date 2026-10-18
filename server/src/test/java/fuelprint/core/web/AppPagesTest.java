package fuelprint.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;

import fuelprint.core.CoreSpringTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

class AppPagesTest extends CoreSpringTest {

    @Autowired MockMvc mvc;

    @BeforeAll
    static void buildFakeApps() throws Exception {
        Path ops = APPS_DIR.resolve("operations/dist");
        Files.createDirectories(ops.resolve("assets"));
        Files.writeString(ops.resolve("index.html"), "<html>operations app</html>");
        Files.writeString(ops.resolve("assets/app.js"), "console.log('ops')");
        Path field = APPS_DIR.resolve("field/dist");
        Files.createDirectories(field);
        Files.writeString(field.resolve("index.html"), "<html>field app</html>");
        Files.writeString(field.resolve("controller.html"), "<html>joystick</html>");
    }

    MockHttpServletResponse call(String url) throws Exception {
        return mvc.perform(get(url)).andReturn().getResponse();
    }

    @Test
    void builtAppsAreServedWithRouterFallbackAndRealAssetErrors() throws Exception {
        assertThat(call("/operations/").getContentAsString()).contains("operations app");
        assertThat(call("/operations/investigations/abc").getContentAsString()).contains("operations app");
        MockHttpServletResponse asset = call("/operations/assets/app.js");
        assertThat(asset.getStatus()).isEqualTo(200);
        assertThat(asset.getContentAsString()).contains("console.log");
        assertThat(call("/operations/assets/missing.js").getStatus()).isEqualTo(404);
        assertThat(call("/field/controller").getContentAsString()).contains("joystick");
        assertThat(call("/field/").getContentAsString()).contains("field app");
        assertThat(call("/operations").getStatus()).isEqualTo(302);
    }

    @Test
    void anAppThatIsNotBuiltSaysHowToBuildIt() throws Exception {
        MockHttpServletResponse page = call("/cases/");
        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(page.getContentType()).startsWith("text/html");
        assertThat(page.getContentAsString()).contains("not built").contains("tools/dev-app.sh cases");
        assertThat(call("/alerts/").getContentAsString()).contains("tools/dev-app.sh alerts");
    }
}
