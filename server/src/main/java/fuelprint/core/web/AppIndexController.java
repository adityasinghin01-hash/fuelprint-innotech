package fuelprint.core.web;

import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The bare app folder (like {@code /operations/}): its built index page, or the "not built" page. */
@RestController
public class AppIndexController {

    private final AppPages pages;

    public AppIndexController(AppPages pages) {
        this.pages = pages;
    }

    @GetMapping({"/operations/", "/cases/", "/field/", "/alerts/"})
    public ResponseEntity<Resource> index(HttpServletRequest request) {
        String app = request.getRequestURI().replace("/", "");
        Path index = pages.indexOf(app);
        Resource body = Files.isRegularFile(index) ? new FileSystemResource(index) : AppPages.notBuilt(app);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).header("Cache-Control", "no-cache").body(body);
    }
}
