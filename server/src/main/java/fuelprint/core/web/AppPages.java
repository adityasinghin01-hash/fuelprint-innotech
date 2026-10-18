package fuelprint.core.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import fuelprint.core.db.DataDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built apps (AGREEMENT section 3) from {@code <repo>/apps/<app>/dist}: {@code /operations/},
 * {@code /cases/}, {@code /field/} (with {@code /field/controller}) and {@code /alerts/}. A path without a file
 * extension that is not a file gets the app's {@code index.html} (the app's router handles it; {@code
 * /field/controller} first tries {@code controller.html}). A missing asset is a 404, never an HTML page. If an app
 * is not built, a short page says how to build it.
 */
@Configuration
public class AppPages implements WebMvcConfigurer {

    public static final List<String> APPS = List.of("operations", "cases", "field", "alerts");

    private final Path appsRoot;

    public AppPages(@Value("${fp.apps-dir:apps}") String appsDir) {
        Path configured = Path.of(appsDir);
        this.appsRoot = configured.isAbsolute() ? configured
                : DataDirectory.findRepoRoot(Path.of("").toAbsolutePath()).resolve(configured);
    }

    /** The built {@code index.html} of {@code app} (it may not exist). */
    Path indexOf(String app) {
        return appsRoot.resolve(app).resolve("dist").resolve("index.html");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/operations/");
        for (String app : APPS) {
            registry.addRedirectViewController("/" + app, "/" + app + "/");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        for (String app : APPS) {
            Path dist = appsRoot.resolve(app).resolve("dist");
            registry.addResourceHandler("/" + app + "/**")
                    .addResourceLocations(dist.toUri().toString())
                    .setCachePeriod(0)
                    .resourceChain(false)
                    .addResolver(new AppResolver(app, dist));
        }
    }

    /** Files from dist, else the app's page for router paths, else 404. */
    static final class AppResolver extends PathResourceResolver {
        private final String app;
        private final Path dist;

        AppResolver(String app, Path dist) {
            this.app = app;
            this.dist = dist;
        }

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource file = location.createRelative(resourcePath);
            if (!resourcePath.isEmpty() && file.exists() && file.isReadable() && Files.isRegularFile(file.getFile().toPath())) {
                return super.getResource(resourcePath, location);
            }
            if ("index.html".equals(resourcePath)) {
                return notBuilt(app);
            }
            String last = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            if (last.contains(".")) {
                return null; // a missing asset: 404
            }
            if (!resourcePath.isEmpty()) {
                for (String candidate : List.of(resourcePath + ".html", resourcePath + "/index.html")) {
                    Path page = dist.resolve(candidate).normalize();
                    if (page.startsWith(dist) && Files.isRegularFile(page)) {
                        return location.createRelative(candidate);
                    }
                }
            }
            if (Files.isRegularFile(dist.resolve("index.html"))) {
                return location.createRelative("index.html");
            }
            return notBuilt(app);
        }
    }

    static Resource notBuilt(String app) {
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>FuelPrint " + app
                + " not built</title></head><body style=\"font-family:sans-serif;background:#F4F2EC;color:#16211D;"
                + "padding:2rem\"><h1>The " + app + " app is not built yet</h1><p>For live reload run <code>tools/dev-app.sh "
                + app + "</code> and open <code>http://localhost:5173/" + app + "/</code>. To build every app and "
                + "serve it here run <code>tools/stall.sh</code>.</p></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "not-built.html";
            }
        };
    }
}
